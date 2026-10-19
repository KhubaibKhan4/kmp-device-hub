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
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.kmpdevicehub.core.AppDataExplorer
import io.github.kmpdevicehub.core.AppDetector
import io.github.kmpdevicehub.core.DataEntry
import io.github.kmpdevicehub.core.DataKind
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceService
import io.github.kmpdevicehub.core.DeviceState
import io.github.kmpdevicehub.core.Json
import io.github.kmpdevicehub.core.NetworkStats
import io.github.kmpdevicehub.core.PrefEntry
import io.github.kmpdevicehub.core.ProjectApps
import io.github.kmpdevicehub.core.SqlTable
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class AppDataToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AppDataPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }
}

/**
 * Your app's private data on a device: browse its files, read SharedPreferences / NSUserDefaults, and look into SQLite
 * databases (Room, SQLDelight, Core Data) with a table view and read-only SQL. Databases are read from a snapshot copied
 * to this computer; Refresh takes a new one.
 */
class AppDataPanel(
    private val project: Project,
    parent: Disposable,
    private val explorer: AppDataExplorer = AppDataExplorer(tools = toolsFor(project)),
    private val devices: () -> List<Device> = { DeviceService(tools = toolsFor(project)).list().devices.filter { it.state == DeviceState.RUNNING } },
    private val detectApps: () -> ProjectApps = { project.basePath?.let { AppDetector.detect(File(it)) } ?: ProjectApps(emptyList(), emptyList()) },
    private val confirm: (String, String) -> Boolean = { title, message -> Messages.showYesNoDialog(project, message, title, Messages.getWarningIcon()) == Messages.YES },
) : JPanel(BorderLayout()) {
    private val deviceBox = ComboBox<Device>().apply { renderer = textRenderer<Device> { (it as? Device)?.name ?: "No running device" } }
    private val appBox = ComboBox<String>().apply { isEditable = true }
    private val openButton = JButton("Open", AllIcons.Actions.MenuOpen)
    private val pathLabel = JBLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }

    private val files = CollectionListModel<DataEntry>()
    private val fileList = JBList(files).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = EntryRenderer()
        emptyText.text = "Pick a device and your app, then Open"
    }

    // Viewers
    private val cards = CardLayout()
    private val viewer = JPanel(cards)
    private val message = JBLabel("", AllIcons.General.Information, JBLabel.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }
    private val prefsModel = object : DefaultTableModel(arrayOf("Key", "Type", "Value"), 0) { override fun isCellEditable(r: Int, c: Int) = false }
    private val textArea = JBTextArea().apply { isEditable = false; font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBFont.label().size); border = JBUI.Borders.empty(6, 8) }
    private val imageLabel = JBLabel().apply { horizontalAlignment = JBLabel.CENTER }
    private val tableList = JBList(CollectionListModel<String>()).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val rowsModel = object : DefaultTableModel() { override fun isCellEditable(r: Int, c: Int) = false }
    private val rowsTable = JBTable(rowsModel).apply { autoResizeMode = JBTable.AUTO_RESIZE_OFF; setShowGrid(true) }
    private val sqlField = JBTextField().apply { emptyText.text = "SELECT * FROM … (read-only)" }
    private val runSqlButton = JButton("Run", AllIcons.Actions.Execute)
    private val dbStatus = JBLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }
    private val status = JBLabel().apply { font = JBFont.small() }

    private val props = PropertiesComponent.getInstance(project)
    private val snapshots = FileUtil.createTempDirectory("kmpdevicehub-data", null, true)
    @Volatile private var apps = ProjectApps(emptyList(), emptyList())
    @Volatile private var root: String? = null
    @Volatile private var current: String? = null
    @Volatile private var device: Device? = null
    @Volatile private var app: String = ""
    @Volatile private var db: File? = null

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar("KmpAppDataToolbar", DefaultActionGroup(
            action("Refresh", "List the folder again (and take a new database snapshot)", AllIcons.Actions.Refresh, { current != null }) { refresh() },
            action("Up", "Go to the parent folder", AllIcons.Actions.MoveUp, { current != null && current != root }) { current?.let { go(it.substringBeforeLast('/')) } },
            action("Home", "Go to the app's data folder", AllIcons.Nodes.HomeFolder, { root != null }) { root?.let(::go) },
            action("Download…", "Save the selected file to this computer", AllIcons.Actions.Download, { fileList.selectedValue?.isDirectory == false }) { download() },
            action("Delete", "Delete the selected file or folder from the app's data", AllIcons.General.Remove, { fileList.selectedValue != null }) { delete() },
        ), true).apply { targetComponent = this@AppDataPanel }

        val pickers = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JBLabel("Device:")); add(deviceBox); add(JBLabel("App:")); add(appBox); add(openButton)
        }
        add(JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(pickers, BorderLayout.CENTER)
            add(pathLabel.apply { border = JBUI.Borders.empty(0, 10) }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)

        viewer.add(JPanel(BorderLayout()).apply { add(message, BorderLayout.CENTER) }, MESSAGE)
        viewer.add(ScrollPaneFactory.createScrollPane(JBTable(prefsModel).apply { setShowGrid(false) }, true), PREFS)
        viewer.add(ScrollPaneFactory.createScrollPane(textArea, true), TEXT)
        viewer.add(ScrollPaneFactory.createScrollPane(imageLabel, true), IMAGE)
        viewer.add(JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                add(sqlField, BorderLayout.CENTER); add(runSqlButton, BorderLayout.EAST)
                border = JBUI.Borders.empty(4, 6)
            }, BorderLayout.NORTH)
            add(OnePixelSplitter(false, 0.22f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(tableList, true)
                secondComponent = ScrollPaneFactory.createScrollPane(rowsTable, true)
            }, BorderLayout.CENTER)
            add(dbStatus.apply { border = JBUI.Borders.empty(2, 8) }, BorderLayout.SOUTH)
        }, DATABASE)
        show(MESSAGE, "Select a file to see what's inside.")

        add(OnePixelSplitter(false, "kmpdevicehub.appdata.split", 0.3f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(fileList, true)
            secondComponent = viewer
        }, BorderLayout.CENTER)
        add(status.apply { border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(3, 8)) }, BorderLayout.SOUTH)

        openButton.addActionListener { open() }
        deviceBox.addActionListener { updateApps() }
        fileList.addListSelectionListener { if (!it.valueIsAdjusting) fileList.selectedValue?.let(::preview) }
        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val entry = fileList.selectedValue ?: return
                if (e.clickCount == 2 && entry.isDirectory) go(entry.path)
            }
        })
        tableList.addListSelectionListener { if (!it.valueIsAdjusting) tableList.selectedValue?.let(::showTable) }
        runSqlButton.addActionListener { runSql() }
        sqlField.addActionListener { runSql() }

        background {
            apps = detectApps()
            val list = runCatching(devices).getOrDefault(emptyList())
            onEdt {
                deviceBox.model = DefaultComboBoxModel(list.toTypedArray())
                updateApps()
            }
        }
    }

    // ---- Navigation ---------------------------------------------------------------------------------------

    private fun updateApps() {
        val d = deviceBox.selectedItem as? Device ?: return
        val choices = (listOfNotNull(props.getValue("kmpdevicehub.app.${d.platform.name}")) + apps.forPlatform(d.platform)).distinct()
        appBox.model = DefaultComboBoxModel(choices.toTypedArray())
        choices.firstOrNull()?.let { appBox.selectedItem = it }
    }

    private fun appId() = (appBox.editor.item as? String ?: appBox.selectedItem as? String).orEmpty().trim()

    fun open() {
        val d = deviceBox.selectedItem as? Device ?: return
        val id = appId()
        status.text = "Opening $id on ${d.name}…"
        background {
            val (result, path) = explorer.root(d, id)
            onEdt {
                if (path == null) { status.text = result.message; status.icon = AllIcons.General.Error; return@onEdt }
                device = d; app = id; root = path; status.icon = null
                props.setValue("kmpdevicehub.app.${d.platform.name}", id)
                go(path)
            }
        }
    }

    private fun go(path: String) {
        val d = device ?: return
        current = path
        pathLabel.text = root?.let { r -> if (path.startsWith(r)) "${app}${path.removePrefix(r).ifEmpty { "/" }}" else path } ?: path
        background {
            val listing = explorer.list(d, app, path)
            onEdt {
                files.replaceAll(listing.entries)
                fileList.emptyText.text = if (listing.ok) "Empty folder" else listing.message
                status.text = if (listing.ok) "${listing.entries.size} item(s) · ${d.name}" else listing.message
                show(MESSAGE, if (d.platform == DevicePlatform.ANDROID) "Select a file. Databases are in databases/, preferences in shared_prefs/."
                    else "Select a file. NSUserDefaults are in Library/Preferences/, files in Documents/.")
            }
        }
    }

    private fun refresh() { db = null; current?.let(::go) }

    // ---- Viewers --------------------------------------------------------------------------------------------

    private fun preview(entry: DataEntry) {
        val d = device ?: return
        if (entry.isDirectory) { show(MESSAGE, "Double-click to open ${entry.name}."); return }
        show(MESSAGE, "Loading ${entry.name}…")
        background {
            when (entry.kind) {
                DataKind.PREFERENCES -> {
                    val (result, prefs) = explorer.preferences(d, app, entry.path)
                    onEdt { if (result.ok) showPrefs(prefs) else show(MESSAGE, result.message) }
                }
                DataKind.DATABASE -> {
                    val (result, file) = explorer.snapshotDatabase(d, app, entry.path, File(snapshots, "db-${System.nanoTime()}"))
                    val tables = file?.let { explorer.tables(it) }
                    onEdt {
                        if (file == null || tables == null || !tables.first.ok) { show(MESSAGE, (tables?.first ?: result).message); return@onEdt }
                        db = file
                        (tableList.model as CollectionListModel<String>).replaceAll(tables.second)
                        rowsModel.setDataVector(arrayOf(), arrayOf())
                        dbStatus.text = "${tables.second.size} table(s) · snapshot of ${entry.name} taken ${java.time.LocalTime.now().withNano(0)}"
                        show(DATABASE)
                        if (tables.second.isNotEmpty()) tableList.selectedIndex = 0
                    }
                }
                DataKind.IMAGE -> {
                    val tmp = File(snapshots, "img-${System.nanoTime()}-${entry.name}")
                    val ok = explorer.pull(d, app, entry.path, tmp).ok
                    onEdt { if (ok) { imageLabel.icon = ImageIcon(tmp.path); show(IMAGE) } else show(MESSAGE, "Couldn't read ${entry.name}.") }
                }
                DataKind.TEXT, DataKind.OTHER -> {
                    if (entry.kind == DataKind.OTHER && entry.size > 256 * 1024) {
                        onEdt { show(MESSAGE, "${entry.name} · ${NetworkStats.bytes(entry.size)}. Use Download to save it.") }
                        return@background
                    }
                    val text = explorer.readText(d, app, entry.path)
                    val shown = text?.let { t -> Json.pretty(t) ?: t }
                    onEdt {
                        when {
                            shown == null -> show(MESSAGE, "Couldn't read ${entry.name}.")
                            shown.count { it == '\u0000' } > 0 -> show(MESSAGE, "${entry.name} is binary (${NetworkStats.bytes(entry.size)}). Use Download to save it.")
                            else -> { textArea.text = shown; textArea.caretPosition = 0; show(TEXT) }
                        }
                    }
                }
                DataKind.FOLDER -> Unit
            }
        }
    }

    private fun showPrefs(prefs: List<PrefEntry>) {
        prefsModel.rowCount = 0
        prefs.forEach { prefsModel.addRow(arrayOf(it.key, it.type, it.value)) }
        show(PREFS)
        status.text = "${prefs.size} preference(s)"
    }

    private fun showTable(table: String) {
        val file = db ?: return
        background {
            val (result, data) = explorer.tableRows(file, table)
            onEdt {
                showRows(data)
                dbStatus.text = if (result.ok) "$table · ${data.total ?: data.rows.size} row(s)" + (if ((data.total ?: 0) > data.rows.size) ", showing ${data.rows.size}" else "") + " · snapshot" else result.message
                sqlField.text = "SELECT * FROM \"$table\" LIMIT 100"
            }
        }
    }

    private fun runSql() {
        val file = db ?: return
        val sql = sqlField.text
        background {
            val (result, data) = explorer.query(file, sql)
            onEdt {
                if (result.ok) showRows(data)
                dbStatus.text = if (result.ok) "${result.message} · read-only snapshot" else "SQL error: ${result.message}"
            }
        }
    }

    private fun showRows(data: SqlTable) {
        rowsModel.setDataVector(data.rows.map { r -> r.map { it ?: "NULL" }.toTypedArray<Any>() }.toTypedArray(), data.columns.toTypedArray<Any>())
        for (i in 0 until rowsTable.columnCount) rowsTable.columnModel.getColumn(i).preferredWidth = JBUI.scale(140)
    }

    // ---- Download / delete ------------------------------------------------------------------------------------

    private fun download() {
        val d = device ?: return
        val entry = fileList.selectedValue?.takeIf { !it.isDirectory } ?: return
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Download ${entry.name}", "Save a copy on this computer"), project)
            .save(entry.name) ?: return
        background {
            val r = explorer.pull(d, app, entry.path, target.file)
            onEdt { status.text = if (r.ok) "Saved ${entry.name} to ${target.file.path}" else r.message }
        }
    }

    private fun delete() {
        val d = device ?: return
        val entry = fileList.selectedValue ?: return
        if (!confirm("Delete from ${d.name}", "Delete ${entry.name} from $app's data on ${d.name}? This can't be undone.")) return
        background {
            val r = explorer.delete(d, app, entry.path)
            onEdt { status.text = r.message; current?.let(::go) }
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------------------

    private fun show(card: String, text: String? = null) {
        text?.let { message.text = it }
        cards.show(viewer, card)
    }

    private fun background(block: () -> Unit) { ApplicationManager.getApplication().executeOnPooledThread(block) }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any(), project.disposed)

    private fun action(text: String, description: String, icon: javax.swing.Icon, enabled: () -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private class EntryRenderer : ColoredListCellRenderer<DataEntry>() {
        override fun customizeCellRenderer(list: JList<out DataEntry>, value: DataEntry?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = when (value.kind) {
                DataKind.FOLDER -> AllIcons.Nodes.Folder
                DataKind.PREFERENCES -> AllIcons.General.Settings
                DataKind.DATABASE -> AllIcons.Nodes.DataTables
                DataKind.IMAGE -> AllIcons.FileTypes.Image
                DataKind.TEXT -> AllIcons.FileTypes.Text
                DataKind.OTHER -> AllIcons.FileTypes.Any_type
            }
            append(value.name)
            if (!value.isDirectory) append("   ${NetworkStats.bytes(value.size)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            append("   ${value.modified}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private companion object {
        const val MESSAGE = "message"; const val PREFS = "prefs"; const val TEXT = "text"; const val IMAGE = "image"; const val DATABASE = "database"
    }

    // ---- Test hooks ----------------------------------------------------------------------------------------

    internal fun selectDevice(id: String) { (0 until deviceBox.itemCount).map { deviceBox.getItemAt(it) }.firstOrNull { it.id == id }?.let { deviceBox.selectedItem = it } }
    internal val deviceCount: Int get() = deviceBox.itemCount
    internal fun setApp(id: String) { appBox.selectedItem = id; appBox.editor.item = id }
    internal val fileNames: List<String> get() = files.items.map { it.name }
    internal fun selectFile(name: String) { fileList.setSelectedValue(files.items.first { it.name == name }, true) }
    internal fun goForTest(name: String) = go(files.items.first { it.name == name }.path)
    internal val prefRows: List<String> get() = (0 until prefsModel.rowCount).map { "${prefsModel.getValueAt(it, 0)}=${prefsModel.getValueAt(it, 2)}" }
    internal val tableNames: List<String> get() = (tableList.model as CollectionListModel<String>).items
    internal val gridColumns: List<String> get() = (0 until rowsModel.columnCount).map { rowsModel.getColumnName(it) }
    internal val gridRowCount: Int get() = rowsModel.rowCount
    internal val textShown: String get() = textArea.text
    internal val statusText: String get() = status.text.orEmpty()
    internal val databaseStatus: String get() = dbStatus.text.orEmpty()
    internal fun runSqlForTest(sql: String) { sqlField.text = sql; runSql() }
}
