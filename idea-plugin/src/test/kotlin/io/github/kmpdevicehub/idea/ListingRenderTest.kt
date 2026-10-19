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

    /** KMP Logs streaming an app's logcat, and KMP App Data showing a Room database. */
    private fun renderLogsAndData() {
        val logLines = listOf(
            "10-19 12:00:01.102  4321  4321 I ActivityManager: Start proc 4321:com.example.shop/u0a190 for top-activity",
            "10-19 12:00:01.480  4321  4321 D ShopApp : Koin started with 14 definitions",
            "10-19 12:00:01.912  4321  4350 D Ktor    : REQUEST: https://api.shop.example.com/v1/products?category=coffee",
            "10-19 12:00:02.096  4321  4350 D Ktor    : RESPONSE: 200 OK (184 ms)",
            "10-19 12:00:02.104  4321  4321 I Catalog : Loaded 3 products",
            "10-19 12:00:02.731  4321  4321 D Compose : Recomposed ProductList in 4 ms",
            "10-19 12:00:04.215  4321  4350 D Ktor    : REQUEST: POST https://api.shop.example.com/v1/orders",
            "10-19 12:00:04.627  4321  4350 W Checkout: Payment provider slow: 412 ms",
            "10-19 12:00:04.630  4321  4350 E Checkout: Order failed: 402 payment_declined (Card was declined)",
            "10-19 12:00:04.631  4321  4350 E Checkout: \tat com.example.shop.checkout.CheckoutViewModel.placeOrder(CheckoutViewModel.kt:88)",
            "10-19 12:00:05.010  4321  4321 I Analytics: event checkout_failed {reason=payment_declined}",
            "10-19 12:00:06.344  4321  4321 D Room    : Inserted 1 row into cart_items",
            "10-19 12:00:07.020  4321  4321 V Compose : Frame 1240 rendered in 7.9 ms",
        )
        val logRunner = object : CommandRunner {
            override fun run(command: List<String>, timeoutSeconds: Long) = CommandResult(0, if ("pidof" in command) "4321\n" else "", "")
            override fun spawn(command: List<String>) {}
            override fun start(command: List<String>): RunningProcess? = null
            override fun stream(command: List<String>, onLine: (String) -> Unit): RunningProcess? {
                logLines.forEach(onLine)
                return object : RunningProcess { override val pid = 1L; override val isAlive = true; override fun waitFor(timeoutSeconds: Long) = true; override fun kill() {} }
            }
        }
        val logs = LogsPanel(project, testRootDisposable, io.github.kmpdevicehub.core.LogStreamer(logRunner, ToolPaths("/adb", null, null)),
            devices = { listOf(devices.devices[0]) }, detectApps = { ProjectApps(listOf("com.example.shop"), emptyList()) }, autoRefresh = false)
        logs.refreshDevicesForTest()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        logs.selectDevice("emulator-5554")
        logs.setApp("com.example.shop")
        logs.setLevel(io.github.kmpdevicehub.core.LogLevel.VERBOSE)
        logs.start()
        assertTrue(waitFor { logs.shownLines.size == logLines.size })
        render(logs, 1400, 420, "logs")

        val sqlite = listOf("/usr/bin/sqlite3", "/opt/homebrew/bin/sqlite3").firstOrNull { File(it).canExecute() } ?: return
        val udid = "0A1B2C3D-0000-1111-2222-333344445555"
        val base = java.nio.file.Files.createTempDirectory("sim").toFile()
        val container = File(base, "CoreSimulator/Devices/$udid/data/Containers/Data/Application/7D3E1A2B-1111-2222-3333-444455556666")
        val db = File(container, "Library/Application Support/shop.db").apply { parentFile.mkdirs() }
        File(container, "Documents/cart.json").apply { parentFile.mkdirs(); writeText("{}") }
        File(container, "Library/Preferences/com.example.shop.plist").apply { parentFile.mkdirs(); writeText("") }
        io.github.kmpdevicehub.core.ProcessCommandRunner().run(listOf(sqlite, db.path,
            "CREATE TABLE products(id INTEGER PRIMARY KEY, name TEXT, price REAL, in_stock INTEGER, tags TEXT, updated_at TEXT);" +
                "INSERT INTO products VALUES (101,'Ethiopia Yirgacheffe',14.5,1,'light, floral','2026-10-19 11:58')," +
                "(102,'Colombia Huila',12.0,1,'medium','2026-10-19 11:58'),(103,'Sumatra Mandheling',13.25,0,'dark, earthy','2026-10-18 09:12')," +
                "(104,'Kenya AA',15.75,1,'bright, berry','2026-10-17 16:40'),(105,'Guatemala Antigua',13.0,1,'chocolate','2026-10-17 16:40');" +
                "CREATE TABLE cart_items(product_id INTEGER, quantity INTEGER); CREATE TABLE orders(id INTEGER PRIMARY KEY, total REAL, status TEXT);"))
        val sim = Device(udid, "iPhone 17 Pro", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING, "27.0")
        val dataRunner = object : CommandRunner {
            private val real = io.github.kmpdevicehub.core.ProcessCommandRunner()
            override fun run(command: List<String>, timeoutSeconds: Long) =
                if (command.getOrNull(1) == "simctl") CommandResult(0, container.path + "\n", "") else real.run(command, timeoutSeconds)
            override fun spawn(command: List<String>) {}
            override fun start(command: List<String>): RunningProcess? = null
        }
        val data = AppDataPanel(project, testRootDisposable, io.github.kmpdevicehub.core.AppDataExplorer(dataRunner, ToolPaths(null, null, "/usr/bin/xcrun"), sqlite),
            devices = { listOf(sim) }, detectApps = { ProjectApps(emptyList(), listOf("com.example.shop")) }, confirm = { _, _ -> false })
        assertTrue(waitFor { data.deviceCount == 1 })
        data.selectDevice(udid)
        data.setApp("com.example.shop")
        data.open()
        assertTrue(waitFor { data.fileNames == listOf("Documents", "Library") })
        data.goForTest("Library")
        assertTrue(waitFor { "Application Support" in data.fileNames })
        data.goForTest("Application Support")
        assertTrue(waitFor { data.fileNames == listOf("shop.db") })
        data.selectFile("shop.db")
        assertTrue(waitFor { data.tableNames.size == 3 })
        data.selectTableForTest("products")
        assertTrue(waitFor { data.gridRowCount == 5 })
        render(data, 1400, 420, "app-data")
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
        renderLogsAndData()
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
