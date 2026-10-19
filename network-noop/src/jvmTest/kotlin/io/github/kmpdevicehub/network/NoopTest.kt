package io.github.kmpdevicehub.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class NoopTest {
    @Test
    fun `same API, calls pass through, nothing connects`() = runBlocking {
        val listener = ServerSocket(0).apply { soTimeout = 500 }
        val client = HttpClient(MockEngine { respond("ok") }) {
            install(DeviceHubNetwork) { enabled = true; port = listener.localPort; redactHeaders = setOf("Authorization") }
        }
        assertEquals("ok", client.get("https://x.test/").bodyAsText())
        assertNull(runCatching { listener.accept() }.getOrNull(), "no connection to an inspector")
        listener.close()

        val ok = OkHttpClient.Builder().addInterceptor(DeviceHubInterceptor { enabled = true })
            .addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("fine".toResponseBody()).build() }
            .build()
        assertEquals("fine", ok.newCall(Request.Builder().url("https://x.test/").build()).execute().body!!.string())
    }
}
