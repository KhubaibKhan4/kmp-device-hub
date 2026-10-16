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
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.kmpdevicehub.core.ActionResult
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
    private val detectApps: () -> ProjectApps = { project.basePath?.let { AppDetector.detect(File(it)) } ?: ProjectApps(emptyList(), emptyList()) },
    private val autoRefresh: Boolean = true,
    /** Asks before destructive actions; tests answer automatically. */
    private val confirm: (title: String, message: String) -> Boolean = { title, message ->
        Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) == Messages.YES
    },
) : JPanel(BorderLayout()) {
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
        ), true).apply { targetComponent = this@DevicesPanel }

        list.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val device = selectedDevice() ?: return
                if (e.clickCount == 2 && canStart(device)) start(device)
            }
        })
        appBox.addActionListener { rememberApp(); checkInstalled() }
        launchButton.addActionListener { appAction("Launching") { d, a -> actions.launch(d, a) } }
        stopAppButton.addActionListener { appAction("Stopping") { d, a -> actions.stop(d, a) } }
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

        val appPanel = panel {
            row("App:") { cell(appBox).align(AlignX.FILL).resizableColumn() }
            row { cell(installed) }
            row {
                cell(launchButton); cell(stopAppButton); cell(clearButton); cell(uninstallButton)
            }
            row { cell(result) }
            row { cell(status) }
        }.apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(6, 10))
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
            onEdt { selectionChanged() }
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

    private fun selectedDevice(): Device? = (list.selectedValue as? Row.Item)?.device

    private fun appId(): String = (appBox.editor.item as? String ?: appBox.selectedItem as? String).orEmpty().trim()

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
        listOf(appBox, launchButton, stopAppButton, clearButton, uninstallButton).forEach { it.isEnabled = usable }
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

    // ---- Actions -------------------------------------------------------------------------------------------

    private fun canStart(device: Device?) = device != null && device.state == DeviceState.STOPPED &&
        (device.platform == DevicePlatform.IOS || device.kind == DeviceKind.EMULATOR)

    private fun canShutdown(device: Device?) = device != null && device.isRunning && device.kind != DeviceKind.PHYSICAL

    private fun start(device: Device) = run("Starting ${device.name}…") { actions.boot(device) }

    private fun shutdown(device: Device) = run("Shutting down ${device.name}…") { actions.shutdown(device) }

    private fun appAction(verb: String, block: (Device, String) -> ActionResult) {
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
            onEdt { checkInstalled() }
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
}
