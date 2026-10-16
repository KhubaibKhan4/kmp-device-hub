package io.github.kmpdevicehub.core

/** Android devices and emulators through adb, plus stopped AVDs from the emulator tool. */
class AndroidBackend(
    private val runner: CommandRunner,
    private val adb: String,
    private val emulator: String?,
    /** Reads a text file, or null when it doesn't exist; used for AVD configs. */
    private val readFile: (String) -> String? = { path -> java.io.File(path).takeIf { it.isFile }?.readText() },
    private val avdHome: String = defaultAvdHome(),
) {
    /** Devices, or a problem message when adb itself fails. */
    fun list(): Pair<List<Device>, String?> {
        val result = runner.run(listOf(adb, "devices", "-l"))
        if (!result.ok) return emptyList<Device>() to "adb: ${result.errorSummary}"
        val connected = parseDevices(result.stdout).map { entry -> if (entry.state == DeviceState.RUNNING) describe(entry) else entry.toDevice() }
        val runningAvds = connected.mapNotNull { it.avdName }.toSet()
        val stopped = listAvds().filter { it !in runningAvds }.map { avd ->
            Device(
                id = "avd:$avd", name = prettyAvdName(avd), platform = DevicePlatform.ANDROID, kind = DeviceKind.EMULATOR,
                state = DeviceState.STOPPED, apiLevel = avdApiLevel(avd), avdName = avd,
            )
        }
        return (connected + stopped) to null
    }

    private fun listAvds(): List<String> {
        val emulator = emulator ?: return emptyList()
        val result = runner.run(listOf(emulator, "-list-avds"))
        if (!result.ok) return emptyList()
        // Newer emulator builds print INFO/WARNING lines before the names.
        return result.stdout.lines().map { it.trim() }.filter { AVD_NAME.matches(it) }
    }

    private fun avdApiLevel(avd: String): Int? {
        val ini = readFile("$avdHome/$avd.ini") ?: return null
        return Regex("""(?m)^target=android-(\d+)""").find(ini)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Reads model, OS and AVD name in one `getprop` call. */
    private fun describe(entry: AdbEntry): Device {
        val props = parseProps(runner.run(listOf(adb, "-s", entry.serial, "shell", "getprop"), timeoutSeconds = 10).stdout)
        val avd = props["ro.boot.qemu.avd_name"] ?: props["ro.kernel.qemu.avd_name"]
        val emulator = entry.serial.startsWith("emulator-") || avd != null
        val name = when {
            avd != null -> prettyAvdName(avd)
            else -> {
                val model = props["ro.product.model"] ?: entry.model?.replace('_', ' ') ?: entry.serial
                val maker = props["ro.product.manufacturer"]?.replaceFirstChar { it.uppercase() }
                if (maker == null || model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
            }
        }
        // No properties at all: the device stopped answering, typically while an emulator shuts down.
        val state = when {
            props.isEmpty() -> DeviceState.OFFLINE
            props["sys.boot_completed"] == "1" -> DeviceState.RUNNING
            else -> DeviceState.BOOTING
        }
        return Device(
            id = entry.serial, name = name, platform = DevicePlatform.ANDROID,
            kind = if (emulator) DeviceKind.EMULATOR else DeviceKind.PHYSICAL,
            state = state,
            osVersion = props["ro.build.version.release"], apiLevel = props["ro.build.version.sdk"]?.toIntOrNull(), avdName = avd,
        )
    }

    internal data class AdbEntry(val serial: String, val state: DeviceState, val model: String?) {
        fun toDevice() = Device(
            id = serial, name = model?.replace('_', ' ') ?: serial, platform = DevicePlatform.ANDROID,
            kind = if (serial.startsWith("emulator-")) DeviceKind.EMULATOR else DeviceKind.PHYSICAL, state = state,
        )
    }

    companion object {
        private val AVD_NAME = Regex("""[A-Za-z0-9._\-]+""")

        fun defaultAvdHome(env: Map<String, String> = System.getenv()): String =
            env["ANDROID_AVD_HOME"] ?: (System.getProperty("user.home") + "/.android/avd")

        /** `Medium_Phone_API_37.0` -> `Medium Phone API 37.0` */
        fun prettyAvdName(avd: String) = avd.replace('_', ' ')

        /** Parses `adb devices -l`. */
        internal fun parseDevices(output: String): List<AdbEntry> = output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val state = when (parts[1]) {
                    "device" -> DeviceState.RUNNING
                    "unauthorized" -> DeviceState.UNAUTHORIZED
                    "offline" -> DeviceState.OFFLINE
                    "authorizing", "connecting" -> DeviceState.BOOTING
                    else -> DeviceState.OFFLINE
                }
                val model = parts.drop(2).firstOrNull { it.startsWith("model:") }?.removePrefix("model:")
                AdbEntry(parts[0], state, model)
            }

        /** Parses `getprop` lines like `[ro.product.model]: [Pixel 9]`. */
        internal fun parseProps(output: String): Map<String, String> =
            Regex("""^\[([^\]]+)]: \[(.*)]$""", RegexOption.MULTILINE).findAll(output)
                .associate { it.groupValues[1] to it.groupValues[2] }
    }
}

/** iOS simulators through `xcrun simctl`. */
class SimulatorBackend(private val runner: CommandRunner, private val xcrun: String) {
    fun list(): Pair<List<Device>, String?> {
        val result = runner.run(listOf(xcrun, "simctl", "list", "devices", "-j"), timeoutSeconds = 30)
        if (!result.ok) return emptyList<Device>() to "iOS simulators: ${result.errorSummary}"
        return parse(result.stdout) to null
    }

    companion object {
        private val IOS_RUNTIME = Regex("""SimRuntime\.iOS-(\d+)-(\d+)(?:-(\d+))?$""")

        internal fun parse(json: String): List<Device> {
            val root = runCatching { Json.parse(json) }.getOrNull() as? Map<*, *> ?: return emptyList()
            val byRuntime = root["devices"] as? Map<*, *> ?: return emptyList()
            return byRuntime.flatMap { (runtime, devices) ->
                val match = IOS_RUNTIME.find(runtime.toString()) ?: return@flatMap emptyList()
                val version = match.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString(".")
                (devices as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
                    .filter { it["isAvailable"] != false }
                    .mapNotNull { d ->
                        val udid = d["udid"] as? String ?: return@mapNotNull null
                        Device(
                            id = udid, name = d["name"] as? String ?: udid, platform = DevicePlatform.IOS,
                            kind = DeviceKind.SIMULATOR, osVersion = version,
                            state = when (d["state"]) {
                                "Booted" -> DeviceState.RUNNING
                                "Shutdown" -> DeviceState.STOPPED
                                "Booting", "Shutting Down", "Creating" -> DeviceState.BOOTING
                                else -> DeviceState.OFFLINE
                            },
                        )
                    }
            }
        }
    }
}

/** Lists every Android device, emulator and iOS simulator, and explains what couldn't be listed. */
class DeviceService(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val tools: ToolPaths = ToolLocator.locate(),
    private val isMac: Boolean = System.getProperty("os.name").startsWith("Mac"),
    private val readFile: (String) -> String? = { path -> java.io.File(path).takeIf { it.isFile }?.readText() },
    private val avdHome: String = AndroidBackend.defaultAvdHome(),
) {
    fun list(): DeviceList {
        val devices = mutableListOf<Device>()
        val problems = mutableListOf<String>()
        if (tools.adb == null) {
            problems += "Android SDK not found. Set ANDROID_HOME, or open a project with sdk.dir in local.properties."
        } else {
            val (android, problem) = AndroidBackend(runner, tools.adb, tools.emulator, readFile, avdHome).list()
            devices += android
            problem?.let(problems::add)
        }
        if (isMac) {
            if (tools.xcrun == null) {
                problems += "Xcode not found. iOS simulators need Xcode from the App Store."
            } else {
                val (ios, problem) = SimulatorBackend(runner, tools.xcrun).list()
                devices += ios
                problem?.let(problems::add)
            }
        }
        return DeviceList(devices.sortedWith(ORDER), problems)
    }

    companion object {
        /** Running first, then Android before iOS, then by name. */
        val ORDER: Comparator<Device> = compareBy<Device>({ !it.isRunning }, { it.platform.ordinal }, { it.name.lowercase() })
    }
}
