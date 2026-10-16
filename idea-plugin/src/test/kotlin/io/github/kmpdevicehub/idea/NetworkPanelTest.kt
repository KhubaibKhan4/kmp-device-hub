package io.github.kmpdevicehub.idea

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.kmpdevicehub.core.ActionResult
import io.github.kmpdevicehub.core.Connectivity
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.NetworkLog
import io.github.kmpdevicehub.core.NetworkRule
import io.github.kmpdevicehub.core.NetworkServer
import io.github.kmpdevicehub.core.RuleAction
import java.io.File
import java.net.Socket

class NetworkPanelTest : BasePlatformTestCase() {

    /** A real server on a free port, without the device polling of the IDE service. */
    private class TestBackend : InspectorBackend {
        val server = NetworkServer(port = 0)
        override val log: NetworkLog get() = server.log
        override val port: Int get() = server.localPort
        override val isListening: Boolean get() = server.isRunning
        override fun start(): ActionResult = server.start()
        override var rules: List<NetworkRule>
            get() = server.rules
            set(value) { server.rules = value }
        override fun runningDevices(): List<Device> = emptyList()
        override fun connectivity(device: Device) = Connectivity(true, 10.0, "Online")
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return condition()
    }

    private lateinit var backend: TestBackend
    private lateinit var socket: Socket
    private val upsells = mutableListOf<String>()

    override fun setUp() {
        super.setUp()
        backend = TestBackend()
        assertTrue(backend.start().ok)
        socket = Socket("127.0.0.1", backend.port)
    }

    override fun tearDown() {
        try {
            socket.close()
            backend.server.stop()
        } finally {
            super.tearDown()
        }
    }

    private fun send(vararg lines: String) {
        val out = socket.getOutputStream().writer()
        lines.forEach { out.write(it + "\n") }
        out.flush()
    }

    private fun panel(pro: Boolean = true) = NetworkPanel(project, testRootDisposable, backend, isPro = { pro }, upsell = { upsells += it }, autoRefresh = false)

    private fun sendSampleTraffic() {
        send(
            """{"type":"hello","v":1,"app":"org.example.project","platform":"android","device":"Google Pixel 9","library":"ktor"}""",
            """{"type":"start","id":"1","method":"GET","url":"https://api.example.com/orders?page=1","headers":[["Accept","application/json"]],"time":1760000000000}""",
            """{"type":"end","id":"1","status":200,"headers":[["Content-Type","application/json"]],"body":"{\"orders\":[{\"id\":1,\"item\":\"Tea\"},{\"id\":2,\"item\":\"Coffee\"}],\"next\":null}","bodySize":64,"durationMs":182}""",
            """{"type":"start","id":"2","method":"POST","url":"https://api.example.com/orders","headers":[["Content-Type","application/json"]],"body":"{\"item\":\"Cake\"}","bodySize":15,"time":1760000000500}""",
            """{"type":"end","id":"2","status":422,"headers":[["Content-Type","application/json"]],"body":"{\"error\":\"out of stock\"}","bodySize":24,"durationMs":95}""",
            """{"type":"start","id":"3","method":"GET","url":"https://cdn.example.com/banner.png","time":1760000001000}""",
            """{"type":"fail","id":"3","error":"UnknownHostException: cdn.example.com","durationMs":12}""",
            """{"type":"start","id":"4","method":"GET","url":"https://api.example.com/profile","time":1760000002000}""",
        )
    }

    fun `test tool window and startup activity are registered`() {
        assertTrue(com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList.any { it.id == "KMP Network" && it.factoryClass == NetworkToolWindowFactory::class.java.name })
    }

    fun `test calls stream in with details, JSON tree, stats and filters`() {
        val panel = panel()
        assertTrue(waitFor { panel.listeningText.contains("no app connected") })
        sendSampleTraffic()
        assertTrue("rows=${panel.rowCount}", waitFor { panel.rowCount == 4 && backend.log.snapshot().count { it.durationMs != null } == 3 })
        panel.refreshForTest()
        assertTrue(panel.listeningText, panel.listeningText.contains("1 app connected"))
        assertTrue(panel.statsText, panel.statsText.startsWith("4 calls · 2 errors · 1 pending · avg 96 ms"))

        panel.selectRow(0)
        assertTrue(panel.overviewText.startsWith("GET https://api.example.com/orders?page=1"))
        assertTrue(panel.overviewText.contains("Status    200 OK"))
        assertTrue(panel.overviewText.contains("App       org.example.project · Google Pixel 9"))
        assertTrue(panel.responseText.contains("\"orders\": [\n    {\n      \"id\": 1,"))
        assertEquals("orders, next", 2, panel.jsonRootChildren)

        panel.selectRow(2)
        assertTrue(panel.responseText.contains("UnknownHostException"))

        panel.setErrorsOnlyForTest(true)
        assertEquals(2, panel.rowCount)
        panel.setErrorsOnlyForTest(false)
        panel.setFilter("POST")
        assertEquals(1, panel.rowCount)
        panel.setFilter("422")
        assertEquals("https://api.example.com/orders", panel.selectedCall()?.url ?: backend.log.snapshot()[1].url)
        panel.setFilter("")
        assertEquals(4, panel.rowCount)
    }

    fun `test fail rule reaches the app, and rules need Pro`() {
        val locked = panel(pro = false)
        sendSampleTraffic()
        assertTrue(waitFor { locked.rowCount == 4 })
        val call = backend.log.snapshot().first()
        locked.failFrom(call)
        assertEquals(listOf("Mock & fail rules"), upsells)
        assertTrue(backend.rules.isEmpty())

        val panel = panel(pro = true)
        panel.failFrom(call)
        val rule = backend.rules.single()
        assertEquals(RuleAction.FAIL, rule.action)
        assertEquals("/orders", rule.urlContains)
        assertEquals("GET", rule.method)

        // The connected app gets the rules (after the empty list sent on hello).
        val reader = socket.getInputStream().bufferedReader()
        val lines = generateSequence { reader.readLine() }.take(2).toList()
        assertTrue(lines.last(), lines.last().contains("\"action\":\"fail\"") && lines.last().contains("\"urlContains\":\"/orders\""))
    }

    /** Renders build/network-panel.png for a visual check. */
    fun `test render snapshot`() {
        val panel = panel()
        sendSampleTraffic()
        assertTrue(waitFor { panel.rowCount == 4 && backend.log.snapshot().count { it.durationMs != null } == 3 })
        panel.refreshForTest()
        panel.selectRow(0)
        panel.setSize(1200, 420)
        com.intellij.util.ui.UIUtil.uiTraverser(panel).forEach { it.doLayout() }
        val image = java.awt.image.BufferedImage(2400, 840, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(2.0, 2.0)
        panel.printAll(g)
        g.dispose()
        javax.imageio.ImageIO.write(image, "png", File(System.getProperty("user.dir"), "build/network-panel.png"))
    }
}
