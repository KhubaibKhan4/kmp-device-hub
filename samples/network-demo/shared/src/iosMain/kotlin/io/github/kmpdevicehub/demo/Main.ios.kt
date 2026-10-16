package io.github.kmpdevicehub.demo

import kotlinx.coroutines.runBlocking

actual fun engine(): io.ktor.client.engine.HttpClientEngineFactory<*> = io.ktor.client.engine.darwin.Darwin

fun main() = runBlocking {
    DemoCalls.run { println("DEMO $it") }
}
