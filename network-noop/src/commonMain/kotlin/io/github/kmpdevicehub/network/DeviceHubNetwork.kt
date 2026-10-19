package io.github.kmpdevicehub.network

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

/**
 * Release-build stand-in for devicehub-network: the same settings, so shared code compiles unchanged, but nothing is
 * captured, nothing connects anywhere, and calls go straight through.
 */
public class DeviceHubNetworkConfig {
    public var enabled: Boolean = false
    public var appName: String? = null
    public var host: String = "127.0.0.1"
    public var port: Int = 38383
    public var maxBodyBytes: Int = 128 * 1024
    public var redactHeaders: Set<String> = emptySet()
}

/** Never thrown here; present so code that catches it compiles. */
public class SimulatedNetworkFailure(message: String) : kotlinx.io.IOException(message)

/** Does nothing: installing it leaves the client exactly as it was. */
public val DeviceHubNetwork: ClientPlugin<DeviceHubNetworkConfig> = createClientPlugin("DeviceHubNetwork", ::DeviceHubNetworkConfig) {}
