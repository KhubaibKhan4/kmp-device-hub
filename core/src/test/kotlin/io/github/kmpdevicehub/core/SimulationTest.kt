package io.github.kmpdevicehub.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimulationTest {
    private val adb = "/sdk/platform-tools/adb"
    private val xcrun = "/usr/bin/xcrun"
    private val tools = ToolPaths(adb, "/sdk/emulator/emulator", xcrun)
    private val emu = Device("emulator-5554", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "17", 37, "Pixel_9")
    private val phone = Device("R58M", "Galaxy", DevicePlatform.ANDROID, DeviceKind.PHYSICAL, DeviceState.RUNNING, "16", 36)
    private val udid = "0A1B2C3D-0000-1111-2222-333344445555"
    private val sim = Device(udid, "iPhone 18 Pro", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")
    private val app = "com.example.app"
    private fun ok(out: String = "") = CommandResult(0, out, "")

    @Test
    fun `location uses geo fix (longitude first) and simctl location`() {
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 emu geo fix 67.0011 24.8607" to ok("OK"),
            "$xcrun simctl location $udid set 24.8607,67.0011" to ok(),
        ))
        val sim8 = DeviceSimulation(runner, tools)
        assertEquals("Location set to 24.8607, 67.0011 on Pixel 9.", sim8.setLocation(emu, 24.8607, 67.0011).message)
        assertTrue(sim8.setLocation(sim, 24.8607, 67.0011).ok)
        assertContains(sim8.setLocation(phone, 1.0, 1.0).message, "only be set on emulators")
        assertContains(sim8.setLocation(sim, 91.0, 0.0).message, "Latitude")
    }

    @Test
    fun `clean status bar uses demo mode on Android and status_bar on iOS`() {
        val runner = FakeRunner(emptyMap())
        val responses = object : CommandRunner by runner {
            override fun run(command: List<String>, timeoutSeconds: Long) = runner.run(command, timeoutSeconds).let { ok() }
        }
        val simulation = DeviceSimulation(responses, tools)
        assertTrue(simulation.cleanStatusBar(emu, true).ok)
        assertTrue(runner.ran.contains("$adb -s emulator-5554 shell settings put global sysui_demo_allowed 1"))
        assertTrue(runner.ran.contains("$adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941"))
        assertTrue(simulation.cleanStatusBar(emu, false).ok)
        assertEquals("$adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command exit", runner.ran.last())
        assertTrue(simulation.cleanStatusBar(sim, true).ok)
        assertContains(runner.ran.last(), "simctl status_bar $udid override --time 9:41")
        simulation.cleanStatusBar(sim, false)
        assertEquals("$xcrun simctl status_bar $udid clear", runner.ran.last())
    }

    @Test
    fun `network profiles throttle emulators and explain the rest`() {
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell cmd connectivity airplane-mode" to ok("enabled"),
            "$adb -s emulator-5554 shell cmd connectivity airplane-mode disable" to ok(),
            "$adb -s emulator-5554 shell cmd connectivity airplane-mode enable" to ok(),
            "$adb -s emulator-5554 emu network speed edge" to ok("OK"),
            "$adb -s emulator-5554 emu network delay edge" to ok("OK"),
            "$adb -s R58M shell cmd connectivity airplane-mode" to ok("disabled"),
        ))
        val simulation = DeviceSimulation(runner, tools)
        assertEquals("Network on Pixel 9: EDGE (slow).", simulation.setNetwork(emu, NetworkProfile.EDGE).message)
        assertTrue(runner.ran.contains("$adb -s emulator-5554 shell cmd connectivity airplane-mode disable"), "back online first")
        assertTrue(simulation.setNetwork(emu, NetworkProfile.OFFLINE).ok)
        assertContains(simulation.setNetwork(phone, NetworkProfile.EDGE).message, "only be changed on emulators")
        assertContains(simulation.setNetwork(sim, NetworkProfile.EDGE).message, "Network Link Conditioner")
    }

    @Test
    fun `push payloads are valid APNs JSON`() {
        val push = PushNotification("Order \"42\"", "Shipped\ntoday", badge = 3, data = mapOf("orderId" to "42"))
        assertEquals("""{"aps":{"alert":{"title":"Order \"42\"","body":"Shipped\ntoday"},"sound":"default","badge":3},"orderId":"42"}""", push.apnsJson())
        val parsed = Json.parse(push.apnsJson()) as Map<*, *>
        assertEquals("42", parsed["orderId"])
        assertEquals(mapOf("a" to "1", "b" to "x=y"), PushNotification.parseData("a=1\n\n b = x=y \nnot a pair"))
    }

    @Test
    fun `push goes through simctl on iOS and a notification-tap intent on Android`() {
        val pushed = mutableListOf<String>()
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $app" to ok("priority=0 preferredOrder=0\n$app/.MainActivity\n"),
            "$adb -s emulator-5554 shell am start -n $app/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10000000 --es orderId 42 --es note 'two words'" to ok("Starting: Intent"),
        ))
        val capture = object : CommandRunner by runner {
            override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
                if (command.getOrNull(1) == "simctl" && command[2] == "push") { pushed += File(command[5]).readText(); return ok() }
                return runner.run(command, timeoutSeconds)
            }
        }
        val simulation = DeviceSimulation(capture, tools)
        val push = PushNotification("Hi", "There", data = mapOf("orderId" to "42", "note" to "two words"))
        assertTrue(simulation.sendPush(sim, app, push).ok)
        assertContains(pushed.single(), "\"title\":\"Hi\"")
        val android = simulation.sendPush(emu, app, push)
        assertTrue(android.ok, android.message)
        assertFalse(simulation.sendPush(sim, app, push.copy(rawJson = "{\"nope\":1}")).ok, "raw JSON needs aps")
        assertContains(simulation.sendPush(emu, app, push.copy(data = mapOf("bad key" to "x"))).message, "isn't a valid data key")
    }

    @Test
    fun `recording stops gracefully and pulls the Android file`() {
        val target = File.createTempFile("rec", ".mp4")
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell pkill -INT screenrecord" to ok(),
            "$adb -s emulator-5554 pull ${DeviceSimulation.ANDROID_RECORDING} ${target.path}" to ok("1 file pulled"),
            "$adb -s emulator-5554 shell rm -f ${DeviceSimulation.ANDROID_RECORDING}" to ok(),
            "kill -INT 4242" to ok(),
        ))
        val simulation = DeviceSimulation(runner, tools)
        val (started, recording) = simulation.startRecording(emu, target)
        assertTrue(started.ok)
        assertEquals("$adb -s emulator-5554 shell screenrecord --bit-rate 8000000 ${DeviceSimulation.ANDROID_RECORDING}", runner.started.single())
        assertEquals(target.path, simulation.stopRecording(assertNotNull(recording)).message)

        val (_, iosRecording) = simulation.startRecording(sim, target)
        assertContains(runner.started.last(), "simctl io $udid recordVideo --codec=h264 --force ${target.path}")
        assertTrue(simulation.stopRecording(assertNotNull(iosRecording)).ok)
        assertTrue(runner.ran.contains("kill -INT 4242"), "SIGINT lets simctl finish the movie file")
    }

    @Test
    fun `adb shell arguments are quoted only when needed`() {
        assertEquals("myapp://orders/42", shellQuote("myapp://orders/42"))
        assertEquals("'https://x.com/?a=1&b=2'", shellQuote("https://x.com/?a=1&b=2"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun `battery, rotation and biometrics on both platforms`() {
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 emu power ac on" to ok("OK"),
            "$adb -s emulator-5554 emu power status charging" to ok("OK"),
            "$adb -s emulator-5554 emu power capacity 15" to ok("OK"),
            "$xcrun simctl status_bar $udid override --batteryLevel 15 --batteryState charging" to ok(),
            "$adb -s emulator-5554 shell settings get system user_rotation" to ok("0\n"),
            "$adb -s emulator-5554 shell settings put system accelerometer_rotation 0" to ok(),
            "$adb -s emulator-5554 shell settings put system user_rotation 1" to ok(),
            "$adb -s emulator-5554 emu finger touch 1" to ok("OK"),
            "$adb -s emulator-5554 emu finger touch 2" to ok("OK"),
            "$xcrun simctl spawn $udid notifyutil -p com.apple.BiometricKit_Sim.pearl.nomatch" to ok(),
            "$xcrun simctl spawn $udid notifyutil -p com.apple.BiometricKit_Sim.fingerTouch.nomatch" to ok(),
            "$xcrun simctl spawn $udid notifyutil -s com.apple.BiometricKit.enrollmentChanged 1" to ok(),
            "$xcrun simctl spawn $udid notifyutil -p com.apple.BiometricKit.enrollmentChanged" to ok(),
        ))
        val s = DeviceSimulation(runner, tools)
        assertEquals("Battery at 15%, charging on Pixel 9.", s.setBattery(emu, 15, charging = true).message)
        assertTrue(s.setBattery(sim, 15, charging = true).ok)
        assertContains(s.setBattery(phone, 15, true).message, "only be simulated on emulators")
        assertContains(s.setBattery(emu, 150, true).message, "0–100")
        assertEquals("Rotated Pixel 9 to landscape.", s.rotate(emu).message)
        assertContains(s.rotate(sim).message, "⌘→")
        assertTrue(s.biometric(emu, match = true).ok)
        assertTrue(s.biometric(emu, match = false).ok)
        assertEquals("Non-matching Face ID / Touch ID sent to iPhone 18 Pro.", s.biometric(sim, match = false).message)
        assertTrue(s.enrollBiometrics(sim, true).ok)
    }

    @Test
    fun `media and clipboard`() {
        val dir = java.nio.file.Files.createTempDirectory("media").toFile()
        val photo = File(dir, "beach.jpg").apply { writeText("x") }
        val video = File(dir, "clip.mp4").apply { writeText("x") }
        val runner = FakeRunner(mapOf(
            "$xcrun simctl addmedia $udid ${photo.path} ${video.path}" to ok(),
            "$adb -s emulator-5554 push ${photo.path} /sdcard/Pictures/beach.jpg" to ok("1 file pushed"),
            "$adb -s emulator-5554 push ${video.path} /sdcard/Movies/clip.mp4" to ok("1 file pushed"),
            "$adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/beach.jpg" to ok(),
            "$adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/clip.mp4" to ok(),
            "/bin/sh -c printf %s \"\$1\" | LC_ALL=en_US.UTF-8 \"\$2\" simctl pbcopy \"\$3\" sh promo code: KMP-42 $xcrun $udid" to ok(),
            "/usr/bin/env LC_ALL=en_US.UTF-8 $xcrun simctl pbpaste $udid" to ok("hello from iOS"),
        ))
        val s = DeviceSimulation(runner, tools)
        assertEquals("Added 2 files to Photos on iPhone 18 Pro.", s.addMedia(sim, listOf(photo, video)).message)
        assertEquals("Added 2 files to the gallery on Pixel 9.", s.addMedia(emu, listOf(photo, video)).message)
        assertContains(s.addMedia(emu, listOf(File(dir, "notes.txt").apply { writeText("x") })).message, "isn't a photo or video")
        assertTrue(s.setClipboard(sim, "promo code: KMP-42").ok)
        assertContains(s.setClipboard(emu, "x").message, "share your computer's clipboard")
        assertEquals("hello from iOS", s.getClipboard(sim))
    }
}
