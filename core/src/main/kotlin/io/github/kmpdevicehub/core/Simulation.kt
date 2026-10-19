package io.github.kmpdevicehub.core

import java.io.File

/** A named place to put the device. */
data class GeoPoint(val label: String, val latitude: Double, val longitude: Double) {
    companion object {
        val COMMON = listOf(
            GeoPoint("Cupertino", 37.3349, -122.0090),
            GeoPoint("New York", 40.7128, -74.0060),
            GeoPoint("London", 51.5072, -0.1276),
            GeoPoint("Dubai", 25.2048, 55.2708),
            GeoPoint("Karachi", 24.8607, 67.0011),
            GeoPoint("Tokyo", 35.6762, 139.6503),
            GeoPoint("Sydney", -33.8688, 151.2093),
        )
    }
}

/** Network conditions for the Android emulator (`adb emu network speed/delay`), plus offline via airplane mode. */
enum class NetworkProfile(val label: String, val speed: String?, val delay: String?) {
    FULL("Full speed", "full", "none"),
    LTE("4G LTE", "lte", "none"),
    HSDPA("3G", "hsdpa", "umts"),
    EDGE("EDGE (slow)", "edge", "edge"),
    GPRS("GPRS (very slow)", "gprs", "gprs"),
    OFFLINE("Offline", null, null),
}

/** A push notification to deliver to the app. [rawJson], when set, is sent to iOS as is. */
data class PushNotification(
    val title: String,
    val body: String,
    val badge: Int? = null,
    val data: Map<String, String> = emptyMap(),
    val rawJson: String? = null,
) {
    /** The APNs payload simctl expects. */
    fun apnsJson(): String {
        rawJson?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val alert = "{\"title\":${quote(title)},\"body\":${quote(body)}}"
        val aps = buildString {
            append("{\"alert\":").append(alert).append(",\"sound\":\"default\"")
            badge?.let { append(",\"badge\":").append(it) }
            append('}')
        }
        val custom = data.entries.joinToString("") { ",${quote(it.key)}:${quote(it.value)}" }
        return "{\"aps\":$aps$custom}"
    }

    companion object {
        internal fun quote(s: String) = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }

        /** `key=value` lines into a map; blank lines are skipped. */
        fun parseData(text: String): Map<String, String> = text.lines()
            .map { it.trim() }.filter { it.isNotEmpty() && '=' in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }
}

/** A screen recording in progress; pass it to [DeviceSimulation.stopRecording]. */
class ScreenRecording internal constructor(
    val device: Device,
    val target: File,
    internal val process: RunningProcess,
    val startedAt: Long = System.currentTimeMillis(),
)

/** Location, status bar, network conditions, push notifications and screen recording. */
class DeviceSimulation(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val tools: ToolPaths = ToolLocator.locate(),
) {
    // ---- Location ---------------------------------------------------------------------------------------

    fun setLocation(device: Device, latitude: Double, longitude: Double): ActionResult = running(device) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@running ActionResult.failed("Latitude must be -90…90 and longitude -180…180.")
        val where = "%.4f, %.4f".format(java.util.Locale.ROOT, latitude, longitude)
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                if (device.kind != DeviceKind.EMULATOR) return@adb ActionResult.failed("Location can only be set on emulators, not physical phones.")
                // geo fix takes longitude first.
                val r = runner.run(listOf(adb, "-s", device.id, "emu", "geo", "fix", longitude.toString(), latitude.toString()))
                outcome(r, "Location set to $where on ${device.name}.")
            }
            DevicePlatform.IOS -> xcrun { x ->
                outcome(runner.run(listOf(x, "simctl", "location", device.id, "set", "$latitude,$longitude")), "Location set to $where on ${device.name}.")
            }
        }
    }

    // ---- Status bar -------------------------------------------------------------------------------------

    /** 9:41, full battery and signal, no notification icons: the status bar for store screenshots. */
    fun cleanStatusBar(device: Device, clean: Boolean): ActionResult = running(device) {
        val done = if (clean) "Clean status bar on ${device.name}." else "Normal status bar on ${device.name}."
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                fun demo(vararg extras: String) = runner.run(listOf(adb, "-s", device.id, "shell", "am", "broadcast", "-a", "com.android.systemui.demo") + extras)
                if (!clean) return@adb outcome(demo("-e", "command", "exit"), done)
                val allow = runner.run(listOf(adb, "-s", device.id, "shell", "settings", "put", "global", "sysui_demo_allowed", "1"))
                if (!allow.ok) return@adb ActionResult.failed("Couldn't enable demo mode: ${allow.errorSummary}")
                val steps = listOf(
                    demo("-e", "command", "enter"),
                    demo("-e", "command", "clock", "-e", "hhmm", "0941"),
                    demo("-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"),
                    demo("-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4"),
                    demo("-e", "command", "network", "-e", "mobile", "show", "-e", "datatype", "none", "-e", "level", "4"),
                    demo("-e", "command", "notifications", "-e", "visible", "false"),
                )
                steps.firstOrNull { !it.ok }?.let { ActionResult.failed("Couldn't change the status bar: ${it.errorSummary}") } ?: ActionResult.done(done)
            }
            DevicePlatform.IOS -> xcrun { x ->
                val command = if (clean) {
                    listOf(x, "simctl", "status_bar", device.id, "override", "--time", "9:41", "--dataNetwork", "wifi", "--wifiMode", "active",
                        "--wifiBars", "3", "--cellularMode", "active", "--cellularBars", "4", "--batteryState", "charged", "--batteryLevel", "100")
                } else {
                    listOf(x, "simctl", "status_bar", device.id, "clear")
                }
                outcome(runner.run(command), done)
            }
        }
    }

    // ---- Network ---------------------------------------------------------------------------------------

    fun setNetwork(device: Device, profile: NetworkProfile): ActionResult = running(device) {
        if (device.platform == DevicePlatform.IOS) {
            return@running ActionResult.failed("iOS simulators share your Mac's network. Use Network Link Conditioner (Xcode Additional Tools) to slow it down.")
        }
        adb { adb ->
            val airplane = runner.run(listOf(adb, "-s", device.id, "shell", "cmd", "connectivity", "airplane-mode"))
            if (profile == NetworkProfile.OFFLINE) {
                return@adb outcome(runner.run(listOf(adb, "-s", device.id, "shell", "cmd", "connectivity", "airplane-mode", "enable")), "${device.name} is offline (airplane mode).")
            }
            if ("enabled" in airplane.stdout) runner.run(listOf(adb, "-s", device.id, "shell", "cmd", "connectivity", "airplane-mode", "disable"))
            if (device.kind != DeviceKind.EMULATOR) {
                return@adb if (profile == NetworkProfile.FULL) ActionResult.done("${device.name} is back online.")
                else ActionResult.failed("Network speed can only be changed on emulators.")
            }
            val speed = runner.run(listOf(adb, "-s", device.id, "emu", "network", "speed", profile.speed!!))
            if (!speed.ok || "KO" in speed.stdout) return@adb ActionResult.failed("Couldn't change network speed: ${speed.errorSummary}")
            val delay = runner.run(listOf(adb, "-s", device.id, "emu", "network", "delay", profile.delay!!))
            if (!delay.ok || "KO" in delay.stdout) return@adb ActionResult.failed("Couldn't change network delay: ${delay.errorSummary}")
            ActionResult.done("Network on ${device.name}: ${profile.label}.")
        }
    }

    // ---- Battery, rotation, biometrics, media, clipboard ------------------------------------------------

    /** Battery level (0–100) and charging state: the emulator's battery, or the simulator's status bar. */
    fun setBattery(device: Device, level: Int, charging: Boolean): ActionResult = running(device) {
        if (level !in 0..100) return@running ActionResult.failed("Battery level must be 0–100.")
        val done = "Battery at $level%${if (charging) ", charging" else ""} on ${device.name}."
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                if (device.kind != DeviceKind.EMULATOR) return@adb ActionResult.failed("The battery can only be simulated on emulators.")
                val steps = listOf(
                    listOf("power", "ac", if (charging) "on" else "off"),
                    listOf("power", "status", if (charging) "charging" else "discharging"),
                    listOf("power", "capacity", level.toString()),
                ).map { runner.run(listOf(adb, "-s", device.id, "emu") + it) }
                steps.firstOrNull { !it.ok || it.stdout.startsWith("KO") }?.let { ActionResult.failed("Couldn't change the battery: ${it.errorSummary}") } ?: ActionResult.done(done)
            }
            DevicePlatform.IOS -> xcrun { x ->
                outcome(runner.run(listOf(x, "simctl", "status_bar", device.id, "override", "--batteryLevel", level.toString(),
                    "--batteryState", if (charging) "charging" else "discharging")), done)
            }
        }
    }

    /** Rotates the screen a quarter turn. Android only: the iOS simulator has no command for it (⌘← / ⌘→ in Simulator). */
    fun rotate(device: Device): ActionResult = running(device) {
        if (device.platform == DevicePlatform.IOS) return@running ActionResult.failed("Simulators can't be rotated from the command line; press ⌘→ in the Simulator window.")
        adb { adb ->
            val current = runner.run(listOf(adb, "-s", device.id, "shell", "settings", "get", "system", "user_rotation")).stdout.trim().toIntOrNull() ?: 0
            val next = (current + 1) % 4
            runner.run(listOf(adb, "-s", device.id, "shell", "settings", "put", "system", "accelerometer_rotation", "0"))
            val r = runner.run(listOf(adb, "-s", device.id, "shell", "settings", "put", "system", "user_rotation", next.toString()))
            outcome(r, "Rotated ${device.name} to ${listOf("portrait", "landscape", "upside-down portrait", "reverse landscape")[next]}.")
        }
    }

    /** Back to automatic rotation (Android). */
    fun resetRotation(device: Device): ActionResult = running(device) {
        if (device.platform == DevicePlatform.IOS) return@running ActionResult.done("Nothing to reset on ${device.name}.")
        adb { adb ->
            runner.run(listOf(adb, "-s", device.id, "shell", "settings", "put", "system", "user_rotation", "0"))
            outcome(runner.run(listOf(adb, "-s", device.id, "shell", "settings", "put", "system", "accelerometer_rotation", "1")), "Auto-rotate on for ${device.name}.")
        }
    }

    /**
     * A fingerprint / Face ID attempt that matches or fails, for testing biometric prompts. Android emulators need a
     * fingerprint enrolled as finger 1 (Settings → Security); simulators need Features → Face ID / Touch ID → Enrolled.
     */
    fun biometric(device: Device, match: Boolean): ActionResult = running(device) {
        val outcomeText = if (match) "Matching" else "Non-matching"
        when (device.platform) {
            DevicePlatform.ANDROID -> adb { adb ->
                if (device.kind != DeviceKind.EMULATOR) return@adb ActionResult.failed("Fingerprints can only be simulated on emulators.")
                outcome(runner.run(listOf(adb, "-s", device.id, "emu", "finger", "touch", if (match) "1" else "2")), "$outcomeText fingerprint sent to ${device.name}.")
            }
            DevicePlatform.IOS -> xcrun { x ->
                // Face ID ("pearl") and Touch ID ("fingerTouch"); the simulator only reacts to the kind it has.
                val suffix = if (match) "match" else "nomatch"
                val results = listOf("pearl", "fingerTouch").map {
                    runner.run(listOf(x, "simctl", "spawn", device.id, "notifyutil", "-p", "com.apple.BiometricKit_Sim.$it.$suffix"))
                }
                results.firstOrNull { !it.ok }?.let { ActionResult.failed(it.errorSummary) } ?: ActionResult.done("$outcomeText Face ID / Touch ID sent to ${device.name}.")
            }
        }
    }

    /** Turns biometric enrollment on or off (iOS simulators). */
    fun enrollBiometrics(device: Device, enrolled: Boolean): ActionResult = running(device) {
        if (device.platform == DevicePlatform.ANDROID) return@running ActionResult.failed("Enroll a fingerprint in the emulator's Settings → Security, then use finger 1.")
        xcrun { x ->
            val set = runner.run(listOf(x, "simctl", "spawn", device.id, "notifyutil", "-s", "com.apple.BiometricKit.enrollmentChanged", if (enrolled) "1" else "0"))
            if (!set.ok) return@xcrun ActionResult.failed(set.errorSummary)
            outcome(runner.run(listOf(x, "simctl", "spawn", device.id, "notifyutil", "-p", "com.apple.BiometricKit.enrollmentChanged")),
                "Face ID / Touch ID ${if (enrolled) "enrolled" else "not enrolled"} on ${device.name}.")
        }
    }

    /** Adds photos and videos to the device's gallery (Photos on iOS, MediaStore on Android). */
    fun addMedia(device: Device, files: List<File>): ActionResult = running(device) {
        if (files.isEmpty()) return@running ActionResult.failed("Choose photos or videos to add.")
        val bad = files.firstOrNull { it.extension.lowercase() !in MEDIA }
        if (bad != null) return@running ActionResult.failed("${bad.name} isn't a photo or video (${MEDIA.joinToString(", ")}).")
        val label = files.singleOrNull()?.name ?: "${files.size} files"
        when (device.platform) {
            DevicePlatform.IOS -> xcrun { x ->
                outcome(runner.run(listOf(x, "simctl", "addmedia", device.id) + files.map { it.path }, timeoutSeconds = 120), "Added $label to Photos on ${device.name}.")
            }
            DevicePlatform.ANDROID -> adb { adb ->
                for (file in files) {
                    val folder = if (file.extension.lowercase() in VIDEO) "Movies" else "Pictures"
                    val remote = "/sdcard/$folder/${file.name}"
                    val push = runner.run(listOf(adb, "-s", device.id, "push", file.path, remote), timeoutSeconds = 120)
                    if (!push.ok) return@adb ActionResult.failed("Couldn't copy ${file.name}: ${push.errorSummary}")
                    // Tell MediaStore about the new file so it shows up in the gallery and photo pickers.
                    runner.run(listOf(adb, "-s", device.id, "shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                        "-d", shellQuote("file://$remote")))
                }
                ActionResult.done("Added $label to the gallery on ${device.name}.")
            }
        }
    }

    /** Puts [text] on the simulator's clipboard. Android emulators already share the Mac's clipboard. */
    fun setClipboard(device: Device, text: String): ActionResult = running(device) {
        if (device.platform == DevicePlatform.ANDROID) {
            return@running ActionResult.failed("Android emulators share your computer's clipboard: copy here and paste there (Extended controls → Settings → Clipboard sharing).")
        }
        xcrun { x ->
            // simctl reads the pasteboard as Mac Roman unless the locale is UTF-8.
            outcome(runner.run(listOf("/bin/sh", "-c", "printf %s \"\$1\" | LC_ALL=en_US.UTF-8 \"\$2\" simctl pbcopy \"\$3\"", "sh", text, x, device.id)),
                "Copied to ${device.name}'s clipboard.")
        }
    }

    /** The simulator's clipboard text, or null. */
    fun getClipboard(device: Device): String? {
        if (!device.isRunning || device.platform != DevicePlatform.IOS) return null
        val x = tools.xcrun ?: return null
        return runner.run(listOf("/usr/bin/env", "LC_ALL=en_US.UTF-8", x, "simctl", "pbpaste", device.id)).takeIf { it.ok }?.stdout
    }

    // ---- Push notifications -------------------------------------------------------------------------------

    /**
     * iOS: a real (simulated) push through `simctl push`. Android has no local push without Firebase, so it opens
     * the app the way tapping a Firebase notification does: the launcher activity with the data as string extras.
     */
    fun sendPush(device: Device, appId: String, push: PushNotification): ActionResult = app(device, appId) {
        when (device.platform) {
            DevicePlatform.IOS -> xcrun { x ->
                val json = push.apnsJson()
                val parsed = runCatching { Json.parse(json) }.getOrNull()
                if (parsed !is Map<*, *> || parsed["aps"] !is Map<*, *>) return@xcrun ActionResult.failed("The payload must be a JSON object with an \"aps\" object.")
                val file = File.createTempFile("kmpdevicehub-push", ".apns")
                try {
                    file.writeText(json)
                    outcome(runner.run(listOf(x, "simctl", "push", device.id, appId, file.path), timeoutSeconds = 30), "Push sent to $appId on ${device.name}.")
                } finally {
                    file.delete()
                }
            }
            DevicePlatform.ANDROID -> adb { adb ->
                val bad = push.data.keys.firstOrNull { !EXTRA_KEY.matches(it) }
                if (bad != null) return@adb ActionResult.failed("\"$bad\" isn't a valid data key (letters, digits, '.', '_' and '-').")
                val resolve = runner.run(listOf(adb, "-s", device.id, "shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", appId))
                val component = resolve.stdout.lines().map { it.trim() }.lastOrNull { '/' in it }
                    ?: return@adb ActionResult.failed("$appId isn't installed on ${device.name}, or has no launcher activity.")
                val extras = push.data.flatMap { (k, v) -> listOf("--es", k, shellQuote(v)) }
                val r = runner.run(listOf(adb, "-s", device.id, "shell", "am", "start", "-n", component, "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER", "-f", "0x10000000") + extras, timeoutSeconds = 30)
                if (r.ok && "Error" !in r.stdout) ActionResult.done("Opened $appId as if its notification was tapped (${push.data.size} data field(s)).")
                else ActionResult.failed("Couldn't open $appId: ${r.errorSummary}")
            }
        }
    }

    // ---- Screen recording ---------------------------------------------------------------------------------

    fun startRecording(device: Device, target: File): Pair<ActionResult, ScreenRecording?> {
        if (!device.isRunning) return ActionResult.failed("Start ${device.name} first.") to null
        target.parentFile?.mkdirs()
        val command = when (device.platform) {
            DevicePlatform.ANDROID -> tools.adb?.let { listOf(it, "-s", device.id, "shell", "screenrecord", "--bit-rate", "8000000", ANDROID_RECORDING) }
            DevicePlatform.IOS -> tools.xcrun?.let { listOf(it, "simctl", "io", device.id, "recordVideo", "--codec=h264", "--force", target.path) }
        } ?: return ActionResult.failed(if (device.platform == DevicePlatform.ANDROID) ADB_MISSING else XCRUN_MISSING) to null
        val process = runner.start(command) ?: return ActionResult.failed("Couldn't start recording ${device.name}.") to null
        val note = if (device.platform == DevicePlatform.ANDROID) " Android stops recording by itself after 3 minutes." else ""
        return ActionResult.done("Recording ${device.name}…$note") to ScreenRecording(device, target, process)
    }

    /** Stops gracefully so the video file is finalized, then copies it off the device (Android). */
    fun stopRecording(recording: ScreenRecording): ActionResult {
        val device = recording.device
        when (device.platform) {
            DevicePlatform.ANDROID -> tools.adb?.let { runner.run(listOf(it, "-s", device.id, "shell", "pkill", "-INT", "screenrecord")) }
            DevicePlatform.IOS -> runner.run(listOf("kill", "-INT", recording.process.pid.toString()))
        }
        if (!recording.process.waitFor(15)) recording.process.kill()
        if (device.platform == DevicePlatform.ANDROID) {
            val adb = tools.adb ?: return ActionResult.failed(ADB_MISSING)
            Thread.sleep(500) // screenrecord writes the mp4 index on exit
            val pull = runner.run(listOf(adb, "-s", device.id, "pull", ANDROID_RECORDING, recording.target.path), timeoutSeconds = 120)
            runner.run(listOf(adb, "-s", device.id, "shell", "rm", "-f", ANDROID_RECORDING))
            if (!pull.ok) return ActionResult.failed("Couldn't copy the recording: ${pull.errorSummary}")
        }
        return ActionResult.done(recording.target.path)
    }

    // ---- Helpers -------------------------------------------------------------------------------------------

    private fun outcome(r: CommandResult, success: String) =
        if (r.ok && !r.stdout.startsWith("KO")) ActionResult.done(success) else ActionResult.failed(r.errorSummary)

    private inline fun running(device: Device, block: () -> ActionResult) =
        if (device.isRunning) block() else ActionResult.failed("Start ${device.name} first.")

    private inline fun app(device: Device, appId: String, block: () -> ActionResult) = when {
        appId.isBlank() -> ActionResult.failed("Choose an app first.")
        !device.isRunning -> ActionResult.failed("Start ${device.name} first.")
        else -> block()
    }

    private inline fun adb(block: (String) -> ActionResult) = tools.adb?.let(block) ?: ActionResult.failed(ADB_MISSING)

    private inline fun xcrun(block: (String) -> ActionResult) = tools.xcrun?.let(block) ?: ActionResult.failed(XCRUN_MISSING)

    companion object {
        internal const val ANDROID_RECORDING = "/data/local/tmp/kmpdevicehub-recording.mp4"
        private const val ADB_MISSING = "adb not found. Install the Android SDK platform tools."
        private const val XCRUN_MISSING = "Xcode not found. iOS simulators need Xcode."
        private val EXTRA_KEY = Regex("""[A-Za-z0-9_.\-]+""")
        private val VIDEO = setOf("mp4", "mov", "m4v", "3gp", "webm")
        private val MEDIA = setOf("jpg", "jpeg", "png", "heic", "gif", "webp") + VIDEO
    }
}

/**
 * `adb shell` joins its arguments into one command line for the device's shell, so anything with spaces or
 * shell characters (`&` in a URL, a JSON value) must be quoted. Plain values are left as they are.
 */
fun shellQuote(value: String): String =
    if (value.isNotEmpty() && Regex("""[A-Za-z0-9_@%+=:,./\-]+""").matches(value)) value
    else "'" + value.replace("'", "'\\''") + "'"
