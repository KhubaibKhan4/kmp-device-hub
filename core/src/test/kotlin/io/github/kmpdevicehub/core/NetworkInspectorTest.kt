package io.github.kmpdevicehub.core

import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkInspectorTest {
    private val server = NetworkServer(port = 0)

    @AfterTest
    fun stop() = server.stop()

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    @Test
    fun `captures calls from a connected app and sends it the rules`() {
        assertTrue(server.start().ok)
        server.rules = listOf(NetworkRule("r1", "/orders", action = RuleAction.FAIL), NetworkRule("off", "/x", enabled = false))
        Socket("127.0.0.1", server.localPort).use { socket ->
            val out = socket.getOutputStream().writer()
            val input = socket.getInputStream().bufferedReader()
            fun send(line: String) { out.write(line + "\n"); out.flush() }

            send("""{"type":"hello","v":1,"app":"com.example","platform":"android","device":"Pixel 9","library":"ktor"}""")
            val rules = Json.parse(input.readLine()) as Map<*, *>
            assertEquals("rules", rules["type"])
            assertEquals(listOf("r1"), (rules["rules"] as List<*>).map { (it as Map<*, *>)["id"] }, "disabled rules aren't sent")
            assertTrue(waitFor { server.log.apps.singleOrNull()?.label == "com.example · Pixel 9" })

            send("""{"type":"start","id":"1","method":"POST","url":"https://api.example.com/orders?page=2","headers":[["Content-Type","application/json"]],"body":"{\"a\":1}","bodySize":7,"time":1000}""")
            send("""{"type":"end","id":"1","status":201,"headers":[["Content-Type","application/json"]],"body":"{\"id\":42}","bodySize":9,"durationMs":120}""")
            send("""{"type":"start","id":"2","method":"GET","url":"https://api.example.com/orders/9","time":2000}""")
            send("""{"type":"fail","id":"2","error":"Simulated network failure","durationMs":3,"rule":"r1"}""")
            send("not json")
            send("""{"type":"end","id":"unknown","status":200}""")
            assertTrue(waitFor { server.log.snapshot().size == 2 && server.log.snapshot().none { it.state == CallState.PENDING } })

            val (post, get) = server.log.snapshot()
            assertEquals(201, post.status)
            assertEquals("/orders?page=2", post.path)
            assertEquals("api.example.com", post.host)
            assertEquals("{\"id\":42}", post.responseBody)
            assertEquals("application/json", post.responseType)
            assertEquals(CallState.FAILED, get.state)
            assertEquals("r1", get.rule)
            assertTrue(get.isError)
        }
        assertTrue(waitFor { server.log.apps.isEmpty() }, "disconnected apps disappear")
    }

    @Test
    fun `paused capture ignores new calls but finishes ones in flight`() {
        val log = NetworkLog()
        val app = ConnectedApp(1, "a", "ios", "iPhone")
        log.put(NetworkCall("1", app, "GET", "https://x/1"))
        log.paused = true
        log.put(NetworkCall("2", app, "GET", "https://x/2"))
        log.put(log.get("1")!!.copy(state = CallState.DONE, status = 200), onlyIfKnown = true)
        assertEquals(listOf("1"), log.snapshot().map { it.id })
        assertEquals(CallState.DONE, log.snapshot().single().state)
    }

    @Test
    fun `rules round-trip and match by url and method`() {
        val rule = NetworkRule("r", "/orders", method = "GET", action = RuleAction.MOCK, status = 503, body = "{\"error\":true}", delayMs = 200)
        assertEquals(listOf(rule), NetworkRule.decodeAll(NetworkRule.encodeAll(listOf(rule))))
        assertTrue(rule.matches("get", "https://x/orders/1"))
        assertTrue(!rule.matches("POST", "https://x/orders/1"))
        assertEquals("GET */orders* → 503 application/json after 200 ms", rule.summary)
        assertEquals(emptyList(), NetworkRule.decodeAll("garbage"))
    }

    @Test
    fun `stats, curl and HAR`() {
        val app = ConnectedApp(1, "com.example", "android", "Pixel 9")
        val now = 100_000L
        val calls = listOf(
            NetworkCall("1", app, "POST", "https://api.example.com/orders?page=2&q=a b", listOf("Content-Type" to "application/json", "Content-Length" to "7"),
                "{\"it's\":1}", 7, startedAt = now - 2_000, state = CallState.DONE, status = 201, responseBody = "{}", responseSize = 2_000, durationMs = 100),
            NetworkCall("2", app, "GET", "https://api.example.com/x", startedAt = now - 500, state = CallState.FAILED, error = "boom", durationMs = 300),
            NetworkCall("3", app, "GET", "https://api.example.com/y", startedAt = now),
        )
        val stats = NetworkStats.of(calls, now)
        assertEquals(3, stats.requests)
        assertEquals(1, stats.errors)
        assertEquals(1, stats.pending)
        assertEquals(200, stats.averageMs)
        assertEquals(2_007, stats.perSecond.sum())
        assertEquals("2.0 KB", NetworkStats.bytes(2_000))

        assertEquals(
            "curl -X POST 'https://api.example.com/orders?page=2&q=a b' \\\n  -H 'Content-Type: application/json' \\\n  --data-raw '{\"it'\\''s\":1}'",
            NetworkExport.curl(calls[0]),
        )
        val har = Json.parse(NetworkExport.har(calls)) as Map<*, *>
        val entries = (har["log"] as Map<*, *>)["entries"] as List<*>
        assertEquals(2, entries.size, "pending calls aren't exported")
        val request = (entries[0] as Map<*, *>)["request"] as Map<*, *>
        assertEquals(listOf(mapOf("name" to "page", "value" to "2"), mapOf("name" to "q", "value" to "a b")), request["queryString"])
        assertEquals("{\"it's\":1}", (request["postData"] as Map<*, *>)["text"])
    }

    @Test
    fun `json pretty printing`() {
        assertEquals("{\n  \"a\": [\n    1,\n    2.5,\n    true\n  ],\n  \"b\": {}\n}", Json.pretty("""{"a":[1,2.5,true],"b":{}}"""))
        assertNull(Json.pretty("plain text"))
        assertNull(Json.pretty("{broken"))
        assertEquals("\"line\\nbreak \\\"q\\\"\"", Json.quote("line\nbreak \"q\""))
    }

    @Test
    fun `bridge reverses adb ports and checks connectivity`() {
        val adb = "/adb"
        val emu = Device("emulator-5554", "Pixel 9", DevicePlatform.ANDROID, DeviceKind.EMULATOR, DeviceState.RUNNING)
        val sim = Device("U", "iPhone", DevicePlatform.IOS, DeviceKind.SIMULATOR, DeviceState.RUNNING)
        val runner = FakeRunner(mapOf(
            "$adb -s emulator-5554 reverse tcp:38383 tcp:38383" to CommandResult(0, "38383\n", ""),
            "$adb -s emulator-5554 shell cmd connectivity airplane-mode" to CommandResult(0, "disabled", ""),
            "$adb -s emulator-5554 shell ping -c 1 -W 2 8.8.8.8" to CommandResult(0, "64 bytes from 8.8.8.8: icmp_seq=1 ttl=255 time=14.2 ms", ""),
            "ping -c 1 -t 2 8.8.8.8" to CommandResult(2, "", "ping: sendto: No route to host"),
        ))
        val bridge = NetworkBridge(runner, ToolPaths(adb, null, "/xcrun"))
        assertTrue(bridge.reverse(emu).ok)
        assertTrue(bridge.reverse(sim).ok)
        assertEquals(Connectivity(true, 14.2, "Online · 14 ms to the internet"), bridge.check(emu))
        assertEquals(false, bridge.check(sim).online)
        val airplane = NetworkBridge(FakeRunner(mapOf("$adb -s emulator-5554 shell cmd connectivity airplane-mode" to CommandResult(0, "enabled", ""))), ToolPaths(adb, null, null))
        assertContains(airplane.check(emu).detail, "Airplane")
    }
}
