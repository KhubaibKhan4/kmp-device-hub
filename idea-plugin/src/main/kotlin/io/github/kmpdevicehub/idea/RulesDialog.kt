package io.github.kmpdevicehub.idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.github.kmpdevicehub.core.NetworkRule
import io.github.kmpdevicehub.core.RuleAction
import java.awt.Dimension
import java.util.UUID
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList

/**
 * Edits mock, fail and delay rules. They're sent to connected apps as soon as the dialog closes with OK; the first
 * enabled rule whose URL text (and method, if set) matches a call applies to it.
 */
class RulesDialog(project: Project, rules: List<NetworkRule>, add: NetworkRule? = null) : DialogWrapper(project) {
    private val model = CollectionListModel(rules + listOfNotNull(add))
    private val list = JBList(model).apply {
        cellRenderer = object : ColoredListCellRenderer<NetworkRule>() {
            override fun customizeCellRenderer(list: JList<out NetworkRule>, value: NetworkRule?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                append(value.summary, if (value.enabled) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (!value.enabled) append("  off", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        emptyText.text = "No rules. Add one, or right-click a call → Mock This Response."
    }

    private val enabledBox = JBCheckBox("Enabled")
    private val urlField = JBTextField().apply { emptyText.text = "/orders" }
    private val methodBox = ComboBox(DefaultComboBoxModel(arrayOf("Any", "GET", "POST", "PUT", "PATCH", "DELETE")))
    private val actionBox = ComboBox<RuleAction>(DefaultComboBoxModel<RuleAction>(RuleAction.entries.toTypedArray())).apply {
        renderer = textRenderer<RuleAction> { (it as? RuleAction)?.label }
    }
    private val statusField = JBTextField(5)
    private val typeField = JBTextField()
    private val bodyArea = JBTextArea(8, 40).apply { font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size) }
    private val delayField = JBTextField(6)
    private val errorField = JBTextField()

    /** The row whose values the editor shows; written back before the selection moves. */
    private var editing = -1
    private var loading = false

    val result: List<NetworkRule> get() = save().let { model.items.toList() }

    init {
        title = "Mock & Fail Rules"
        init()
        list.addListSelectionListener { if (!it.valueIsAdjusting) { save(); load(list.selectedIndex) } }
        actionBox.addActionListener { if (!loading) updateEnabled() }
        list.selectedIndex = if (add != null) model.size - 1 else 0
        load(list.selectedIndex)
    }

    override fun createCenterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                save()
                model.add(NetworkRule(UUID.randomUUID().toString().take(8), "", body = "{}"))
                list.selectedIndex = model.size - 1
            }
            .setRemoveAction {
                val index = list.selectedIndex.takeIf { it >= 0 } ?: return@setRemoveAction
                editing = -1
                model.remove(index)
                list.selectedIndex = (index - 1).coerceAtLeast(0).coerceAtMost(model.size - 1)
                load(list.selectedIndex)
            }
            .disableUpDownActions()
            .createPanel()
        val editor = panel {
            row { cell(enabledBox) }
            row("URL contains:") { cell(urlField).align(AlignX.FILL).resizableColumn() }
            row("Method:") { cell(methodBox) }
            row("Action:") { cell(actionBox) }
            row("Status:") { cell(statusField) }
            row("Content type:") { cell(typeField).align(AlignX.FILL).resizableColumn() }
            row("Body:") { cell(ScrollPaneFactory.createScrollPane(bodyArea)).align(AlignX.FILL).resizableColumn() }
            row("Delay (ms):") { cell(delayField) }
            row("Error message:") { cell(errorField).align(AlignX.FILL).resizableColumn() }
            row { comment("Mock: the app gets this response without calling the server. Fail: the call throws like a lost connection. Delay: the call goes through, later.", maxLineLength = 60) }
        }
        return JBSplitter(false, 0.4f).apply {
            firstComponent = decorated
            secondComponent = editor.apply { border = JBUI.Borders.emptyLeft(10) }
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(460))
        }
    }

    private fun load(index: Int) {
        editing = index
        val rule = model.items.getOrNull(index)
        loading = true
        try {
            enabledBox.isSelected = rule?.enabled ?: true
            urlField.text = rule?.urlContains.orEmpty()
            methodBox.selectedItem = rule?.method?.takeIf { it.isNotBlank() }?.uppercase() ?: "Any"
            actionBox.selectedItem = rule?.action ?: RuleAction.MOCK
            statusField.text = (rule?.status ?: 200).toString()
            typeField.text = rule?.contentType ?: "application/json"
            bodyArea.text = rule?.body.orEmpty()
            delayField.text = (rule?.delayMs ?: 0).toString()
            errorField.text = rule?.error ?: "Simulated network failure"
        } finally {
            loading = false
        }
        listOf(enabledBox, urlField, methodBox, actionBox, statusField, typeField, bodyArea, delayField, errorField).forEach { it.isEnabled = rule != null }
        if (rule != null) updateEnabled()
    }

    private fun updateEnabled() {
        val action = actionBox.selectedItem as? RuleAction
        statusField.isEnabled = action == RuleAction.MOCK
        typeField.isEnabled = action == RuleAction.MOCK
        bodyArea.isEnabled = action == RuleAction.MOCK
        errorField.isEnabled = action == RuleAction.FAIL
    }

    private fun save() {
        val rule = model.items.getOrNull(editing) ?: return
        val updated = rule.copy(
            enabled = enabledBox.isSelected,
            urlContains = urlField.text.trim(),
            method = (methodBox.selectedItem as? String)?.takeIf { it != "Any" }.orEmpty(),
            action = actionBox.selectedItem as? RuleAction ?: RuleAction.MOCK,
            status = statusField.text.trim().toIntOrNull()?.coerceIn(100, 599) ?: rule.status,
            contentType = typeField.text.trim().ifEmpty { "application/json" },
            body = bodyArea.text,
            delayMs = delayField.text.trim().toLongOrNull()?.coerceIn(0, 120_000) ?: rule.delayMs,
            error = errorField.text.trim().ifEmpty { "Simulated network failure" },
        )
        if (updated != rule) model.setElementAt(updated, editing)
    }

    override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
        save()
        val blank = model.items.indexOfFirst { it.urlContains.isBlank() }
        return if (blank >= 0) com.intellij.openapi.ui.ValidationInfo("Every rule needs the URL text it matches (e.g. /orders).", urlField).also {
            if (list.selectedIndex != blank) list.selectedIndex = blank
        } else null
    }
}
