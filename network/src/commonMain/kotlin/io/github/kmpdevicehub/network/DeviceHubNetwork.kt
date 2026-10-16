package io.github.kmpdevicehub.network

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.save
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Sends every call this client makes to the KMP Device Hub Network Inspector in the IDE:
 *
 * ```
 * val client = HttpClient {
 *     install(DeviceHubNetwork) { enabled = isDebugBuild }
 * }
 * ```
 *
 * Mock, fail and delay rules set in the IDE apply to this client's calls while it's connected.
 */
public val DeviceHubNetwork: io.ktor.client.plugins.api.ClientPlugin<DeviceHubNetworkConfig> =
    createClientPlugin("DeviceHubNetwork", ::DeviceHubNetworkConfig) {
        val config = pluginConfig
        if (!config.enabled) return@createClientPlugin
        val connection = DeviceHubConnection.of(config)
        val httpClient = client

        on(Send) { request ->
            val id = connection.nextId()
            val method = request.method.value
            val url = request.url.buildString()
            val started = TimeSource.Monotonic.markNow()
            fun elapsed() = started.elapsedNow().inWholeMilliseconds

            val body = request.body as? OutgoingContent
            val requestHeaders = request.headers.entries().flatMap { (k, values) -> values.map { k to it } } +
                listOfNotNull(body?.contentType?.let { HttpHeaders.ContentType to it.toString() })
            connection.start(id, method, url, requestHeaders, requestBody(connection, body))

            val rule = connection.ruleFor(method, url)
            try {
                if (rule != null && rule.delayMs > 0) delay(rule.delayMs)
                when (rule?.action) {
                    "fail" -> throw SimulatedNetworkFailure(rule.error)
                    "mock" -> {
                        val bytes = rule.body.encodeToByteArray()
                        val call = mockCall(httpClient, request, rule.status, rule.contentType, bytes)
                        connection.end(id, rule.status, listOf(HttpHeaders.ContentType to rule.contentType), connection.capture(bytes, rule.contentType), elapsed(), rule)
                        return@on call
                    }
                }
                val original = proceed(request)
                val response = original.response
                val type = response.contentType()?.toString()
                val length = response.contentLength()
                // Read the body only when that can't break streaming or use much memory; the app still gets it.
                val readable = type?.startsWith("text/event-stream") != true && DeviceHubConnection.isTextual(type) &&
                    (length == null || length <= config.maxBodyBytes)
                val call = if (readable) original.save() else original
                val bytes = if (readable) call.response.bodyAsBytes() else null
                val headers = call.response.headers.entries().flatMap { (k, values) -> values.map { k to it } }
                connection.end(id, call.response.status.value, headers, connection.capture(bytes, type, length), elapsed(), rule)
                call
            } catch (e: Throwable) {
                connection.fail(id, if (e is CancellationException) CancellationException("Cancelled") else e, elapsed(), rule)
                throw e
            }
        }
    }

private fun requestBody(connection: DeviceHubConnection, body: OutgoingContent?): CapturedBody = when (body) {
    null -> CapturedBody(null, 0)
    is TextContent -> connection.capture(body.text.encodeToByteArray(), body.contentType.toString())
    is OutgoingContent.ByteArrayContent -> connection.capture(body.bytes(), body.contentType?.toString())
    is OutgoingContent.NoContent -> CapturedBody(null, 0)
    else -> CapturedBody(body.contentLength?.let { "($it bytes, streamed)" } ?: "(streamed body)", body.contentLength ?: 0)
}

@OptIn(InternalAPI::class)
private fun mockCall(client: HttpClient, request: HttpRequestBuilder, status: Int, contentType: String, bytes: ByteArray): HttpClientCall {
    val data = request.build()
    val headers = headersOf(HttpHeaders.ContentType to listOf(contentType), HttpHeaders.ContentLength to listOf(bytes.size.toString()))
    val response = HttpResponseData(HttpStatusCode.fromValue(status), GMTDate(), headers, HttpProtocolVersion.HTTP_1_1, ByteReadChannel(bytes), Job())
    return HttpClientCall(client, data, response)
}
