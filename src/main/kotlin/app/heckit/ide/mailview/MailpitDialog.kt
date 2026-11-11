package app.heckit.ide.mailview

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.net.URI
import javax.swing.JComponent

/** Name, Adresse und Geltung einer Mailpit-Instanz, optional Basic Auth (Benutzer/Passwort). */
class MailpitDialog(project: Project, instance: MailpitInstance?, user: String, password: String) : DialogWrapper(project) {
    private val nameField = JBTextField(instance?.name.orEmpty())
    private val urlField = JBTextField(instance?.url.orEmpty()).apply { emptyText.text = MailpitSettings.DEFAULT_URL }
    private val globalBox = JBCheckBox(MailViewBundle.message("mailpit.configure.global"), instance?.global ?: false)
    private val userField = JBTextField(user)
    private val passwordField = JBPasswordField().apply { text = password }

    val name: String get() = nameField.text.trim().ifEmpty { MailpitInstances.hostOf(url) }
    val url: String get() = urlField.text.trim().trimEnd('/').ifEmpty { MailpitSettings.DEFAULT_URL }
    val global: Boolean get() = globalBox.isSelected
    val user: String get() = userField.text.trim()
    val password: String get() = String(passwordField.password)

    init {
        title = MailViewBundle.message(if (instance == null || instance.auto) "mailpit.add" else "mailpit.edit")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(MailViewBundle.message("mailpit.configure.name")) {
            cell(nameField).columns(COLUMNS_LARGE).comment(MailViewBundle.message("mailpit.configure.name.comment"))
        }
        row(MailViewBundle.message("mailpit.configure.url")) {
            cell(urlField).columns(COLUMNS_LARGE).comment(MailViewBundle.message("mailpit.configure.url.comment"), maxLineLength = 60)
        }
        row { cell(globalBox) }
        group(MailViewBundle.message("mailpit.configure.auth")) {
            row(MailViewBundle.message("mailpit.configure.user")) { cell(userField).columns(COLUMNS_LARGE) }
            row(MailViewBundle.message("mailpit.configure.password")) { cell(passwordField).columns(COLUMNS_LARGE) }
            row { comment(MailViewBundle.message("mailpit.configure.auth.comment"), maxLineLength = 60) }
        }
    }

    override fun doValidate(): ValidationInfo? {
        val uri = runCatching { URI(url) }.getOrNull()
        return if (uri?.scheme !in setOf("http", "https") || uri?.host == null)
            ValidationInfo(MailViewBundle.message("mailpit.configure.url.invalid"), urlField) else null
    }

    override fun getPreferredFocusedComponent(): JComponent = urlField
}
