package io.github.kmpdevicehub.core

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A named set of device conditions to test under. Null fields are left as they are; [language] "" means the
 * device language, otherwise an [AppLanguage.android] code.
 */
data class TestPreset(
    val name: String,
    val darkMode: Boolean? = null,
    val textSize: TextSize? = null,
    val language: String? = null,
    val network: NetworkProfile? = null,
    val cleanStatusBar: Boolean? = null,
    val builtIn: Boolean = false,
) {
    val summary: String
        get() = listOfNotNull(
            darkMode?.let { if (it) "dark" else "light" },
            textSize?.let { "${it.label.lowercase()} text" },
            language?.let { code -> if (code.isEmpty()) "device language" else AppLanguage.COMMON.firstOrNull { it.android == code }?.label ?: code },
            network?.label?.let { "network: $it" },
            cleanStatusBar?.let { if (it) "clean status bar" else "normal status bar" },
        ).joinToString(", ").ifEmpty { "no changes" }

    /** One line for saving: `name|dark|text|language|network|statusbar`, empty for "leave as is". */
    fun encode(): String = listOf(
        name.replace('|', '/').replace('\n', ' '),
        darkMode?.toString().orEmpty(),
        textSize?.name.orEmpty(),
        language?.let { if (it.isEmpty()) DEVICE else it }.orEmpty(),
        network?.name.orEmpty(),
        cleanStatusBar?.toString().orEmpty(),
    ).joinToString("|")

    companion object {
        private const val DEVICE = "device"

        fun decode(line: String): TestPreset? {
            val parts = line.split('|')
            if (parts.size != 6 || parts[0].isBlank()) return null
            return TestPreset(
                name = parts[0],
                darkMode = parts[1].toBooleanStrictOrNull(),
                textSize = TextSize.entries.firstOrNull { it.name == parts[2] },
                language = parts[3].takeIf { it.isNotEmpty() }?.let { if (it == DEVICE) "" else it },
                network = NetworkProfile.entries.firstOrNull { it.name == parts[4] },
                cleanStatusBar = parts[5].toBooleanStrictOrNull(),
            )
        }

        val BUILT_IN = listOf(
            TestPreset("Back to normal", darkMode = false, textSize = TextSize.DEFAULT, language = "", network = NetworkProfile.FULL, cleanStatusBar = false, builtIn = true),
            TestPreset("Dark mode + largest text", darkMode = true, textSize = TextSize.LARGEST, builtIn = true),
            TestPreset("Right-to-left (Arabic)", language = "ar", builtIn = true),
            TestPreset("Long words (German) + larger text", language = "de", textSize = TextSize.LARGER, builtIn = true),
            TestPreset("Slow network (EDGE)", network = NetworkProfile.EDGE, builtIn = true),
            TestPreset("Offline", network = NetworkProfile.OFFLINE, builtIn = true),
            TestPreset("Store screenshots", darkMode = false, textSize = TextSize.DEFAULT, cleanStatusBar = true, builtIn = true),
        )
    }
}

/** Applies presets, skipping (and saying so) what a device can't do instead of failing the whole preset. */
class PresetRunner(private val settings: DeviceSettings, private val simulation: DeviceSimulation) {
    fun apply(device: Device, appId: String, preset: TestPreset): ActionResult {
        if (!device.isRunning) return ActionResult.failed("Start ${device.name} first.")
        val failures = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        fun step(label: String, result: ActionResult) { if (!result.ok) failures += "$label: ${result.message}" }

        preset.darkMode?.let { step("dark mode", settings.setDarkMode(device, it)) }
        preset.textSize?.let { step("text size", settings.setTextSize(device, it)) }
        preset.language?.let { code ->
            when {
                appId.isBlank() -> skipped += "language (no app chosen)"
                device.platform == DevicePlatform.ANDROID && (device.apiLevel ?: 0) in 1..32 -> skipped += "language (needs Android 13+)"
                else -> step("language", settings.setAppLanguage(device, appId, AppLanguage.COMMON.firstOrNull { it.android == code }))
            }
        }
        preset.network?.let { profile ->
            when {
                device.platform == DevicePlatform.IOS -> skipped += "network (iOS uses the Mac's network)"
                device.kind != DeviceKind.EMULATOR && profile != NetworkProfile.OFFLINE && profile != NetworkProfile.FULL -> skipped += "network speed (emulators only)"
                else -> step("network", simulation.setNetwork(device, profile))
            }
        }
        preset.cleanStatusBar?.let { step("status bar", simulation.cleanStatusBar(device, it)) }

        val note = if (skipped.isEmpty()) "" else " Skipped ${skipped.joinToString(", ")}."
        return if (failures.isEmpty()) ActionResult.done("Applied \"${preset.name}\" on ${device.name}.$note")
        else ActionResult.failed("\"${preset.name}\" on ${device.name}: ${failures.joinToString("; ")}.$note")
    }
}

/** Runs one action on several devices at once and sums up the outcome. */
object Batch {
    fun run(devices: List<Device>, action: (Device) -> ActionResult): ActionResult {
        if (devices.isEmpty()) return ActionResult.failed("No running devices.")
        if (devices.size == 1) return action(devices.single())
        val pool = Executors.newFixedThreadPool(devices.size.coerceAtMost(8))
        try {
            val results = devices.map { d -> d to pool.submit<ActionResult> { runCatching { action(d) }.getOrElse { ActionResult.failed(it.message ?: it.toString()) } } }
                .map { (d, f) -> d to runCatching { f.get(5, TimeUnit.MINUTES) }.getOrElse { ActionResult.failed("timed out") } }
            return summarize(results)
        } finally {
            pool.shutdownNow()
        }
    }

    internal fun summarize(results: List<Pair<Device, ActionResult>>): ActionResult {
        val failed = results.filter { !it.second.ok }
        return if (failed.isEmpty()) ActionResult.done("Done on all ${results.size} devices.")
        else ActionResult.failed("${results.size - failed.size} of ${results.size} done. " +
            failed.joinToString(" ") { (d, r) -> "${d.name}: ${r.message.removeSuffix(".")}." })
    }
}

/** Deep links an app declares: Android intent filters and iOS URL schemes. */
object LinkDetector {
    fun detect(root: File, maxDepth: Int = 6): List<String> {
        val manifests = mutableListOf<String>()
        val plists = mutableListOf<String>()
        AppDetector.walk(root, maxDepth) { file ->
            when {
                file.name == "AndroidManifest.xml" -> manifests += file.readText()
                file.name == "Info.plist" -> plists += file.readText()
            }
        }
        return fromContents(manifests, plists)
    }

    fun fromContents(manifests: List<String>, plists: List<String>): List<String> {
        val links = mutableListOf<String>()
        for (manifest in manifests) {
            for (filter in Regex("""<intent-filter[\s\S]*?</intent-filter>""").findAll(manifest).map { it.value }) {
                if ("android.intent.action.VIEW" !in filter) continue
                // Usually each <data> is one link; attributes on scheme-less <data> elements apply to all of them.
                val data = Regex("""<data\b[^>]*>""").findAll(filter).map { it.value }.toList()
                fun attr(element: String, name: String) = Regex("""android:$name\s*=\s*"([^"]*)"""").find(element)?.groupValues?.get(1)
                val sharedHost = data.filter { attr(it, "scheme") == null }.firstNotNullOfOrNull { attr(it, "host") }
                for (element in data) {
                    val scheme = attr(element, "scheme") ?: continue
                    val host = attr(element, "host") ?: sharedHost.orEmpty()
                    if (scheme.startsWith("http") && host.isEmpty()) continue
                    links += "$scheme://$host" + (attr(element, "path") ?: attr(element, "pathPrefix")).orEmpty()
                }
            }
        }
        for (plist in plists) {
            Regex("""<key>CFBundleURLSchemes</key>\s*<array>([\s\S]*?)</array>""").findAll(plist).forEach { m ->
                Regex("""<string>([^<$]+)</string>""").findAll(m.groupValues[1]).forEach { links += "${it.groupValues[1].trim()}://" }
            }
        }
        return links.distinct()
    }
}
