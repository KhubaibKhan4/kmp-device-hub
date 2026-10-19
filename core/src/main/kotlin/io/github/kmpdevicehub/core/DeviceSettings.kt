package io.github.kmpdevicehub.core

import java.io.File

/** Common runtime permissions, mapped to each platform. `ios == null` means simctl can't control it. */
enum class AppPermission(val label: String, val android: List<String>, val ios: String?) {
    CAMERA("Camera", listOf("android.permission.CAMERA"), null),
    LOCATION("Location", listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"), "location"),
    MICROPHONE("Microphone", listOf("android.permission.RECORD_AUDIO"), "microphone"),
    CONTACTS("Contacts", listOf("android.permission.READ_CONTACTS"), "contacts"),
    PHOTOS("Photos", listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_EXTERNAL_STORAGE"), "photos"),
    CALENDAR("Calendar", listOf("android.permission.READ_CALENDAR"), "calendar"),
    NOTIFICATIONS("Notifications", listOf("android.permission.POST_NOTIFICATIONS"), null),
}

enum class PermissionState { GRANTED, DENIED, NOT_REQUESTED, UNKNOWN }

/** Five text sizes that mean the same thing on both platforms. */
enum class TextSize(val label: String, val androidScale: Double, val iosCategory: String) {
    SMALL("Small", 0.85, "small"),
    DEFAULT("Default", 1.0, "large"),
    LARGE("Large", 1.15, "extra-large"),
    LARGER("Larger", 1.3, "extra-extra-large"),
    LARGEST("Largest", 2.0, "accessibility-extra-extra-extra-large");

    companion object {
        fun nearestAndroid(scale: Double) = entries.minBy { kotlin.math.abs(it.androidScale - scale) }
        fun fromIos(category: String) = entries.firstOrNull { it.iosCategory == category.trim() }
            ?: if (category.startsWith("accessibility")) LARGEST else DEFAULT
    }
}

/** A language to run the app in; `rtl` marks right-to-left layouts worth testing. */
data class AppLanguage(val label: String, val android: String, val ios: String, val rtl: Boolean = false) {
    companion object {
        val COMMON = listOf(
            AppLanguage("English", "en", "en"),
            AppLanguage("Arabic (RTL)", "ar", "ar", rtl = true),
            AppLanguage("Urdu (RTL)", "ur", "ur", rtl = true),
            AppLanguage("Hebrew (RTL)", "iw", "he", rtl = true),
            AppLanguage("German (long words)", "de", "de"),
            AppLanguage("French", "fr", "fr"),
            AppLanguage("Spanish", "es", "es"),
            AppLanguage("Portuguese (Brazil)", "pt-BR", "pt-BR"),
            AppLanguage("Hindi", "hi", "hi"),
            AppLanguage("Japanese", "ja", "ja"),
            AppLanguage("Chinese (Simplified)", "zh-CN", "zh-Hans"),
        )
    }
}

/** Current settings of a running device, for showing toggles in the right state. */
data class DeviceSettingsSnapshot(val darkMode: Boolean?, val textSize: TextSize?)

/** Permissions, appearance, text size, language, deep links, screenshots and Wi-Fi pairing. */
class DeviceSettings(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val tools: ToolPaths = ToolLocator.locate(),
) {
    // ---- Snapshot -------------------------------------------------------------------------------------

    fun read(device: Device): DeviceSettingsSnapshot {
        if (!device.isRunning) return DeviceSettingsSnapshot(null, null)
        return when (device.platform) {
            DevicePlatform.ANDROID -> {
                val adb = tools.adb ?: return DeviceSettingsSnapshot(null, null)
                val night = runner.run(listOf(adb, "-s", device.id, "shell", "cmd", "uimode", "night")).stdout
                val scale = runner.run(listOf(adb, "-s", device.id, "shell", "settings", "get", "system", "font_scale")).stdout.trim().toDoubleOrNull()
                DeviceSettingsSnapshot(
                    darkMode = when { "yes" in night -> true; "no" in night -> false; else -> null },
                    textSize = TextSize.nearestAndroid(scale ?: 1.0),
                )
            }
            DevicePlatform.IOS -> {
                val xcrun = tools.xcrun ?: return DeviceSettingsSnapshot(null, null)
                val appearance = runner.run(listOf(xcrun, "simctl", "ui", device.id, "appearance")).stdout.trim()
                val size = runner.run(listOf(xcrun, "simctl", "ui", device.id, "content_size")).stdout.trim()
                DeviceSettingsSnapshot(
                    darkMode = when (appearance) { "dark" -> true; "light" -> false; else -> null },
                    textSize = size.takeIf { it.isNotEmpty() && it != "unsupported" }?.let(TextSize::fromIos),
                )
            }
        }
    }

    // ---- Appearance and text size --------------------------------------------------------------------

    fun setDarkMode(device: Device, dark: Boolean): ActionResult = running(device) {
        val label = if (dark) "Dark mode on" else "Dark mode off"
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb -> outcome(runner.run(listOf(adb, "-s", device.id, "shell", "cmd", "uimode", "night", if (dark) "yes" else "no")), "$label on ${device.name}.") }
            DevicePlatform.IOS -> xcrun { x -> outcome(runner.run(listOf(x, "simctl", "ui", device.id, "appearance", if (dark) "dark" else "light")), "$label on ${device.name}.") }
        }
    }

    fun setTextSize(device: Device, size: TextSize): ActionResult = running(device) {
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                outcome(runner.run(listOf(adb, "-s", device.id, "shell", "settings", "put", "system", "font_scale", size.androidScale.toString())), "Text size ${size.label} on ${device.name}.")
            }
            DevicePlatform.IOS -> xcrun { x ->
                outcome(runner.run(listOf(x, "simctl", "ui", device.id, "content_size", size.iosCategory)), "Text size ${size.label} on ${device.name}.")
            }
        }
    }

    // ---- Permissions ----------------------------------------------------------------------------------

    /** Android reports each permission's state; the iOS simulator has no query, so its states are UNKNOWN. */
    fun permissions(device: Device, appId: String): Map<AppPermission, PermissionState> {
        val unknown = AppPermission.entries.associateWith { PermissionState.UNKNOWN }
        if (!device.isRunning || device.platform == DevicePlatform.IOS) return unknown
        val adb = tools.adb ?: return unknown
        val dump = runner.run(listOf(adb, "-s", device.id, "shell", "dumpsys", "package", appId)).stdout
        val (requested, granted) = parsePackageDump(dump)
        return AppPermission.entries.associateWith { p ->
            val asked = p.android.filter { it in requested }
            when {
                asked.isEmpty() -> PermissionState.NOT_REQUESTED
                asked.any { granted[it] == true } -> PermissionState.GRANTED
                else -> PermissionState.DENIED
            }
        }
    }

    fun setPermission(device: Device, appId: String, permission: AppPermission, grant: Boolean): ActionResult = app(device, appId) {
        val verb = if (grant) "Granted" else "Revoked"
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                val dump = runner.run(listOf(adb, "-s", device.id, "shell", "dumpsys", "package", appId)).stdout
                val requested = parsePackageDump(dump).first
                val targets = permission.android.filter { it in requested }
                if (targets.isEmpty()) return@adb ActionResult.failed("$appId doesn't request ${permission.label} permission.")
                val failures = targets.mapNotNull { perm ->
                    val r = runner.run(listOf(adb, "-s", device.id, "shell", "pm", if (grant) "grant" else "revoke", appId, perm))
                    r.takeIf { !it.ok }?.errorSummary
                }
                if (failures.isEmpty()) ActionResult.done("$verb ${permission.label} for $appId.")
                else ActionResult.failed("Couldn't change ${permission.label}: ${failures.first()}")
            }
            DevicePlatform.IOS -> xcrun { x ->
                val service = permission.ios ?: return@xcrun ActionResult.failed("The iOS simulator can't change ${permission.label} permission.")
                outcome(runner.run(listOf(x, "simctl", "privacy", device.id, if (grant) "grant" else "revoke", service, appId)), "$verb ${permission.label} for $appId.")
            }
        }
    }

    /** Back to "ask on next use" for every permission. */
    fun resetPermissions(device: Device, appId: String): ActionResult = app(device, appId) {
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                val dump = runner.run(listOf(adb, "-s", device.id, "shell", "dumpsys", "package", appId)).stdout
                val runtime = parsePackageDump(dump).second.keys
                runtime.forEach { perm ->
                    runner.run(listOf(adb, "-s", device.id, "shell", "pm", "revoke", appId, perm))
                    runner.run(listOf(adb, "-s", device.id, "shell", "pm", "clear-permission-flags", appId, perm, "user-set", "user-fixed"))
                }
                ActionResult.done("Reset ${runtime.size} permission(s) for $appId; the app will ask again.")
            }
            DevicePlatform.IOS -> xcrun { x -> outcome(runner.run(listOf(x, "simctl", "privacy", device.id, "reset", "all", appId)), "Reset permissions for $appId; the app will ask again.") }
        }
    }

    // ---- Language ---------------------------------------------------------------------------------------

    /** Runs the app in [language]; null goes back to the device language. */
    fun setAppLanguage(device: Device, appId: String, language: AppLanguage?): ActionResult = app(device, appId) {
        val label = language?.label ?: "the device language"
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                if ((device.apiLevel ?: 0) in 1..32) return@adb ActionResult.failed("Per-app language needs Android 13 or newer.")
                // An empty locale list means "follow the device"; quoted so adb shell keeps the empty argument.
                val command = listOf(adb, "-s", device.id, "shell", "cmd", "locale", "set-app-locales", appId, "--locales", shellQuote(language?.android ?: ""))
                val set = runner.run(command)
                if (!set.ok) return@adb ActionResult.failed("Couldn't change the language: ${set.errorSummary}")
                // Many apps (Compose Multiplatform resources, AppCompat on older setups) only read the locale at
                // start, so restart the app the way iOS does.
                runner.run(listOf(adb, "-s", device.id, "shell", "am", "force-stop", appId))
                val launch = runner.run(listOf(adb, "-s", device.id, "shell", "monkey", "-p", appId, "-c", "android.intent.category.LAUNCHER", "1"))
                if ("No activities found" in launch.stdout + launch.stderr || !launch.ok) ActionResult.done("$appId will use $label next time it starts.")
                else ActionResult.done("Relaunched $appId in $label.")
            }
            DevicePlatform.IOS -> xcrun { x ->
                // Simulator apps read their language from launch arguments; relaunch with them.
                val args = language?.let { listOf("-AppleLanguages", "(${it.ios})", "-AppleLocale", it.ios) }.orEmpty()
                outcome(runner.run(listOf(x, "simctl", "launch", "--terminate-running-process", device.id, appId) + args, timeoutSeconds = 60), "Relaunched $appId in $label.")
            }
        }
    }

    // ---- Deep links ---------------------------------------------------------------------------------------

    fun openLink(device: Device, url: String, appId: String? = null): ActionResult = running(device) {
        val link = url.trim()
        if (!LINK.matches(link)) return@running ActionResult.failed("\"$link\" isn't a link (expected something like myapp://home or https://…).")
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                val command = listOf(adb, "-s", device.id, "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", shellQuote(link)) +
                    listOfNotNull(appId?.takeIf { it.isNotBlank() })
                val r = runner.run(command, timeoutSeconds = 30)
                when {
                    "unable to resolve Intent" in r.stdout + r.stderr -> ActionResult.failed("No app on ${device.name} handles $link.")
                    "Error" in r.stdout || !r.ok -> ActionResult.failed("Couldn't open $link: ${r.errorSummary}")
                    else -> ActionResult.done("Opened $link on ${device.name}.")
                }
            }
            DevicePlatform.IOS -> xcrun { x ->
                val r = runner.run(listOf(x, "simctl", "openurl", device.id, link), timeoutSeconds = 30)
                if (r.ok) ActionResult.done("Opened $link on ${device.name}.") else ActionResult.failed("No app on ${device.name} handles $link.")
            }
        }
    }

    // ---- Screenshot -------------------------------------------------------------------------------------

    fun screenshot(device: Device, target: File): ActionResult = running(device) {
        target.parentFile?.mkdirs()
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                val remote = "/data/local/tmp/kmpdevicehub-screenshot.png"
                val cap = runner.run(listOf(adb, "-s", device.id, "shell", "screencap", "-p", remote), timeoutSeconds = 30)
                if (!cap.ok) return@adb ActionResult.failed("Couldn't take a screenshot: ${cap.errorSummary}")
                val pull = runner.run(listOf(adb, "-s", device.id, "pull", remote, target.path), timeoutSeconds = 30)
                runner.run(listOf(adb, "-s", device.id, "shell", "rm", "-f", remote))
                if (pull.ok) ActionResult.done(target.path) else ActionResult.failed("Couldn't copy the screenshot: ${pull.errorSummary}")
            }
            DevicePlatform.IOS -> xcrun { x ->
                val r = runner.run(listOf(x, "simctl", "io", device.id, "screenshot", target.path), timeoutSeconds = 30)
                if (r.ok) ActionResult.done(target.path) else ActionResult.failed("Couldn't take a screenshot: ${r.errorSummary}")
            }
        }
    }

    /** The package of an "isn't responding" dialog covering an Android screen, or null. */
    fun notRespondingDialog(device: Device): String? {
        if (device.platform != DevicePlatform.ANDROID || !device.isRunning) return null
        val adb = tools.adb ?: return null
        val windows = runner.run(listOf(adb, "-s", device.id, "shell", "dumpsys", "window", "windows")).stdout
        return Regex("""Window\{\S+ \S+ Application Not Responding: ([\w.]+)}""").find(windows)?.groupValues?.get(1)
    }

    // ---- Wi-Fi debugging (Android 11+) --------------------------------------------------------------------

    /** Pairs with the code shown under Developer options → Wireless debugging → Pair device with pairing code. */
    fun pair(hostPort: String, code: String): ActionResult = adb { adb ->
        if (!HOST_PORT.matches(hostPort.trim())) return@adb ActionResult.failed("Enter the pairing address as ip:port, e.g. 192.168.1.20:37099.")
        if (!Regex("\\d{6}").matches(code.trim())) return@adb ActionResult.failed("The pairing code has 6 digits.")
        val r = runner.run(listOf(adb, "pair", hostPort.trim(), code.trim()), timeoutSeconds = 30)
        if ("Successfully paired" in r.stdout) ActionResult.done("Paired. Now connect using the address shown under Wireless debugging.")
        else ActionResult.failed("Pairing failed: ${r.errorSummary}")
    }

    fun connect(hostPort: String): ActionResult = adb { adb ->
        if (!HOST_PORT.matches(hostPort.trim())) return@adb ActionResult.failed("Enter the device address as ip:port, e.g. 192.168.1.20:41355.")
        val r = runner.run(listOf(adb, "connect", hostPort.trim()), timeoutSeconds = 30)
        if ("connected to" in r.stdout) ActionResult.done("Connected to ${hostPort.trim()} over Wi-Fi.")
        else ActionResult.failed("Couldn't connect: ${r.errorSummary}")
    }

    // ---- Helpers -------------------------------------------------------------------------------------------

    private fun outcome(r: CommandResult, success: String) = if (r.ok) ActionResult.done(success) else ActionResult.failed(r.errorSummary)

    private inline fun running(device: Device, block: () -> ActionResult) =
        if (device.isRunning) block() else ActionResult.failed("Start ${device.name} first.")

    private inline fun app(device: Device, appId: String, block: () -> ActionResult) = when {
        appId.isBlank() -> ActionResult.failed("Choose an app first.")
        !device.isRunning -> ActionResult.failed("Start ${device.name} first.")
        else -> block()
    }

    private inline fun adb(block: (String) -> ActionResult) = tools.adb?.let(block) ?: ActionResult.failed("adb not found. Install the Android SDK platform tools.")

    private inline fun xcrun(block: (String) -> ActionResult) = tools.xcrun?.let(block) ?: ActionResult.failed("Xcode not found. iOS simulators need Xcode.")

    companion object {
        private val LINK = Regex("""[A-Za-z][A-Za-z0-9+.\-]*:\S+""")
        private val HOST_PORT = Regex("""[A-Za-z0-9.\-\[\]:]+:\d{2,5}""")

        /** From `dumpsys package <app>`: requested permissions, and runtime permission → granted. */
        internal fun parsePackageDump(dump: String): Pair<Set<String>, Map<String, Boolean>> {
            val requested = mutableSetOf<String>()
            val granted = mutableMapOf<String, Boolean>()
            var section: String? = null
            for (raw in dump.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("requested permissions:") -> { section = "requested"; continue }
                    line.startsWith("install permissions:") -> { section = "install"; continue }
                    line.startsWith("runtime permissions:") -> { section = "runtime"; continue }
                    line.isEmpty() || (line.endsWith(":") && !line.startsWith("android.permission")) -> { section = null; continue }
                }
                when (section) {
                    "requested" -> Regex("""^([\w.]+)""").find(line)?.let { requested += it.groupValues[1] }
                    "runtime" -> Regex("""^([\w.]+): granted=(true|false)""").find(line)?.let { granted[it.groupValues[1]] = it.groupValues[2] == "true" }
                }
            }
            return requested to granted
        }
    }
}
