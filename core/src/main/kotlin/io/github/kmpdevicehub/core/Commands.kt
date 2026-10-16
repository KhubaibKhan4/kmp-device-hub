package io.github.kmpdevicehub.core

import java.io.File
import java.util.concurrent.TimeUnit

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** The most useful single line to show a user when the command failed. */
    val errorSummary: String
        get() = when {
            timedOut -> "timed out"
            else -> (stderr.lineSequence() + stdout.lineSequence()).map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: "exit code $exitCode"
        }
}

/** Runs external tools (adb, emulator, xcrun). Tests swap in a fake. */
interface CommandRunner {
    fun run(command: List<String>, timeoutSeconds: Long = 20): CommandResult

    /** Starts a long-running process (e.g. an emulator) without waiting for it. */
    fun spawn(command: List<String>)
}

class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, timeoutSeconds: Long): CommandResult {
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: java.io.IOException) {
            return CommandResult(-1, "", e.message ?: "could not start ${command.first()}")
        }
        process.outputStream.close()
        // Read both streams concurrently so a chatty tool can't block on a full pipe.
        var stderr = ""
        val errReader = Thread { stderr = process.errorStream.bufferedReader().readText() }.apply { isDaemon = true; start() }
        var stdout = ""
        val outReader = Thread { stdout = process.inputStream.bufferedReader().readText() }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outReader.join(500); errReader.join(500)
            return CommandResult(-1, stdout, stderr, timedOut = true)
        }
        outReader.join(2_000); errReader.join(2_000)
        return CommandResult(process.exitValue(), stdout, stderr)
    }

    override fun spawn(command: List<String>) {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}

/** Where the platform tools live, or null when a tool isn't installed. */
data class ToolPaths(val adb: String?, val emulator: String?, val xcrun: String?)

/** Finds adb, emulator and xcrun the way Android Studio and Xcode set them up. */
object ToolLocator {
    fun locate(
        sdkDir: String? = null,
        env: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name"),
        home: String = System.getProperty("user.home"),
        exists: (String) -> Boolean = { File(it).isFile },
    ): ToolPaths {
        val windows = osName.startsWith("Windows")
        val exe = if (windows) ".exe" else ""
        val sdkCandidates = listOfNotNull(
            sdkDir,
            env["ANDROID_HOME"],
            env["ANDROID_SDK_ROOT"],
            when {
                osName.startsWith("Mac") -> "$home/Library/Android/sdk"
                windows -> env["LOCALAPPDATA"]?.let { "$it\\Android\\Sdk" }
                else -> "$home/Android/Sdk"
            },
        ).filter { it.isNotBlank() }
        fun inSdk(relative: String) = sdkCandidates.map { "$it/$relative$exe".replace('\\', '/') }.firstOrNull(exists)
        fun onPath(name: String) = env["PATH"].orEmpty().split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }.map { "$it/$name$exe" }.firstOrNull(exists)
        val xcrun = if (osName.startsWith("Mac")) "/usr/bin/xcrun".takeIf(exists) else null
        return ToolPaths(
            adb = inSdk("platform-tools/adb") ?: onPath("adb"),
            emulator = inSdk("emulator/emulator") ?: onPath("emulator"),
            xcrun = xcrun,
        )
    }
}
