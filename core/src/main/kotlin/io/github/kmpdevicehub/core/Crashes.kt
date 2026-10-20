package io.github.kmpdevicehub.core

/**
 * A source location in a stack trace. [suffix] is the end of the file's path that identifies it in a project
 * (`com/example/shop/Cart.kt` for a JVM frame, `iosApp/ContentView.swift` for Swift); [path] is set when the trace has
 * the full path (Kotlin/Native).
 */
data class StackFrame(val fileName: String, val line: Int, val suffix: String, val path: String? = null, val className: String? = null) {
    /** JDK, Kotlin and Android frames, which are never in the project. */
    val isFramework: Boolean get() = className != null && FRAMEWORK.any { className.startsWith(it) }

    private companion object {
        val FRAMEWORK = listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "android.", "androidx.", "com.android.", "dalvik.", "libcore.", "com.google.android.")
    }
}

/** One line of a crash as shown: the text, and its source location when it is a stack frame with one. */
data class TraceLine(val text: String, val frame: StackFrame? = null)

/** A crash found in an app's log: [title] is the exception (or signal) and its message. */
data class Crash(val id: Int, val time: String, val title: String, val trace: List<TraceLine>) {
    val text: String get() = trace.joinToString("\n") { it.text }
}

/** Reads stack frames from JVM, Kotlin/Native and Swift traces. */
object StackTraces {
    // at com.example.shop.Cart.add(Cart.kt:42)
    private val JVM = Regex("""^\s*at\s+([\w$.]+)\.[^.(\s]+\(([^():]+):(\d+)\)""")
    // at 3   iosApp   0x0000000104f5a1b0   kfun:com.example.shop.Cart#add(){} + 120 (/Users/me/shop/shared/src/.../Cart.kt:42:13)
    private val NATIVE = Regex("""^\s*at\s+\d+\s+\S+\s+0x[0-9a-fA-F]+\s+.*\((/[^():]+):(\d+)(?::\d+)?\)\s*$""")
    // iosApp/ContentView.swift:12: Fatal error: Unexpectedly found nil
    private val SWIFT = Regex("""^\s*(\S+\.swift):(\d+): """)

    fun frame(line: String): StackFrame? {
        JVM.find(line)?.destructured?.let { (className, file, number) ->
            val pkg = className.substringBeforeLast('.', "").replace('.', '/')
            return StackFrame(file, number.toInt(), if (pkg.isEmpty()) file else "$pkg/$file", className = className)
        }
        NATIVE.find(line)?.destructured?.let { (path, number) ->
            return StackFrame(path.substringAfterLast('/'), number.toInt(), path.split('/').takeLast(2).joinToString("/"), path = path)
        }
        SWIFT.find(line)?.destructured?.let { (file, number) ->
            return StackFrame(file.substringAfterLast('/'), number.toInt(), file)
        }
        return null
    }

    fun traceLine(text: String) = TraceLine(text, frame(text))
}

/**
 * Finds crashes in a stream of log entries: Android's `FATAL EXCEPTION` blocks and native signals, Kotlin/Native's
 * `Uncaught Kotlin exception` (from the app's output), uncaught Objective-C exceptions and Swift fatal errors.
 *
 * [feed] returns the crash an entry starts or adds to (the same [Crash.id] as it grows), or null.
 */
class CrashDetector {
    private enum class Kind { ANDROID, NATIVE }

    private var open: Crash? = null
    private var kind: Kind? = null
    private var titled = false
    private var nextId = 1

    fun feed(entry: LogEntry): Crash? {
        val message = entry.message.trimEnd()
        start(entry, message)?.let { return it }
        val current = open ?: return null
        return when {
            continues(entry, message) -> {
                val line = message.trimStart().let { if (it.startsWith("at ")) "    $it" else it }
                val title = if (!titled && !line.startsWith("Process:")) { titled = true; line } else current.title
                current.copy(title = title, trace = current.trace + StackTraces.traceLine(line)).also { open = it }
            }
            // Other threads keep logging while Android prints the trace.
            kind == Kind.ANDROID && entry.tag != "AndroidRuntime" -> null
            else -> { open = null; kind = null; null }
        }
    }

    private fun start(entry: LogEntry, message: String): Crash? = when {
        entry.tag == "AndroidRuntime" && message.startsWith("FATAL EXCEPTION") -> start(entry, Kind.ANDROID, message, message)
        message.startsWith("Uncaught Kotlin exception:") -> start(entry, Kind.NATIVE, message.removePrefix("Uncaught Kotlin exception:").trim(), message)
        NS_EXCEPTION.containsMatchIn(message) -> single(entry, NS_EXCEPTION.find(message)!!.let { m -> "${m.groupValues[1]}: ${m.groupValues[2]}" }, message)
        SWIFT_FATAL.containsMatchIn(message) -> single(entry, "Fatal error: " + SWIFT_FATAL.find(message)!!.groupValues[1], message)
        NATIVE_SIGNAL.containsMatchIn(message) -> single(entry, NATIVE_SIGNAL.find(message)!!.value.substringBefore(','), message)
        else -> null
    }

    private fun continues(entry: LogEntry, message: String): Boolean = when (kind) {
        Kind.ANDROID -> entry.tag == "AndroidRuntime" && entry.level == LogLevel.ERROR
        Kind.NATIVE -> message.trimStart().let { it.startsWith("at ") || it.startsWith("Caused by:") || it.startsWith("...") }
        null -> false
    }

    private fun start(entry: LogEntry, kind: Kind, title: String, first: String): Crash {
        this.kind = kind
        titled = kind == Kind.NATIVE
        return Crash(nextId++, entry.time, title, listOf(TraceLine(first))).also { open = it }
    }

    private fun single(entry: LogEntry, title: String, message: String): Crash {
        open = null; kind = null
        return Crash(nextId++, entry.time, title, message.lines().map(StackTraces::traceLine))
    }

    private companion object {
        val NS_EXCEPTION = Regex("""Terminating app due to uncaught exception '([^']+)', reason: '(.*?)'""", RegexOption.DOT_MATCHES_ALL)
        val SWIFT_FATAL = Regex("""\.swift:\d+: Fatal error: (.*)""")
        val NATIVE_SIGNAL = Regex("""Fatal signal \d+ \(\w+\).*""")
    }
}
