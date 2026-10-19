package io.github.kmpdevicehub.idea

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.LicensingFacade
import java.awt.Component

/**
 * KMP Device Hub Pro, sold through JetBrains Marketplace (which also runs the free trial).
 *
 * The product code lives only in the built plugin.xml (set with `-Pdevicehub.productCode` once Marketplace
 * approves the paid plan). A build without one is the free early-access build: every Pro feature is on.
 */
object ProLicense {
    const val PLUGIN_ID = "io.github.khubaibkhan4.kmpdevicehub"
    private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/plugin/35272-kmp-device-hub"

    val features = listOf(
        "Actions on all running devices at once",
        "Test presets (dark + large text, RTL, slow network…) and one-click store screenshots in every language",
        "Deep link library",
        "Screen recording",
        "Push notification tester",
        "Simulated location, network, battery, rotation, biometrics, media and a clean status bar",
        "Network Inspector: mock responses, fail requests, add latency, export HAR",
    )

    /** The Marketplace product code, or null in the free early-access build. */
    val productCode: String? by lazy {
        // Read from our own built plugin.xml: the platform's plugin-descriptor lookups are internal API.
        val xml = runCatching {
            val jar = java.io.File(ProLicense::class.java.protectionDomain.codeSource.location.toURI())
            if (!jar.isFile) return@runCatching ""
            java.util.jar.JarFile(jar).use { file ->
                file.getJarEntry("META-INF/plugin.xml")?.let { file.getInputStream(it).bufferedReader().readText() }.orEmpty()
            }
        }.getOrDefault("")
        Regex("""<product-descriptor\b[^>]*\bcode="([^"]+)"""").find(xml)?.groupValues?.get(1)
    }

    fun isLicensed(): Boolean {
        val code = productCode ?: return true
        if (devOverride()) return true
        val stamp = LicensingFacade.getInstance()?.getConfirmationStamp(code) ?: return false
        // key: a license key, stamp: a JetBrains Account license, eval: the Marketplace trial.
        return stamp.startsWith("key:") || stamp.startsWith("stamp:") || stamp.startsWith("eval:")
    }

    /** `-Dkmpdevicehub.pro=true` unlocks Pro in tests and the sandbox IDE only. */
    private fun devOverride(): Boolean {
        val app = ApplicationManager.getApplication() ?: return false
        return (app.isUnitTestMode || app.isInternal) && System.getProperty("kmpdevicehub.pro") == "true"
    }

    /** Explains Pro and offers the trial; returns after the dialog closes. */
    fun showUpsell(project: Project, feature: String, component: Component?) {
        val message = buildString {
            append("$feature is part of KMP Device Hub Pro.\n\nPro adds:\n")
            features.forEach { append("  • ").append(it).append('\n') }
            append("\nTry it free: JetBrains Marketplace runs the trial, and you keep everything else in the free version.")
        }
        val choice = Messages.showDialog(project, message, "KMP Device Hub Pro", arrayOf("Start Free Trial", "Not Now"), 0, Messages.getInformationIcon())
        if (choice != 0) return
        // The IDE's own license dialog lists paid plugins with a trial button; fall back to the Marketplace page.
        val register = ActionManager.getInstance().getAction("Register")
        if (register == null || component == null) BrowserUtil.browse(MARKETPLACE_URL)
        else ActionManager.getInstance().tryToExecute(register, null, component, "KmpDeviceHub", true)
    }
}
