package io.github.kmpdevicehub.core

enum class DevicePlatform { ANDROID, IOS }

enum class DeviceKind { PHYSICAL, EMULATOR, SIMULATOR }

enum class DeviceState {
    /** Running and ready for commands. */
    RUNNING,

    /** Exists but isn't running (a stopped emulator or a shut-down simulator). */
    STOPPED,

    /** Starting or shutting down. */
    BOOTING,

    /** A physical Android device waiting for the "Allow USB debugging" prompt. */
    UNAUTHORIZED,

    /** Connected but not responding. */
    OFFLINE,
}

data class Device(
    /** Stable id: the adb serial, `avd:<name>` for a stopped emulator, or the simulator UDID. */
    val id: String,
    val name: String,
    val platform: DevicePlatform,
    val kind: DeviceKind,
    val state: DeviceState,
    /** e.g. "16" for Android, "27.0" for iOS. */
    val osVersion: String? = null,
    /** Android API level, when known. */
    val apiLevel: Int? = null,
    /** The AVD behind an emulator, running or not. */
    val avdName: String? = null,
) {
    val isRunning: Boolean get() = state == DeviceState.RUNNING

    /** "Android 16 (API 36)" or "iOS 27.0". */
    val osLabel: String
        get() = when (platform) {
            // A stopped emulator only knows its API level until it runs.
            DevicePlatform.ANDROID -> when {
                osVersion != null && apiLevel != null -> "Android $osVersion (API $apiLevel)"
                osVersion != null -> "Android $osVersion"
                apiLevel != null -> "Android API $apiLevel"
                else -> "Android"
            }
            DevicePlatform.IOS -> "iOS" + (osVersion?.let { " $it" } ?: "")
        }
}

/** The result of discovery: the devices found, and why a platform couldn't be listed (if it couldn't). */
data class DeviceList(val devices: List<Device>, val problems: List<String> = emptyList())
