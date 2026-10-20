package io.github.kmpdevicehub.idea

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.kmpdevicehub.core.CommandResult
import io.github.kmpdevicehub.core.CommandRunner
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DeviceKind
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceState
import io.github.kmpdevicehub.core.LogLevel
import io.github.kmpdevicehub.core.LogStreamer
import io.github.kmpdevicehub.core.ProjectApps
import io.github.kmpdevicehub.core.RunningProcess
import io.github.kmpdevicehub.core.ToolPaths
import java.util.concurrent.CopyOnWriteArrayList

class LogsPanelTest : BasePlatformTestCase() {
    private val emu = Device("emulator-5554", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "17", 37)
    private val sim = Device("U", "iPhone 17", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")

    /** pidof answers [pid]; logcat/log stream emit canned lines on a background thread, like a real stream. */
    private class StreamRunner : CommandRunner {
        @Volatile var pid = "4321"
        val streamed = CopyOnWriteArrayList<String>()
        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
            val c = command.joinToString(" ")
            return when {
                "pidof" in c -> CommandResult(0, "$pid\n", "")
                "get_app_container" in c -> CommandResult(0, "/b/iosApp.app\n", "")
                "plutil" in c -> CommandResult(0, "iosApp\n", "")
                else -> CommandResult(0, "", "")
            }
        }
        override fun spawn(command: List<String>) {}
        override fun start(command: List<String>): RunningProcess? = null
        override fun stream(command: List<String>, onLine: (String) -> Unit): RunningProcess {
            val c = command.joinToString(" ")
            streamed += c
            val lines = when {
                "--pid=4321" in c -> listOf(
                    "10-19 12:00:00.001  4321  4321 D Shop    : Loading products",
                    "10-19 12:00:00.002  4321  4330 W Shop    : Slow response (2.1 s)",
                    "10-19 12:00:00.003  4321  4330 E Shop    : java.io.IOException: timeout",
                )
                "--pid=5555" in c -> listOf("10-19 12:01:00.000  5555  5555 I Shop    : Started again")
                "--pid=6666" in c -> listOf(
                    "10-19 12:02:00.000  6666  6666 D Shop    : Checkout tapped",
                    "10-19 12:02:00.001  6666  6666 E AndroidRuntime: FATAL EXCEPTION: main",
                    "10-19 12:02:00.001  6666  6666 E AndroidRuntime: Process: com.example.shop, PID: 6666",
                    "10-19 12:02:00.001  6666  6666 E AndroidRuntime: java.lang.IllegalStateException: Cart is empty",
                    "10-19 12:02:00.001  6666  6666 E AndroidRuntime: \tat android.view.View.performClick(View.java:8024)",
                    "10-19 12:02:00.001  6666  6666 E AndroidRuntime: \tat com.example.shop.CartViewModel.checkout(CartViewModel.kt:2)",
                )
                "log stream" in c -> listOf("2026-10-19 12:00:00.100 Df iosApp[9:a] [shop:ui] Rendered home")
                "--console-pty" in c -> listOf("com.example.shop: 9", "println from Kotlin")
                else -> emptyList()
            }
            Thread { lines.forEach(onLine) }.start()
            return object : RunningProcess {
                override val pid = 1L
                override val isAlive = true
                override fun waitFor(timeoutSeconds: Long) = true
                override fun kill() {}
            }
        }
    }

    override fun tearDown() {
        try {
            val props = com.intellij.ide.util.PropertiesComponent.getInstance(project)
            listOf("ANDROID", "IOS").forEach { props.unsetValue("kmpdevicehub.app.$it") }
        } finally {
            super.tearDown()
        }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(20) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return condition()
    }

    fun `test tool window is registered`() {
        assertTrue(com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList.any { it.id == "KMP Logs" && it.factoryClass == LogsToolWindowFactory::class.java.name })
    }

    fun `test streams android logs, filters them and follows a restart`() {
        val runner = StreamRunner()
        val panel = LogsPanel(project, testRootDisposable, LogStreamer(runner, ToolPaths("/adb", null, "/xcrun")), devices = { listOf(emu, sim) },
            detectApps = { ProjectApps(listOf("com.example.shop"), listOf("com.example.shop")) }, autoRefresh = false)
        panel.refreshDevicesForTest()
        assertTrue(waitFor { true })
        panel.selectDevice("emulator-5554")
        panel.setApp("com.example.shop")
        panel.start()
        assertTrue("lines: ${panel.shownLines}", waitFor { panel.shownLines.size == 3 })
        assertTrue(runner.streamed.contains("/adb -s emulator-5554 logcat -v threadtime --pid=4321"))
        assertTrue(panel.statusText, panel.statusText.startsWith("Streaming com.example.shop (pid 4321) on Pixel 9"))

        panel.setLevel(LogLevel.WARN)
        assertEquals(2, panel.shownLines.size)
        panel.setSearch("timeout")
        assertEquals(listOf("12:00:00.003 E Shop: java.io.IOException: timeout"), panel.shownLines)
        panel.setSearch("")
        panel.setLevel(LogLevel.VERBOSE)

        runner.pid = "5555"
        ApplicationManagerHelper.onPooled { panel.followForTest() }
        assertTrue(waitFor { panel.shownLines.any { "Started again" in it } })
        assertTrue(panel.shownLines.any { "started (pid 5555)" in it })
        panel.stop()
        assertFalse(panel.isStreaming)
    }

    fun `test lists crashes, links frames to project files and exports the log`() {
        myFixture.addFileToProject("shared/src/commonMain/kotlin/com/example/shop/CartViewModel.kt", "package com.example.shop\n\nclass CartViewModel {\n    fun checkout() = error(\"Cart is empty\")\n}\n")
        val runner = StreamRunner().apply { pid = "6666" }
        val panel = LogsPanel(project, testRootDisposable, LogStreamer(runner, ToolPaths("/adb", null, "/xcrun")), devices = { listOf(emu) },
            detectApps = { ProjectApps(listOf("com.example.shop"), emptyList()) }, autoRefresh = false)
        panel.refreshDevicesForTest()
        assertTrue(waitFor { true })
        panel.setApp("com.example.shop")
        assertFalse(panel.crashPaneShown)
        panel.start()
        assertTrue("crashes: ${panel.crashTitles}", waitFor { panel.linkedFrames.isNotEmpty() })
        assertEquals(listOf("java.lang.IllegalStateException: Cart is empty"), panel.crashTitles)
        assertTrue(panel.crashPaneShown)
        assertEquals(listOf("CartViewModel.kt:2"), panel.linkedFrames)
        assertEquals("    at com.example.shop.CartViewModel.checkout(CartViewModel.kt:2)", panel.selectedTraceLine)
        assertTrue(panel.statusText, panel.statusText.endsWith("6 of 6 lines  ·  1 crash"))

        val exported = panel.exportText().lines()
        assertTrue(exported[0], exported[0].startsWith("# com.example.shop  ·  Pixel 9 (Android 17)  ·  "))
        assertEquals("12:02:00.000 D Shop: Checkout tapped", exported[1])
        assertEquals(8, exported.size) // header, six lines, trailing newline
    }

    fun `test streams the iOS log, or the app's println output`() {
        val runner = StreamRunner()
        val panel = LogsPanel(project, testRootDisposable, LogStreamer(runner, ToolPaths("/adb", null, "/xcrun")), devices = { listOf(sim) },
            detectApps = { ProjectApps(emptyList(), listOf("com.example.shop")) }, autoRefresh = false)
        panel.refreshDevicesForTest()
        assertTrue(waitFor { true })
        panel.selectDevice("U")
        panel.setApp("com.example.shop")
        panel.start()
        assertTrue(waitFor { panel.shownLines.any { "Rendered home" in it } })
        assertTrue(runner.streamed.any { it.contains("simctl spawn U log stream") && it.endsWith("process == \"iosApp\"") })

        panel.setIncludePrintln(true)
        panel.start()
        assertTrue(waitFor { panel.shownLines.any { "println from Kotlin" in it } })
        assertTrue(runner.streamed.contains("/xcrun simctl launch --console-pty --terminate-running-process U com.example.shop"))
    }
}

private object ApplicationManagerHelper {
    fun onPooled(block: () -> Unit) { com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(block) }
}
