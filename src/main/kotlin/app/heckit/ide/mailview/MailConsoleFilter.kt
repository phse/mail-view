package app.heckit.ide.mailview

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile

class MailConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(MailConsoleFilter())
}

/**
 * Run- und Debug-Konsole: Gibt eine Anwendung Mails aus (Django `console`-Backend, Rails-Entwicklungs-Log), wird der
 * Betreff zum Link, der die Mail in Mail View öffnet. Die Zeilen sammelt der Filter mit, bis die Mail endet (Django-Trenner,
 * schließender MIME-Trenner, nächste Logzeile). Ein Filter je Konsole, die Zeilen kommen der Reihe nach.
 */
class MailConsoleFilter : Filter, DumbAware {
    /** Kopfzeilen seit der letzten Zeile, die keine war. */
    private val block = ArrayList<String>()
    private val names = HashSet<String>()
    private var laravel = false
    private var mail: ConsoleMail? = null

    @Synchronized
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val text = line.trimEnd('\n', '\r')
        mail?.let { current ->
            when (current.feed(text)) {
                ConsoleMail.Feed.PART -> return null
                ConsoleMail.Feed.LAST -> { mail = null; return null }
                ConsoleMail.Feed.AFTER -> mail = null
            }
        }
        if (text.isBlank()) return reset()
        var body = text
        if (block.isEmpty()) {
            val prefix = MailLog.LOG_PREFIX.find(text)?.value
            laravel = prefix != null
            if (prefix != null) body = text.substring(prefix.length)
        }
        val continuation = block.isNotEmpty() && (body[0] == ' ' || body[0] == '\t')
        val name = if (continuation) null else MailLog.HEADER.find(body)?.groupValues?.get(1)?.lowercase()
        if (!continuation && name == null) return reset()
        block += body
        name?.let { names += it }
        // Am Betreff entscheiden: davor schon zwei bekannte Felder, darunter eins, das nur Mails haben.
        if (name != "subject" || names.count { it in MailLog.KNOWN && it != "subject" } < 2 || names.none { it in SIGNS }) return null
        val holder = ConsoleMail(block.toList(), laravel)
        mail = holder
        reset()
        val lineStart = entireLength - line.length
        val nameStart = text.length - body.length
        var from = text.indexOf(':', nameStart) + 1
        while (from < text.length && text[from] == ' ') from++
        if (from >= text.length) from = nameStart
        return Filter.Result(lineStart + from, lineStart + text.length, holder)
    }

    private fun reset(): Filter.Result? {
        block.clear()
        names.clear()
        return null
    }

    private companion object {
        val SIGNS = setOf("mime-version", "message-id", "content-type")
    }
}

/** Eine Mail aus der Konsole: sammelt ihre Zeilen; der Link öffnet sie (unvollständig, falls sie noch nicht zu Ende ist). */
private class ConsoleMail(header: List<String>, private val laravel: Boolean) : HyperlinkInfo {
    enum class Feed { PART, LAST, AFTER }

    private val lines = ArrayList(header)
    private var inHeader = true
    private var boundary: String? = null
    private var size = header.sumOf { it.length }
    private var done = false
    private var file: LightVirtualFile? = null

    @Synchronized
    fun feed(text: String): Feed {
        if (inHeader) {
            if (text.isBlank()) {
                inHeader = false
                boundary = MailLog.boundary(lines.joinToString("\n", postfix = "\n"))
            }
            lines += text
            return Feed.PART
        }
        val multipart = boundary != null
        if (multipart && text.trimEnd() == "--$boundary--") {
            lines += text
            done = true
            return Feed.LAST
        }
        if (MailLog.endsBefore(text, multipart)) {
            done = true
            return Feed.AFTER
        }
        lines += text
        size += text.length
        if (size > MAX_CHARS) {
            done = true
            return Feed.LAST
        }
        return Feed.PART
    }

    override fun navigate(project: Project) {
        val target = synchronized(this) {
            file ?: run {
                val text = lines.joinToString("\n").trimEnd()
                ClipboardMail.file(if (laravel) MailLog.undoQuotedPrintable(text) else text).also { if (done) file = it }
            }
        }
        OpenFileDescriptor(project, target).navigate(true)
    }

    private companion object {
        const val MAX_CHARS = 20 * 1024 * 1024
    }
}
