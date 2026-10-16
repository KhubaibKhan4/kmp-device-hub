package io.github.kmpdevicehub.core

import java.io.File

/** What an action did, phrased for the user. */
data class ActionResult(val ok: Boolean, val message: String) {
    companion object {
        fun done(message: String) = ActionResult(true, message)
        fun failed(message: String) = ActionResult(false, message)
    }
}

/** File access for clearing simulator app data; faked in tests. */
interface FileOps {
    fun children(path: String): List<String>
    fun deleteRecursively(path: String): Boolean
}

object RealFileOps : FileOps {
    override fun children(path: String): List<String> = File(path).listFiles()?.map { it.path }.orEmpty()
    override fun deleteRecursively(path: String): Boolean = File(path).deleteRecursively()
}

/** Boot, shut down and app actions on Android devices/emulators and iOS simulators. */
class DeviceActions(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val tools: ToolPaths = ToolLocator.locate(),
    private val files: FileOps = RealFileOps,
) {
    // ---- Device lifecycle ---------------------------------------------------------------------------

    fun boot(device: Device): ActionResult = when (device.platform) {
        DevicePlatform.ANDROID -> {
            val avd = device.avdName
            val emulator = tools.emulator
            when {
                device.kind != DeviceKind.EMULATOR || avd == null -> ActionResult.failed("Only emulators can be started from here.")
                emulator == null -> ActionResult.failed("The Android emulator isn't installed (SDK Manager → Android Emulator).")
                else -> {
                    runner.spawn(listOf(emulator, "-avd", avd))
                    ActionResult.done("Starting ${device.name}…")
                }
            }
        }
        DevicePlatform.IOS -> withXcrun { xcrun ->
            val result = runner.run(listOf(xcrun, "simctl", "boot", device.id), timeoutSeconds = 120)
            if (!result.ok && "current state: Booted" !in result.stderr) return@withXcrun ActionResult.failed("Couldn't start ${device.name}: ${result.errorSummary}")
            // Show the simulator window too; booting alone runs it headless.
            runner.spawn(listOf("open", "-a", "Simulator", "--args", "-CurrentDeviceUDID", device.id))
            ActionResult.done("Started ${device.name}.")
        }
    }

    fun shutdown(device: Device): ActionResult = when (device.platform) {
        DevicePlatform.ANDROID -> when (device.kind) {
            DeviceKind.EMULATOR -> withAdb { adb ->
                val result = runner.run(listOf(adb, "-s", device.id, "emu", "kill"))
                if (result.ok) ActionResult.done("Shutting down ${device.name}…") else ActionResult.failed("Couldn't stop ${device.name}: ${result.errorSummary}")
            }
            else -> ActionResult.failed("Physical devices can't be shut down from here.")
        }
        DevicePlatform.IOS -> withXcrun { xcrun ->
            val result = runner.run(listOf(xcrun, "simctl", "shutdown", device.id), timeoutSeconds = 60)
            if (result.ok || "current state: Shutdown" in result.stderr) ActionResult.done("Shut down ${device.name}.")
            else ActionResult.failed("Couldn't shut down ${device.name}: ${result.errorSummary}")
        }
    }

    // ---- App actions ----------------------------------------------------------------------------------

    fun isInstalled(device: Device, appId: String): Boolean {
        if (!device.isRunning) return false
        return when (device.platform) {
            DevicePlatform.ANDROID -> tools.adb?.let { adb ->
                runner.run(listOf(adb, "-s", device.id, "shell", "pm", "path", appId)).stdout.contains("package:")
            } ?: false
            DevicePlatform.IOS -> tools.xcrun?.let { xcrun ->
                runner.run(listOf(xcrun, "simctl", "get_app_container", device.id, appId)).ok
            } ?: false
        }
    }

    fun launch(device: Device, appId: String): ActionResult = app(device, appId) {
        when (device.platform) {
            DevicePlatform.ANDROID -> withAdb { adb ->
                // monkey starts the launcher activity without us knowing its class name.
                val result = runner.run(listOf(adb, "-s", device.id, "shell", "monkey", "-p", appId, "-c", "android.intent.category.LAUNCHER", "1"))
                when {
                    "No activities found" in result.stdout || "No activities found" in result.stderr ->
                        ActionResult.failed("$appId isn't installed on ${device.name}, or has no launcher activity.")
                    result.ok -> ActionResult.done("Launched $appId on ${device.name}.")
                    else -> ActionResult.failed("Couldn't launch $appId: ${result.errorSummary}")
                }
            }
            DevicePlatform.IOS -> withXcrun { xcrun ->
                val result = runner.run(listOf(xcrun, "simctl", "launch", device.id, appId), timeoutSeconds = 60)
                when {
                    result.ok -> ActionResult.done("Launched $appId on ${device.name}.")
                    // simctl reports a missing app as an opaque FBSOpenApplicationServiceErrorDomain error.
                    !runner.run(listOf(xcrun, "simctl", "get_app_container", device.id, appId)).ok ->
                        ActionResult.failed("$appId isn't installed on ${device.name}.")
                    else -> ActionResult.failed("Couldn't launch $appId on ${device.name}: ${result.errorSummary}")
                }
            }
        }
    }

    fun stop(device: Device, appId: String): ActionResult = app(device, appId) {
        when (device.platform) {
            DevicePlatform.ANDROID -> withAdb { adb ->
                val result = runner.run(listOf(adb, "-s", device.id, "shell", "am", "force-stop", appId))
                if (result.ok) ActionResult.done("Stopped $appId on ${device.name}.") else ActionResult.failed("Couldn't stop $appId: ${result.errorSummary}")
            }
            DevicePlatform.IOS -> withXcrun { xcrun ->
                val result = runner.run(listOf(xcrun, "simctl", "terminate", device.id, appId))
                if (result.ok || "found nothing to terminate" in result.stderr) ActionResult.done("Stopped $appId on ${device.name}.")
                else ActionResult.failed("Couldn't stop $appId: ${result.errorSummary}")
            }
        }
    }

    fun clearData(device: Device, appId: String): ActionResult = app(device, appId) {
        when (device.platform) {
            DevicePlatform.ANDROID -> withAdb { adb ->
                val result = runner.run(listOf(adb, "-s", device.id, "shell", "pm", "clear", appId))
                if ("Success" in result.stdout) ActionResult.done("Cleared $appId data on ${device.name}.")
                else ActionResult.failed("Couldn't clear $appId data: ${result.errorSummary}")
            }
            DevicePlatform.IOS -> withXcrun { xcrun -> clearSimulatorData(xcrun, device, appId) }
        }
    }

    fun uninstall(device: Device, appId: String): ActionResult = app(device, appId) {
        when (device.platform) {
            DevicePlatform.ANDROID -> withAdb { adb ->
                val result = runner.run(listOf(adb, "-s", device.id, "uninstall", appId), timeoutSeconds = 60)
                if ("Success" in result.stdout) ActionResult.done("Uninstalled $appId from ${device.name}.")
                else ActionResult.failed("Couldn't uninstall $appId: ${result.errorSummary}")
            }
            DevicePlatform.IOS -> withXcrun { xcrun ->
                val result = runner.run(listOf(xcrun, "simctl", "uninstall", device.id, appId), timeoutSeconds = 60)
                if (result.ok) ActionResult.done("Uninstalled $appId from ${device.name}.")
                else ActionResult.failed("Couldn't uninstall $appId: ${result.errorSummary}")
            }
        }
    }

    /**
     * Simulators have no "clear data" command: stop the app, then empty Documents, Library and tmp in its data
     * container. Only ever touches a path that is this app's data container inside this simulator.
     */
    private fun clearSimulatorData(xcrun: String, device: Device, appId: String): ActionResult {
        val container = runner.run(listOf(xcrun, "simctl", "get_app_container", device.id, appId, "data"))
        if (!container.ok) return ActionResult.failed("$appId isn't installed on ${device.name}.")
        val path = container.stdout.trim().trimEnd('/')
        if (!isSimulatorDataContainer(path, device.id)) {
            return ActionResult.failed("Refusing to clear an unexpected folder: $path")
        }
        runner.run(listOf(xcrun, "simctl", "terminate", device.id, appId))
        var failures = 0
        for (dir in listOf("Documents", "Library", "tmp")) {
            for (child in files.children("$path/$dir")) {
                if (!files.deleteRecursively(child)) failures++
            }
        }
        return if (failures == 0) ActionResult.done("Cleared $appId data on ${device.name}.")
        else ActionResult.failed("Cleared most of $appId's data, but $failures item(s) couldn't be deleted.")
    }

    private inline fun app(device: Device, appId: String, block: () -> ActionResult): ActionResult = when {
        appId.isBlank() -> ActionResult.failed("Choose an app first.")
        !APP_ID.matches(appId) -> ActionResult.failed("\"$appId\" doesn't look like an app id (e.g. com.example.app).")
        !device.isRunning -> ActionResult.failed("Start ${device.name} first.")
        else -> block()
    }

    private inline fun withAdb(block: (String) -> ActionResult): ActionResult =
        tools.adb?.let(block) ?: ActionResult.failed("adb not found. Install the Android SDK platform tools.")

    private inline fun withXcrun(block: (String) -> ActionResult): ActionResult =
        tools.xcrun?.let(block) ?: ActionResult.failed("Xcode not found. iOS simulators need Xcode.")

    companion object {
        /** Android application ids and iOS bundle ids: dot-separated letters, digits, `_` and `-`. */
        private val APP_ID = Regex("""[A-Za-z0-9_\-]+(\.[A-Za-z0-9_\-]+)+""")

        internal fun isSimulatorDataContainer(path: String, udid: String): Boolean =
            Regex("""/CoreSimulator/Devices/${Regex.escape(udid)}/data/Containers/Data/Application/[0-9A-Fa-f\-]{36}$""").containsMatchIn(path) &&
                ".." !in path
    }
}
