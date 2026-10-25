package app.heckit.ide.mailview

import com.intellij.DynamicBundle
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import java.net.URLEncoder
import java.text.DateFormat

/**
 * Antworten, Allen antworten und Weiterleiten über das Mailprogramm des Systems (mailto:). Der Text der Mail wird
 * zitiert (ganz oder nur der markierte Teil) und gekürzt, weil mailto-Links nicht beliebig lang sein dürfen.
 * Anhänge kann mailto nicht mitgeben: Auf Wunsch liegen sie in einem Ordner bereit, um sie in die neue Mail zu ziehen.
 */
object Compose {
    private const val MAX_QUOTE = 1800

    fun open(project: Project, mail: ParsedMail, action: String, selection: String) {
        val link = link(mail, action, selection, MailViewSettings.replyPrefix, MailViewSettings.forwardPrefix, MailViewSettings.quoteHeader) ?: return
        // Nur beim Weiterleiten: Antworten ohne Anhänge ist üblich.
        val attachments = if (action == "forward") mail.attachments + MailHtmlRenderer.unreferencedInline(mail) else emptyList()
        if (attachments.isNotEmpty()) {
            val answer = Messages.showYesNoCancelDialog(project,
                MailViewBundle.message("action.attachments.question", attachments.size),
                MailViewBundle.message("action.attachments.title"),
                MailViewBundle.message("action.attachments.yes"), MailViewBundle.message("action.attachments.no"),
                Messages.getCancelButton(), Messages.getQuestionIcon())
            if (answer == Messages.CANCEL) return
            if (answer == Messages.YES) {
                val dir = FileUtil.createTempDirectory("mail-view-attachments", null, true)
                attachments.forEach { MailExport.unique(dir, it.name).writeBytes(it.data) }
                RevealFileAction.openDirectory(dir)
            }
        }
        BrowserUtil.browse(link)
    }

    internal fun link(mail: ParsedMail, action: String, selection: String, replyPrefix: String, forwardPrefix: String, quoteTemplate: String): String? {
        val subject = mail.subject ?: ""
        val sender = (mail.replyTo.ifEmpty { mail.from }).mapNotNull { it.address }
        return when (action) {
            "reply" -> mailto(sender, emptyList(), prefixed(replyPrefix, subject, reply = true), quote(mail, selection, quoteTemplate))
            "replyAll" -> {
                val others = (mail.to + mail.cc).mapNotNull { it.address }.filter { a -> sender.none { it.equals(a, ignoreCase = true) } }
                mailto(sender, others.distinctBy { it.lowercase() }, prefixed(replyPrefix, subject, reply = true), quote(mail, selection, quoteTemplate))
            }
            "forward" -> mailto(emptyList(), emptyList(), prefixed(forwardPrefix, subject, reply = false), forwarded(mail, selection))
            else -> null
        }
    }

    /** Kein zweites „Re:“ vor „Re: …“; gängige Kürzel (AW:, WG:, Fw:) und das eigene zählen mit. */
    private fun prefixed(prefix: String, subject: String, reply: Boolean): String {
        val known = (if (reply) listOf("re:", "aw:") else listOf("fwd:", "fw:", "wg:")) + prefix.trim().lowercase()
        return if (known.any { it.isNotEmpty() && subject.trimStart().lowercase().startsWith(it) }) subject else "${prefix.trim()} $subject".trim()
    }

    /** Markierter Text, sonst die ganze Mail. */
    private fun text(mail: ParsedMail, selection: String): String =
        (selection.ifEmpty { null } ?: mail.text ?: StringUtil.stripHtml(mail.html ?: "", true)).replace("\r\n", "\n").trim().let {
            if (it.length > MAX_QUOTE) it.take(MAX_QUOTE) + "\n[…]" else it
        }

    private fun quote(mail: ParsedMail, selection: String, template: String): String {
        val intro = quoteHeader(mail, template)
        return "\n\n" + (listOf(intro) + text(mail, selection).lines()).joinToString("\n") { "> $it" }
    }

    /** „Am 25.10.2026 um 10:35 schrieb Name <adresse>:“ - Vorlage aus den Einstellungen. */
    internal fun quoteHeader(mail: ParsedMail, template: String): String {
        val locale = DynamicBundle.getLocale()
        val sender = mail.from.firstOrNull()?.let { a -> listOfNotNull(a.name, a.address?.let { if (a.name != null) "<$it>" else it }).joinToString(" ") } ?: ""
        // Ohne Datum in der Mail (manche Entwürfe, selbst gebaute Mails) nur der Absender, sonst stünde „Am  um “ da.
        val effective = if (mail.date == null) MailViewBundle.message("settings.quoteHeader.noDate") else template
        return effective
            .replace("{date}", mail.date?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(it) } ?: "")
            .replace("{time}", mail.date?.let { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(it) } ?: "")
            .replace("{sender}", sender)
    }

    private fun forwarded(mail: ParsedMail, selection: String): String {
        fun list(a: List<MailAddress>) = a.joinToString(", ") { listOfNotNull(it.name, it.address?.let { "<$it>" }).joinToString(" ") }
        val head = listOfNotNull(
            "---------- Forwarded message ----------",
            "From: ${list(mail.from)}",
            mail.date?.let { "Date: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) },
            "Subject: ${mail.subject ?: ""}",
            "To: ${list(mail.to)}".takeIf { mail.to.isNotEmpty() },
            "Cc: ${list(mail.cc)}".takeIf { mail.cc.isNotEmpty() },
        )
        return "\n\n" + head.joinToString("\n") + "\n\n" + text(mail, selection)
    }

    private fun mailto(to: List<String>, cc: List<String>, subject: String, body: String): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        val params = listOfNotNull(
            cc.takeIf { it.isNotEmpty() }?.let { "cc=" + enc(it.joinToString(",")) },
            "subject=" + enc(subject),
            "body=" + enc(body),
        )
        return "mailto:" + to.joinToString(",") { enc(it).replace("%40", "@") } + "?" + params.joinToString("&")
    }
}
