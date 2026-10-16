package io.github.kmpdevicehub.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers commands from a map; unknown commands fail like a missing tool would. */
class FakeRunner(private val responses: Map<String, CommandResult>) : CommandRunner {
    val ran = mutableListOf<String>()
    val spawned = mutableListOf<String>()
    override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
        val key = command.joinToString(" ")
        ran += key
        return responses[key] ?: CommandResult(127, "", "unexpected command: $key")
    }
    override fun spawn(command: List<String>) { spawned += command.joinToString(" ") }
    val started = mutableListOf<String>()
    override fun start(command: List<String>): RunningProcess {
        started += command.joinToString(" ")
        return object : RunningProcess {
            override val pid = 4242L
            override var isAlive = true
            override fun waitFor(timeoutSeconds: Long) = true.also { isAlive = false }
            override fun kill() { isAlive = false }
        }
    }
}

private fun ok(stdout: String) = CommandResult(0, stdout, "")
private fun resource(name: String) = DeviceServiceTest::class.java.getResource("/$name")!!.readText()

class DeviceServiceTest {
    private val adb = "/sdk/platform-tools/adb"
    private val emulator = "/sdk/emulator/emulator"
    private val xcrun = "/usr/bin/xcrun"

    private val adbDevices = """
        List of devices attached
        emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
        emulator-5556          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:4
        R58M123ABC             unauthorized usb:1-1 transport_id:2
        adb-R5CT90._adb-tls-connect._tcp device product:o1sxxx model:SM_G991B device:o1s transport_id:3
        192.168.1.40:5555      offline transport_id:5
        emulator-5558          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:6
    """.trimIndent()

    private val emulatorProps = """
        [ro.boot.qemu.avd_name]: [Medium_Phone_API_37.0]
        [ro.build.version.release]: [17]
        [ro.build.version.sdk]: [37]
        [ro.product.manufacturer]: [Google]
        [ro.product.model]: [sdk_gphone64_arm64]
        [sys.boot_completed]: [1]
    """.trimIndent()

    private val bootingEmulatorProps = """
        [ro.kernel.qemu.avd_name]: [Pixel_Tablet]
        [ro.build.version.release]: [15]
        [ro.build.version.sdk]: [35]
    """.trimIndent()

    private val phoneProps = """
        [ro.build.version.release]: [16]
        [ro.build.version.sdk]: [36]
        [ro.product.manufacturer]: [samsung]
        [ro.product.model]: [SM-G991B]
        [sys.boot_completed]: [1]
    """.trimIndent()

    private fun service(responses: Map<String, CommandResult>, tools: ToolPaths = ToolPaths(adb, emulator, xcrun), isMac: Boolean = true) =
        DeviceService(
            FakeRunner(responses), tools, isMac,
            readFile = { path -> if (path == "/avd/Medium_Phone.ini") "path=/x\ntarget=android-37.0\n" else null },
            avdHome = "/avd",
        )

    private val allResponses = mapOf(
        "$adb devices -l" to ok(adbDevices),
        "$adb -s emulator-5554 shell getprop" to ok(emulatorProps),
        "$adb -s emulator-5556 shell getprop" to ok(bootingEmulatorProps),
        "$adb -s adb-R5CT90._adb-tls-connect._tcp shell getprop" to ok(phoneProps),
        "$adb -s emulator-5558 shell getprop" to CommandResult(1, "", "error: closed"),
        "$emulator -list-avds" to ok("INFO    | Storing crashdata in: /tmp/foo\n" + resource("emulator-list-avds.txt")),
        "$xcrun simctl list devices -j" to ok(resource("simctl-list.json")),
    )

    @Test
    fun `lists Android devices, stopped AVDs and iOS simulators together`() {
        val list = service(allResponses).list()
        assertEquals(emptyList(), list.problems)
        val byId = list.devices.associateBy { it.id }

        val emu = byId.getValue("emulator-5554")
        assertEquals("Medium Phone API 37.0", emu.name)
        assertEquals(DeviceKind.EMULATOR, emu.kind)
        assertEquals(DeviceState.RUNNING, emu.state)
        assertEquals("Android 17 (API 37)", emu.osLabel)

        assertEquals(DeviceState.BOOTING, byId.getValue("emulator-5556").state, "no sys.boot_completed yet")
        assertEquals("Pixel Tablet", byId.getValue("emulator-5556").name, "older emulators use ro.kernel.qemu.avd_name")

        val phone = byId.getValue("adb-R5CT90._adb-tls-connect._tcp")
        assertEquals("Samsung SM-G991B", phone.name)
        assertEquals(DeviceKind.PHYSICAL, phone.kind)

        assertEquals(DeviceState.UNAUTHORIZED, byId.getValue("R58M123ABC").state)
        assertEquals(DeviceState.OFFLINE, byId.getValue("192.168.1.40:5555").state)
        assertEquals(DeviceState.OFFLINE, byId.getValue("emulator-5558").state, "an emulator that stopped answering isn't 'running'")

        // The running AVD isn't listed twice; the other one shows as stopped, with its API level from the AVD config.
        assertNull(byId["avd:Medium_Phone_API_37.0"])
        val stopped = byId.getValue("avd:Medium_Phone")
        assertEquals(DeviceState.STOPPED, stopped.state)
        assertEquals(37, stopped.apiLevel)

        val sims = list.devices.filter { it.platform == DevicePlatform.IOS }
        assertEquals(11, sims.size)
        assertTrue(sims.all { it.osVersion == "27.0" && it.kind == DeviceKind.SIMULATOR })
        assertTrue(sims.any { it.name == "iPhone 18 Pro" })
    }

    @Test
    fun `running devices come first, Android before iOS`() {
        val devices = service(allResponses).list().devices
        val firstStopped = devices.indexOfFirst { !it.isRunning }
        assertTrue(devices.take(firstStopped).all { it.isRunning })
        assertTrue(devices.drop(firstStopped).none { it.isRunning })
        val running = devices.take(firstStopped)
        assertEquals(running.sortedBy { it.platform.ordinal }, running)
    }

    @Test
    fun `explains missing tools instead of failing`() {
        val noSdk = service(emptyMap(), ToolPaths(null, null, null), isMac = true).list()
        assertEquals(2, noSdk.problems.size)
        assertContains(noSdk.problems[0], "Android SDK not found")
        assertContains(noSdk.problems[1], "Xcode not found")

        val linux = service(emptyMap(), ToolPaths(null, null, null), isMac = false).list()
        assertEquals(1, linux.problems.size, "no iOS message off macOS")

        val adbBroken = service(mapOf("$adb devices -l" to CommandResult(1, "", "* failed to start daemon\nerror: cannot connect to daemon")), isMac = false).list()
        assertEquals(listOf("adb: * failed to start daemon"), adbBroken.problems)
    }

    @Test
    fun `simctl parsing keeps only available iOS simulators`() {
        val json = """{"devices":{
            "com.apple.CoreSimulator.SimRuntime.iOS-18-2":[
              {"udid":"A","name":"iPhone 16","state":"Booted","isAvailable":true},
              {"udid":"B","name":"iPhone 15","state":"Shutdown","isAvailable":false}],
            "com.apple.CoreSimulator.SimRuntime.watchOS-11-0":[{"udid":"W","name":"Watch","state":"Shutdown","isAvailable":true}],
            "com.apple.CoreSimulator.SimRuntime.iOS-17-5-1":[{"udid":"C","name":"iPad Air","state":"Booting","isAvailable":true}]
        }}"""
        val devices = SimulatorBackend.parse(json)
        assertEquals(listOf("A", "C"), devices.map { it.id })
        assertEquals("18.2", devices[0].osVersion)
        assertEquals(DeviceState.RUNNING, devices[0].state)
        assertEquals("17.5.1", devices[1].osVersion)
        assertEquals(DeviceState.BOOTING, devices[1].state)
    }

    @Test
    fun `tool locator finds the SDK the way Android Studio sets it up`() {
        val files = setOf("/Users/me/Library/Android/sdk/platform-tools/adb", "/Users/me/Library/Android/sdk/emulator/emulator", "/usr/bin/xcrun")
        val mac = ToolLocator.locate(env = emptyMap(), osName = "Mac OS X", home = "/Users/me", exists = { it in files })
        assertEquals(ToolPaths("/Users/me/Library/Android/sdk/platform-tools/adb", "/Users/me/Library/Android/sdk/emulator/emulator", "/usr/bin/xcrun"), mac)

        val viaEnv = ToolLocator.locate(env = mapOf("ANDROID_HOME" to "/opt/sdk"), osName = "Linux", home = "/home/me", exists = { it == "/opt/sdk/platform-tools/adb" })
        assertEquals("/opt/sdk/platform-tools/adb", viaEnv.adb)
        assertNull(viaEnv.xcrun, "xcrun only on macOS")

        val sdkDirWins = ToolLocator.locate(sdkDir = "/proj/sdk", env = mapOf("ANDROID_HOME" to "/opt/sdk"), osName = "Linux", home = "/h",
            exists = { it == "/proj/sdk/platform-tools/adb" || it == "/opt/sdk/platform-tools/adb" })
        assertEquals("/proj/sdk/platform-tools/adb", sdkDirWins.adb)

        val win = ToolLocator.locate(env = mapOf("LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local"), osName = "Windows 11", home = "C:\\Users\\me",
            exists = { it == "C:/Users/me/AppData/Local/Android/Sdk/platform-tools/adb.exe" })
        assertEquals("C:/Users/me/AppData/Local/Android/Sdk/platform-tools/adb.exe", win.adb)
    }

    @Test
    fun `real process runner captures output, exit codes and timeouts`() {
        val runner = ProcessCommandRunner()
        val echo = runner.run(listOf("sh", "-c", "echo hello; echo oops >&2; exit 3"))
        assertEquals(3, echo.exitCode)
        assertEquals("hello\n", echo.stdout)
        assertEquals("oops", echo.errorSummary)
        assertTrue(runner.run(listOf("sleep", "5"), timeoutSeconds = 1).timedOut)
        assertEquals(-1, runner.run(listOf("/definitely/not/a/tool")).exitCode)
    }
}
