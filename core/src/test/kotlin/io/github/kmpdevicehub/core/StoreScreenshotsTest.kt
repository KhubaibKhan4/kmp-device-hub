package io.github.kmpdevicehub.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreScreenshotsTest {
    private val tools = ToolPaths("/adb", null, "/xcrun")
    private val sim = Device("U", "iPhone 17", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")
    private val oldEmu = Device("emulator-5554", "Pixel 4", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "12", 31)

    /** Every command succeeds; screenshots write a file so the capture is real. */
    private class Recorder : CommandRunner {
        val ran = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
            ran += command.joinToString(" ")
            if (command.getOrNull(2) == "io" && command.getOrNull(4) == "screenshot") File(command[5]).writeText("png")
            if ("pull" in command) File(command.last()).writeText("png")
            return CommandResult(0, when { "launch" in command -> "app: 1"; "monkey" in command -> "Events injected: 1"; else -> "" }, "")
        }
        override fun spawn(command: List<String>) {}
        override fun start(command: List<String>): RunningProcess? = null
    }

    @Test
    fun `captures each language and appearance, then restores the device`() {
        val runner = Recorder()
        val out = Files.createTempDirectory("store").toFile()
        val shots = StoreScreenshots(DeviceSettings(runner, tools), DeviceSimulation(runner, tools), DeviceActions(runner, tools), settleMillis = 0, sleep = {})
        val arabic = AppLanguage.COMMON.first { it.android == "ar" }
        val result = shots.capture(sim, "com.example.shop", StoreShotPlan(listOf(null, arabic), darkToo = true), out)
        assertTrue(result.ok, result.message)
        assertContains(result.message, "4 screenshot(s) from iPhone 17")
        assertEquals(listOf("ar-dark.png", "ar-light.png", "device-dark.png", "device-light.png"), File(out, "iPhone 17").list()!!.sorted())
        assertTrue(runner.ran.any { it.contains("status_bar U override --time 9:41") }, "clean status bar first")
        assertEquals("/xcrun simctl status_bar U clear", runner.ran.last(), "status bar restored last")
        assertTrue(runner.ran.contains("/xcrun simctl ui U appearance light"), "back to light mode")
    }

    @Test
    fun `warns when an isn't-responding dialog covers the screen`() {
        val runner = object : CommandRunner {
            override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
                if ("pull" in command) File(command.last()).writeText("png")
                val out = when {
                    "dumpsys" in command -> "  Window #6 Window{3de852b u0 Application Not Responding: com.google.android.apps.wellbeing}:"
                    "monkey" in command -> "Events injected: 1"
                    else -> ""
                }
                return CommandResult(0, out, "")
            }
            override fun spawn(command: List<String>) {}
            override fun start(command: List<String>): RunningProcess? = null
        }
        val emu = oldEmu.copy(apiLevel = 37, osVersion = "17")
        val result = StoreScreenshots(DeviceSettings(runner, tools), DeviceSimulation(runner, tools), DeviceActions(runner, tools), 0, {})
            .capture(emu, "com.example.shop", StoreShotPlan(listOf(null)), Files.createTempDirectory("store").toFile())
        assertFalse(result.ok)
        assertContains(result.message, "com.google.android.apps.wellbeing isn't responding")
    }

    @Test
    fun `older Android keeps the device language only and says why`() {
        val runner = Recorder()
        val out = Files.createTempDirectory("store").toFile()
        val shots = StoreScreenshots(DeviceSettings(runner, tools), DeviceSimulation(runner, tools), DeviceActions(runner, tools), settleMillis = 0, sleep = {})
        val result = shots.capture(oldEmu, "com.example.shop", StoreShotPlan(listOf(null, AppLanguage.COMMON[1])), out)
        assertTrue(result.ok, result.message)
        assertContains(result.message, "Android 13+")
        assertEquals(listOf("device-light.png"), File(out, "Pixel 4").list()!!.sorted())
        assertFalse(shots.capture(oldEmu.copy(state = DeviceState.STOPPED), "x.y", StoreShotPlan(listOf(null)), out).ok)
    }
}
