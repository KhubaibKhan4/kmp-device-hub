package io.github.kmpdevicehub.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsTest {
    private val adb = "/sdk/platform-tools/adb"
    private val xcrun = "/usr/bin/xcrun"
    private val tools = ToolPaths(adb, "/sdk/emulator/emulator", xcrun)
    private val emu = Device("emulator-5554", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "17", 37, "Pixel_9")
    private val oldEmu = emu.copy(id = "emulator-5556", apiLevel = 30)
    private val udid = "0A1B2C3D-0000-1111-2222-333344445555"
    private val sim = Device(udid, "iPhone 18 Pro", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")
    private val app = "com.example.app"
    private fun ok(out: String = "") = CommandResult(0, out, "")

    private val dump = """
        Packages:
          Package [com.example.app] (1a2b3c):
            userId=10190
            requested permissions:
              android.permission.INTERNET
              android.permission.CAMERA
              android.permission.ACCESS_FINE_LOCATION
              android.permission.ACCESS_COARSE_LOCATION
              android.permission.POST_NOTIFICATIONS
            install permissions:
              android.permission.INTERNET: granted=true
            User 0: ceDataInode=31337 installed=true hidden=false
              gids=[3003]
              runtime permissions:
                android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
                android.permission.ACCESS_FINE_LOCATION: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
                android.permission.CAMERA: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]
                android.permission.ACCESS_COARSE_LOCATION: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED]
              disabledComponents:
                com.example.app.Unused
    """.trimIndent()

    @Test
    fun `reads Android permission states from the package dump`() {
        val (requested, granted) = DeviceSettings.parsePackageDump(dump)
        assertTrue("android.permission.CAMERA" in requested)
        assertEquals(true, granted["android.permission.CAMERA"])
        assertEquals(false, granted["android.permission.POST_NOTIFICATIONS"])
        assertFalse("android.permission.INTERNET" in granted, "install permissions aren't runtime permissions")

        val runner = FakeRunner(mapOf("$adb -s emulator-5554 shell dumpsys package $app" to ok(dump)))
        val states = DeviceSettings(runner, tools).permissions(emu, app)
        assertEquals(PermissionState.GRANTED, states[AppPermission.CAMERA])
        assertEquals(PermissionState.GRANTED, states[AppPermission.LOCATION], "coarse is granted")
        assertEquals(PermissionState.DENIED, states[AppPermission.NOTIFICATIONS])
        assertEquals(PermissionState.NOT_REQUESTED, states[AppPermission.MICROPHONE])
        assertEquals(PermissionState.UNKNOWN, DeviceSettings(runner, tools).permissions(sim, app)[AppPermission.CAMERA])
    }

    @Test
    fun `grants only permissions the app requests`() {
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell dumpsys package $app" to ok(dump),
            "$adb -s emulator-5554 shell pm grant $app android.permission.ACCESS_FINE_LOCATION" to ok(),
            "$adb -s emulator-5554 shell pm grant $app android.permission.ACCESS_COARSE_LOCATION" to ok(),
            "$xcrun simctl privacy $udid grant location $app" to ok(),
        ))
        val settings = DeviceSettings(runner, tools)
        assertTrue(settings.setPermission(emu, app, AppPermission.LOCATION, grant = true).ok)
        assertContains(settings.setPermission(emu, app, AppPermission.MICROPHONE, grant = true).message, "doesn't request Microphone")
        assertTrue(settings.setPermission(sim, app, AppPermission.LOCATION, grant = true).ok)
        assertContains(settings.setPermission(sim, app, AppPermission.CAMERA, grant = true).message, "can't change Camera")
    }

    @Test
    fun `dark mode and text size map to each platform`() {
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell cmd uimode night yes" to ok("Night mode: yes"),
            "$adb -s emulator-5554 shell settings put system font_scale 1.3" to ok(),
            "$xcrun simctl ui $udid appearance dark" to ok(),
            "$xcrun simctl ui $udid content_size extra-extra-large" to ok(),
            "$adb -s emulator-5554 shell cmd uimode night" to ok("Night mode: yes"),
            "$adb -s emulator-5554 shell settings get system font_scale" to ok("1.15\n"),
            "$xcrun simctl ui $udid appearance" to ok("light\n"),
            "$xcrun simctl ui $udid content_size" to ok("accessibility-medium\n"),
        ))
        val settings = DeviceSettings(runner, tools)
        assertTrue(settings.setDarkMode(emu, true).ok)
        assertTrue(settings.setTextSize(emu, TextSize.LARGER).ok)
        assertTrue(settings.setDarkMode(sim, true).ok)
        assertTrue(settings.setTextSize(sim, TextSize.LARGER).ok)
        assertEquals(DeviceSettingsSnapshot(true, TextSize.LARGE), settings.read(emu))
        assertEquals(DeviceSettingsSnapshot(false, TextSize.LARGEST), settings.read(sim))
    }

    @Test
    fun `app language uses per-app locales on Android 13+ and launch arguments on iOS`() {
        val arabic = AppLanguage.COMMON.first { it.android == "ar" }
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell cmd locale set-app-locales $app --locales ar" to ok(),
            "$xcrun simctl launch --terminate-running-process $udid $app -AppleLanguages (ar) -AppleLocale ar" to ok("$app: 99"),
            "$xcrun simctl launch --terminate-running-process $udid $app" to ok("$app: 100"),
        ))
        val settings = DeviceSettings(runner, tools)
        assertTrue(settings.setAppLanguage(emu, app, arabic).ok)
        assertTrue(settings.setAppLanguage(sim, app, arabic).ok)
        assertTrue(settings.setAppLanguage(sim, app, null).ok, "back to the device language")
        assertContains(settings.setAppLanguage(oldEmu, app, arabic).message, "Android 13")
        assertTrue(AppLanguage.COMMON.count { it.rtl } >= 3)
    }

    @Test
    fun `deep links open on both platforms and explain failures`() {
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d myapp://orders/42 $app" to ok("Starting: Intent { ... }\nStatus: ok"),
            "$adb -s emulator-5554 shell am start -W -a android.intent.action.VIEW -d nope://x" to ok("Error: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW dat=nope://x }"),
            "$xcrun simctl openurl $udid myapp://orders/42" to ok(),
            "$xcrun simctl openurl $udid nope://x" to CommandResult(1, "", "An error was encountered processing the command (domain=NSOSStatusErrorDomain, code=-10814)"),
        ))
        val settings = DeviceSettings(runner, tools)
        assertTrue(settings.openLink(emu, "myapp://orders/42", app).ok)
        assertContains(settings.openLink(emu, "nope://x").message, "No app on Pixel 9 handles nope://x")
        assertTrue(settings.openLink(sim, " myapp://orders/42 ").ok)
        assertFalse(settings.openLink(sim, "nope://x").ok)
        assertContains(settings.openLink(sim, "orders/42").message, "isn't a link")
    }

    @Test
    fun `screenshots go through a temp file on Android and simctl io on iOS`() {
        val target = File.createTempFile("shot", ".png")
        val remote = "/data/local/tmp/kmpdevicehub-screenshot.png"
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell screencap -p $remote" to ok(),
            "$adb -s emulator-5554 pull $remote ${target.path}" to ok("1 file pulled"),
            "$adb -s emulator-5554 shell rm -f $remote" to ok(),
            "$xcrun simctl io $udid screenshot ${target.path}" to ok("Wrote screenshot"),
        ))
        val settings = DeviceSettings(runner, tools)
        assertEquals(target.path, settings.screenshot(emu, target).message)
        assertTrue(runner.ran.contains("$adb -s emulator-5554 shell rm -f $remote"), "temp file cleaned up")
        assertTrue(settings.screenshot(sim, target).ok)
    }

    @Test
    fun `wifi pairing validates input`() {
        val runner = FakeRunner(mapOf(
            "$adb pair 192.168.1.20:37099 123456" to ok("Successfully paired to 192.168.1.20:37099 [guid=adb-XYZ]"),
            "$adb connect 192.168.1.20:41355" to ok("connected to 192.168.1.20:41355"),
        ))
        val settings = DeviceSettings(runner, tools)
        assertTrue(settings.pair("192.168.1.20:37099", "123456").ok)
        assertContains(settings.pair("192.168.1.20", "123456").message, "ip:port")
        assertContains(settings.pair("192.168.1.20:37099", "12ab").message, "6 digits")
        assertTrue(settings.connect("192.168.1.20:41355").ok)
    }
}
