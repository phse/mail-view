package app.heckit.ide.mailview

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_TINY
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.not
import com.intellij.ui.layout.selectedValueIs
import javax.swing.JCheckBox

/**
 * Settings | Tools | Mail View: Aussehen beim Öffnen; Kürzel für Antworten/Weiterleiten und die Zeile über dem Zitat
 * nur, solange diese Funktionen sichtbar sind (MailHtmlRenderer.SHOW_COMPOSE).
 */
class MailViewConfigurable : BoundConfigurable(MailViewBundle.message("settings.title")) {
    override fun createPanel(): DialogPanel = panel {
        row(MailViewBundle.message("settings.appearance")) {
            comboBox(MailViewSettings.Appearance.entries, textListCellRenderer { MailViewBundle.message("settings.appearance." + it!!.name.lowercase()) })
                .bindItem(MailViewSettings::appearance.toNullableProperty())
        }
        lateinit var remember: Cell<JCheckBox>
        row {
            remember = checkBox(MailViewBundle.message("settings.rememberTab")).bindSelected(MailViewSettings::rememberTab)
        }
        row(MailViewBundle.message("settings.startTab")) {
            comboBox(MailViewSettings.StartTab.entries, textListCellRenderer { MailViewBundle.message("settings.startTab." + it!!.name.lowercase()) })
                .bindItem(MailViewSettings::startTab.toNullableProperty())
                .comment(MailViewBundle.message("settings.startTab.comment"))
        }.enabledIf(remember.selected.not())
        row {
            checkBox(MailViewBundle.message("settings.preferText")).bindSelected(MailViewSettings::preferText)
                .comment(MailViewBundle.message("settings.preferText.comment"))
        }
        group(MailViewBundle.message("settings.remote")) {
            lateinit var mode: Cell<ComboBox<MailViewSettings.RemoteContent>>
            row(MailViewBundle.message("settings.remote.mode")) {
                mode = comboBox(MailViewSettings.RemoteContent.entries,
                    textListCellRenderer { MailViewBundle.message("settings.remote." + it!!.name.lowercase()) })
                    .bindItem(MailViewSettings::remoteContent.toNullableProperty())
            }
            row(MailViewBundle.message("settings.trusted")) {
                textArea().bindText(MailViewSettings::trustedSenders).rows(4).align(AlignX.FILL)
                    .comment(MailViewBundle.message("settings.trusted.comment"))
            }.enabledIf(mode.component.selectedValueIs(MailViewSettings.RemoteContent.TRUSTED))
        }
        group(MailViewBundle.message("settings.attachments")) {
            row {
                checkBox(MailViewBundle.message("settings.expandAttachments")).bindSelected(MailViewSettings::expandAttachments)
            }
            row {
                checkBox(MailViewBundle.message("settings.revealUnknown")).bindSelected(MailViewSettings::revealUnknown)
                    .comment(MailViewBundle.message("settings.revealUnknown.comment"))
            }
        }
        if (MailHtmlRenderer.SHOW_COMPOSE) group(MailViewBundle.message("settings.compose")) {
            row(MailViewBundle.message("settings.replyPrefix")) {
                textField().bindText(MailViewSettings::replyPrefix).columns(COLUMNS_TINY)
            }
            row(MailViewBundle.message("settings.forwardPrefix")) {
                textField().bindText(MailViewSettings::forwardPrefix).columns(COLUMNS_TINY)
            }
            row(MailViewBundle.message("settings.quoteHeader")) {
                textField().bindText(MailViewSettings::quoteHeader).align(AlignX.FILL)
                    .comment(MailViewBundle.message("settings.quoteHeader.comment"))
            }
        }
    }
}
