package io.github.kmpdevicehub.idea

import com.intellij.ide.ui.LafManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.kmpdevicehub.core.ActionResult
import io.github.kmpdevicehub.core.CommandResult
import io.github.kmpdevicehub.core.CommandRunner
import io.github.kmpdevicehub.core.Connectivity
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DeviceActions
import io.github.kmpdevicehub.core.DeviceKind
import io.github.kmpdevicehub.core.DeviceList
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceSettings
import io.github.kmpdevicehub.core.DeviceState
import io.github.kmpdevicehub.core.NetworkLog
import io.github.kmpdevicehub.core.NetworkRule
import io.github.kmpdevicehub.core.NetworkServer
import io.github.kmpdevicehub.core.ProjectApps
import io.github.kmpdevicehub.core.RunningProcess
import io.github.kmpdevicehub.core.ToolPaths
import java.awt.Component
import java.io.File
import java.net.Socket
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Renders the panels with realistic sample data into build/listing/ for Marketplace screenshots and the preview video.
 * Not an assertion-heavy test: it checks the panels render and keeps the images reproducible.
 */
class ListingRenderTest : BasePlatformTestCase() {

    private val devices = DeviceList(listOf(
        Device("emulator-5554", "Pixel 9 Pro API 36", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING, "16", 36, "Pixel_9_Pro_API_36"),
        Device("UDID-1", "iPhone 17 Pro", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0"),
        Device("R58M", "Samsung SM-S928B", DevicePlatform.ANDROID, DeviceKind.PHYSICAL, DeviceState.RUNNING, "15", 35),
        Device("avd:Pixel_Tablet", "Pixel Tablet API 36", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.STOPPED, apiLevel = 36, avdName = "Pixel_Tablet"),
        Device("avd:Small_Phone", "Small Phone API 34", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.STOPPED, apiLevel = 34, avdName = "Small_Phone"),
        Device("UDID-2", "iPad Air 11-inch (M4)", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.STOPPED, "27.0"),
        Device("UDID-3", "iPhone 17e", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.STOPPED, "27.0"),
        Device("UDID-4", "iPhone Air", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.STOPPED, "27.0"),
    ))

    /** Answers like a healthy device with the app installed. */
    private class HappyRunner : CommandRunner {
        override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
            val c = command.joinToString(" ")
            return when {
                "pm path" in c -> CommandResult(0, "package:/data/app/base.apk", "")
                "cmd uimode night" == c.substringAfter("shell ") -> CommandResult(0, "Night mode: yes", "")
                "font_scale" in c -> CommandResult(0, "1.15", "")
                "dumpsys package" in c -> CommandResult(0, """
                    requested permissions:
                      android.permission.CAMERA
                      android.permission.ACCESS_FINE_LOCATION
                      android.permission.POST_NOTIFICATIONS
                      android.permission.RECORD_AUDIO
                    runtime permissions:
                      android.permission.CAMERA: granted=true, flags=[ ]
                      android.permission.ACCESS_FINE_LOCATION: granted=false, flags=[ ]
                      android.permission.POST_NOTIFICATIONS: granted=true, flags=[ ]
                      android.permission.RECORD_AUDIO: granted=false, flags=[ ]
                """.trimIndent(), "")
                else -> CommandResult(0, "", "")
            }
        }
        override fun spawn(command: List<String>) {}
        override fun start(command: List<String>): RunningProcess? = null
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); Thread.sleep(20) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return condition()
    }

    private val out = File(System.getProperty("user.dir"), "build/listing").apply { mkdirs() }

    private fun render(component: JComponent, width: Int, height: Int, name: String) {
        component.setSize(width, height)
        repeat(2) {
            com.intellij.util.ui.UIUtil.uiTraverser(component).forEach { (it as? Component)?.doLayout() }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        val image = java.awt.image.BufferedImage(width * 2, height * 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(2.0, 2.0)
        component.printAll(g)
        g.dispose()
        ImageIO.write(image, "png", File(out, "$name.png"))
    }

    private fun renderAll(suffix: String) {
        val tools = ToolPaths("/sdk/adb", "/sdk/emulator", "/usr/bin/xcrun")
        val runner = HappyRunner()
        val panel = DevicesPanel(
            project, testRootDisposable, load = { devices }, actions = DeviceActions(runner, tools), settings = DeviceSettings(runner, tools),
            detectApps = { ProjectApps(listOf("com.example.shop"), listOf("com.example.shop.iosApp")) },
            detectLinks = { listOf("shop://orders", "https://shop.example.com/products") },
            autoRefresh = false, isPro = { true },
        )
        assertTrue(waitFor { panel.rowCount == 11 })
        panel.select("emulator-5554")
        assertTrue(waitFor { panel.installedText.startsWith("✓") })
        for (tab in listOf("App", "Settings", "Permissions", "Links", "Simulate", "Push", "Presets")) {
            panel.selectTab(tab)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(150)
            render(panel, 440, 860, "devices-${tab.lowercase()}$suffix")
        }

        val server = NetworkServer(port = 0)
        server.start()
        val backend = object : InspectorBackend {
            override val log: NetworkLog get() = server.log
            override val port: Int get() = 38383
            override val isListening = true
            override fun start() = ActionResult.done("")
            override var rules: List<NetworkRule>
                get() = server.rules
                set(value) { server.rules = value }
            override fun runningDevices(): List<Device> = emptyList()
            override fun connectivity(device: Device) = Connectivity(true, 14.0, "Online")
        }
        val network = NetworkPanel(project, testRootDisposable, backend, isPro = { true }, autoRefresh = false)
        Socket("127.0.0.1", server.localPort).use { socket ->
            val now = System.currentTimeMillis()
            val w = socket.getOutputStream().writer()
            fun send(line: String) { w.write(line + "\n") }
            send("""{"type":"hello","v":1,"app":"com.example.shop","platform":"android","device":"Pixel 9 Pro API 36 (emulator)","library":"kmp-device-hub-network/0.1.0"}""")
            val calls = listOf(
                Triple("GET", "https://api.shop.example.com/v1/products?category=coffee&page=1", """{"items":[{"id":101,"name":"Ethiopia Yirgacheffe","price":14.5,"inStock":true,"tags":["light","floral"]},{"id":102,"name":"Colombia Huila","price":12.0,"inStock":true,"tags":["medium"]},{"id":103,"name":"Sumatra Mandheling","price":13.25,"inStock":false,"tags":["dark"]}],"page":1,"total":3}"""),
                Triple("GET", "https://api.shop.example.com/v1/cart", """{"items":[{"productId":101,"quantity":2}],"subtotal":29.0,"currency":"USD"}"""),
                Triple("POST", "https://api.shop.example.com/v1/orders", """{"error":"payment_declined","message":"Card was declined"}"""),
                Triple("GET", "https://api.shop.example.com/v1/profile", """{"id":"u_42","name":"Sara","tier":"gold"}"""),
                Triple("GET", "https://cdn.shop.example.com/img/hero.webp", ""),
                Triple("GET", "https://api.shop.example.com/v1/recommendations", ""),
            )
            val statuses = listOf(200, 200, 402, 200, 200, 0)
            val durations = listOf(184, 96, 412, 73, 220, 0)
            calls.forEachIndexed { i, (method, url, body) ->
                val id = "c$i"
                send("""{"type":"start","id":"$id","method":"$method","url":"$url","headers":[["Accept","application/json"],["Authorization","██"]],${if (method == "POST") "\"body\":\"{\\\"items\\\":[{\\\"productId\\\":101,\\\"quantity\\\":2}]}\",\"bodySize\":44," else ""}"time":${now - 60_000 + i * 7_000}}""")
                when {
                    i == 4 -> send("""{"type":"end","id":"$id","status":200,"headers":[["Content-Type","image/webp"]],"body":"(48211 bytes of image/webp)","bodySize":48211,"durationMs":${durations[i]}}""")
                    i == 5 -> send("""{"type":"fail","id":"$id","error":"UnknownHostException: Unable to resolve host \"api.shop.example.com\"","durationMs":31}""")
                    else -> send("""{"type":"end","id":"$id","status":${statuses[i]},"headers":[["Content-Type","application/json"],["Cache-Control","no-store"]],"body":${io.github.kmpdevicehub.core.Json.quote(body)},"bodySize":${body.length},"durationMs":${durations[i]}}""")
                }
            }
            w.flush()
            assertTrue(waitFor { network.rowCount == 6 && server.log.snapshot().none { it.state == io.github.kmpdevicehub.core.CallState.PENDING } })
            network.refreshForTest()
            network.selectRow(0)
            render(network, 1400, 420, "network-response$suffix")
            network.selectDetailsTab("JSON")
            render(network, 1400, 420, "network-json$suffix")
            network.selectRow(2)
            network.selectDetailsTab("Response")
            render(network, 1400, 420, "network-error$suffix")
        }
        server.stop()
    }

    fun `test render listing panels`() {
        // Marketing images only; large off-screen renders aren't worth CI time or memory.
        if (System.getenv("CI") != null) return
        // The plugin icon, rasterized by the IDE's own SVG renderer.
        val icon = com.intellij.openapi.util.IconLoader.getIcon("/META-INF/pluginIcon.svg", DevicesPanel::class.java.classLoader)
        val big = com.intellij.util.IconUtil.scale(icon, null, 512f / icon.iconWidth)
        val iconImage = java.awt.image.BufferedImage(big.iconWidth, big.iconHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        iconImage.createGraphics().also { big.paintIcon(null, it, 0, 0); it.dispose() }
        ImageIO.write(iconImage, "png", File(out, "plugin-icon.png"))
        renderAll("")
        // Dark theme too, when the test IDE has one installed.
        val laf = LafManager.getInstance()
        val dark = laf.installedThemes.firstOrNull { it.isDark }
        if (dark != null) {
            laf.setCurrentLookAndFeel(dark, false)
            laf.updateUI()
            renderAll("-dark")
        }
        println("LIVE listing renders: " + out.list()?.sorted())
    }
}
