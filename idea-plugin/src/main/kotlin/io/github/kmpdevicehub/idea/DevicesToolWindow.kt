package io.github.kmpdevicehub.idea

import com.intellij.icons.AllIcons
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
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.kmpdevicehub.core.DeviceList
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceService
import io.github.kmpdevicehub.core.DeviceState
import io.github.kmpdevicehub.core.ToolLocator
import java.awt.BorderLayout
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class DevicesToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DevicesPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }
}

/** Uses the project's `sdk.dir` when it has one, like Gradle does. */
internal fun deviceServiceFor(project: Project): DeviceService {
    val sdkDir = project.basePath?.let { File(it, "local.properties") }?.takeIf { it.isFile }?.let { file ->
        Properties().apply { file.reader().use { load(it) } }.getProperty("sdk.dir")
    }
    return DeviceService(tools = ToolLocator.locate(sdkDir = sdkDir))
}

/** Every Android device, emulator and iOS simulator in one list, refreshed while visible. */
class DevicesPanel(
    private val project: Project,
    parent: Disposable,
    private val load: () -> DeviceList = { deviceServiceFor(project).list() },
    private val autoRefresh: Boolean = true,
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
        border = JBUI.Borders.empty(4, 12)
    }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)
    private val refreshing = AtomicBoolean(false)

    @Volatile
    internal var lastList: DeviceList = DeviceList(emptyList())
        private set

    init {
        val actions = DefaultActionGroup(object : DumbAwareAction("Refresh", "Find devices again", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("KmpDevicesToolbar", actions, true).apply {
            targetComponent = this@DevicesPanel
        }
        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(notices, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        refresh()
        if (autoRefresh) schedule()
    }

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
            val result = load()
            lastList = result
            ApplicationManager.getApplication().invokeLater({ show(result) }, ModalityState.any(), project.disposed)
        } finally {
            refreshing.set(false)
        }
    }

    private fun show(result: DeviceList) {
        val selectedId = (list.selectedValue as? Row.Item)?.device?.id
        model.replaceAll(rowsFor(result))
        if (selectedId != null) {
            val index = (0 until model.size).firstOrNull { (model.getElementAt(it) as? Row.Item)?.device?.id == selectedId }
            if (index != null) list.selectedIndex = index
        }
        list.emptyText.text = if (result.problems.isEmpty()) "No devices or simulators found" else "No devices found"

        notices.removeAll()
        result.problems.forEach { problem ->
            notices.add(JBLabel(problem, AllIcons.General.Warning, JBLabel.LEFT).apply {
                border = JBUI.Borders.empty(6, 12)
                isOpaque = true
                background = JBColor.namedColor("Banner.warningBackground", JBColor(0xFFF6DE, 0x3D3223))
            })
        }
        notices.isVisible = result.problems.isNotEmpty()
        notices.revalidate()

        val running = result.devices.count { it.state == DeviceState.RUNNING }
        status.text = "${result.devices.size} devices · $running running"
    }

    companion object {
        private const val REFRESH_MS = 4_000

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

    // Test hooks.
    internal val rowCount: Int get() = model.size
    internal val statusText: String get() = status.text.orEmpty()
    internal val noticeCount: Int get() = notices.componentCount
}
