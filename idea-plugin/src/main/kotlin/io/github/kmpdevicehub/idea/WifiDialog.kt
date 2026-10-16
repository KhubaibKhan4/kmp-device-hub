package io.github.kmpdevicehub.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.kmpdevicehub.core.DeviceSettings
import javax.swing.JButton
import javax.swing.JComponent

/** Android 11+ wireless debugging: pair with a code, then connect. */
internal class WifiDialog(project: Project, private val settings: DeviceSettings, private val onConnected: () -> Unit) : DialogWrapper(project) {
    private val pairAddress = JBTextField().apply { emptyText.text = "192.168.1.20:37099" }
    private val pairCode = JBTextField().apply { emptyText.text = "123456"; columns = 8 }
    private val connectAddress = JBTextField().apply { emptyText.text = "192.168.1.20:41355" }
    private val status = JBLabel()

    init {
        title = "Wi-Fi Debugging"
        setOKButtonText("Close")
        init()
    }

    override fun createActions() = arrayOf(okAction)

    override fun createCenterPanel(): JComponent = panel {
        row {
            comment("On the phone: Settings → Developer options → Wireless debugging. The phone and this computer must be on the same Wi-Fi.")
        }
        group("1. Pair (first time only)") {
            row { comment("Tap \"Pair device with pairing code\" and enter what it shows.") }
            row("Pairing address:") { cell(pairAddress).align(AlignX.FILL) }
            row("Code:") { cell(pairCode) }
            row { cell(JButton("Pair").apply { addActionListener { act { settings.pair(pairAddress.text, pairCode.text) } } }) }
        }
        group("2. Connect") {
            row { comment("Use the IP address & port shown at the top of the Wireless debugging screen.") }
            row("Device address:") { cell(connectAddress).align(AlignX.FILL) }
            row { cell(JButton("Connect").apply { addActionListener { act(connected = true) { settings.connect(connectAddress.text) } } }) }
        }
        row { cell(status) }
    }

    private fun act(connected: Boolean = false, block: () -> io.github.kmpdevicehub.core.ActionResult) {
        status.text = "Working…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = block()
            ApplicationManager.getApplication().invokeLater({
                status.text = result.message
                status.foreground = if (result.ok) DeviceColors.running else DeviceColors.problem
                if (result.ok && connected) onConnected()
            }, ModalityState.any())
        }
    }
}
