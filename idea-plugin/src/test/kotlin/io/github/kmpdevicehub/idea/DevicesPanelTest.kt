package io.github.kmpdevicehub.idea

import com.intellij.openapi.wm.ToolWindowEP
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.kmpdevicehub.core.Device
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
            Device("avd:Pixel_Tablet", "Pixel Tablet", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.STOPPED, apiLevel = 36),
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

    /** Renders build/devices-panel.png for a visual check. */
    fun `test render snapshot`() {
        val panel = DevicesPanel(project, testRootDisposable, load = { sample }, autoRefresh = false)
        assertTrue(waitFor { panel.rowCount == 9 })
        panel.setSize(420, 640)
        com.intellij.util.ui.UIUtil.uiTraverser(panel).forEach { it.doLayout() }
        val image = java.awt.image.BufferedImage(840, 1280, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(2.0, 2.0)
        panel.printAll(g)
        g.dispose()
        javax.imageio.ImageIO.write(image, "png", File(System.getProperty("user.dir"), "build/devices-panel.png"))
    }
}
