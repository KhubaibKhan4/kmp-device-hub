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

    enum class Status { FREE_BUILD, LICENSED, NOT_LICENSED, LICENSING_UNAVAILABLE }

    private val verifier = LicenseVerifier()
    @Volatile private var cached: Pair<Status, Long>? = null

    /**
     * Whether Pro is unlocked. The IDE checks the license itself at startup and daily; this verifies the stamp it hands
     * us really comes from JetBrains, and caches the answer (JetBrains asks for only a few checks a day).
     */
    fun isLicensed(): Boolean = status().let { it == Status.FREE_BUILD || it == Status.LICENSED } || devOverride()

    fun status(): Status {
        val code = productCode ?: return Status.FREE_BUILD
        cached?.let { (status, at) -> if (System.currentTimeMillis() - at < CACHE_MS) return status }
        // No facade: the IDE's licensing isn't loaded yet, or (Android Studio, community IDEs) the
        // "JetBrains Marketplace Licensing Support" plugin isn't installed. Don't cache, so it's picked up once it is.
        val facade = LicensingFacade.getInstance() ?: return Status.LICENSING_UNAVAILABLE
        val status = if (verifier.isValid(facade.getConfirmationStamp(code))) Status.LICENSED else Status.NOT_LICENSED
        cached = status to System.currentTimeMillis()
        return status
    }

    /** `-Dkmpdevicehub.pro=true` unlocks Pro in the plugin's own tests only. */
    private fun devOverride(): Boolean {
        val app = ApplicationManager.getApplication() ?: return false
        return app.isUnitTestMode && System.getProperty("kmpdevicehub.pro") == "true"
    }

    /** Explains Pro and offers the trial (or how to enable licensing); returns after the dialog closes. */
    fun showUpsell(project: Project, feature: String, component: Component?) {
        if (status() == Status.LICENSING_UNAVAILABLE) {
            val choice = Messages.showDialog(project,
                "$feature is part of KMP Device Hub Pro.\n\nThis IDE can't check JetBrains Marketplace licenses yet. In Android Studio and other " +
                    "community IDEs, install the free \"JetBrains Marketplace Licensing Support\" plugin (Settings → Plugins → Marketplace), " +
                    "restart, and then start your free trial.",
                "KMP Device Hub Pro", arrayOf("Open Plugins", "Not Now"), 0, Messages.getInformationIcon())
            if (choice == 0) com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(project, "preferences.pluginManager", "JetBrains Marketplace Licensing")
            return
        }
        val message = buildString {
            append("$feature is part of KMP Device Hub Pro.\n\nPro adds:\n")
            features.forEach { append("  • ").append(it).append('\n') }
            append("\nTry it free: JetBrains Marketplace runs the trial, and you keep everything else in the free version.")
        }
        val choice = Messages.showDialog(project, message, "KMP Device Hub Pro", arrayOf("Start Free Trial", "Not Now"), 0, Messages.getInformationIcon())
        if (choice == 0) requestLicense(message)
    }

    /** Opens the IDE's license dialog with KMP Device Hub preselected, as JetBrains' reference code does. */
    private fun requestLicense(message: String) {
        val code = productCode
        val actions = ActionManager.getInstance()
        val register = actions.getAction("RegisterPlugins") ?: actions.getAction("Register")
        if (code == null || register == null) { BrowserUtil.browse(MARKETPLACE_URL); return }
        val context = com.intellij.openapi.actionSystem.DataContext { id ->
            when (id) {
                "register.product-descriptor.code" -> code
                "register.message" -> message
                else -> null
            }
        }
        performAction(register,
            com.intellij.openapi.actionSystem.AnActionEvent.createEvent(context, com.intellij.openapi.actionSystem.Presentation(), "",
                com.intellij.openapi.actionSystem.ActionUiKind.NONE, null))
        cached = null
    }

    /**
     * `ActionUtil.performAction` only exists from 2025.2 (a NoSuchMethodError in 2025.1, which this plugin supports);
     * 2025.1 has `performActionDumbAwareWithCallbacks`, which newer IDEs deprecate. Looked up at runtime.
     */
    private fun performAction(action: com.intellij.openapi.actionSystem.AnAction, event: com.intellij.openapi.actionSystem.AnActionEvent) {
        val util = com.intellij.openapi.actionSystem.ex.ActionUtil::class.java
        val types = arrayOf(com.intellij.openapi.actionSystem.AnAction::class.java, com.intellij.openapi.actionSystem.AnActionEvent::class.java)
        val method = runCatching { util.getMethod("performAction", *types) }.getOrNull()
            ?: util.getMethod("performActionDumbAwareWithCallbacks", *types)
        method.invoke(null, action, event)
    }

    private const val CACHE_MS = 6 * 60 * 60 * 1000L
}
