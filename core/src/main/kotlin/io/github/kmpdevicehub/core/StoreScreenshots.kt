package io.github.kmpdevicehub.core

import java.io.File

/** What to capture: the app in each of [languages] (null = the device language), in light mode and optionally dark. */
data class StoreShotPlan(val languages: List<AppLanguage?>, val darkToo: Boolean = false) {
    val shotsPerDevice: Int get() = languages.size * (if (darkToo) 2 else 1)
}

/**
 * App Store / Play Store screenshots in one go: a clean 9:41 status bar and default text, then the app relaunched in each
 * language (and appearance) and captured to `<out>/<device>/<language>-<light|dark>.png`. The device is put back as it
 * was found (device language, light mode, normal status bar) afterwards.
 */
class StoreScreenshots(
    private val settings: DeviceSettings,
    private val simulation: DeviceSimulation,
    private val actions: DeviceActions,
    /** How long the relaunched app gets to draw its first screen. */
    private val settleMillis: Long = 3_500,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun capture(device: Device, appId: String, plan: StoreShotPlan, outDir: File, onProgress: (String) -> Unit = {}): ActionResult {
        if (!device.isRunning) return ActionResult.failed("Start ${device.name} first.")
        if (appId.isBlank()) return ActionResult.failed("Choose your app first.")
        if (plan.languages.isEmpty()) return ActionResult.failed("Pick at least one language.")
        val oldAndroid = device.platform == DevicePlatform.ANDROID && (device.apiLevel ?: 0) in 1..32
        val languages = if (oldAndroid) plan.languages.filter { it == null }.ifEmpty { listOf(null) } else plan.languages
        val folder = File(outDir, device.name.replace(Regex("[^A-Za-z0-9 ._()-]"), "").trim().ifEmpty { device.id })
        folder.mkdirs()

        simulation.cleanStatusBar(device, true)
        settings.setTextSize(device, TextSize.DEFAULT)
        val failures = mutableListOf<String>()
        var saved = 0
        var first = true
        var blockedBy: String? = null
        try {
            for (dark in listOfNotNull(false, true.takeIf { plan.darkToo })) {
                settings.setDarkMode(device, dark)
                for (language in languages) {
                    val code = language?.android ?: "device"
                    val name = "$code-${if (dark) "dark" else "light"}"
                    onProgress("${device.name}: $name")
                    // Android 12 and older have no per-app language: just restart the app in the device language.
                    val relaunch = if (oldAndroid) actions.stop(device, appId).let { actions.launch(device, appId) }
                        else settings.setAppLanguage(device, appId, language)
                    if (!relaunch.ok) { failures += "$name: ${relaunch.message}"; continue }
                    // The first launch also loads caches and icons; give it longer.
                    sleep(if (first) settleMillis * 2 else settleMillis)
                    first = false
                    // A system "isn't responding" dialog would ruin the shot; note it so the user can fix the device.
                    settings.notRespondingDialog(device)?.let { blockedBy = it }
                    val shot = settings.screenshot(device, File(folder, "$name.png"))
                    if (shot.ok) saved++ else failures += "$name: ${shot.message}"
                }
            }
        } finally {
            // Leave the device as a developer expects it.
            if (!oldAndroid) settings.setAppLanguage(device, appId, null)
            settings.setDarkMode(device, false)
            simulation.cleanStatusBar(device, false)
        }
        val dialog = blockedBy?.let { " Warning: a \"$it isn't responding\" dialog was on screen; restart ${device.name} (or close the dialog) and capture again." }.orEmpty()
        val skipped = if (oldAndroid && plan.languages.any { it != null }) " Languages skipped: per-app language needs Android 13+." else ""
        return when {
            saved == 0 -> ActionResult.failed("No screenshots from ${device.name}: ${failures.firstOrNull() ?: "unknown error"}")
            failures.isNotEmpty() -> ActionResult.failed("$saved screenshot(s) from ${device.name} in ${folder.path}; ${failures.size} failed (${failures.first()}).$skipped$dialog")
            dialog.isNotEmpty() -> ActionResult.failed("$saved screenshot(s) from ${device.name} in ${folder.path}.$skipped$dialog")
            else -> ActionResult.done("$saved screenshot(s) from ${device.name} in ${folder.path}.$skipped")
        }
    }
}
