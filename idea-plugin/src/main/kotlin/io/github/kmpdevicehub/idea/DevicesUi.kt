package io.github.kmpdevicehub.idea

import com.intellij.ui.JBColor
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.kmpdevicehub.core.Device
import io.github.kmpdevicehub.core.DeviceKind
import io.github.kmpdevicehub.core.DevicePlatform
import io.github.kmpdevicehub.core.DeviceState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/** One row of the device list. */
internal sealed class Row {
    class Header(val title: String, val count: Int) : Row()
    class Item(val device: Device) : Row()
}

internal object DeviceColors {
    val android = JBColor(Color(0x2E7D32), Color(0x7BC67E))
    val ios = JBColor(Color(0x455A64), Color(0xB0BEC5))
    val running = JBColor(Color(0x2E9D4F), Color(0x5FD38D))
    val booting = JBColor(Color(0xC28A00), Color(0xF2C14E))
    val problem = JBColor(Color(0xD32F2F), Color(0xFF6E6E))
    val stopped = JBColor(Color(0x9AA0A6), Color(0x6F737A))

    fun of(state: DeviceState): Color = when (state) {
        DeviceState.RUNNING -> running
        DeviceState.BOOTING -> booting
        DeviceState.UNAUTHORIZED, DeviceState.OFFLINE -> problem
        DeviceState.STOPPED -> stopped
    }

    fun label(state: DeviceState) = when (state) {
        DeviceState.RUNNING -> "Running"
        DeviceState.BOOTING -> "Booting…"
        DeviceState.UNAUTHORIZED -> "Allow USB debugging on the device"
        DeviceState.OFFLINE -> "Offline"
        DeviceState.STOPPED -> "Stopped"
    }
}

/** A small rounded tag, e.g. "Android" or "iOS". */
internal class Badge(var text: String = "", var color: Color = DeviceColors.android) : JComponent() {
    init {
        font = JBFont.small()
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(10), fm.height + JBUI.scale(2))
    }

    override fun getMinimumSize() = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            g2.color = Color(color.red, color.green, color.blue, 30)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = color
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.font = font
            val fm = g2.fontMetrics
            g2.drawString(text, JBUI.scale(5), (height - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}

/** A filled circle showing device state. */
internal class StatusDot : JComponent() {
    var color: Color = DeviceColors.stopped

    override fun getPreferredSize() = Dimension(JBUI.scale(10), JBUI.scale(10))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val d = JBUI.scale(8)
            g2.fillOval((width - d) / 2, (height - d) / 2, d, d)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * Renderer root that lays out its subtree when sized; CellRendererPane only validates components in a displayable
 * window, so off-screen painting (snapshots) would otherwise show empty rows.
 */
internal class SelfLayingPanel(layout: java.awt.LayoutManager) : JPanel(layout) {
    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        val resized = width != this.width || height != this.height
        super.setBounds(x, y, width, height)
        if (resized || !isValid) layoutTree(this)
    }

    private fun layoutTree(c: java.awt.Container) {
        c.doLayout()
        c.components.forEach { if (it is java.awt.Container) layoutTree(it) }
    }
}

internal class DeviceRenderer : ListCellRenderer<Row> {
    private val dot = StatusDot()
    private val nameLabel = JLabel().apply { font = JBFont.label().asBold() }
    private val platform = Badge()
    private val kindLabel = JLabel().apply { font = JBFont.small() }
    private val details = JLabel().apply { font = JBFont.small() }

    private val card = SelfLayingPanel(BorderLayout(JBUI.scale(10), 0)).apply {
        border = JBUI.Borders.empty(7, 12)
        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false; add(nameLabel); add(platform); add(kindLabel) }
        val center = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(top, BorderLayout.NORTH)
            add(details, BorderLayout.CENTER)
        }
        add(JPanel(BorderLayout()).apply { isOpaque = false; add(dot, BorderLayout.CENTER) }, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
    }

    private val header = JLabel().apply {
        font = JBFont.small().asBold()
        border = JBUI.Borders.empty(10, 12, 4, 12)
    }

    override fun getListCellRendererComponent(list: JList<out Row>, value: Row, index: Int, selected: Boolean, focused: Boolean): Component {
        if (value is Row.Header) {
            header.text = "${value.title}  ${value.count}"
            header.foreground = UIUtil.getContextHelpForeground()
            return header
        }
        val device = (value as Row.Item).device
        card.background = if (selected) RenderingUtil.getSelectionBackground(list) else list.background
        card.isOpaque = true
        val fg = if (selected) RenderingUtil.getSelectionForeground(list) else list.foreground
        val muted = if (selected) fg else UIUtil.getContextHelpForeground()
        nameLabel.text = device.name
        nameLabel.foreground = fg
        platform.text = if (device.platform == DevicePlatform.ANDROID) "Android" else "iOS"
        platform.color = if (device.platform == DevicePlatform.ANDROID) DeviceColors.android else DeviceColors.ios
        kindLabel.text = when (device.kind) {
            DeviceKind.PHYSICAL -> "Device"
            DeviceKind.EMULATOR -> "Emulator"
            DeviceKind.SIMULATOR -> "Simulator"
        }
        kindLabel.foreground = muted
        dot.color = DeviceColors.of(device.state)
        details.text = "${device.osLabel}  ·  ${DeviceColors.label(device.state)}"
        details.foreground = if (device.state == DeviceState.UNAUTHORIZED || device.state == DeviceState.OFFLINE) DeviceColors.problem else muted
        return card
    }
}

/** The selected device above the tabs, so it's always clear which device the actions apply to. */
internal class DeviceHeader : JPanel(BorderLayout(JBUI.scale(10), 0)) {
    private val dot = StatusDot()
    private val nameLabel = JLabel().apply { font = JBFont.h4().asBold() }
    private val platform = Badge()
    private val kindLabel = JLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }
    private val details = JLabel().apply { font = JBFont.small() }
    val powerButton = javax.swing.JButton()
    val screenshotButton = javax.swing.JButton(com.intellij.icons.AllIcons.FileTypes.Image).apply { toolTipText = "Screenshot (saved and copied to the clipboard)" }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12))
        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false; add(nameLabel); add(platform); add(kindLabel) }
        val center = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(top, BorderLayout.NORTH)
            add(details, BorderLayout.CENTER)
        }
        add(JPanel(BorderLayout()).apply { isOpaque = false; add(dot, BorderLayout.CENTER) }, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false; add(powerButton); add(screenshotButton) }, BorderLayout.EAST)
        show(null)
    }

    val title: String get() = nameLabel.text.orEmpty()
    val powerText: String get() = powerButton.text.orEmpty()

    fun show(device: Device?) {
        val visible = device != null
        listOf(platform, kindLabel, dot, powerButton, screenshotButton).forEach { it.isVisible = visible }
        if (device == null) {
            nameLabel.text = "No device selected"
            nameLabel.foreground = UIUtil.getContextHelpForeground()
            details.text = "Pick an emulator, phone or simulator from the list."
            details.foreground = UIUtil.getContextHelpForeground()
            return
        }
        nameLabel.text = device.name
        nameLabel.foreground = UIUtil.getLabelForeground()
        platform.text = if (device.platform == DevicePlatform.ANDROID) "Android" else "iOS"
        platform.color = if (device.platform == DevicePlatform.ANDROID) DeviceColors.android else DeviceColors.ios
        kindLabel.text = when (device.kind) {
            DeviceKind.PHYSICAL -> "Device"
            DeviceKind.EMULATOR -> "Emulator"
            DeviceKind.SIMULATOR -> "Simulator"
        }
        dot.color = DeviceColors.of(device.state)
        details.text = "${device.osLabel}  ·  ${DeviceColors.label(device.state)}"
        details.foreground = if (device.state == DeviceState.UNAUTHORIZED || device.state == DeviceState.OFFLINE) DeviceColors.problem else UIUtil.getContextHelpForeground()
        val canStart = device.state == DeviceState.STOPPED && (device.platform == DevicePlatform.IOS || device.kind == DeviceKind.EMULATOR)
        val canStop = device.isRunning && device.kind != DeviceKind.PHYSICAL
        powerButton.isVisible = canStart || canStop
        powerButton.text = if (canStart) "Start" else "Shut down"
        powerButton.icon = if (canStart) com.intellij.icons.AllIcons.Actions.Execute else com.intellij.icons.AllIcons.Actions.Suspend
        screenshotButton.isVisible = device.isRunning
    }
}
