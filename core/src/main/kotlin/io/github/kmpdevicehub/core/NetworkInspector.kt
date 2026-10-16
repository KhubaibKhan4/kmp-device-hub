package io.github.kmpdevicehub.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/*
 * Network Inspector wire protocol (v1): one JSON object per line over TCP on 127.0.0.1:38383.
 * Android apps reach it through `adb reverse`, iOS simulators directly (they share the Mac's network).
 *
 * App -> IDE
 *   {"type":"hello","v":1,"app":"com.example","platform":"android","device":"Pixel 9","deviceId":"","library":"ktor"}
 *   {"type":"start","id":"1","method":"GET","url":"https://…","headers":[["Accept","application/json"]],
 *    "body":"…","bodySize":12,"time":1760000000000}
 *   {"type":"end","id":"1","status":200,"headers":[…],"body":"…","bodySize":345,"durationMs":120,"rule":"r1"}
 *   {"type":"fail","id":"1","error":"UnknownHostException: …","durationMs":30,"rule":"r1"}
 * IDE -> App
 *   {"type":"rules","rules":[{"id":"r1","urlContains":"/orders","method":"GET","action":"mock","status":500,
 *    "body":"{}","contentType":"application/json","delayMs":0,"error":""}]}
 */

typealias HeaderList = List<Pair<String, String>>

enum class CallState { PENDING, DONE, FAILED }

/** One HTTP call the app made, as captured by the device hub library. */
data class NetworkCall(
    val id: String,
    val app: ConnectedApp,
    val method: String,
    val url: String,
    val requestHeaders: HeaderList = emptyList(),
    val requestBody: String? = null,
    val requestSize: Long = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val state: CallState = CallState.PENDING,
    val status: Int? = null,
    val responseHeaders: HeaderList = emptyList(),
    val responseBody: String? = null,
    val responseSize: Long = 0,
    val durationMs: Long? = null,
    val error: String? = null,
    /** The id of the rule that mocked, failed or delayed this call. */
    val rule: String? = null,
) {
    // Parsed leniently: apps send URLs that java.net.URI rejects (spaces, unencoded characters).
    val host: String get() = URL_PARTS.find(url)?.groupValues?.get(1).orEmpty()
    val path: String get() = URL_PARTS.find(url)?.groupValues?.get(2)?.substringBefore('#')?.ifEmpty { "/" }?.let { if (it.startsWith("?")) "/$it" else it } ?: url
    val query: String get() = url.substringBefore('#').substringAfter('?', "")
    val isError: Boolean get() = state == CallState.FAILED || (status ?: 0) >= 400
    fun header(headers: HeaderList, name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    val responseType: String? get() = header(responseHeaders, "Content-Type")

    private companion object {
        val URL_PARTS = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://(?:[^@/?#]*@)?(\[[^\]]*]|[^:/?#]*)(?::\d+)?(.*)$""")
    }
}

/** An app connected to the inspector. */
data class ConnectedApp(
    val connection: Int,
    val app: String,
    val platform: String,
    val device: String,
    val deviceId: String = "",
    val library: String = "",
) {
    val label: String get() = if (device.isBlank()) app else "$app · $device"

    companion object {
        val UNKNOWN = ConnectedApp(0, "unknown app", "", "")
    }
}

/** What to do with matching calls. */
enum class RuleAction(val label: String) { MOCK("Mock response"), FAIL("Fail request"), DELAY("Add delay") }

data class NetworkRule(
    val id: String,
    val urlContains: String,
    val method: String = "",
    val action: RuleAction = RuleAction.MOCK,
    val status: Int = 200,
    val body: String = "",
    val contentType: String = "application/json",
    val delayMs: Long = 0,
    val error: String = "Simulated network failure",
    val enabled: Boolean = true,
) {
    fun matches(method: String, url: String) = enabled && urlContains.isNotBlank() && urlContains in url &&
        (this.method.isBlank() || this.method.equals(method, ignoreCase = true))

    val summary: String
        get() = (if (method.isBlank()) "" else "$method ") + "*$urlContains* → " + when (action) {
            RuleAction.MOCK -> "$status ${if (body.isBlank()) "(empty)" else contentType}" + (if (delayMs > 0) " after $delayMs ms" else "")
            RuleAction.FAIL -> "fail: $error"
            RuleAction.DELAY -> "+$delayMs ms"
        }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "urlContains" to urlContains, "method" to method, "action" to action.name.lowercase(), "status" to status,
        "body" to body, "contentType" to contentType, "delayMs" to delayMs, "error" to error, "enabled" to enabled,
    )

    companion object {
        fun fromMap(map: Map<*, *>): NetworkRule? {
            val id = map["id"] as? String ?: return null
            return NetworkRule(
                id = id,
                urlContains = map["urlContains"] as? String ?: return null,
                method = map["method"] as? String ?: "",
                action = RuleAction.entries.firstOrNull { it.name.equals(map["action"] as? String, true) } ?: RuleAction.MOCK,
                status = (map["status"] as? Number)?.toInt() ?: 200,
                body = map["body"] as? String ?: "",
                contentType = map["contentType"] as? String ?: "application/json",
                delayMs = (map["delayMs"] as? Number)?.toLong() ?: 0,
                error = map["error"] as? String ?: "Simulated network failure",
                enabled = map["enabled"] as? Boolean ?: true,
            )
        }

        fun encodeAll(rules: List<NetworkRule>) = Json.write(rules.map { it.toMap() })

        fun decodeAll(text: String): List<NetworkRule> =
            (runCatching { Json.parse(text) }.getOrNull() as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::fromMap) }
    }
}

/** Captured calls (newest last, capped) and connected apps, with change listeners. Thread-safe. */
class NetworkLog(private val capacity: Int = 2_000) {
    private val calls = LinkedHashMap<String, NetworkCall>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    val apps = CopyOnWriteArrayList<ConnectedApp>()

    @Volatile
    var paused = false

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun snapshot(): List<NetworkCall> = synchronized(calls) { calls.values.toList() }

    fun clear() {
        synchronized(calls) { calls.clear() }
        changed()
    }

    internal fun put(call: NetworkCall, onlyIfKnown: Boolean = false) {
        synchronized(calls) {
            if (onlyIfKnown && call.id !in calls) return
            if (!onlyIfKnown && paused) return
            calls[call.id] = call
            while (calls.size > capacity) calls.remove(calls.keys.first())
        }
        changed()
    }

    internal fun get(id: String) = synchronized(calls) { calls[id] }

    internal fun changed() = listeners.forEach { runCatching { it() } }
}

/** Listens for apps using the KMP Device Hub network library. */
class NetworkServer(val log: NetworkLog = NetworkLog(), val port: Int = DEFAULT_PORT) {
    private var server: ServerSocket? = null
    private val connections = CopyOnWriteArrayList<Connection>()
    private val nextConnection = AtomicInteger(1)

    @Volatile
    var rules: List<NetworkRule> = emptyList()
        set(value) {
            field = value
            connections.forEach { it.sendRules(value) }
        }

    val isRunning: Boolean get() = server?.isClosed == false

    /** The port actually bound (differs from [port] only when [port] is 0, as in tests). */
    val localPort: Int get() = server?.localPort ?: port

    @Synchronized
    fun start(): ActionResult {
        if (isRunning) return ActionResult.done("Listening on port $localPort.")
        val socket = try {
            ServerSocket(port, 50, InetAddress.getLoopbackAddress())
        } catch (e: java.io.IOException) {
            return ActionResult.failed("Port $port is in use (is another IDE window inspecting?): ${e.message}")
        }
        server = socket
        Thread({ acceptLoop(socket) }, "KMP Device Hub network server").apply { isDaemon = true; start() }
        return ActionResult.done("Listening on port $localPort.")
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        connections.forEach { it.close() }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (e: SocketException) { return }
            val connection = Connection(nextConnection.getAndIncrement(), client)
            connections += connection
            Thread({ connection.read() }, "KMP Device Hub network client").apply { isDaemon = true; start() }
        }
    }

    private inner class Connection(val number: Int, val socket: Socket) {
        private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        var app: ConnectedApp? = null

        fun read() {
            try {
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { line -> if (line.isNotBlank()) runCatching { handle(Json.parse(line) as? Map<*, *> ?: return@runCatching) } }
                }
            } catch (_: java.io.IOException) {
            } finally {
                close()
            }
        }

        fun handle(event: Map<*, *>) {
            fun str(key: String) = event[key] as? String
            fun long(key: String) = (event[key] as? Number)?.toLong()
            val id = "$number:${str("id")}"
            when (str("type")) {
                "hello" -> {
                    val hello = ConnectedApp(number, str("app") ?: "app", str("platform").orEmpty(), str("device").orEmpty(), str("deviceId").orEmpty(), str("library").orEmpty())
                    app = hello
                    // A reconnect of the same app on the same device replaces the old entry.
                    log.apps.removeIf { it.app == hello.app && it.device == hello.device && it.deviceId == hello.deviceId }
                    log.apps += hello
                    sendRules(rules)
                    log.changed()
                }
                "start" -> log.put(NetworkCall(
                    id = id, app = app ?: ConnectedApp.UNKNOWN, method = str("method") ?: "GET", url = str("url").orEmpty(),
                    requestHeaders = headers(event["headers"]), requestBody = str("body"), requestSize = long("bodySize") ?: 0,
                    startedAt = long("time") ?: System.currentTimeMillis(),
                ))
                "end" -> log.get(id)?.let { call ->
                    log.put(call.copy(
                        state = CallState.DONE, status = long("status")?.toInt(), responseHeaders = headers(event["headers"]),
                        responseBody = str("body"), responseSize = long("bodySize") ?: 0, durationMs = long("durationMs"), rule = str("rule"),
                    ), onlyIfKnown = true)
                }
                "fail" -> log.get(id)?.let { call ->
                    log.put(call.copy(state = CallState.FAILED, error = str("error") ?: "failed", durationMs = long("durationMs"), rule = str("rule")), onlyIfKnown = true)
                }
            }
        }

        @Synchronized
        fun sendRules(rules: List<NetworkRule>) {
            if (app == null) return
            runCatching {
                writer.write(Json.write(mapOf("type" to "rules", "rules" to rules.filter { it.enabled }.map { it.toMap() })))
                writer.write("\n")
                writer.flush()
            }
        }

        fun close() {
            runCatching { socket.close() }
            connections -= this
            app?.let { gone -> if (log.apps.remove(gone)) log.changed() }
        }
    }

    companion object {
        const val DEFAULT_PORT = 38383

        internal fun headers(value: Any?): HeaderList = (value as? List<*>).orEmpty().mapNotNull { pair ->
            val p = pair as? List<*> ?: return@mapNotNull null
            (p.getOrNull(0) as? String ?: return@mapNotNull null) to (p.getOrNull(1) as? String).orEmpty()
        }
    }
}

/** Totals and throughput over the captured calls. */
data class NetworkStats(
    val requests: Int,
    val errors: Int,
    val pending: Int,
    val averageMs: Long?,
    val bytesIn: Long,
    val bytesOut: Long,
    /** Bytes received per second for the last [SECONDS] seconds, oldest first. */
    val perSecond: LongArray,
) {
    companion object {
        const val SECONDS = 60

        fun of(calls: List<NetworkCall>, now: Long = System.currentTimeMillis()): NetworkStats {
            val done = calls.filter { it.durationMs != null }
            val buckets = LongArray(SECONDS)
            for (call in calls) {
                val end = call.startedAt + (call.durationMs ?: continue)
                val age = ((now - end) / 1000).toInt()
                if (age in 0 until SECONDS) buckets[SECONDS - 1 - age] += call.responseSize + call.requestSize
            }
            return NetworkStats(
                requests = calls.size,
                errors = calls.count { it.isError },
                pending = calls.count { it.state == CallState.PENDING },
                averageMs = done.takeIf { it.isNotEmpty() }?.map { it.durationMs!! }?.average()?.toLong(),
                bytesIn = calls.sumOf { it.responseSize },
                bytesOut = calls.sumOf { it.requestSize },
                perSecond = buckets,
            )
        }

        fun bytes(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
            else -> "%.1f MB".format(n / 1024.0 / 1024.0)
        }
    }
}

/** Exports and copies: cURL commands and HAR 1.2 archives. */
object NetworkExport {
    fun curl(call: NetworkCall): String = buildString {
        append("curl")
        if (call.method != "GET") append(" -X ").append(call.method)
        append(' ').append(sq(call.url))
        call.requestHeaders.filterNot { it.first.equals("Content-Length", true) }.forEach { (k, v) -> append(" \\\n  -H ").append(sq("$k: $v")) }
        call.requestBody?.takeIf { it.isNotEmpty() }?.let { append(" \\\n  --data-raw ").append(sq(it)) }
    }

    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun har(calls: List<NetworkCall>): String {
        fun headers(list: HeaderList) = list.map { mapOf("name" to it.first, "value" to it.second) }
        val entries = calls.filter { it.state != CallState.PENDING }.map { call ->
            val query = call.query.split('&').filter { it.isNotEmpty() }
                .map { mapOf("name" to it.substringBefore('='), "value" to it.substringAfter('=', "")) }
            linkedMapOf(
                "startedDateTime" to Instant.ofEpochMilli(call.startedAt).toString(),
                "time" to (call.durationMs ?: 0),
                "request" to linkedMapOf(
                    "method" to call.method, "url" to call.url, "httpVersion" to "HTTP/1.1", "cookies" to emptyList<Any>(),
                    "headers" to headers(call.requestHeaders), "queryString" to query, "headersSize" to -1, "bodySize" to call.requestSize,
                ).apply {
                    call.requestBody?.let { put("postData", mapOf("mimeType" to (call.header(call.requestHeaders, "Content-Type") ?: ""), "text" to it)) }
                },
                "response" to linkedMapOf(
                    "status" to (call.status ?: 0), "statusText" to (call.error ?: ""), "httpVersion" to "HTTP/1.1", "cookies" to emptyList<Any>(),
                    "headers" to headers(call.responseHeaders), "redirectURL" to (call.header(call.responseHeaders, "Location") ?: ""),
                    "headersSize" to -1, "bodySize" to call.responseSize,
                    "content" to linkedMapOf("size" to call.responseSize, "mimeType" to (call.responseType ?: ""), "text" to (call.responseBody ?: "")),
                ),
                "cache" to emptyMap<String, Any>(),
                "timings" to mapOf("send" to 0, "wait" to (call.durationMs ?: 0), "receive" to 0),
                "comment" to call.app.label,
            )
        }
        return Json.write(mapOf("log" to linkedMapOf(
            "version" to "1.2",
            "creator" to mapOf("name" to "KMP Device Hub", "version" to "0.1"),
            "entries" to entries,
        )), indent = 2)
    }
}

/** Whether a device is online, and how far away the internet is. */
data class Connectivity(val online: Boolean?, val latencyMs: Double?, val detail: String)

/** Connectivity checks and the `adb reverse` that lets Android apps reach the inspector. */
class NetworkBridge(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val tools: ToolPaths = ToolLocator.locate(),
) {
    /** Makes `127.0.0.1:port` on the Android device reach the IDE. iOS simulators need nothing. */
    fun reverse(device: Device, port: Int = NetworkServer.DEFAULT_PORT): ActionResult {
        if (device.platform != DevicePlatform.ANDROID) return ActionResult.done("iOS simulators reach the IDE directly.")
        if (device.state != DeviceState.RUNNING) return ActionResult.failed("Start ${device.name} first.")
        val adb = tools.adb ?: return ActionResult.failed("adb not found.")
        val r = runner.run(listOf(adb, "-s", device.id, "reverse", "tcp:$port", "tcp:$port"))
        return if (r.ok) ActionResult.done("${device.name} can reach the inspector.") else ActionResult.failed("adb reverse failed on ${device.name}: ${r.errorSummary}")
    }

    /** Serials of Android devices adb can talk to; cheap enough to poll in the background. */
    fun androidSerials(): List<String> {
        val adb = tools.adb ?: return emptyList()
        return runner.run(listOf(adb, "devices")).stdout.lines().drop(1)
            .map { it.trim().split(Regex("\\s+")) }.filter { it.size >= 2 && it[1] == "device" }.map { it[0] }
    }

    fun reverse(serial: String, port: Int = NetworkServer.DEFAULT_PORT): Boolean {
        val adb = tools.adb ?: return false
        return runner.run(listOf(adb, "-s", serial, "reverse", "tcp:$port", "tcp:$port")).ok
    }

    fun check(device: Device): Connectivity {
        if (device.state != DeviceState.RUNNING) return Connectivity(null, null, "Not running")
        val result = when (device.platform) {
            DevicePlatform.ANDROID -> {
                val adb = tools.adb ?: return Connectivity(null, null, "adb not found")
                val airplane = runner.run(listOf(adb, "-s", device.id, "shell", "cmd", "connectivity", "airplane-mode")).stdout
                if ("enabled" in airplane) return Connectivity(false, null, "Airplane mode")
                runner.run(listOf(adb, "-s", device.id, "shell", "ping", "-c", "1", "-W", "2", PING_HOST), timeoutSeconds = 6)
            }
            // Simulators use the Mac's network.
            DevicePlatform.IOS -> runner.run(listOf("ping", "-c", "1", "-t", "2", PING_HOST), timeoutSeconds = 6)
        }
        val latency = Regex("""time[=<]([\d.]+) ?ms""").find(result.stdout)?.groupValues?.get(1)?.toDoubleOrNull()
        return when {
            latency != null -> Connectivity(true, latency, "Online · %.0f ms to the internet".format(latency))
            result.ok -> Connectivity(true, null, "Online")
            else -> Connectivity(false, null, "No internet connection")
        }
    }

    companion object {
        private const val PING_HOST = "8.8.8.8"
    }
}
