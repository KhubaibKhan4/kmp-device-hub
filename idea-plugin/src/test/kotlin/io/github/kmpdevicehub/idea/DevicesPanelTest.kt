package io.github.kmpdevicehub.idea

import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.kmpdevicehub.core.CommandResult
import io.github.kmpdevicehub.core.CommandRunner
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DeviceActions
import io.github.kmpdevicehub.core.DeviceSettings
import io.github.kmpdevicehub.core.AppPermission
import io.github.kmpdevicehub.core.ProjectApps
import io.github.kmpdevicehub.core.ToolPaths
import io.github.kmpdevicehub.core.DeviceKind
import io.github.kmpdevicehub.core.DeviceList
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceState
import java.io.File

class DevicesPanelTest : BasePlatformTestCase() {

    private val sample = DeviceList(
        listOf(
            Device("emulator-5554", "Medium Phone API 37.0", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "17", 37, "Medium_Phone_API_37.0"),
            Device("UDID-1", "iPhone 18 Pro", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0"),
            Device("R58M", "Samsung SM-G991B", DevicePlatform.ANDROID, DeviceKind.PHYSICAL, DeviceState.UNAUTHORIZED),
            Device("avd:Pixel_Tablet", "Pixel Tablet", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.STOPPED, apiLevel = 36, avdName = "Pixel_Tablet"),
            Device("UDID-2", "iPad Air 11-inch (M4)", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.STOPPED, "27.0"),
            Device("UDID-3", "iPhone 17e", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.STOPPED, "27.0"),
        ),
    )

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return condition()
    }

    fun `test tool window is registered`() {
        assertTrue(ToolWindowEP.EP_NAME.extensionList.any { it.id == "KMP Devices" && it.factoryClass == DevicesToolWindowFactory::class.java.name })
    }

    fun `test rows are grouped running, Android, iOS`() {
        val rows = DevicesPanel.rowsFor(sample)
        val headers = rows.filterIsInstance<Row.Header>().map { it.title to it.count }
        assertEquals(listOf("Running" to 3, "Android emulators" to 1, "iOS simulators" to 2), headers)
        assertEquals(9, rows.size)
    }

    fun `test panel shows devices and problems`() {
        val withProblem = sample.copy(problems = listOf("Xcode not found. iOS simulators need Xcode from the App Store."))
        val panel = DevicesPanel(project, testRootDisposable, load = { withProblem }, autoRefresh = false)
        assertTrue(waitFor { panel.rowCount == 9 })
        assertEquals("6 devices · 2 running", panel.statusText)
        assertEquals(1, panel.noticeCount)
    }

    /** Uses the real adb/xcrun when present; on machines without them it must report problems, not crash. */
    fun `test real discovery never throws`() {
        val panel = DevicesPanel(project, testRootDisposable, autoRefresh = false)
        assertTrue(waitFor(60_000) { panel.lastList.devices.isNotEmpty() || panel.lastList.problems.isNotEmpty() })
        println("LIVE ${panel.lastList.devices.size} devices, problems=${panel.lastList.problems}")
    }

    /** Records commands and answers like adb/simctl would for a healthy app. */
    private class RecordingRunner : CommandRunner {
        val ran = java.util.concurrent.CopyOnWriteArrayList<String>()
        val spawned = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
            val c = command.joinToString(" ")
            ran += c
            return when {
                "pm path" in c -> CommandResult(0, "package:/data/app/base.apk", "")
                "monkey" in c -> CommandResult(0, "Events injected: 1", "")
                "pm clear" in c -> CommandResult(0, "Success", "")
                "cmd uimode night" == c.substringAfter("shell ") -> CommandResult(0, "Night mode: no", "")
                "dumpsys package" in c -> CommandResult(0, "requested permissions:\n  android.permission.CAMERA\nruntime permissions:\n  android.permission.CAMERA: granted=false, flags=[ ]\n", "")
                "am start -W" in c -> CommandResult(0, "Status: ok", "")
                "resolve-activity" in c -> CommandResult(0, "org.example.project/.MainActivity\n", "")
                else -> CommandResult(0, "", "")
            }
        }
        override fun spawn(command: List<String>) { spawned += command.joinToString(" ") }
        val started = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun start(command: List<String>): io.github.kmpdevicehub.core.RunningProcess {
            started += command.joinToString(" ")
            return object : io.github.kmpdevicehub.core.RunningProcess {
                override val pid = 777L
                override val isAlive = true
                override fun waitFor(timeoutSeconds: Long) = true
                override fun kill() {}
            }
        }
    }

    fun `test app id is detected per platform and actions run on the selected device`() {
        val runner = RecordingRunner()
        val actions = DeviceActions(runner, ToolPaths("/sdk/adb", "/sdk/emulator", "/usr/bin/xcrun"))
        var confirmed = 0
        val panel = DevicesPanel(
            project, testRootDisposable, load = { sample }, actions = actions,
            detectApps = { ProjectApps(listOf("org.example.project"), listOf("org.example.project.KotlinProject")) },
            autoRefresh = false, confirm = { _, _ -> confirmed++; true },
        )
        assertTrue("step 1: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { panel.rowCount == 9 })

        panel.select("emulator-5554")
        assertTrue("step 2: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { panel.currentAppId == "org.example.project" })
        assertTrue("step 3: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { panel.installedText.startsWith("✓ Installed") })
        panel.clickLaunch()
        assertTrue("step 4: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { panel.lastResult?.message?.startsWith("Launched org.example.project") == true })
        assertTrue(runner.ran.any { it.contains("-s emulator-5554 shell monkey -p org.example.project") })

        panel.clickClearData()
        assertTrue("step 5: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { panel.lastResult?.message?.startsWith("Cleared org.example.project data") == true })
        assertEquals(1, confirmed)

        panel.select("UDID-1")
        assertTrue("step 6: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { panel.currentAppId == "org.example.project.KotlinProject" })
        panel.clickLaunch()
        assertTrue("step 7: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { runner.ran.any { it == "/usr/bin/xcrun simctl launch UDID-1 org.example.project.KotlinProject" } })

        panel.select("avd:Pixel_Tablet")
        assertTrue("step 8: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { panel.installedText.startsWith("Start Pixel Tablet") })
        panel.startSelected()
        assertTrue("step 9: app='${panel.currentAppId}' installed='${panel.installedText}' result='${panel.lastResult}' ran=${runner.ran.takeLast(3)}", waitFor { runner.spawned.any { it == "/sdk/emulator -avd Pixel_Tablet" } })
    }

    fun `test settings, permissions, links and screenshot tabs run the right commands`() {
        val runner = RecordingRunner()
        val tools = ToolPaths("/sdk/adb", "/sdk/emulator", "/usr/bin/xcrun")
        val shots = java.nio.file.Files.createTempDirectory("shots").toFile()
        val panel = DevicesPanel(
            project, testRootDisposable, load = { sample }, actions = DeviceActions(runner, tools), settings = DeviceSettings(runner, tools),
            screenshotDir = shots, detectApps = { ProjectApps(listOf("org.example.project"), listOf("org.example.project.KotlinProject")) },
            autoRefresh = false,
        )
        assertTrue(waitFor { panel.rowCount == 9 })
        panel.select("emulator-5554")
        assertTrue(waitFor { panel.currentAppId == "org.example.project" })
        assertTrue("camera state: '${panel.permissionStateText(AppPermission.CAMERA)}'", waitFor { panel.permissionStateText(AppPermission.CAMERA) == "Denied" })
        assertEquals("Not requested", panel.permissionStateText(AppPermission.MICROPHONE))

        panel.toggleDarkMode(true)
        assertTrue(waitFor { runner.ran.any { it == "/sdk/adb -s emulator-5554 shell cmd uimode night yes" } })

        panel.grant(AppPermission.CAMERA)
        assertTrue(waitFor { runner.ran.any { it == "/sdk/adb -s emulator-5554 shell pm grant org.example.project android.permission.CAMERA" } })

        panel.openLinkForTest("myapp://orders/42")
        assertTrue(waitFor { runner.ran.any { it.endsWith("-d myapp://orders/42 org.example.project") } })

        panel.takeScreenshot()
        assertTrue(waitFor { runner.ran.any { it.startsWith("/sdk/adb -s emulator-5554 pull /data/local/tmp/kmpdevicehub-screenshot.png ${shots.path}/Medium Phone API 37.0 ") } })
        assertTrue(waitFor { panel.lastResult?.message?.startsWith("Screenshot saved") == true })

        panel.select("UDID-1")
        assertTrue(waitFor { panel.currentAppId == "org.example.project.KotlinProject" })
        assertTrue(waitFor { panel.permissionStateText(AppPermission.CAMERA) == "Not on simulator" })
        panel.toggleDarkMode(true)
        assertTrue(waitFor { runner.ran.any { it == "/usr/bin/xcrun simctl ui UDID-1 appearance dark" } })
    }

    private fun proPanel(runner: RecordingRunner, pro: Boolean, upsells: MutableList<String> = mutableListOf()): DevicesPanel {
        val tools = ToolPaths("/sdk/adb", "/sdk/emulator", "/usr/bin/xcrun")
        return DevicesPanel(
            project, testRootDisposable, load = { sample }, actions = DeviceActions(runner, tools), settings = DeviceSettings(runner, tools),
            simulation = io.github.kmpdevicehub.core.DeviceSimulation(runner, tools),
            recordingDir = java.nio.file.Files.createTempDirectory("rec").toFile(),
            detectApps = { ProjectApps(listOf("org.example.project"), listOf("org.example.project.KotlinProject")) },
            detectLinks = { listOf("myapp://orders") },
            autoRefresh = false, isPro = { pro }, upsell = { upsells += it },
        )
    }

    fun `test pro features run on every running device`() {
        val runner = RecordingRunner()
        val panel = proPanel(runner, pro = true)
        assertTrue(waitFor { panel.rowCount == 9 })
        assertEquals(listOf("App", "Settings", "Permissions", "Links", "Simulate", "Push", "Presets"), panel.tabTitles)
        panel.select("emulator-5554")
        assertTrue(waitFor { panel.currentAppId == "org.example.project" })

        panel.setAllDevices(true)
        assertTrue(panel.allDevicesOn)
        panel.toggleDarkMode(true)
        assertTrue(waitFor { runner.ran.contains("/sdk/adb -s emulator-5554 shell cmd uimode night yes") && runner.ran.contains("/usr/bin/xcrun simctl ui UDID-1 appearance dark") })
        assertTrue(waitFor { panel.lastResult?.message == "Done on all 2 devices." })

        // Each platform launches its own app id.
        panel.clickLaunch()
        assertTrue(waitFor { runner.ran.contains("/usr/bin/xcrun simctl launch UDID-1 org.example.project.KotlinProject") && runner.ran.any { "monkey -p org.example.project " in it } })

        panel.simulate.setPlace(4) // Karachi
        panel.simulate.clickSetLocation()
        assertTrue(waitFor { runner.ran.contains("/sdk/adb -s emulator-5554 emu geo fix 67.0011 24.8607") && runner.ran.contains("/usr/bin/xcrun simctl location UDID-1 set 24.8607,67.0011") })

        panel.presets.applyPreset("Right-to-left (Arabic)")
        assertTrue(waitFor { runner.ran.contains("/sdk/adb -s emulator-5554 shell cmd locale set-app-locales org.example.project --locales ar") })

        panel.setAllDevices(false)
        panel.simulate.applyNetwork(io.github.kmpdevicehub.core.NetworkProfile.EDGE)
        assertTrue(waitFor { runner.ran.contains("/sdk/adb -s emulator-5554 emu network speed edge") })

        panel.push.sendWith("Hello", "orderId=42")
        assertTrue(waitFor { runner.ran.any { it.contains("-n org.example.project/.MainActivity") && it.endsWith("--es orderId 42") } })

        panel.links.saveLink("myapp://orders/7")
        assertEquals(listOf("myapp://orders/7", "myapp://orders"), panel.links.libraryLinks)

        panel.presets.save(io.github.kmpdevicehub.core.TestPreset("My preset", darkMode = true))
        assertTrue("My preset" in panel.presets.presetNames)

        panel.toggleRecordingForTest()
        assertTrue(panel.isRecording)
        assertTrue(runner.started.single().contains("shell screenrecord"))
        panel.toggleRecordingForTest()
        assertTrue(waitFor { panel.lastResult?.message?.startsWith("Recording saved to ") == true })
        assertTrue(runner.ran.contains("/sdk/adb -s emulator-5554 shell pkill -INT screenrecord"))
    }

    fun `test pro features explain Pro instead of running when locked`() {
        val runner = RecordingRunner()
        val upsells = mutableListOf<String>()
        val panel = proPanel(runner, pro = false, upsells = upsells)
        assertTrue(waitFor { panel.rowCount == 9 })
        panel.select("emulator-5554")
        assertTrue(waitFor { panel.currentAppId == "org.example.project" })
        val before = runner.ran.size

        panel.setAllDevices(true)
        assertFalse("stays off without Pro", panel.allDevicesOn)
        panel.simulate.clickSetLocation()
        panel.presets.applyPreset("Offline")
        panel.toggleRecordingForTest()
        assertFalse(panel.isRecording)
        assertEquals(listOf("Actions on all running devices", "Simulated location", "Test presets", "Screen recording"), upsells)
        assertTrue(runner.ran.drop(before).none { "geo fix" in it || "airplane-mode" in it })

        // Free features keep working.
        panel.toggleDarkMode(true)
        assertTrue(waitFor { runner.ran.contains("/sdk/adb -s emulator-5554 shell cmd uimode night yes") })
    }

    /** Renders build/devices-panel.png for a visual check. */
    fun `test render snapshot`() {
        val panel = DevicesPanel(project, testRootDisposable, load = { sample }, autoRefresh = false,
            detectApps = { ProjectApps(listOf("org.example.project"), listOf("org.example.project.KotlinProject")) },
            actions = DeviceActions(RecordingRunner(), ToolPaths("/sdk/adb", "/sdk/emulator", "/usr/bin/xcrun")))
        assertTrue(waitFor { panel.rowCount == 9 })
        panel.select("emulator-5554")
        assertTrue(waitFor { panel.installedText.startsWith("✓") })
        val names = listOf("App", "Simulate", "Push", "Presets")
        val image = java.awt.image.BufferedImage(880 * names.size, 1520, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(2.0, 2.0)
        for (name in names) {
            panel.selectTab(name)
            panel.setSize(440, 760)
            com.intellij.util.ui.UIUtil.uiTraverser(panel).forEach { it.doLayout() }
            panel.printAll(g)
            g.translate(440, 0)
        }
        g.dispose()
        javax.imageio.ImageIO.write(image, "png", File(System.getProperty("user.dir"), "build/devices-panel.png"))
    }
}
