package app.heckit.ide.mailview

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeUtility
import java.security.MessageDigest

/**
 * Vergleichsfassung einer Mail: Kopfzeilen entfaltet, RFC-2047-Wörter dekodiert und nach Namen sortiert, danach Text,
 * HTML und Anhänge dekodiert. Transfer-Kodierung (base64, quoted-printable), MIME-Grenzen und die Reihenfolge der
 * Kopfzeilen tauchen so nicht als Unterschied auf.
 */
object MailNormalizer {
    fun normalize(mail: ParsedMail): String = buildString {
        append("=== Headers ===\n")
        headerLines(mail.headers).forEach { append(it).append('\n') }
        mail.text?.let { append("\n=== text/plain ===\n").append(clean(it)).append('\n') }
        mail.html?.let { append("\n=== text/html ===\n").append(clean(it)).append('\n') }
        if (mail.inlineParts.isNotEmpty()) {
            append("\n=== Inline ===\n")
            mail.inlineParts.entries.sortedBy { it.key }.forEach { (id, part) -> append(describe("cid:$id", part)).append('\n') }
        }
        if (mail.attachments.isNotEmpty()) {
            append("\n=== Attachments ===\n")
            mail.attachments.sortedBy { it.name }.forEach { append(describe(it.name, it)).append('\n') }
        }
    }

    /** Je Kopfzeile eine Zeile „Name: Wert“; stabil nach Namen sortiert, gleichnamige (Received) behalten ihre Reihenfolge. */
    internal fun headerLines(headers: String?): List<String> {
        if (headers.isNullOrBlank()) return emptyList()
        val unfolded = ArrayList<String>()
        for (line in StringUtil.convertLineSeparators(headers).split('\n')) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.isNotEmpty()) unfolded[unfolded.lastIndex] += " " + line.trim()
            else if (line.isNotBlank()) unfolded += line
        }
        return unfolded.mapNotNull { line ->
            val name = canonicalName(line.substringBefore(':').trim())
            val value = line.substringAfter(':', "").trim()
            // Transfer-Kodierung und MIME-Grenze sind Verpackung, kein Inhalt.
            if (name == "Content-Transfer-Encoding") return@mapNotNull null
            if (name == "Content-Type") return@mapNotNull name to contentType(value)
            val decoded = runCatching { MimeUtility.decodeText(value) }.getOrDefault(value)
            name to decoded.replace(Regex("\\s+"), " ")
        }.withIndex().sortedWith(compareBy({ it.value.first.lowercase() }, { it.index })).map { "${it.value.first}: ${it.value.second}" }
    }

    /** „text/plain; charset="UTF-8"“ und „text/plain; charset=utf-8“ gleich; boundary fällt weg. */
    private fun contentType(value: String): String = runCatching {
        val type = ContentType(value)
        val params = type.parameterList
        val names = params.names.toList().map { it.lowercase() }.filter { it != "boundary" }.sorted()
        (listOf(type.baseType.lowercase()) + names.map { n ->
            val v = params.get(n) ?: ""
            "$n=" + if (n == "charset") v.lowercase() else v
        }).joinToString("; ")
    }.getOrDefault(value)

    /** Schreibweise vereinheitlichen: „content-type“ und „Content-Type“ sind derselbe Header. */
    private fun canonicalName(name: String) = name.split('-').joinToString("-") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }

    private fun clean(text: String) = StringUtil.convertLineSeparators(text).lines().joinToString("\n") { it.trimEnd() }.trimEnd()

    private fun describe(name: String, part: MailAttachment): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(part.data).take(8).joinToString("") { "%02x".format(it) }
        return "$name  ${part.mimeType}  ${part.data.size} bytes  sha256:$hash"
    }
}

/** „Compare Mails“: zwei markierte Mails dekodiert im Diff-Viewer der IDE. */
class CompareMailsAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = mails(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (left, right) = mails(e) ?: return
        val texts = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<List<String>, Exception>({
                listOf(left, right).map { MailNormalizer.normalize(MailParser.parse(it.contentsToByteArray())) }
            }, MailViewBundle.message("compare.progress"), true, project)
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, MailViewBundle.message("preview.error", ex.message ?: ex.javaClass.simpleName),
                MailViewBundle.message("compare.title"))
            return
        }
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(MailViewBundle.message("compare.title"),
            factory.create(project, texts[0]), factory.create(project, texts[1]), left.presentableUrl, right.presentableUrl)
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun mails(e: AnActionEvent): Pair<VirtualFile, VirtualFile>? {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return null
        if (files.size != 2 || !files.all(::isComparableMail)) return null
        return files[0] to files[1]
    }

    private fun isComparableMail(file: VirtualFile) =
        !file.isDirectory && (isTextMail(file) || file.extension?.lowercase() in MSG_EXTENSIONS || isTnefFile(file))

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
