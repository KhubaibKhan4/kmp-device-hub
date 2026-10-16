package io.github.kmpdevicehub.network

internal actual fun epochMillis(): Long = System.currentTimeMillis()

/** Android apps run this jvm code too; Android details are read by reflection so there's no Android dependency. */
internal actual fun platformInfo(): PlatformInfo {
    val build = runCatching { Class.forName("android.os.Build") }.getOrNull()
    if (build != null) {
        fun field(name: String) = runCatching { build.getField(name).get(null) as? String }.getOrNull().orEmpty()
        val manufacturer = field("MANUFACTURER").replaceFirstChar { it.uppercase() }
        val model = field("MODEL")
        val process = runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ').substringBefore(':') }.getOrDefault("")
        val device = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
        return PlatformInfo("android", device, field("SERIAL").takeIf { it != "unknown" }.orEmpty(), process.ifEmpty { "Android app" })
    }
    val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("")
    return PlatformInfo("jvm", host, "", System.getProperty("sun.java.command")?.substringBefore(' ')?.substringAfterLast('.') ?: "JVM app")
}

private val lock = Any()

internal actual fun <T> locked(block: () -> T): T = synchronized(lock, block)
