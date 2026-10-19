package io.github.kmpdevicehub.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionsTest {
    private val adb = "/sdk/platform-tools/adb"
    private val emulator = "/sdk/emulator/emulator"
    private val xcrun = "/usr/bin/xcrun"
    private val tools = ToolPaths(adb, emulator, xcrun)

    private val emu = Device("emulator-5554", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, avdName = "Pixel_9")
    private val stoppedEmu = Device("avd:Pixel_9", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.STOPPED, avdName = "Pixel_9")
    private val phone = Device("R58M", "Samsung", DevicePlatform.ANDROID, DeviceKind.PHYSICAL, DeviceState.RUNNING)
    private val udid = "0A1B2C3D-0000-1111-2222-333344445555"
    private val sim = Device(udid, "iPhone 18 Pro", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")
    private val stoppedSim = sim.copy(state = DeviceState.STOPPED)

    private fun ok(out: String = "") = CommandResult(0, out, "")

    @Test
    fun `boot starts the AVD or the simulator and its window`() {
        val runner = FakeRunner(mapOf("$xcrun simctl boot $udid" to ok()))
        val actions = DeviceActions(runner, tools)
        assertTrue(actions.boot(stoppedEmu).ok)
        assertEquals("$emulator -avd Pixel_9", runner.spawned[0])
        assertTrue(actions.boot(stoppedSim).ok)
        assertEquals("open -a Simulator --args -CurrentDeviceUDID $udid", runner.spawned[1])
        assertFalse(actions.boot(phone).ok)

        // Already booted counts as success.
        val booted = FakeRunner(mapOf("$xcrun simctl boot $udid" to CommandResult(149, "", "Unable to boot device in current state: Booted")))
        assertTrue(DeviceActions(booted, tools).boot(stoppedSim).ok)
    }

    @Test
    fun `shutdown kills the emulator or shuts the simulator down`() {
        val runner = FakeRunner(mapOf("$adb -s emulator-5554 emu kill" to ok("OK: killing emulator, bye bye"), "$xcrun simctl shutdown $udid" to ok()))
        val actions = DeviceActions(runner, tools)
        assertTrue(actions.shutdown(emu).ok)
        assertTrue(actions.shutdown(sim).ok)
        assertFalse(actions.shutdown(phone).ok, "physical devices aren't shut down")
    }

    @Test
    fun `app actions run the right command on each platform`() {
        val app = "com.example.app"
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 shell monkey -p $app -c android.intent.category.LAUNCHER 1" to ok("Events injected: 1"),
            "$adb -s emulator-5554 shell am force-stop $app" to ok(),
            "$adb -s emulator-5554 shell pm clear $app" to ok("Success"),
            "$adb -s emulator-5554 uninstall $app" to ok("Success"),
            "$adb -s emulator-5554 shell pm path $app" to ok("package:/data/app/~~x/base.apk"),
            "$xcrun simctl launch $udid $app" to ok("$app: 4242"),
            "$xcrun simctl terminate $udid $app" to CommandResult(3, "", "found nothing to terminate"),
            "$xcrun simctl uninstall $udid $app" to ok(),
            "$xcrun simctl get_app_container $udid $app" to ok("/x/App.app"),
        ))
        val actions = DeviceActions(runner, tools)
        for (device in listOf(emu, sim)) {
            assertTrue(actions.launch(device, app).ok, "launch ${device.platform}")
            assertTrue(actions.stop(device, app).ok, "stop ${device.platform} (not running is fine)")
            assertTrue(actions.uninstall(device, app).ok, "uninstall ${device.platform}")
            assertTrue(actions.isInstalled(device, app))
        }
        assertTrue(actions.clearData(emu, app).ok)
    }

    @Test
    fun `app actions explain bad input and stopped devices`() {
        val actions = DeviceActions(FakeRunner(mapOf(
            "$adb -s emulator-5554 shell monkey -p com.missing.app -c android.intent.category.LAUNCHER 1" to ok("** No activities found to run, monkey aborted."),
        )), tools)
        assertContains(actions.launch(emu, "").message, "Choose an app")
        assertContains(actions.launch(emu, "not an id").message, "doesn't look like an app id")
        assertContains(actions.launch(stoppedSim, "com.example.app").message, "Start iPhone 18 Pro first")
        assertContains(actions.launch(emu, "com.missing.app").message, "isn't installed")
        assertContains(DeviceActions(FakeRunner(emptyMap()), ToolPaths(null, null, null)).launch(emu, "com.example.app").message, "adb not found")
    }

    @Test
    fun `a missing iOS app gets a plain message instead of simctl's error code`() {
        val runner = FakeRunner(mapOf(
            "$xcrun simctl launch $udid com.example.missing" to CommandResult(4, "", "An error was encountered processing the command (domain=FBSOpenApplicationServiceErrorDomain, code=4):"),
            "$xcrun simctl get_app_container $udid com.example.missing" to CommandResult(2, "", "No such file or directory"),
        ))
        assertEquals("com.example.missing isn't installed on iPhone 18 Pro.", DeviceActions(runner, tools).launch(sim, "com.example.missing").message)
    }

    @Test
    fun `clearing simulator data empties Documents, Library and tmp only`() {
        val root = Files.createTempDirectory("sim").toFile()
        val container = java.io.File(root, "CoreSimulator/Devices/$udid/data/Containers/Data/Application/7D3E1A2B-1111-2222-3333-444455556666")
        listOf("Documents/notes.db", "Library/Preferences/com.example.app.plist", "Library/Caches/img/a.png", "tmp/x", "SystemData/keep").forEach {
            java.io.File(container, it).apply { parentFile.mkdirs(); writeText("data") }
        }
        val metadata = java.io.File(container, ".com.apple.mobile_container_manager.metadata.plist").apply { writeText("meta") }
        val app = "com.example.app"
        val runner = FakeRunner(mapOf(
            "$xcrun simctl get_app_container $udid $app data" to ok(container.path + "\n"),
            "$xcrun simctl terminate $udid $app" to ok(),
        ))
        val result = DeviceActions(runner, tools).clearData(sim, app)
        assertTrue(result.ok, result.message)
        for (dir in listOf("Documents", "Library", "tmp")) {
            val d = java.io.File(container, dir)
            assertTrue(d.isDirectory, "$dir itself is kept")
            assertEquals(0, d.listFiles()!!.size, "$dir is emptied")
        }
        assertTrue(java.io.File(container, "SystemData/keep").exists())
        assertTrue(metadata.exists(), "container metadata must survive")
        assertTrue(runner.ran.contains("$xcrun simctl terminate $udid $app"), "app is stopped first")
    }

    @Test
    fun `refuses to clear anything that isn't the app's simulator data container`() {
        assertTrue(DeviceActions.isSimulatorDataContainer("/Users/a/Library/Developer/CoreSimulator/Devices/$udid/data/Containers/Data/Application/7D3E1A2B-1111-2222-3333-444455556666", udid))
        assertFalse(DeviceActions.isSimulatorDataContainer("/Users/a", udid))
        assertFalse(DeviceActions.isSimulatorDataContainer("/Users/a/Library/Developer/CoreSimulator/Devices/OTHER-UDID/data/Containers/Data/Application/7D3E1A2B-1111-2222-3333-444455556666", udid))
        assertFalse(DeviceActions.isSimulatorDataContainer("/Users/a/Library/Developer/CoreSimulator/Devices/$udid/data/Containers/Data/Application", udid))

        var deleted = 0
        val files = object : FileOps {
            override fun children(path: String) = listOf("$path/x").also { deleted++ }
            override fun deleteRecursively(path: String) = true.also { deleted++ }
        }
        val runner = FakeRunner(mapOf("$xcrun simctl get_app_container $udid com.example.app data" to ok("/Users/a\n")))
        val result = DeviceActions(runner, tools, files).clearData(sim, "com.example.app")
        assertFalse(result.ok)
        assertEquals(0, deleted, "nothing touched")
    }

    @Test
    fun `detects app ids like the KMP wizard writes them`() {
        val composeApp = """
            android {
                namespace = "org.example.project"
                defaultConfig {
                    applicationId = "org.example.project"
                    applicationIdSuffix = ".debug"
                }
            }
        """.trimIndent()
        val groovy = "android {\n  defaultConfig {\n    applicationId 'com.legacy.app'\n  }\n}"
        val xcconfig = "TEAM_ID=\n\nPRODUCT_NAME=KotlinProject\nPRODUCT_BUNDLE_IDENTIFIER=org.example.project.KotlinProject\$(TEAM_ID)\n\nCURRENT_PROJECT_VERSION=1\n"
        val pbxproj = """
            PRODUCT_BUNDLE_IDENTIFIER = "${'$'}{PRODUCT_BUNDLE_IDENTIFIER}";
            PRODUCT_BUNDLE_IDENTIFIER = org.example.project.KotlinProjectTests;
        """.trimIndent()
        val apps = AppDetector.fromContents(listOf(composeApp, groovy), listOf(xcconfig), listOf(pbxproj))
        assertEquals(listOf("org.example.project", "com.legacy.app"), apps.android)
        assertEquals(listOf("org.example.project.KotlinProject"), apps.ios)
    }

    @Test
    fun `detects app ids from a project folder`() {
        val root = Files.createTempDirectory("proj").toFile()
        fun write(path: String, text: String) = java.io.File(root, path).apply { parentFile.mkdirs(); writeText(text) }
        write("composeApp/build.gradle.kts", "android { defaultConfig { applicationId = \"com.acme.notes\" } }".replace("{ a", "{\n a").replace(" applicationId", "\napplicationId"))
        write("iosApp/Configuration/Config.xcconfig", "PRODUCT_BUNDLE_IDENTIFIER=com.acme.notes.ios\n")
        write("composeApp/build/generated/build.gradle.kts", "applicationId = \"com.should.skip\"\n")
        val apps = AppDetector.detect(root)
        assertEquals(listOf("com.acme.notes"), apps.android)
        assertEquals(listOf("com.acme.notes.ios"), apps.ios)
    }

    @Test
    fun `installs apks and simulator apps, and explains the usual failures`() {
        val dir = Files.createTempDirectory("builds").toFile()
        val apk = java.io.File(dir, "app-debug.apk").apply { writeText("x") }
        val split = java.io.File(dir, "split_config.arm64_v8a.apk").apply { writeText("x") }
        val app = java.io.File(dir, "iosApp.app").apply { mkdirs() }
        val aab = java.io.File(dir, "app.aab").apply { writeText("x") }
        val ipa = java.io.File(dir, "App.ipa").apply { writeText("x") }
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 install -r -t -d ${apk.path}" to ok("Performing Streamed Install\nSuccess"),
            "$adb -s emulator-5554 install-multiple -r -t -d ${apk.path} ${split.path}" to ok("Success"),
            "$xcrun simctl install $udid ${app.path}" to ok(),
            "$adb -s R58M install -r -t -d ${apk.path}" to CommandResult(1, "", "adb: failed to install ${apk.path}: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match]"),
        ))
        val actions = DeviceActions(runner, tools)
        assertEquals("Installed app-debug.apk on Pixel 9.", actions.install(emu, listOf(apk)).message)
        assertEquals("Installed 2 files on Pixel 9.", actions.install(emu, listOf(apk, split)).message)
        assertEquals("Installed iosApp.app on iPhone 18 Pro.", actions.install(sim, listOf(app)).message)
        assertContains(actions.install(phone, listOf(apk)).message, "signed with a different key")
        assertContains(actions.install(emu, listOf(aab)).message, "App Bundle")
        assertContains(actions.install(sim, listOf(ipa)).message, "device build")
        assertContains(actions.install(sim, listOf(apk)).message, "isn't an .app")
        assertContains(actions.install(stoppedSim, listOf(app)).message, "Start iPhone 18 Pro first")
    }
}
