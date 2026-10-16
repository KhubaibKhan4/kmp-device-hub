package io.github.kmpdevicehub.network

import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

internal actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

internal actual fun platformInfo(): PlatformInfo {
    val environment = NSProcessInfo.processInfo.environment
    // Simulators expose their UDID, which lets the IDE match the app to the simulator in its device list.
    val udid = environment["SIMULATOR_UDID"] as? String ?: ""
    val name = environment["SIMULATOR_DEVICE_NAME"] as? String ?: UIDevice.currentDevice.name
    return PlatformInfo("ios", name, udid, NSBundle.mainBundle.bundleIdentifier ?: "iOS app")
}

private val lock = NSLock()

internal actual fun <T> locked(block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}
