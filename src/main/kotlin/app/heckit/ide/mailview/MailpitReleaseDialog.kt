package app.heckit.ide.mailview

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Empfänger fürs Weiterleiten über den SMTP-Server, den Mailpit dafür eingerichtet hat. Vorgeschlagen werden die
 * Empfänger der Mail; erlaubte und gesperrte Adressen (Regex aus Mailpit) werden schon hier geprüft.
 */
class MailpitReleaseDialog(project: Project, private val relay: MailpitRelay, messages: List<MailpitMessage>) : DialogWrapper(project) {
    private val toField = JBTextField(messages.flatMap { it.recipients }.distinct().joinToString(", "))
    private val count = messages.size

    val recipients: List<String> get() = toField.text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    init {
        title = MailViewBundle.message("mailpit.release.title")
        setOKButtonText(MailViewBundle.message("mailpit.release.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(MailViewBundle.message("mailpit.release.intro", count, relay.smtpServer.ifEmpty { "SMTP" })) }
        row(MailViewBundle.message("mailpit.release.to")) {
            cell(toField).columns(COLUMNS_LARGE).comment(MailViewBundle.message("mailpit.release.to.comment"), maxLineLength = 60)
        }
        if (relay.overrideFrom.isNotEmpty()) row { comment(MailViewBundle.message("mailpit.release.from", relay.overrideFrom), maxLineLength = 60) }
        if (relay.allowedRecipients.isNotEmpty()) row { comment(MailViewBundle.message("mailpit.release.allowed", relay.allowedRecipients), maxLineLength = 60) }
    }

    override fun doValidate(): ValidationInfo? {
        val list = recipients
        if (list.isEmpty()) return ValidationInfo(MailViewBundle.message("mailpit.release.to.empty"), toField)
        list.firstOrNull { !ADDRESS.matches(it) }?.let { return ValidationInfo(MailViewBundle.message("mailpit.release.to.invalid", it), toField) }
        // Wie Mailpit (Go, MatchString: Teiltreffer, Groß-/Kleinschreibung zählt); was Java nicht versteht, prüft nur Mailpit.
        val allowed = relay.allowedRecipients.toRegexOrNull()
        list.firstOrNull { allowed != null && !allowed.containsMatchIn(it) }
            ?.let { return ValidationInfo(MailViewBundle.message("mailpit.release.to.notAllowed", it), toField) }
        val blocked = relay.blockedRecipients.toRegexOrNull()
        list.firstOrNull { blocked != null && blocked.containsMatchIn(it) }
            ?.let { return ValidationInfo(MailViewBundle.message("mailpit.release.to.notAllowed", it), toField) }
        return null
    }

    private fun String.toRegexOrNull(): Regex? = takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it) }.getOrNull() }

    override fun getPreferredFocusedComponent(): JComponent = toField

    private companion object {
        val ADDRESS = Regex("""[^@\s,;<>]+@[^@\s,;<>]+""")
    }
}
