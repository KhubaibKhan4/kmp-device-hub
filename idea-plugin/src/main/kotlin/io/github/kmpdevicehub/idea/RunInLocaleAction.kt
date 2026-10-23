package io.github.kmpdevicehub.idea

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import io.github.kmpdevicehub.core.AppLanguage

/**
 * Relaunches the app in a language on the device selected in KMP Devices. Not in any menu: other plugins (KMP
 * Translations Hub's "Run in this locale") call it by id, passing the BCP 47 tag as [LOCALE] in the data context,
 * so neither plugin needs the other at compile time.
 */
class RunInLocaleAction : DumbAwareAction("Run App in Locale") {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(LOCALE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val language = AppLanguage.fromTag(e.getData(LOCALE) ?: return)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("KMP Devices") ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.contents.firstNotNullOfOrNull { it.component as? DevicesPanel } ?: return@activate
            if (!panel.runAppIn(language)) {
                NotificationGroupManager.getInstance().getNotificationGroup("KMP Device Hub")
                    .createNotification("Select a running device in KMP Devices to run the app in ${language.label}.", NotificationType.INFORMATION)
                    .notify(project)
            }
        }
    }

    companion object {
        const val ID = "KmpDeviceHub.RunInLocale"

        /** The language tag, e.g. `de`, `pt-BR`, `zh-Hans`. */
        val LOCALE: DataKey<String> = DataKey.create("kmpdevicehub.locale")
    }
}
