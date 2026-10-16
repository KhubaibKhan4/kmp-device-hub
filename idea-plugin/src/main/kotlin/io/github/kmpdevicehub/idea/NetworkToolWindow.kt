package io.github.kmpdevicehub.idea

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import io.github.kmpdevicehub.core.CallState
import io.github.kmpdevicehub.core.ConnectedApp
import io.github.kmpdevicehub.core.Json
import io.github.kmpdevicehub.core.NetworkCall
import io.github.kmpdevicehub.core.NetworkExport
import io.github.kmpdevicehub.core.NetworkRule
import io.github.kmpdevicehub.core.NetworkStats
import io.github.kmpdevicehub.core.RuleAction
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class NetworkToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val backend = ApplicationManager.getApplication().getService(NetworkInspectorService::class.java)
        backend.start()
        val panel = NetworkPanel(project, toolWindow.disposable, backend)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, null, false))
    }
}

/** Every HTTP call your app makes, with request/response details, a JSON tree, totals, throughput and connectivity. */
class NetworkPanel(
    private val project: Project,
    parent: Disposable,
    private val backend: InspectorBackend,
    private val isPro: () -> Boolean = ProLicense::isLicensed,
    private val upsell: ((feature: String) -> Unit)? = null,
    private val autoRefresh: Boolean = true,
) : JPanel(BorderLayout()) {
    private val model = CallsModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        autoCreateRowSorter = false
        columnModel.getColumn(0).preferredWidth = JBUI.scale(70)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(60)
        columnModel.getColumn(2).preferredWidth = JBUI.scale(320)
        columnModel.getColumn(3).preferredWidth = JBUI.scale(160)
        columnModel.getColumn(4).preferredWidth = JBUI.scale(70)
        columnModel.getColumn(5).preferredWidth = JBUI.scale(70)
        columnModel.getColumn(6).preferredWidth = JBUI.scale(180)
        setDefaultRenderer(Any::class.java, CallRenderer())
    }
    private val filterField = SearchTextField(false).apply { textEditor.emptyText.text = "Filter by URL, method or status" }
    private val appBox = ComboBox<Any>(DefaultComboBoxModel<Any>(arrayOf<Any>(ALL_APPS))).apply {
        renderer = textRenderer<Any> { (it as? ConnectedApp)?.label ?: it?.toString() }
    }
    private var errorsOnly = false

    private val overview = detailArea()
    private val requestArea = detailArea()
    private val responseArea = detailArea()
    private val jsonTree = Tree(DefaultTreeModel(DefaultMutableTreeNode("No JSON"))).apply { isRootVisible = false }
    private val details = JBTabbedPane().apply {
        addTab("Overview", ScrollPaneFactory.createScrollPane(overview, true))
        addTab("Request", ScrollPaneFactory.createScrollPane(requestArea, true))
        addTab("Response", ScrollPaneFactory.createScrollPane(responseArea, true))
        addTab("JSON", ScrollPaneFactory.createScrollPane(jsonTree, true))
    }

    private val listening = JBLabel().apply { font = JBFont.small() }
    private val stats = JBLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }
    private val sparkline = Sparkline()
    private val connectivity = JBLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }

    private val uiAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private val pollAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)

    @Volatile
    internal var lastConnectivity: String = ""
        private set

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar("KmpNetworkToolbar", DefaultActionGroup(
            object : DumbAwareToggleAction("Pause Capture", "Stop recording new calls", AllIcons.Actions.Pause) {
                override fun isSelected(e: AnActionEvent) = backend.log.paused
                override fun setSelected(e: AnActionEvent, state: Boolean) { backend.log.paused = state; refreshSoon() }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            action("Clear", "Remove all captured calls", AllIcons.Actions.GC) { backend.log.clear() },
            object : DumbAwareToggleAction("Errors Only", "Show only failed calls and 4xx/5xx responses", AllIcons.General.Error) {
                override fun isSelected(e: AnActionEvent) = errorsOnly
                override fun setSelected(e: AnActionEvent, state: Boolean) { errorsOnly = state; refreshNow() }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            Separator.getInstance(),
            callAction("Copy as cURL", "Copy the selected call as a cURL command", AllIcons.Actions.Copy) { copy(NetworkExport.curl(it)) },
            action("Mock & Fail Rules…", "Mock responses, fail requests or add latency (Pro)", AllIcons.Actions.Edit) { editRules(null) },
            action("Export HAR…", "Save the captured calls as a HAR file (Pro)", AllIcons.ToolbarDecorator.Export) { exportHar() },
        ), true).apply { targetComponent = this@NetworkPanel }

        val filters = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(filterField.apply { preferredSize = Dimension(JBUI.scale(260), preferredSize.height) })
            add(appBox)
        }
        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(filters, BorderLayout.CENTER)
        }

        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showDetails() }
        PopupHandler.installPopupMenu(table, DefaultActionGroup(
            callAction("Copy as cURL", null, AllIcons.Actions.Copy) { copy(NetworkExport.curl(it)) },
            callAction("Copy URL", null, null) { copy(it.url) },
            callAction("Copy Response Body", null, null, { it.responseBody != null }) { copy(it.responseBody!!) },
            Separator.getInstance(),
            callAction("Mock This Response…", "Return this response (or an edited one) without calling the server (Pro)", null) { mockFrom(it) },
            callAction("Fail This Request", "Make this request fail like a lost connection (Pro)", null) { failFrom(it) },
        ), "KmpNetworkPopup")

        filterField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = refreshNow()
        })
        appBox.addActionListener { refreshNow() }

        val splitter = OnePixelSplitter(false, 0.6f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(table, true)
            secondComponent = details
        }
        val status = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(3))).apply {
            add(listening); add(stats); add(sparkline); add(connectivity)
            border = JBUI.Borders.customLineTop(JBColor.border())
        }
        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        val unsubscribe = backend.log.addListener { refreshSoon() }
        Disposer.register(parent) { unsubscribe() }
        refreshNow()
        showDetails()
        if (autoRefresh) pollConnectivity()
    }

    // ---- Refresh -------------------------------------------------------------------------------------------

    private fun refreshSoon() {
        uiAlarm.cancelAllRequests()
        uiAlarm.addRequest({ refreshNow() }, 150)
    }

    private fun refreshNow() {
        val all = backend.log.snapshot()
        val apps = backend.log.apps.toList()
        val chosen = appBox.selectedItem
        if ((0 until appBox.itemCount).map { appBox.getItemAt(it) } != listOf<Any>(ALL_APPS) + apps) {
            appBox.model = DefaultComboBoxModel((listOf<Any>(ALL_APPS) + apps).toTypedArray())
            appBox.selectedItem = if (chosen is ConnectedApp && chosen in apps) chosen else ALL_APPS
        }
        val app = appBox.selectedItem as? ConnectedApp
        val query = filterField.text.trim()
        val shown = all.filter { call ->
            (app == null || call.app.connection == app.connection) &&
                (!errorsOnly || call.isError) &&
                (query.isEmpty() || query.split(' ').filter { it.isNotEmpty() }.all { term ->
                    call.url.contains(term, true) || call.method.equals(term, true) || call.status?.toString() == term
                })
        }
        val selectedId = selected()?.id
        model.update(shown)
        selectedId?.let { id -> shown.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { table.selectionModel.setSelectionInterval(it, it) } }
        if (selectedId != null && selected()?.id == selectedId) showDetails()

        listening.text = when {
            !backend.isListening -> "Not listening on port ${backend.port} (another IDE window may be using it)"
            apps.isEmpty() -> "● Listening on port ${backend.port} · no app connected"
            else -> "● Listening · ${apps.size} app${if (apps.size == 1) "" else "s"} connected"
        }
        listening.foreground = if (apps.isNotEmpty()) DeviceColors.running else UIUtil.getContextHelpForeground()
        val s = NetworkStats.of(all)
        stats.text = buildString {
            append("${s.requests} calls")
            if (s.errors > 0) append(" · ${s.errors} errors")
            if (s.pending > 0) append(" · ${s.pending} pending")
            s.averageMs?.let { append(" · avg $it ms") }
            append(" · ↓ ${NetworkStats.bytes(s.bytesIn)} ↑ ${NetworkStats.bytes(s.bytesOut)}")
        }
        sparkline.values = s.perSecond
        sparkline.toolTipText = "Traffic per second, last minute (peak ${NetworkStats.bytes(s.perSecond.maxOrNull() ?: 0)}/s)"

        table.emptyText.clear()
        when {
            !backend.isListening -> table.emptyText.appendText("The inspector isn't listening: port ${backend.port} is in use.")
            apps.isEmpty() -> {
                table.emptyText.appendText("No app connected")
                table.emptyText.appendSecondaryText("Add the devicehub-network library to your app. ", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
                table.emptyText.appendSecondaryText("Copy setup code", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { copy(SETUP_SNIPPET) }
            }
            all.isEmpty() -> table.emptyText.appendText("Waiting for your app's first call…")
            else -> table.emptyText.appendText("No calls match the filter")
        }
    }

    /** Connectivity of running devices, every 15 seconds while the window is visible. */
    private fun pollConnectivity() {
        pollAlarm.addRequest({
            if (isShowing) {
                val text = runCatching {
                    backend.runningDevices().take(3).joinToString("   ") { d -> "${d.name}: ${backend.connectivity(d).detail}" }
                }.getOrDefault("")
                lastConnectivity = text
                ApplicationManager.getApplication().invokeLater({ connectivity.text = text }, ModalityState.any())
            }
            pollConnectivity()
        }, if (lastConnectivity.isEmpty()) 500 else 15_000)
    }

    // ---- Details ------------------------------------------------------------------------------------------

    private fun selected(): NetworkCall? = table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(it) }

    private fun showDetails() {
        val call = selected()
        if (call == null) {
            overview.text = "Select a call to see its details."
            requestArea.text = ""
            responseArea.text = ""
            jsonTree.model = DefaultTreeModel(DefaultMutableTreeNode("No JSON"))
            return
        }
        overview.text = buildString {
            appendLine("${call.method} ${call.url}")
            appendLine()
            appendLine("Status    " + when (call.state) {
                CallState.PENDING -> "waiting for the response…"
                CallState.FAILED -> "failed: ${call.error}"
                CallState.DONE -> "${call.status} ${reason(call.status)}"
            })
            call.durationMs?.let { appendLine("Time      $it ms") }
            appendLine("Sent      ${NetworkStats.bytes(call.requestSize)}")
            appendLine("Received  ${NetworkStats.bytes(call.responseSize)}")
            appendLine("Started   ${TIME.format(Date(call.startedAt))}")
            appendLine("App       ${call.app.label}")
            call.rule?.let { id -> appendLine("Rule      " + (backend.rules.firstOrNull { it.id == id }?.summary ?: "a mock/fail rule")) }
        }
        requestArea.text = headersAndBody(call.requestHeaders, call.requestBody, "No request body")
        responseArea.text = when (call.state) {
            CallState.PENDING -> "Waiting for the response…"
            CallState.FAILED -> "The call failed:\n${call.error}"
            CallState.DONE -> headersAndBody(call.responseHeaders, call.responseBody, "Empty response")
        }
        listOf(overview, requestArea, responseArea).forEach { it.caretPosition = 0 }
        val parsed = call.responseBody?.trim()?.takeIf { it.startsWith("{") || it.startsWith("[") }?.let { runCatching { Json.parse(it) }.getOrNull() }
        val root = DefaultMutableTreeNode("response")
        if (parsed != null) addJson(root, null, parsed) else root.add(DefaultMutableTreeNode("The response isn't JSON"))
        jsonTree.model = DefaultTreeModel(root)
        TreeUtil.expand(jsonTree, 2)
    }

    private fun headersAndBody(headers: List<Pair<String, String>>, body: String?, empty: String) = buildString {
        headers.forEach { (k, v) -> appendLine("$k: $v") }
        if (headers.isNotEmpty()) appendLine()
        append(body?.let { Json.pretty(it) ?: it } ?: empty)
    }

    private fun addJson(parent: DefaultMutableTreeNode, key: String?, value: Any?) {
        val prefix = key?.let { "$it: " }.orEmpty()
        when (value) {
            is Map<*, *> -> DefaultMutableTreeNode("$prefix{${value.size}}").also { node ->
                parent.add(node)
                value.forEach { (k, v) -> addJson(node, k.toString(), v) }
            }
            is List<*> -> DefaultMutableTreeNode("$prefix[${value.size}]").also { node ->
                parent.add(node)
                value.forEachIndexed { i, v -> addJson(node, i.toString(), v) }
            }
            is String -> parent.add(DefaultMutableTreeNode(prefix + Json.quote(value)))
            else -> parent.add(DefaultMutableTreeNode(prefix + value.toString()))
        }
    }

    // ---- Pro: rules and HAR ----------------------------------------------------------------------------------

    private fun requirePro(feature: String): Boolean {
        if (isPro()) return true
        upsell?.invoke(feature) ?: ProLicense.showUpsell(project, feature, this)
        return false
    }

    private fun editRules(add: NetworkRule?) {
        if (!requirePro("Mock & fail rules")) return
        val dialog = RulesDialog(project, backend.rules, add)
        if (dialog.showAndGet()) backend.rules = dialog.result
    }

    internal fun mockFrom(call: NetworkCall) = editRules(ruleFrom(call, RuleAction.MOCK))

    internal fun failFrom(call: NetworkCall) {
        if (!requirePro("Mock & fail rules")) return
        backend.rules = backend.rules + ruleFrom(call, RuleAction.FAIL)
    }

    private fun ruleFrom(call: NetworkCall, action: RuleAction) = NetworkRule(
        id = UUID.randomUUID().toString().take(8),
        urlContains = call.path.substringBefore('?'),
        method = call.method,
        action = action,
        status = call.status ?: 200,
        body = call.responseBody?.takeUnless { it.startsWith("(") && it.endsWith(")") }.orEmpty(),
        contentType = call.responseType ?: "application/json",
    )

    private fun exportHar() {
        if (!requirePro("HAR export")) return
        val calls = model.rows.ifEmpty { backend.log.snapshot() }
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Export HAR", "Save the captured calls as an HTTP Archive", "har"), project)
            .save("network-${SimpleDateFormat("yyyy-MM-dd-HHmmss").format(Date())}.har") ?: return
        target.file.writeText(NetworkExport.har(calls))
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun action(text: String, description: String?, icon: javax.swing.Icon?, perform: () -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = perform()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    /** An action on the selected call, disabled when [enabled] says no (by default: when nothing is selected). */
    private fun callAction(text: String, description: String?, icon: javax.swing.Icon?, enabled: (NetworkCall) -> Boolean = { true }, perform: (NetworkCall) -> Unit) =
        object : DumbAwareAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) { selected()?.let(perform) }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = selected()?.let(enabled) == true }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    // ---- Table ----------------------------------------------------------------------------------------------

    private class CallsModel : AbstractTableModel() {
        var rows: List<NetworkCall> = emptyList()
            private set

        fun update(calls: List<NetworkCall>) {
            rows = calls
            fireTableDataChanged()
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = COLUMNS[column]
        override fun getValueAt(row: Int, column: Int): Any = rows[row]
    }

    private inner class CallRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val call = value as? NetworkCall ?: return
            when (column) {
                0 -> {
                    val (text, color) = when {
                        call.state == CallState.PENDING -> "…" to UIUtil.getContextHelpForeground()
                        call.state == CallState.FAILED -> "failed" to DeviceColors.problem
                        (call.status ?: 0) >= 500 -> call.status.toString() to DeviceColors.problem
                        (call.status ?: 0) >= 400 -> call.status.toString() to JBColor(0xC77700, 0xE5A84B)
                        (call.status ?: 0) >= 300 -> call.status.toString() to JBColor(0x2F6BD8, 0x6FA0F6)
                        else -> call.status.toString() to DeviceColors.running
                    }
                    append(text, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                    if (call.rule != null) append(" ⚑", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                1 -> append(call.method)
                2 -> append(call.path)
                3 -> append(call.host, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                4 -> append(call.durationMs?.let { "$it ms" } ?: "", if ((call.durationMs ?: 0) > 1000) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                5 -> append(if (call.state == CallState.DONE) NetworkStats.bytes(call.responseSize) else "")
                6 -> append(call.app.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            toolTipText = if (column == 2) call.url else null
        }
    }

    /** Bars for bytes per second over the last minute. */
    private class Sparkline : JComponent() {
        var values: LongArray = LongArray(NetworkStats.SECONDS)
            set(value) { field = value; repaint() }

        init { preferredSize = Dimension(JBUI.scale(120), JBUI.scale(16)) }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
            val barWidth = width.toDouble() / values.size
            g2.color = JBColor.border()
            g2.drawLine(0, height - 1, width, height - 1)
            g2.color = JBColor(0x3D7EF0, 0x5B9BF8)
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = ((v.toDouble() / max) * (height - 2)).toInt().coerceAtLeast(1)
                g2.fillRect((i * barWidth).toInt(), height - 1 - h, barWidth.toInt().coerceAtLeast(1), h)
            }
        }
    }

    companion object {
        private const val ALL_APPS = "All apps"
        private val COLUMNS = listOf("Status", "Method", "Path", "Host", "Time", "Size", "App")
        private val TIME = SimpleDateFormat("HH:mm:ss.SSS")

        internal val SETUP_SNIPPET = """
            // build.gradle.kts — commonMain (or debugImplementation in an Android-only app)
            implementation("io.github.khubaibkhan4:devicehub-network:0.1.0")

            // Ktor
            val client = HttpClient {
                install(DeviceHubNetwork) { enabled = isDebugBuild }
            }

            // OkHttp / Retrofit (Android)
            OkHttpClient.Builder().addInterceptor(DeviceHubInterceptor { enabled = BuildConfig.DEBUG })
        """.trimIndent()

        private fun detailArea() = JBTextArea().apply {
            isEditable = false
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBFont.label().size)
            border = JBUI.Borders.empty(6, 8)
        }

        private fun reason(status: Int?) = when (status) {
            200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
            301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 422 -> "Unprocessable Content"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
            else -> ""
        }
    }

    // ---- Test hooks ----------------------------------------------------------------------------------------

    internal val rowCount: Int get() = model.rowCount
    internal val listeningText: String get() = listening.text
    internal val statsText: String get() = stats.text
    internal val overviewText: String get() = overview.text
    internal val responseText: String get() = responseArea.text
    internal val jsonRootChildren: Int get() = (jsonTree.model.root as DefaultMutableTreeNode).let { r -> (r.firstChild as? DefaultMutableTreeNode)?.childCount ?: 0 }
    internal fun selectRow(index: Int) = table.selectionModel.setSelectionInterval(index, index)
    internal fun setFilter(text: String) { filterField.text = text; refreshNow() }
    internal fun setErrorsOnlyForTest(on: Boolean) { errorsOnly = on; refreshNow() }
    internal fun refreshForTest() = refreshNow()
    internal fun selectedCall() = selected()
}
