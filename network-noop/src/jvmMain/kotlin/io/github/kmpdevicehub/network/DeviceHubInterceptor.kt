package io.github.kmpdevicehub.network

import okhttp3.Interceptor
import okhttp3.Response

/** Release-build stand-in: passes every call straight through. */
public class DeviceHubInterceptor(@Suppress("UNUSED_PARAMETER") configure: DeviceHubNetworkConfig.() -> Unit = {}) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
