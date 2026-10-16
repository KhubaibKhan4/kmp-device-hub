package io.github.kmpdevicehub.network

internal actual fun epochMillis(): Long = System.currentTimeMillis()

/** Android apps run this jvm code too; Android details are read by reflection so there's no Android dependency. */
internal actual fun platformInfo(): PlatformInfo {
    val build = runCatching { Class.forName("android.os.Build") }.getOrNull()
    if (build != null) {
        fun field(name: String) = runCatching { build.getField(name).get(null) as? String }.getOrNull().orEmpty()
        val process = runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ').substringBefore(':') }.getOrDefault("")
        // Emulators report a generic model (sdk_gphone64_arm64); their AVD name is what developers recognise.
        val avd = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name").firstNotNullOfOrNull { systemProperty(it)?.takeIf(String::isNotBlank) }
        val device = androidDeviceName(field("MANUFACTURER"), field("MODEL"), avd)
        return PlatformInfo("android", device, field("SERIAL").takeIf { it != "unknown" }.orEmpty(), process.ifEmpty { "Android app" })
    }
    val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("")
    return PlatformInfo("jvm", host, "", System.getProperty("sun.java.command")?.substringBefore(' ')?.substringAfterLast('.') ?: "JVM app")
}

private fun systemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
}.getOrNull()

private val lock = Any()

internal actual fun <T> locked(block: () -> T): T = synchronized(lock, block)
