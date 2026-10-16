package io.github.kmpdevicehub.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where and how to send this app's HTTP traffic to KMP Device Hub. */
public class DeviceHubNetworkConfig {
    /** Turn off for release builds, e.g. `enabled = BuildConfig.DEBUG`. Nothing is captured or sent when false. */
    public var enabled: Boolean = true

    /** Shown in the IDE; defaults to the Android package name or iOS bundle id. */
    public var appName: String? = null

    /** The IDE's inspector. Android reaches it through `adb reverse`, which KMP Device Hub sets up. */
    public var host: String = "127.0.0.1"
    public var port: Int = 38383

    /** Bodies larger than this are reported by size only. */
    public var maxBodyBytes: Int = 128 * 1024

    /** Header values replaced with "██" before leaving the app, e.g. setOf("Authorization"). */
    public var redactHeaders: Set<String> = emptySet()
}

/** A rule the IDE sent: mock, fail or delay matching calls. */
internal class WireRule(
    val id: String,
    val urlContains: String,
    val method: String,
    val action: String,
    val status: Int,
    val body: String,
    val contentType: String,
    val delayMs: Long,
    val error: String,
) {
    fun matches(method: String, url: String) =
        urlContains.isNotEmpty() && urlContains in url && (this.method.isEmpty() || this.method.equals(method, ignoreCase = true))
}

/** Thrown for calls a "Fail request" rule matched. */
public class SimulatedNetworkFailure(message: String) : kotlinx.io.IOException(message)

/**
 * One connection per inspector address, shared by every client in the app. Keeps reconnecting in the background
 * (backing off when no IDE is listening) and never blocks or fails the app's own calls.
 */
internal class DeviceHubConnection private constructor(private val config: DeviceHubNetworkConfig) {
    // A small buffer: calls made before the IDE connects (app start) still show up, without holding much memory.
    private val outgoing = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var rules: List<WireRule> = emptyList()
        private set

    @Volatile
    var connected: Boolean = false
        private set

    init {
        scope.launch { connectLoop() }
    }

    fun nextId(): String = Random.nextLong(Long.MAX_VALUE).toString(36)

    fun ruleFor(method: String, url: String): WireRule? = rules.firstOrNull { it.matches(method, url) }

    fun start(id: String, method: String, url: String, headers: List<Pair<String, String>>, body: CapturedBody) = send(
        "start", "id" to id, "method" to method, "url" to url, "headers" to redact(headers),
        "body" to body.text, "bodySize" to body.size, "time" to epochMillis(),
    )

    fun end(id: String, status: Int, headers: List<Pair<String, String>>, body: CapturedBody, durationMs: Long, rule: WireRule?) = send(
        "end", "id" to id, "status" to status, "headers" to redact(headers), "body" to body.text, "bodySize" to body.size,
        "durationMs" to durationMs, "rule" to rule?.id,
    )

    fun fail(id: String, error: Throwable, durationMs: Long, rule: WireRule?) = send(
        "fail", "id" to id, "error" to (error::class.simpleName?.let { "$it: " }.orEmpty() + (error.message ?: "")), "durationMs" to durationMs, "rule" to rule?.id,
    )

    /** A body to report: its text when small and textual, otherwise just its size. */
    fun capture(bytes: ByteArray?, contentType: String?, knownSize: Long? = null): CapturedBody = when {
        // Not read (binary, streaming or too large): describe it instead.
        bytes == null -> CapturedBody(
            when {
                contentType == null || isTextual(contentType) && knownSize == null -> null
                knownSize != null -> "($knownSize bytes of $contentType)"
                else -> "($contentType)"
            },
            knownSize ?: 0,
        )
        bytes.size > config.maxBodyBytes -> CapturedBody("(${bytes.size} bytes, larger than the inspector limit)", bytes.size.toLong())
        !isTextual(contentType) && bytes.isNotEmpty() -> CapturedBody("(${bytes.size} bytes of ${contentType ?: "binary data"})", bytes.size.toLong())
        else -> CapturedBody(bytes.decodeToString(), bytes.size.toLong())
    }

    private fun redact(headers: List<Pair<String, String>>) =
        headers.map { (k, v) -> if (config.redactHeaders.any { it.equals(k, ignoreCase = true) }) k to "██" else k to v }

    private fun send(type: String, vararg fields: Pair<String, Any?>) {
        outgoing.trySend(MiniJson.obj(listOf("type" to type) + fields))
    }

    private suspend fun connectLoop() {
        val selector = SelectorManager(Dispatchers.Default)
        var backoff = 1_000L
        while (true) {
            try {
                val socket = aSocket(selector).tcp().connect(config.host, config.port)
                try {
                    val write = socket.openWriteChannel(autoFlush = false)
                    val read = socket.openReadChannel()
                    val info = platformInfo()
                    write.writeStringUtf8(MiniJson.obj(listOf(
                        "type" to "hello", "v" to 1, "app" to (config.appName ?: info.appId), "platform" to info.platform,
                        "device" to info.device, "deviceId" to info.deviceId, "library" to LIBRARY,
                    )) + "\n")
                    write.flush()
                    connected = true
                    backoff = 1_000L
                    coroutineScope {
                        val writer = launch {
                            for (line in outgoing) {
                                write.writeStringUtf8(line + "\n")
                                write.flush()
                            }
                        }
                        launch {
                            while (true) onMessage(read.readUTF8Line() ?: break)
                            writer.cancel() // the IDE closed the connection
                        }
                    }
                } finally {
                    socket.close()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // No IDE listening (or it went away): try again later.
            }
            connected = false
            rules = emptyList()
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun onMessage(line: String) {
        val message = runCatching { MiniJson.parse(line) }.getOrNull() as? Map<*, *> ?: return
        if (message["type"] != "rules") return
        rules = (message["rules"] as? List<*>).orEmpty().mapNotNull { r ->
            val m = r as? Map<*, *> ?: return@mapNotNull null
            WireRule(
                id = m["id"] as? String ?: return@mapNotNull null,
                urlContains = m["urlContains"] as? String ?: return@mapNotNull null,
                method = m["method"] as? String ?: "",
                action = m["action"] as? String ?: "mock",
                status = (m["status"] as? Number)?.toInt() ?: 200,
                body = m["body"] as? String ?: "",
                contentType = m["contentType"] as? String ?: "application/json",
                delayMs = (m["delayMs"] as? Number)?.toLong() ?: 0,
                error = m["error"] as? String ?: "Simulated network failure",
            )
        }
    }

    companion object {
        internal const val LIBRARY_VERSION = "0.1.0"
        private val LIBRARY = "kmp-device-hub-network/$LIBRARY_VERSION"
        private val connections = HashMap<String, DeviceHubConnection>()

        fun of(config: DeviceHubNetworkConfig): DeviceHubConnection = locked {
            connections.getOrPut("${config.host}:${config.port}") { DeviceHubConnection(config) }
        }

        internal fun isTextual(contentType: String?): Boolean {
            val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
            return type.startsWith("text/") || type.endsWith("json") || type.endsWith("+json") || type.endsWith("xml") ||
                type == "application/x-www-form-urlencoded" || type == "application/javascript" || type == "application/graphql"
        }
    }
}

internal class CapturedBody(val text: String?, val size: Long)

/** The app and device, for the IDE's list of connected apps. */
internal class PlatformInfo(val platform: String, val device: String, val deviceId: String, val appId: String)

internal expect fun platformInfo(): PlatformInfo

internal expect fun epochMillis(): Long

/** Runs [block] under one process-wide lock. */
internal expect fun <T> locked(block: () -> T): T
