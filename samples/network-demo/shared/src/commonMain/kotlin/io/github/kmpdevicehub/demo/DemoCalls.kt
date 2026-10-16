package io.github.kmpdevicehub.demo

import io.github.kmpdevicehub.network.DeviceHubNetwork
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay

/** The calls a typical app makes: a JSON GET, an authenticated POST, a 404 and an unreachable host. */
object DemoCalls {
    private const val API = "https://jsonplaceholder.typicode.com"

    fun client() = HttpClient(engine()) {
        install(DeviceHubNetwork) {
            appName = "io.github.kmpdevicehub.demo"
            redactHeaders = setOf("Authorization")
        }
    }

    /** Two rounds, so a mock rule set in the IDE after the first shows up in the second. */
    suspend fun run(log: (String) -> Unit) {
        val client = client()
        delay(4_000) // give the library time to connect to the IDE
        for (round in 1..2) {
            log("round $round")
            call(log, "GET /todos/1") { client.get("$API/todos/1") }
            call(log, "POST /posts") {
                client.post("$API/posts") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer demo-secret-token")
                    setBody("""{"title":"Tea","body":"Earl Grey","userId":1}""")
                }
            }
            call(log, "GET /does-not-exist") { client.get("$API/does-not-exist") }
            call(log, "GET unreachable host") { client.get("https://kmpdevicehub-demo.invalid/ping") }
            if (round == 1) delay(10_000)
        }
        delay(2_000) // let the last events reach the IDE
        client.close()
        log("done")
    }

    private suspend fun call(log: (String) -> Unit, label: String, block: suspend () -> io.ktor.client.statement.HttpResponse) {
        try {
            val response = block()
            log("$label -> ${response.status.value} ${response.bodyAsText().replace("\n", " ").take(80)}")
        } catch (e: Exception) {
            log("$label -> failed: ${e::class.simpleName}: ${e.message?.take(80)}")
        }
    }
}

expect fun engine(): io.ktor.client.engine.HttpClientEngineFactory<*>
