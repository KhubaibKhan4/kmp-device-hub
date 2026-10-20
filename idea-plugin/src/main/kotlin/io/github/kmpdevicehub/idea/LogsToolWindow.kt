package io.github.kmpdevicehub.idea

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.kmpdevicehub.core.AppDetector
import io.github.kmpdevicehub.core.Crash
import io.github.kmpdevicehub.core.CrashDetector
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceService
import io.github.kmpdevicehub.core.DeviceState
import io.github.kmpdevicehub.core.LogEntry
import io.github.kmpdevicehub.core.LogLevel
import io.github.kmpdevicehub.core.LogStreamer
import io.github.kmpdevicehub.core.ProjectApps
import io.github.kmpdevicehub.core.RunningProcess
import io.github.kmpdevicehub.core.StackFrame
import io.github.kmpdevicehub.core.StackTraces
import io.github.kmpdevicehub.core.TraceLine
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.DefaultComboBoxModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class LogsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LogsPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }
}

/**
 * Your app's logs from an Android device (logcat, filtered to the app's process and following restarts) or an iOS
 * simulator (the unified log for the app's process, or its stdout so Kotlin/Native `println` shows up). Crashes
 * are listed under the log with their stack traces, whose lines in your code open in the editor.
 */
class LogsPanel(
    private val project: Project,
    private val parent: Disposable,
    private val streamer: LogStreamer = LogStreamer(tools = toolsFor(project)),
    private val devices: () -> List<Device> = { DeviceService(tools = toolsFor(project)).list().devices.filter { it.state == DeviceState.RUNNING } },
    private val detectApps: () -> ProjectApps = { project.basePath?.let { AppDetector.detect(File(it)) } ?: ProjectApps(emptyList(), emptyList()) },
    private val autoRefresh: Boolean = true,
) : JPanel(BorderLayout()) {
    private val model = LogModel()
    private val table = JBTable(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        columnModel.getColumn(0).apply { preferredWidth = JBUI.scale(118); minWidth = JBUI.scale(110); maxWidth = JBUI.scale(140) }
        columnModel.getColumn(1).apply { preferredWidth = JBUI.scale(28); maxWidth = JBUI.scale(34) }
        columnModel.getColumn(2).apply { preferredWidth = JBUI.scale(170) }
        columnModel.getColumn(3).preferredWidth = JBUI.scale(900)
        setDefaultRenderer(Any::class.java, EntryRenderer())
        emptyText.text = "Choose a device and your app, then press Start"
    }
    private val deviceBox = ComboBox<Device>().apply { renderer = textRenderer<Device> { (it as? Device)?.let { d -> "${d.name}  ·  ${if (d.platform == DevicePlatform.IOS) "iOS" else "Android"}" } ?: "No running device" } }
    private val appBox = ComboBox<String>().apply { isEditable = true }
    private val levelBox = ComboBox(DefaultComboBoxModel(LogLevel.entries.toTypedArray())).apply {
        renderer = textRenderer<LogLevel> { (it as? LogLevel)?.label }
        selectedItem = LogLevel.DEBUG
    }
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Search messages and tags" }
    private val printlnBox = JBCheckBox("Include println (relaunches the app)").apply {
        toolTipText = "Kotlin/Native println and crash stack traces go to stdout, not the iOS log. This relaunches the app attached to its output."
    }
    private val status = JBLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }

    private val pending = ConcurrentLinkedQueue<LogEntry>()
    private val all = ArrayList<LogEntry>()
    private val uiAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)
    private val props = PropertiesComponent.getInstance(project)
    @Volatile private var process: RunningProcess? = null
    @Volatile private var streaming: Device? = null
    @Volatile private var androidPid: String? = null
    private var follow = true
    @Volatile private var apps = ProjectApps(emptyList(), emptyList())

    private var detector = CrashDetector()
    private val sources = SourceFrames(project)
    private val crashModel = CollectionListModel<Crash>()
    private val crashList = JBList(crashModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<Crash>() {
            override fun customizeCellRenderer(list: JList<out Crash>, value: Crash, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = AllIcons.General.Error
                append(value.time + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(value.title)
            }
        }
    }
    private val traceModel = CollectionListModel<TraceLine>()
    /** Files in the project that the shown crash's frames point at. */
    private var traceFiles: Map<StackFrame, VirtualFile> = emptyMap()
    private val traceList = JBList(traceModel).apply { cellRenderer = TraceRenderer(); emptyText.text = "Select a crash" }
    private val crashTitle = JBLabel().apply { font = JBFont.label().asBold(); foreground = DeviceColors.problem }
    private val splitter = OnePixelSplitter(true, "kmpdevicehub.logs.crashSplit", 0.6f)
    private val crashPane: JPanel

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar("KmpLogsToolbar", DefaultActionGroup(
            object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) = if (streaming != null) stop() else start()
                override fun update(e: AnActionEvent) {
                    val on = streaming != null
                    e.presentation.text = if (on) "Stop" else "Start"
                    e.presentation.description = if (on) "Stop streaming logs" else "Stream the app's logs from the chosen device"
                    e.presentation.icon = if (on) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
                    e.presentation.isEnabled = on || deviceBox.selectedItem is Device
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareAction("Clear", "Clear the log here (and the device's logcat buffer on Android)", AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) = clear()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareToggleAction("Scroll to End", "Keep the newest line in view", AllIcons.RunConfigurations.Scroll_down) {
                override fun isSelected(e: AnActionEvent) = follow
                override fun setSelected(e: AnActionEvent, state: Boolean) { follow = state }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareAction("Copy Shown Lines", "Copy the lines that match the filters", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(model.rows.joinToString("\n") { format(it) }))
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            object : DumbAwareAction("Export Log…", "Save the lines that match the filters as a text file", AllIcons.ToolbarDecorator.Export) {
                override fun actionPerformed(e: AnActionEvent) = export()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = model.rowCount > 0 }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        ), true).apply { targetComponent = this@LogsPanel }

        val crashToolbar = ActionManager.getInstance().createActionToolbar("KmpLogsCrashToolbar", DefaultActionGroup(
            object : DumbAwareAction("Copy Crash", "Copy the crash and its stack trace", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) { crashList.selectedValue?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.text)) } }
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = crashList.selectedValue != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        ), true).apply { targetComponent = traceList }
        crashPane = OnePixelSplitter(false, "kmpdevicehub.logs.crashListSplit", 0.3f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(crashList, true)
            secondComponent = JPanel(BorderLayout()).apply {
                add(JPanel(BorderLayout()).apply {
                    add(crashTitle, BorderLayout.CENTER)
                    add(crashToolbar.component, BorderLayout.EAST)
                    border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.emptyLeft(8))
                }, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(traceList, true), BorderLayout.CENTER)
            }
        }

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JBLabel("Device:")); add(deviceBox)
            add(JBLabel("App:")); add(appBox)
            add(JBLabel("Level:")); add(levelBox)
            add(search.apply { preferredSize = java.awt.Dimension(JBUI.scale(240), preferredSize.height) })
            add(printlnBox)
        }
        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(controls, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(table, true)
        add(splitter, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.WEST)
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(3, 8))
        }, BorderLayout.SOUTH)

        levelBox.addActionListener { refilter() }
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = refilter()
        })
        deviceBox.addActionListener { updateAppChoices() }
        crashList.addListSelectionListener { if (!it.valueIsAdjusting) showCrash(crashList.selectedValue) }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean { openTraceLine(); return true }
        }.installOn(traceList)
        traceList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) openTraceLine() }
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val frame = model.rows.getOrNull(table.selectedRow)?.let { StackTraces.frame(it.message) } ?: return false
                ReadAction.nonBlocking<VirtualFile?> { sources.find(frame) }.inSmartMode(project).expireWith(parent)
                    .finishOnUiThread(ModalityState.any()) { file -> file?.let { sources.open(it, frame.line) } }
                    .submit(AppExecutorUtil.getAppExecutorService())
                return true
            }
        }.installOn(table)
        appBox.addActionListener { (deviceBox.selectedItem as? Device)?.let { d -> appId().takeIf { it.isNotBlank() }?.let { props.setValue(appKey(d.platform), it) } } }
        Disposer.register(parent) { stop() }

        ApplicationManager.getApplication().executeOnPooledThread {
            apps = detectApps()
            refreshDevices()
        }
        if (autoRefresh) poll()
        status.text = "Not streaming"
    }

    // ---- Devices and app -------------------------------------------------------------------------------------

    private fun refreshDevices() {
        val list = runCatching(devices).getOrDefault(emptyList())
        onEdt {
            val current = deviceBox.selectedItem as? Device
            if (list.map { it.id } != (0 until deviceBox.itemCount).map { deviceBox.getItemAt(it).id }) {
                deviceBox.model = DefaultComboBoxModel(list.toTypedArray())
                deviceBox.selectedItem = list.firstOrNull { it.id == current?.id } ?: list.firstOrNull()
                updateAppChoices()
            }
        }
    }

    private fun updateAppChoices() {
        val device = deviceBox.selectedItem as? Device ?: return
        val remembered = props.getValue(appKey(device.platform))
        val choices = (listOfNotNull(remembered) + apps.forPlatform(device.platform)).distinct()
        appBox.model = DefaultComboBoxModel(choices.toTypedArray())
        choices.firstOrNull()?.let { appBox.selectedItem = it }
        printlnBox.isVisible = device.platform == DevicePlatform.IOS
    }

    private fun appId(): String = (appBox.editor.item as? String ?: appBox.selectedItem as? String).orEmpty().trim()

    /** Refreshes devices every few seconds while visible, and follows the app's process on Android. */
    private fun poll() {
        pollAlarm.addRequest({
            if (isShowing) {
                refreshDevices()
                followAndroidRestart()
            }
            poll()
        }, 3_000)
    }

    // ---- Streaming ----------------------------------------------------------------------------------------

    fun start() {
        val device = deviceBox.selectedItem as? Device ?: return
        val app = appId()
        if (app.isBlank()) { status.text = "Type your app id (applicationId or bundle id)."; return }
        props.setValue(appKey(device.platform), app)
        stop()
        streaming = device
        status.text = "Starting…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = when (device.platform) {
                DevicePlatform.ANDROID -> {
                    val pid = streamer.androidPid(device, app)
                    androidPid = pid
                    if (pid == null) "Waiting for $app to start on ${device.name}…"
                    else { process = streamer.streamAndroid(device, pid, ::receive); "Streaming $app (pid $pid) on ${device.name}" }
                }
                DevicePlatform.IOS -> if (printlnBox.isSelected) {
                    process = streamer.streamIosConsole(device, app, ::receive)
                    "Streaming $app's output on ${device.name} (relaunched)"
                } else {
                    val name = streamer.iosProcessName(device, app)
                    if (name == null) "$app isn't installed on ${device.name}."
                    else { process = streamer.streamIos(device, name, ::receive); "Streaming $name's log on ${device.name}" }
                }
            }
            onEdt { status.text = text }
        }
    }

    fun stop() {
        process?.kill()
        process = null
        streaming = null
        androidPid = null
        onEdt { if (status.text.startsWith("Streaming") || status.text.startsWith("Waiting")) status.text = "Stopped" }
    }

    /** A restarted Android app gets a new pid; switch the stream to it (its startup lines come with the history). */
    private fun followAndroidRestart() {
        val device = streaming?.takeIf { it.platform == DevicePlatform.ANDROID } ?: return
        val app = appId()
        val pid = streamer.androidPid(device, app) ?: return
        if (pid == androidPid) return
        process?.kill()
        androidPid = pid
        receive(LogEntry(now(), LogLevel.INFO, "KMP Device Hub", "— $app started (pid $pid) —"))
        process = streamer.streamAndroid(device, pid, ::receive)
        onEdt { status.text = "Streaming $app (pid $pid) on ${device.name}" }
    }

    private fun receive(entry: LogEntry) {
        pending += entry
        if (uiAlarm.isEmpty) uiAlarm.addRequest(::flush, 150)
    }

    private fun flush() {
        var added = false
        val newCrashes = mutableSetOf<Int>()
        while (true) {
            val e = pending.poll() ?: break
            all += e
            added = true
            detector.feed(e)?.let { if (onCrash(it)) newCrashes += it.id }
        }
        if (all.size > MAX_LINES) all.subList(0, all.size - MAX_LINES).clear()
        if (added) refilter()
        if (!isShowing) crashModel.items.filter { it.id in newCrashes }.forEach(::notifyCrash)
    }

    private fun refilter() {
        val min = levelBox.selectedItem as? LogLevel ?: LogLevel.VERBOSE
        val query = search.text.trim()
        model.update(all.filter { it.level.ordinal >= min.ordinal && (query.isEmpty() || it.message.contains(query, true) || it.tag.contains(query, true)) })
        if (follow && model.rowCount > 0) table.scrollRectToVisible(table.getCellRect(model.rowCount - 1, 0, true))
        if (streaming != null) status.text = status.text.substringBefore("  ·  ") + "  ·  ${model.rowCount} of ${all.size} lines" +
            crashModel.size.let { if (it > 0) "  ·  $it ${if (it == 1) "crash" else "crashes"}" else "" }
    }

    private fun clear() {
        all.clear(); pending.clear(); model.update(emptyList())
        detector = CrashDetector()
        crashModel.removeAll()
        splitter.secondComponent = null
        val device = streaming ?: deviceBox.selectedItem as? Device
        if (device?.platform == DevicePlatform.ANDROID) ApplicationManager.getApplication().executeOnPooledThread { streamer.clearAndroid(device) }
    }

    // ---- Crashes and export --------------------------------------------------------------------------------

    /** Adds a new crash (shown and selected) or updates a growing one; true when it is new. */
    private fun onCrash(crash: Crash): Boolean {
        val index = crashModel.items.indexOfFirst { it.id == crash.id }
        if (index >= 0) {
            crashModel.setElementAt(crash, index)
            if (crashList.selectedIndex == index) showCrash(crash)
            return false
        }
        crashModel.add(crash)
        if (splitter.secondComponent == null) splitter.secondComponent = crashPane
        crashList.selectedIndex = crashModel.size - 1
        return true
    }

    private fun showCrash(crash: Crash?) {
        crashTitle.text = crash?.title.orEmpty()
        crashTitle.toolTipText = crash?.title
        traceModel.replaceAll(crash?.trace.orEmpty())
        val frames = crash?.trace?.mapNotNull { it.frame }.orEmpty()
        traceFiles = traceFiles.filterKeys { it in frames }
        if (crash == null || frames.isEmpty()) return
        ReadAction.nonBlocking<Map<StackFrame, VirtualFile>> { frames.mapNotNull { f -> sources.find(f)?.let { f to it } }.toMap() }
            .inSmartMode(project).expireWith(parent)
            .finishOnUiThread(ModalityState.any()) { found ->
                if (crashList.selectedValue?.id != crash.id) return@finishOnUiThread
                val first = traceFiles.isEmpty()
                traceFiles = found
                traceList.repaint()
                // Select the first line in your code, where the crash most likely starts.
                if (first) traceModel.items.indexOfFirst { it.frame in found }.takeIf { it >= 0 }?.let { traceList.selectedIndex = it; traceList.ensureIndexIsVisible(it) }
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun openTraceLine() {
        val frame = traceList.selectedValue?.frame ?: return
        traceFiles[frame]?.let { sources.open(it, frame.line) }
    }

    private fun notifyCrash(crash: Crash) {
        val app = appId().ifBlank { "Your app" }
        NotificationGroupManager.getInstance().getNotificationGroup("KMP Device Hub")
            .createNotification("$app crashed", StringUtil.escapeXmlEntities(crash.title), NotificationType.ERROR)
            .addAction(NotificationAction.createSimpleExpiring("Show crash") {
                ToolWindowManager.getInstance(project).getToolWindow("KMP Logs")?.show()
                crashModel.items.indexOfFirst { it.id == crash.id }.takeIf { it >= 0 }?.let { crashList.selectedIndex = it }
            })
            .notify(project)
    }

    private fun export() {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss").format(Date())
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Export Log", "Save the shown lines as a text file", "log", "txt"), project)
            .save("${appId().ifBlank { "app" }}-$stamp.log") ?: return
        target.file.writeText(exportText())
    }

    /** The shown lines, after a line saying which app and device they came from. */
    internal fun exportText(): String {
        val device = streaming ?: deviceBox.selectedItem as? Device
        val header = listOfNotNull(
            appId().ifBlank { null },
            device?.let { "${it.name} (${if (it.platform == DevicePlatform.IOS) "iOS" else "Android"} ${it.osVersion.orEmpty()})".replace(" )", ")") },
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()),
        ).joinToString("  ·  ")
        return "# $header\n" + model.rows.joinToString("") { format(it) + "\n" }
    }

    private inner class TraceRenderer : ColoredListCellRenderer<TraceLine>() {
        override fun customizeCellRenderer(list: JList<out TraceLine>, value: TraceLine, index: Int, selected: Boolean, hasFocus: Boolean) {
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBFont.label().size)
            val file = value.frame?.let { traceFiles[it] }
            val text = value.text.trimStart()
            val attributes = when {
                file != null -> SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                text.startsWith("at ") || text.startsWith("...") -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                else -> SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, DeviceColors.problem)
            }
            append(value.text.replace("\t", "    "), attributes)
            toolTipText = file?.let { "Double-click to open ${it.name}:${value.frame?.line}" }
        }
    }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any(), project.disposed)

    // ---- Table ----------------------------------------------------------------------------------------------

    private class LogModel : AbstractTableModel() {
        var rows: List<LogEntry> = emptyList()
            private set
        fun update(entries: List<LogEntry>) { rows = entries; fireTableDataChanged() }
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("Time", "", "Tag", "Message")[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row]
    }

    private class EntryRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val e = value as? LogEntry ?: return
            val color = when (e.level) {
                LogLevel.ERROR -> DeviceColors.problem
                LogLevel.WARN -> JBColor(0xB26B00, 0xE5A84B)
                LogLevel.INFO -> UIUtil.getLabelForeground()
                LogLevel.DEBUG -> JBColor(0x2F6BD8, 0x6FA0F6)
                LogLevel.VERBOSE -> UIUtil.getContextHelpForeground()
            }
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBFont.label().size)
            when (column) {
                0 -> append(e.time, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                1 -> append(e.level.letter.toString(), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                2 -> append(e.tag, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
                3 -> append(e.message, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (e.level >= LogLevel.WARN) color else UIUtil.getLabelForeground()))
            }
            toolTipText = if (column == 3 && e.message.length > 80) e.message else null
        }
    }

    companion object {
        private const val MAX_LINES = 20_000
        private fun appKey(platform: DevicePlatform) = "kmpdevicehub.app.${platform.name}"
        private fun now() = java.time.LocalTime.now().toString().padEnd(12, '0').take(12)
        internal fun format(e: LogEntry) = "${e.time} ${e.level.letter} ${e.tag}: ${e.message}"
    }

    // ---- Test hooks ----------------------------------------------------------------------------------------

    internal val shownLines: List<String> get() = model.rows.map { format(it) }
    internal val statusText: String get() = status.text.orEmpty()
    internal fun setLevel(level: LogLevel) { levelBox.selectedItem = level }
    internal fun setSearch(text: String) { search.text = text; refilter() }
    internal fun setApp(id: String) { appBox.selectedItem = id; appBox.editor.item = id }
    internal fun setIncludePrintln(on: Boolean) { printlnBox.isSelected = on }
    internal fun refreshDevicesForTest() = refreshDevices()
    internal fun followForTest() = followAndroidRestart()
    internal val isStreaming: Boolean get() = streaming != null
    internal val crashTitles: List<String> get() = crashModel.items.map { it.title }
    internal val linkedFrames: List<String> get() = traceModel.items.mapNotNull { line -> line.frame?.let { frame -> traceFiles[frame]?.let { "${it.name}:${frame.line}" } } }
    internal val selectedTraceLine: String? get() = traceList.selectedValue?.text
    internal val crashPaneShown: Boolean get() = splitter.secondComponent != null
    internal fun selectDevice(id: String) { (0 until deviceBox.itemCount).map { deviceBox.getItemAt(it) }.firstOrNull { it.id == id }?.let { deviceBox.selectedItem = it } }
}

/** Finds the project files that stack frames point at, and opens them. */
internal class SourceFrames(private val project: Project) {
    /** The project file for [frame], or null for JDK and Android frames and files outside the project. Needs a read action. */
    fun find(frame: StackFrame): VirtualFile? {
        if (frame.isFramework) return null
        val index = ProjectFileIndex.getInstance(project)
        frame.path?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.takeIf { index.isInContent(it) }?.let { return it }
        if (DumbService.isDumb(project)) return null
        val candidates = FilenameIndex.getVirtualFilesByName(frame.fileName, GlobalSearchScope.projectScope(project))
        return candidates.firstOrNull { it.path.endsWith("/" + frame.suffix) } ?: candidates.singleOrNull()
    }

    fun open(file: VirtualFile, line: Int) = OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
}
