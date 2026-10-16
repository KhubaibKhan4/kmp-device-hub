package io.github.kmpdevicehub.demo

actual fun engine(): io.ktor.client.engine.HttpClientEngineFactory<*> = io.ktor.client.engine.okhttp.OkHttp
