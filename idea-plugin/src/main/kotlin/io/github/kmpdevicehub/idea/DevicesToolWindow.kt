package io.github.kmpdevicehub.idea

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.kmpdevicehub.core.ActionResult
import io.github.kmpdevicehub.core.AppLanguage
import io.github.kmpdevicehub.core.AppPermission
import io.github.kmpdevicehub.core.DeviceSettings
import io.github.kmpdevicehub.core.PermissionState
import io.github.kmpdevicehub.core.TextSize
import io.github.kmpdevicehub.core.AppDetector
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DeviceActions
import io.github.kmpdevicehub.core.DeviceKind
import io.github.kmpdevicehub.core.DeviceList
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceService
import io.github.kmpdevicehub.core.DeviceState
import io.github.kmpdevicehub.core.ProjectApps
import io.github.kmpdevicehub.core.ToolLocator
import io.github.kmpdevicehub.core.ToolPaths
import io.github.kmpdevicehub.core.Batch
import io.github.kmpdevicehub.core.DeviceSimulation
import io.github.kmpdevicehub.core.LinkDetector
import io.github.kmpdevicehub.core.PresetRunner
import io.github.kmpdevicehub.core.ScreenRecording
import io.github.kmpdevicehub.core.TestPreset
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class DevicesToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DevicesPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }
}

/** Tool locations for this project: its `sdk.dir` (like Gradle uses) when set. */
internal fun toolsFor(project: Project): ToolPaths {
    val sdkDir = project.basePath?.let { File(it, "local.properties") }?.takeIf { it.isFile }?.let { file ->
        Properties().apply { file.reader().use { load(it) } }.getProperty("sdk.dir")
    }
    return ToolLocator.locate(sdkDir = sdkDir)
}

/**
 * Every Android device, emulator and iOS simulator in one list, refreshed while visible, with device
 * (start/stop) and app (launch, stop, clear data, uninstall) actions for the selected device.
 */
class DevicesPanel(
    private val project: Project,
    parent: Disposable,
    private val load: () -> DeviceList = { DeviceService(tools = toolsFor(project)).list() },
    private val actions: DeviceActions = DeviceActions(tools = toolsFor(project)),
    private val settings: DeviceSettings = DeviceSettings(tools = toolsFor(project)),
    private val simulation: DeviceSimulation = DeviceSimulation(tools = toolsFor(project)),
    private val screenshotDir: File = File(System.getProperty("user.home"), "Pictures/KMP Device Hub"),
    private val recordingDir: File = File(System.getProperty("user.home"), "Movies/KMP Device Hub"),
    private val detectApps: () -> ProjectApps = { project.basePath?.let { AppDetector.detect(File(it)) } ?: ProjectApps(emptyList(), emptyList()) },
    private val detectLinks: () -> List<String> = { project.basePath?.let { LinkDetector.detect(File(it)) }.orEmpty() },
    private val isPro: () -> Boolean = ProLicense::isLicensed,
    private val autoRefresh: Boolean = true,
    /** Asks before destructive actions; tests answer automatically. */
    private val confirm: (title: String, message: String) -> Boolean = { title, message ->
        Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) == Messages.YES
    },
    /** Shown when a Pro feature is used without Pro. */
    private val upsell: ((feature: String) -> Unit)? = null,
) : JPanel(BorderLayout()), PanelContext {
    private val model = CollectionListModel<Row>()
    private val list = JBList(model).apply {
        cellRenderer = DeviceRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "Looking for devices…"
    }
    private val notices = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isVisible = false
    }
    private val status = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val result = JBLabel().apply { font = JBFont.small() }
    private val appBox = ComboBox<String>().apply { isEditable = true }
    private val installed = JBLabel().apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val launchButton = JButton("Launch", AllIcons.Actions.Execute)
    private val stopAppButton = JButton("Stop", AllIcons.Actions.Suspend)
    private val clearButton = JButton("Clear data", AllIcons.Actions.GC)
    private val uninstallButton = JButton("Uninstall", AllIcons.Actions.Cancel)

    // Settings tab
    private val darkBox = JBCheckBox("Dark mode")
    private val textSizeBox = ComboBox<TextSize>(DefaultComboBoxModel<TextSize>(TextSize.entries.toTypedArray())).apply {
        renderer = labelRenderer<TextSize> { (it as? TextSize)?.label }
    }
    private val languageBox = ComboBox<Any>(DefaultComboBoxModel<Any>((listOf<Any>(DEVICE_LANGUAGE) + AppLanguage.COMMON).toTypedArray())).apply {
        renderer = labelRenderer<Any> { (it as? AppLanguage)?.label ?: it?.toString() }
    }
    private val applyLanguageButton = JButton("Run app in this language")
    private val settingsHint = HintLabel()

    // Permissions tab
    private val permissionStates = AppPermission.entries.associateWith { JBLabel().apply { font = JBFont.small() } }
    private val permissionButtons = AppPermission.entries.associateWith { JButton("Grant") to JButton("Revoke") }
    private val resetPermissionsButton = JButton("Reset all")
    private val permissionsHint = HintLabel()

    private val allDevicesBox = JBCheckBox("All running devices").apply {
        toolTipText = "Apply settings, links, launches, presets and pushes to every running device at once (Pro)"
    }
    private val linksTab = LinksTab(this, PropertiesComponent.getInstance(project)) { openLink(it) }
    private val simulateTab = SimulateTab(this, simulation)
    private val pushTab = PushTab(this, simulation)
    private val presetsTab = PresetsTab(this, PresetRunner(settings, simulation), PropertiesComponent.getInstance()) { currentConditions() }
    private val deviceTabs = listOf(linksTab, simulateTab, pushTab, presetsTab)

    @Volatile
    private var recording: ScreenRecording? = null
    private lateinit var tabs: JBTabs

    /** True while controls are being set from device state, so their listeners don't act. */
    private var updating = false

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)
    private val refreshing = AtomicBoolean(false)
    private val props = PropertiesComponent.getInstance(project)

    @Volatile
    private var apps = ProjectApps(emptyList(), emptyList())

    @Volatile
    internal var lastList: DeviceList = DeviceList(emptyList())
        private set

    @Volatile
    internal var lastResult: ActionResult? = null
        private set

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar("KmpDevicesToolbar", DefaultActionGroup(
            action("Refresh", "Find devices again", AllIcons.Actions.Refresh, { true }) { refresh() },
            action("Start", "Start the selected emulator or simulator", AllIcons.Actions.Execute, { canStart(it) }) { start(it!!) },
            action("Stop", "Shut down the selected emulator or simulator", AllIcons.Actions.Suspend, { canShutdown(it) }) { shutdown(it!!) },
            com.intellij.openapi.actionSystem.Separator.getInstance(),
            action("Screenshot", "Save a screenshot and copy it to the clipboard", AllIcons.FileTypes.Image, { it?.isRunning == true }) { screenshot(it!!) },
            recordAction(),
            action("Wi-Fi Debugging", "Pair and connect an Android phone over Wi-Fi", AllIcons.Nodes.Plugin, { true }) { WifiDialog(project, settings) { refresh() }.show() },
        ), true).apply { targetComponent = this@DevicesPanel }

        list.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val device = selectedDevice() ?: return
                if (e.clickCount == 2 && canStart(device)) start(device)
            }
        })
        appBox.addActionListener { rememberApp(); checkInstalled() }
        launchButton.addActionListener { rememberApp(); runOnTargets("Launching ${appId()}…") { d, a -> actions.launch(d, a) } }
        stopAppButton.addActionListener { rememberApp(); runOnTargets("Stopping ${appId()}…") { d, a -> actions.stop(d, a) } }
        clearButton.addActionListener {
            val d = selectedDevice() ?: return@addActionListener
            if (confirm("Clear App Data", "Delete all data of ${appId()} on ${d.name}? The app starts fresh, as if just installed.")) {
                appAction("Clearing data") { device, a -> actions.clearData(device, a) }
            }
        }
        uninstallButton.addActionListener {
            val d = selectedDevice() ?: return@addActionListener
            if (confirm("Uninstall App", "Uninstall ${appId()} from ${d.name}?")) {
                appAction("Uninstalling") { device, a -> actions.uninstall(device, a) }
            }
        }

        darkBox.addActionListener {
            if (updating) return@addActionListener
            val dark = darkBox.isSelected
            runOnTargets("Switching appearance…") { d, _ -> settings.setDarkMode(d, dark) }
        }
        textSizeBox.addActionListener {
            if (updating) return@addActionListener
            val size = textSizeBox.selectedItem as? TextSize ?: return@addActionListener
            runOnTargets("Changing text size…") { d, _ -> settings.setTextSize(d, size) }
        }
        applyLanguageButton.addActionListener {
            val language = languageBox.selectedItem as? AppLanguage
            runOnTargets("Changing language…") { d, a -> settings.setAppLanguage(d, a, language) }
        }
        permissionButtons.forEach { (permission, buttons) ->
            buttons.first.addActionListener { permissionAction(permission, grant = true) }
            buttons.second.addActionListener { permissionAction(permission, grant = false) }
        }
        resetPermissionsButton.addActionListener {
            val d = selectedDevice() ?: return@addActionListener
            val id = appId()
            run("Resetting permissions…") { settings.resetPermissions(d, id) }
        }
        allDevicesBox.addActionListener {
            if (allDevicesBox.isSelected && !requirePro("Actions on all running devices")) allDevicesBox.isSelected = false
        }

        val appTab = panel {
            row("App:") { cell(appBox).align(AlignX.FILL).resizableColumn() }
            row { cell(installed) }
            row { cell(launchButton); cell(stopAppButton) }
            row { cell(clearButton); cell(uninstallButton) }
        }
        val settingsTab = panel {
            row { cell(darkBox) }
            row("Text size:") { cell(textSizeBox) }
            row("App language:") { cell(languageBox).align(AlignX.FILL).resizableColumn() }
            row { cell(applyLanguageButton) }
            row { cell(settingsHint) }
        }
        val permissionsTab = panel {
            AppPermission.entries.forEach { permission ->
                val (grant, revoke) = permissionButtons.getValue(permission)
                row(permission.label + ":") {
                    cell(permissionStates.getValue(permission)).resizableColumn()
                    cell(grant); cell(revoke)
                }
            }
            row { cell(resetPermissionsButton) }
            row { cell(permissionsHint) }
        }
        // The IDE's own tabs (as in editors and tool windows): readable in every theme, with an overflow menu when narrow.
        tabs = JBTabsFactory.createTabs(project, parent)
        listOf(
            "App" to appTab,
            "Settings" to settingsTab,
            "Permissions" to ScrollPaneFactory.createScrollPane(permissionsTab, true),
            "Links" to linksTab.component,
            "Simulate" to ScrollPaneFactory.createScrollPane(simulateTab.component, true),
            "Push" to pushTab.component,
            "Presets" to presetsTab.component,
        ).forEach { (title, content) -> tabs.addTab(TabInfo(content).setText(title)) }
        val appPanel = JPanel(BorderLayout()).apply {
            add(panel { row { cell(allDevicesBox) } }.apply { border = JBUI.Borders.empty(4, 10, 0, 10) }, BorderLayout.NORTH)
            add(tabs.component, BorderLayout.CENTER)
            add(panel {
                row { cell(result) }
                row { cell(status) }
            }.apply { border = JBUI.Borders.empty(2, 10, 6, 10) }, BorderLayout.SOUTH)
            border = JBUI.Borders.customLineTop(JBColor.border())
        }

        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(notices, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        add(appPanel, BorderLayout.SOUTH)

        selectionChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            apps = detectApps()
            val links = detectLinks()
            onEdt { linksTab.setDetected(links); selectionChanged() }
        }
        refresh()
        if (autoRefresh) schedule()
    }

    // ---- Refresh -------------------------------------------------------------------------------------------

    /** Polls every few seconds, but only while the tool window is actually visible. */
    private fun schedule() {
        alarm.addRequest({
            if (isShowing) refreshNow()
            schedule()
        }, REFRESH_MS)
    }

    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread { refreshNow() }
    }

    private fun refreshNow() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            val loaded = load()
            lastList = loaded
            onEdt { show(loaded) }
        } finally {
            refreshing.set(false)
        }
    }

    private fun show(loaded: DeviceList) {
        val selectedId = selectedDevice()?.id
        model.replaceAll(rowsFor(loaded))
        if (selectedId != null) {
            val index = (0 until model.size).firstOrNull { (model.getElementAt(it) as? Row.Item)?.device?.id == selectedId }
            if (index != null) list.selectedIndex = index
        }
        list.emptyText.text = if (loaded.problems.isEmpty()) "No devices or simulators found" else "No devices found"

        notices.removeAll()
        loaded.problems.forEach { problem ->
            notices.add(JBLabel(problem, AllIcons.General.Warning, JBLabel.LEFT).apply {
                border = JBUI.Borders.empty(6, 12)
                isOpaque = true
                background = JBColor.namedColor("Banner.warningBackground", JBColor(0xFFF6DE, 0x3D3223))
            })
        }
        notices.isVisible = loaded.problems.isNotEmpty()
        notices.revalidate()

        val running = loaded.devices.count { it.state == DeviceState.RUNNING }
        status.text = "${loaded.devices.size} devices · $running running"
        selectionChanged()
    }

    // ---- Selection and app ---------------------------------------------------------------------------------

    override fun selectedDevice(): Device? = (list.selectedValue as? Row.Item)?.device

    override fun appId(): String = (appBox.editor.item as? String ?: appBox.selectedItem as? String).orEmpty().trim()

    /** The app id to use on [device]: the one chosen here for its platform, else remembered or detected. */
    private fun appIdFor(device: Device): String =
        if (device.platform == selectedDevice()?.platform) appId()
        else props.getValue(appKey(device.platform)) ?: apps.forPlatform(device.platform).firstOrNull().orEmpty()

    override fun requirePro(feature: String): Boolean {
        if (isPro()) return true
        upsell?.invoke(feature) ?: ProLicense.showUpsell(project, feature, this)
        return false
    }

    /** The selected device, or every running device with "All running devices" on. */
    private fun targets(): List<Device> =
        if (allDevicesBox.isSelected) lastList.devices.filter { it.state == DeviceState.RUNNING }
        else listOfNotNull(selectedDevice()?.takeIf { it.isRunning })

    override fun runOnTargets(progress: String, block: (device: Device, appId: String) -> ActionResult) {
        if (allDevicesBox.isSelected && !requirePro("Actions on all running devices")) return
        val devices = targets()
        val ids = devices.associateWith { appIdFor(it) }
        run(if (devices.size > 1) "${progress.removeSuffix("…")} on ${devices.size} devices…" else progress) {
            Batch.run(devices) { d -> block(d, ids.getValue(d)) }
        }
    }

    /** Current settings controls as a preset, for "Save current". */
    private fun currentConditions() = TestPreset(
        name = "",
        darkMode = darkBox.isSelected,
        textSize = textSizeBox.selectedItem as? TextSize,
        language = (languageBox.selectedItem as? AppLanguage)?.android ?: "",
        network = simulateTab.networkBox.selectedItem as? io.github.kmpdevicehub.core.NetworkProfile,
        cleanStatusBar = simulateTab.statusBarBox.isSelected,
    )

    private fun selectionChanged() {
        val device = selectedDevice()
        val candidates = device?.let { apps.forPlatform(it.platform) }.orEmpty()
        val remembered = device?.let { props.getValue(appKey(it.platform)) }
        val choices = (listOfNotNull(remembered) + candidates).distinct()
        val current = remembered ?: candidates.firstOrNull()
        if (choices != (0 until appBox.itemCount).map { appBox.getItemAt(it) }) {
            appBox.model = DefaultComboBoxModel(choices.toTypedArray())
        }
        if (current != null && appId() != current && device != null) appBox.selectedItem = current
        val usable = device != null && device.isRunning
        listOf(appBox, launchButton, stopAppButton, clearButton, uninstallButton, darkBox, textSizeBox, languageBox,
            applyLanguageButton, resetPermissionsButton).forEach { it.isEnabled = usable }
        deviceTabs.forEach { it.deviceChanged(device) }
        permissionButtons.forEach { (permission, buttons) ->
            val supported = device?.platform != DevicePlatform.IOS || permission.ios != null
            buttons.first.isEnabled = usable && supported
            buttons.second.isEnabled = usable && supported
        }
        refreshSettings()
        installed.text = when {
            device == null -> "Select a device to manage your app on it."
            !device.isRunning -> "Start ${device.name} to manage apps on it."
            choices.isEmpty() -> "Type your app id (no ${if (device.platform == DevicePlatform.ANDROID) "applicationId" else "bundle id"} found in this project)."
            else -> "Checking ${device.name}…"
        }
        checkInstalled()
    }

    private fun rememberApp() {
        val device = selectedDevice() ?: return
        val id = appId()
        if (id.isNotBlank()) props.setValue(appKey(device.platform), id)
    }

    private fun checkInstalled() {
        val device = selectedDevice()?.takeIf { it.isRunning } ?: return
        val id = appId().takeIf { it.isNotBlank() } ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val yes = actions.isInstalled(device, id)
            onEdt {
                if (selectedDevice()?.id == device.id && appId() == id) {
                    installed.text = if (yes) "✓ Installed on ${device.name}" else "Not installed on ${device.name}"
                }
            }
        }
    }

    // ---- Settings ------------------------------------------------------------------------------------------

    /** Reads dark mode, text size and permission states from the selected device. */
    private fun refreshSettings() {
        val device = selectedDevice()
        if (device == null || !device.isRunning) {
            settingsHint.hint = device?.let { "Start ${it.name} to change its settings." } ?: "Select a device."
            permissionStates.values.forEach { it.text = "" }
            permissionsHint.hint = settingsHint.hint
            return
        }
        val id = appId()
        settingsHint.hint = if (device.platform == DevicePlatform.ANDROID) "App language needs Android 13+." else "Language relaunches the app with that language."
        permissionsHint.hint = when {
            id.isBlank() -> "Choose your app on the App tab."
            device.platform == DevicePlatform.IOS -> "The iOS simulator doesn't report permission state. Camera and notifications can't be changed there."
            else -> "Only permissions your app requests can be granted."
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val snapshot = settings.read(device)
            val states = if (id.isBlank()) emptyMap() else settings.permissions(device, id)
            onEdt {
                if (selectedDevice()?.id != device.id) return@onEdt
                updating = true
                try {
                    snapshot.darkMode?.let { darkBox.isSelected = it }
                    snapshot.textSize?.let { textSizeBox.selectedItem = it }
                } finally {
                    updating = false
                }
                permissionStates.forEach { (permission, label) ->
                    val state = states[permission]
                    label.text = when {
                        device.platform == DevicePlatform.IOS && permission.ios == null -> "Not on simulator"
                        state == PermissionState.GRANTED -> "Granted"
                        state == PermissionState.DENIED -> "Denied"
                        state == PermissionState.NOT_REQUESTED -> "Not requested"
                        else -> ""
                    }
                    label.foreground = when (state) {
                        PermissionState.GRANTED -> DeviceColors.running
                        PermissionState.DENIED -> DeviceColors.problem
                        else -> UIUtil.getContextHelpForeground()
                    }
                }
            }
        }
    }

    private fun permissionAction(permission: AppPermission, grant: Boolean) {
        val device = selectedDevice() ?: return
        val id = appId()
        run(if (grant) "Granting ${permission.label}…" else "Revoking ${permission.label}…") { settings.setPermission(device, id, permission, grant) }
    }

    private fun openLink(link: String) {
        runOnTargets("Opening link…") { d, a -> settings.openLink(d, link, a.takeIf { d.platform == DevicePlatform.ANDROID }) }
    }

    private fun fileFor(dir: File, device: Device, extension: String): File {
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"))
        return File(dir, "${device.name.replace(Regex("[^A-Za-z0-9 ._()-]"), "")} $stamp.$extension")
    }

    private fun screenshot(device: Device) {
        if (allDevicesBox.isSelected) {
            runOnTargets("Taking screenshots…") { d, _ -> settings.screenshot(d, fileFor(screenshotDir, d, "png")) }
            return
        }
        val target = fileFor(screenshotDir, device, "png")
        showResult(null, "Taking a screenshot of ${device.name}…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = settings.screenshot(device, target)
            val shown = if (outcome.ok) {
                val copied = copyImageToClipboard(target)
                ActionResult.done("Screenshot saved to ${target.path}" + if (copied) " and copied to the clipboard." else ".")
            } else outcome
            lastResult = shown
            onEdt { showResult(shown, shown.message) }
        }
    }

    private fun copyImageToClipboard(file: File): Boolean = runCatching {
        val image = javax.imageio.ImageIO.read(file) ?: return false
        val transferable = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.imageFlavor
            override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = image
        }
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(transferable)
        true
    }.getOrDefault(false)

    // ---- Screen recording ----------------------------------------------------------------------------------

    private fun recordAction() = object : DumbAwareAction("Record Screen", "Record the selected device's screen to a video (Pro)", AllIcons.Debugger.Db_set_breakpoint) {
        override fun actionPerformed(e: AnActionEvent) = toggleRecording()
        override fun update(e: AnActionEvent) {
            val active = recording
            e.presentation.text = if (active != null) "Stop Recording" else "Record Screen"
            e.presentation.icon = if (active != null) AllIcons.Actions.Suspend else AllIcons.Debugger.Db_set_breakpoint
            e.presentation.isEnabled = active != null || selectedDevice()?.isRunning == true
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            recording = null
            showResult(null, "Saving the recording of ${active.device.name}…")
            ApplicationManager.getApplication().executeOnPooledThread {
                val outcome = simulation.stopRecording(active).let { if (it.ok) ActionResult.done("Recording saved to ${it.message}") else it }
                lastResult = outcome
                onEdt { showResult(outcome, outcome.message) }
            }
            return
        }
        val device = selectedDevice()?.takeIf { it.isRunning } ?: return
        if (!requirePro("Screen recording")) return
        val (outcome, started) = simulation.startRecording(device, fileFor(recordingDir, device, if (device.platform == DevicePlatform.IOS) "mov" else "mp4"))
        recording = started
        lastResult = outcome
        showResult(if (outcome.ok) null else outcome, if (outcome.ok) "● ${outcome.message} Click Stop Recording to save." else outcome.message)
    }

    // ---- Actions -------------------------------------------------------------------------------------------

    private fun canStart(device: Device?) = device != null && device.state == DeviceState.STOPPED &&
        (device.platform == DevicePlatform.IOS || device.kind == DeviceKind.EMULATOR)

    private fun canShutdown(device: Device?) = device != null && device.isRunning && device.kind != DeviceKind.PHYSICAL

    private fun start(device: Device) = run("Starting ${device.name}…") { actions.boot(device) }

    private fun shutdown(device: Device) = run("Shutting down ${device.name}…") { actions.shutdown(device) }

    private fun appAction(verb: String, block: (Device, String) -> ActionResult) {
        // Destructive app actions only ever run on the selected device.
        val device = selectedDevice() ?: return
        val id = appId()
        rememberApp()
        run("$verb $id…") { block(device, id) }
    }

    private fun run(progress: String, block: () -> ActionResult) {
        showResult(null, progress)
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching(block).getOrElse { ActionResult.failed(it.message ?: it.toString()) }
            lastResult = outcome
            onEdt { showResult(outcome, outcome.message) }
            refreshNow()
            onEdt { checkInstalled(); refreshSettings() }
        }
    }

    private fun showResult(outcome: ActionResult?, text: String) {
        result.text = text
        result.icon = when (outcome?.ok) {
            true -> AllIcons.General.InspectionsOK
            false -> AllIcons.General.Error
            null -> AllIcons.Process.Step_1
        }
        result.foreground = if (outcome?.ok == false) JBColor.RED else UIUtil.getLabelForeground()
    }

    private fun action(text: String, description: String, icon: javax.swing.Icon, enabled: (Device?) -> Boolean, perform: (Device?) -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform(selectedDevice())
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled(selectedDevice())
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any(), project.disposed)

    companion object {
        private const val REFRESH_MS = 4_000
        private const val DEVICE_LANGUAGE = "Device language"

        private fun <T> labelRenderer(text: (Any?) -> String?) = object : com.intellij.ui.ColoredListCellRenderer<T>() {
            override fun customizeCellRenderer(list: javax.swing.JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(text(value).orEmpty())
            }
        }

        private fun appKey(platform: DevicePlatform) = "kmpdevicehub.app.${platform.name}"

        /** Running (or attention-needing) devices first, then stopped Android and iOS. */
        internal fun rowsFor(result: DeviceList): List<Row> {
            val active = result.devices.filter { it.state != DeviceState.STOPPED }
            val android = result.devices.filter { it.state == DeviceState.STOPPED && it.platform == DevicePlatform.ANDROID }
            val ios = result.devices.filter { it.state == DeviceState.STOPPED && it.platform == DevicePlatform.IOS }
            return buildList {
                if (active.isNotEmpty()) { add(Row.Header("Running", active.size)); active.forEach { add(Row.Item(it)) } }
                if (android.isNotEmpty()) { add(Row.Header("Android emulators", android.size)); android.forEach { add(Row.Item(it)) } }
                if (ios.isNotEmpty()) { add(Row.Header("iOS simulators", ios.size)); ios.forEach { add(Row.Item(it)) } }
            }
        }
    }

    // ---- Test hooks ----------------------------------------------------------------------------------------

    internal val rowCount: Int get() = model.size
    internal val statusText: String get() = status.text.orEmpty()
    internal val noticeCount: Int get() = notices.componentCount
    internal val installedText: String get() = installed.text.orEmpty()
    internal val currentAppId: String get() = appId()
    internal fun select(deviceId: String) {
        list.selectedIndex = (0 until model.size).first { (model.getElementAt(it) as? Row.Item)?.device?.id == deviceId }
    }
    internal fun clickLaunch() = launchButton.doClick()
    internal fun clickClearData() = clearButton.doClick()
    internal fun startSelected() = selectedDevice()?.let(::start)
    internal fun stopSelected() = selectedDevice()?.let(::shutdown)
    internal fun toggleDarkMode(on: Boolean) { darkBox.isSelected = on; darkBox.actionListeners.forEach { it.actionPerformed(null) } }
    internal fun grant(permission: AppPermission) = permissionButtons.getValue(permission).first.doClick()
    internal fun permissionStateText(permission: AppPermission): String = permissionStates.getValue(permission).text.orEmpty()
    internal fun openLinkForTest(url: String) { linksTab.field.text = url; openLink(url) }
    internal fun setAllDevices(on: Boolean) { allDevicesBox.isSelected = on; allDevicesBox.actionListeners.forEach { it.actionPerformed(null) } }
    internal val allDevicesOn: Boolean get() = allDevicesBox.isSelected
    internal val simulate get() = simulateTab
    internal val push get() = pushTab
    internal val presets get() = presetsTab
    internal val links get() = linksTab
    internal fun toggleRecordingForTest() = toggleRecording()
    internal val isRecording: Boolean get() = recording != null
    internal fun selectTab(title: String) { tabs.tabs.firstOrNull { it.text == title }?.let { tabs.select(it, false) } }
    internal val tabTitles: List<String> get() = tabs.tabs.map { it.text }
    internal fun takeScreenshot() = selectedDevice()?.let(::screenshot)
    internal val darkModeChecked: Boolean get() = darkBox.isSelected
}
