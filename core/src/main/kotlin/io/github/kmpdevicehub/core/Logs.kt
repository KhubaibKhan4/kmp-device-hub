package io.github.kmpdevicehub.core

/** Log levels shared by Android (logcat) and Apple (unified logging). */
enum class LogLevel(val label: String, val letter: Char) {
    VERBOSE("Verbose", 'V'), DEBUG("Debug", 'D'), INFO("Info", 'I'), WARN("Warn", 'W'), ERROR("Error", 'E'),
}

/** One log line from a device. [time] is HH:mm:ss.SSS; [tag] is the logcat tag or the Apple subsystem/category. */
data class LogEntry(val time: String, val level: LogLevel, val tag: String, val message: String, val pid: String? = null)

/** Parses the line formats `adb logcat -v threadtime`, `log stream --style compact` and app stdout produce. */
object LogParser {
    private val LOGCAT = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d\.\d+)\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.*?)\s*: ?(.*)$""")
    private val APPLE = Regex("""^\d{4}-\d\d-\d\d (\d\d:\d\d:\d\d\.\d{3})\d*(?:[+-]\d{4})?\s+(Df|Db|I|E|F|A|D|W)\s+(\S+?)\[(\d+):[0-9a-fx]+]\s+(?:\[([^\]]*)]\s+)?(.*)$""")

    fun logcat(line: String): LogEntry? = LOGCAT.find(line)?.destructured?.let { (time, pid, level, tag, message) ->
        val l = when (level[0]) {
            'V' -> LogLevel.VERBOSE; 'D' -> LogLevel.DEBUG; 'I' -> LogLevel.INFO; 'W' -> LogLevel.WARN
            else -> LogLevel.ERROR // E, F (fatal) and A (assert)
        }
        LogEntry(time.take(12), l, tag.trim(), message, pid)
    }

    fun apple(line: String): LogEntry? = APPLE.find(line)?.destructured?.let { (time, type, process, pid, category, message) ->
        val l = when (type) {
            "Db", "D" -> LogLevel.DEBUG
            "E", "F", "A" -> LogLevel.ERROR
            "W" -> LogLevel.WARN
            else -> LogLevel.INFO // Df (default) and I
        }
        LogEntry(time, l, category.ifEmpty { process }, message, pid)
    }

    /** App stdout/stderr (e.g. Kotlin/Native println), which has no structure of its own. */
    fun console(line: String, now: () -> String = ::clock): LogEntry? {
        if (line.isBlank()) return null
        val level = when {
            Regex("""\b(Exception|Error|FATAL|Uncaught)\b""").containsMatchIn(line) -> LogLevel.ERROR
            Regex("""\b(WARN|Warning)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        return LogEntry(now(), level, "stdout", line.trimEnd())
    }

    private fun clock(): String = java.time.LocalTime.now().toString().padEnd(12, '0').take(12)
}

/** Streams an app's logs from an Android device (logcat) or an iOS simulator (unified log, or the app's stdout). */
class LogStreamer(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val tools: ToolPaths = ToolLocator.locate(),
) {
    /** The app's process id on an Android device, or null when it isn't running. */
    fun androidPid(device: Device, appId: String): String? {
        val adb = tools.adb ?: return null
        return runner.run(listOf(adb, "-s", device.id, "shell", "pidof", appId)).stdout.trim().split(' ').firstOrNull { it.isNotBlank() }
    }

    fun clearAndroid(device: Device): ActionResult {
        val adb = tools.adb ?: return ActionResult.failed("adb not found.")
        val r = runner.run(listOf(adb, "-s", device.id, "logcat", "-c"))
        return if (r.ok) ActionResult.done("Cleared the log buffer on ${device.name}.") else ActionResult.failed(r.errorSummary)
    }

    /** Logcat for one process (its recent history first), or for the whole device when [pid] is null. */
    fun streamAndroid(device: Device, pid: String?, onEntry: (LogEntry) -> Unit): RunningProcess? {
        val adb = tools.adb ?: return null
        val command = listOf(adb, "-s", device.id, "logcat", "-v", "threadtime") + listOfNotNull(pid?.let { "--pid=$it" })
        return runner.stream(command) { line -> LogParser.logcat(line)?.let(onEntry) }
    }

    /** The executable name of an installed simulator app, which is what Apple's log calls the process. */
    fun iosProcessName(device: Device, appId: String): String? {
        val xcrun = tools.xcrun ?: return null
        val bundle = runner.run(listOf(xcrun, "simctl", "get_app_container", device.id, appId, "app"))
        if (!bundle.ok) return null
        val path = bundle.stdout.trim()
        val exe = runner.run(listOf("/usr/bin/plutil", "-extract", "CFBundleExecutable", "raw", "$path/Info.plist")).stdout.trim()
        return exe.ifEmpty { path.substringAfterLast('/').removeSuffix(".app") }.takeIf { it.isNotEmpty() }
    }

    /** The app's unified log (os_log, NSLog, Kermit/Napier on Apple) at debug level and up. */
    fun streamIos(device: Device, processName: String, onEntry: (LogEntry) -> Unit): RunningProcess? {
        val xcrun = tools.xcrun ?: return null
        val predicate = "process == \"${processName.replace("\"", "")}\""
        return runner.stream(listOf(xcrun, "simctl", "spawn", device.id, "log", "stream", "--style", "compact", "--level", "debug",
            "--predicate", predicate)) { line -> LogParser.apple(line)?.let(onEntry) }
    }

    /**
     * Relaunches the app attached to its stdout/stderr, so Kotlin/Native `println` shows up too (it doesn't reach the
     * unified log). Stopping the stream leaves the app running.
     */
    fun streamIosConsole(device: Device, appId: String, onEntry: (LogEntry) -> Unit): RunningProcess? {
        val xcrun = tools.xcrun ?: return null
        return runner.stream(listOf(xcrun, "simctl", "launch", "--console-pty", "--terminate-running-process", device.id, appId)) { line ->
            // simctl first prints "<bundle id>: <pid>".
            if (!line.startsWith("$appId:")) LogParser.console(line)?.let(onEntry)
        }
    }
}
