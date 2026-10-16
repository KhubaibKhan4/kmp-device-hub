package io.github.kmpdevicehub.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Sends every call an OkHttp client makes to the KMP Device Hub Network Inspector (for Android code that uses OkHttp
 * or Retrofit directly; Ktor clients use [DeviceHubNetwork]):
 *
 * ```
 * OkHttpClient.Builder().addInterceptor(DeviceHubInterceptor { enabled = BuildConfig.DEBUG }).build()
 * ```
 */
public class DeviceHubInterceptor(configure: DeviceHubNetworkConfig.() -> Unit = {}) : Interceptor {
    private val config = DeviceHubNetworkConfig().apply(configure)
    private val connection = if (config.enabled) DeviceHubConnection.of(config) else null

    override fun intercept(chain: Interceptor.Chain): Response {
        val connection = connection ?: return chain.proceed(chain.request())
        val request = chain.request()
        val id = connection.nextId()
        val method = request.method
        val url = request.url.toString()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000

        val body = request.body
        val requestBytes = when {
            body == null -> null
            body.isDuplex() || body.isOneShot() -> null
            else -> runCatching { okio.Buffer().also { body.writeTo(it) }.readByteArray() }.getOrNull()
        }
        val requestHeaders = request.headers.map { it.first to it.second } +
            listOfNotNull(body?.contentType()?.let { "Content-Type" to it.toString() })
        connection.start(id, method, url, requestHeaders, connection.capture(requestBytes, body?.contentType()?.toString(), body?.contentLength()?.takeIf { it >= 0 }))

        val rule = connection.ruleFor(method, url)
        try {
            if (rule != null && rule.delayMs > 0) Thread.sleep(rule.delayMs)
            when (rule?.action) {
                "fail" -> throw SimulatedNetworkFailure(rule.error)
                "mock" -> {
                    val bytes = rule.body.encodeToByteArray()
                    connection.end(id, rule.status, listOf("Content-Type" to rule.contentType), connection.capture(bytes, rule.contentType), elapsed(), rule)
                    return Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(rule.status).message("Mocked by KMP Device Hub")
                        .header("Content-Type", rule.contentType)
                        .body(rule.body.toResponseBody(rule.contentType.toMediaTypeOrNull()))
                        .build()
                }
            }
            val response = chain.proceed(request)
            val type = response.header("Content-Type")
            val readable = type?.startsWith("text/event-stream") != true && DeviceHubConnection.isTextual(type)
            // peekBody leaves the body untouched for the app.
            val bytes = if (readable) runCatching { response.peekBody(config.maxBodyBytes.toLong() + 1).bytes() }.getOrNull() else null
            val length = response.body?.contentLength()?.takeIf { it >= 0 }
            connection.end(id, response.code, response.headers.map { it.first to it.second }, connection.capture(bytes, type, length), elapsed(), rule)
            return response
        } catch (e: Exception) {
            connection.fail(id, e, elapsed(), rule)
            throw e
        }
    }
}
