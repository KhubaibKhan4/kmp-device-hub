package io.github.kmpdevicehub.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Stands in for the IDE: records every message and can send rules. */
class FakeInspector : AutoCloseable {
    private val server = ServerSocket(0)
    val port = server.localPort
    val messages = CopyOnWriteArrayList<Map<*, *>>()
    @Volatile private var out: java.io.Writer? = null

    init {
        Thread {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@Thread
            out = socket.getOutputStream().writer()
            socket.getInputStream().bufferedReader().forEachLine { messages += MiniJson.parse(it) as Map<*, *> }
        }.apply { isDaemon = true; start() }
    }

    fun sendRules(vararg rules: String) {
        val writer = out!!
        writer.write("""{"type":"rules","rules":[${rules.joinToString(",")}]}""" + "\n")
        writer.flush()
    }

    fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    fun ofType(type: String) = messages.filter { it["type"] == type }

    override fun close() = server.close()
}

class DeviceHubNetworkTest {
    private val inspector = FakeInspector()

    @AfterTest
    fun close() = inspector.close()

    private fun connectionOf(config: DeviceHubNetworkConfig) = DeviceHubConnection.of(config)

    @Test
    fun `ktor calls, bodies and rules reach the inspector`() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/orders" -> respond("""{"id":42}""", HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
                "/image" -> respond(ByteArray(10), HttpStatusCode.OK, headersOf("Content-Type", "image/png"))
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) {
            install(DeviceHubNetwork) { port = inspector.port; appName = "com.example.test"; redactHeaders = setOf("Authorization") }
        }
        assertTrue(inspector.waitFor { inspector.ofType("hello").isNotEmpty() })
        val hello = inspector.ofType("hello").single()
        assertEquals("com.example.test", hello["app"])
        assertEquals("jvm", hello["platform"])

        val created = client.post("https://api.example.com/orders?x=1") {
            contentType(ContentType.Application.Json)
            headers.append("Authorization", "Bearer secret")
            setBody("""{"item":"tea"}""")
        }
        assertEquals("""{"id":42}""", created.bodyAsText(), "the app still reads the body")
        client.get("https://api.example.com/image")

        assertTrue(inspector.waitFor { inspector.ofType("end").size == 2 })
        val start = inspector.ofType("start").first()
        assertEquals("POST", start["method"])
        assertEquals("https://api.example.com/orders?x=1", start["url"])
        assertEquals("""{"item":"tea"}""", start["body"])
        assertTrue(listOf("Authorization", "██") in (start["headers"] as List<*>), "redacted: ${start["headers"]}")
        val end = inspector.ofType("end").first()
        assertEquals(201L, end["status"])
        assertEquals("""{"id":42}""", end["body"])
        assertTrue((inspector.ofType("end")[1]["body"] as String).endsWith("image/png)"), "binary bodies are described, not sent")

        // Rules from the IDE: mock one call, fail another.
        inspector.sendRules(
            """{"id":"m","urlContains":"/orders","method":"GET","action":"mock","status":503,"body":"{\"down\":true}","contentType":"application/json","delayMs":0}""",
            """{"id":"f","urlContains":"/fail","action":"fail","error":"No internet"}""",
        )
        val connection = connectionOf(DeviceHubNetworkConfig().apply { port = inspector.port })
        assertTrue(inspector.waitFor { connection.rules.size == 2 })
        val mocked = client.get("https://api.example.com/orders/1")
        assertEquals(HttpStatusCode.ServiceUnavailable, mocked.status)
        assertEquals("""{"down":true}""", mocked.bodyAsText())
        val failure = assertFailsWith<SimulatedNetworkFailure> { client.get("https://api.example.com/fail") }
        assertEquals("No internet", failure.message)

        assertTrue(inspector.waitFor { inspector.ofType("fail").isNotEmpty() && inspector.ofType("end").size == 3 })
        assertEquals("m", inspector.ofType("end")[2]["rule"])
        assertEquals("SimulatedNetworkFailure: No internet", inspector.ofType("fail").single()["error"])
    }

    @Test
    fun `okhttp interceptor captures calls and applies rules`() {
        val client = OkHttpClient.Builder()
            .addInterceptor(DeviceHubInterceptor { port = inspector.port })
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "application/json").body("""{"ok":true}""".toResponseBody()).build()
            }
            .build()
        assertTrue(inspector.waitFor { inspector.ofType("hello").isNotEmpty() })
        val response = client.newCall(Request.Builder().url("https://api.example.com/login").post("""{"user":"a"}""".toRequestBody()).build()).execute()
        assertEquals("""{"ok":true}""", response.body!!.string(), "body still readable by the app")
        assertTrue(inspector.waitFor { inspector.ofType("end").isNotEmpty() })
        assertEquals("""{"user":"a"}""", inspector.ofType("start").single()["body"])
        assertEquals("""{"ok":true}""", inspector.ofType("end").single()["body"])

        inspector.sendRules("""{"id":"m","urlContains":"/login","action":"mock","status":401,"body":"denied","contentType":"text/plain","delayMs":50}""")
        val connection = connectionOf(DeviceHubNetworkConfig().apply { port = inspector.port })
        assertTrue(inspector.waitFor { connection.rules.isNotEmpty() })
        val mocked = client.newCall(Request.Builder().url("https://api.example.com/login").build()).execute()
        assertEquals(401, mocked.code)
        assertEquals("denied", mocked.body!!.string())
    }

    @Test
    fun `disabled config sends nothing and textual detection`() = runBlocking {
        val client = HttpClient(MockEngine { respond("x") }) { install(DeviceHubNetwork) { enabled = false; port = inspector.port } }
        client.get("https://x.test/")
        Thread.sleep(300)
        assertTrue(inspector.messages.isEmpty())
        assertTrue(DeviceHubConnection.isTextual("application/json; charset=utf-8"))
        assertTrue(DeviceHubConnection.isTextual("application/problem+json"))
        assertTrue(!DeviceHubConnection.isTextual("image/png"))
        assertEquals("""{"a":"q\"\n","b":[["k","v"]],"n":1}""", MiniJson.obj(listOf("a" to "q\"\n", "b" to listOf("k" to "v"), "n" to 1, "skip" to null)))
    }

    @Test
    fun `errors and device names read well in the IDE`() {
        val darwin = "Exception in http request: Error Domain=NSURLErrorDomain Code=-1003 \"A server with the specified hostname could not be found.\" " +
            "UserInfo={_kCFStreamErrorCodeKey=8, NSUnderlyingError=0x104c4a400 {Error Domain=kCFErrorDomainCFNetwork Code=-1003 \"(null)\"}}"
        assertEquals(
            "DarwinHttpRequestException: A server with the specified hostname could not be found. (NSURLErrorDomain -1003)",
            errorSummary("DarwinHttpRequestException", darwin),
        )
        assertEquals("UnknownHostException: Unable to resolve host", errorSummary("UnknownHostException", "Unable to resolve host\n  at x.y"))
        assertEquals("CancellationException", errorSummary("CancellationException", null))
        assertEquals(300, errorSummary("E", "x".repeat(1000)).length)

        assertEquals("Medium Phone API 37.0 (emulator)", androidDeviceName("Google", "sdk_gphone64_arm64", "Medium_Phone_API_37.0"))
        assertEquals("Samsung SM-G991B", androidDeviceName("samsung", "SM-G991B", null))
        assertEquals("Google Pixel 9", androidDeviceName("Google", "Pixel 9", ""))
    }
}
