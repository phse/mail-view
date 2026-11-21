package app.heckit.ide.mailview

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MimeUtility
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * Mails mitten in Logs und Konsolenausgaben: Laravel (`MAIL_MAILER=log`, die Mail folgt auf `[Datum] local.DEBUG: `),
 * Django (`console`- und `filebased`-Backend, Trenner aus 79 Bindestrichen), Rails (`:file`-Zustellung schreibt Mails
 * nacheinander, das Entwicklungs-Log nach „Delivered mail“).
 *
 * Eine Mail beginnt mit einem Kopfblock aus mindestens drei bekannten Feldern, darunter MIME-Version oder Message-ID
 * (das haben alle Frameworks, zitierte Weiterleitungen im Text nicht). Sie endet am schließenden MIME-Trenner, am
 * Django-Trenner, an der nächsten Logzeile oder dort, wo die nächste Mail beginnt.
 */
object MailLog {
    /** Liste der Mails einer Logdatei: Diese Light-Datei zeigt auf die Datei mit dem Log. */
    val SOURCE = Key.create<VirtualFile>("mailview.log.source")

    /** Monolog/Laravel: `[2026-11-20 10:00:00] local.DEBUG: ` vor der ersten Kopfzeile. */
    internal val LOG_PREFIX = Regex("""^\[\d{4}-\d{2}-\d{2}[^\]]*] [\w.-]+\.[A-Z]+: """)
    /** Beginn der nächsten Logzeile (Monolog/Laravel): Dort endet eine Mail. */
    private val LOG_LINE = Regex("""^\[\d{4}-\d{2}-\d{2}[^\]]*] """)
    /** Rails-Logzeilen, die auf eine Mail ohne MIME-Trenner folgen. */
    private val RAILS_LINE = Regex("""^(Started |Completed |Processing by |Redirected to |Delivered mail |Enqueued |Performed |\[ActiveJob])""")
    internal val HEADER = Regex("""^([A-Za-z0-9-]+):""")
    private val ATTACHMENT = Regex("""(?i)^content-disposition:\s*attachment""")
    private val DJANGO_SEPARATOR = "-".repeat(79)
    internal val KNOWN = setOf("from", "to", "cc", "subject", "date", "message-id", "mime-version", "content-type", "reply-to", "sender", "return-path")
    private val STRONG = setOf("mime-version", "message-id")
    private val QP_HEADER = Regex("""(?im)^(Content-Transfer-Encoding:[ \t]*)quoted-printable""")
    private const val MAX_HEADER = 64 * 1024

    /**
     * Zeilenweise Erkennung; [onMail] bekommt jede gefundene Mail (Lage in Zeichen bzw. Bytes, je nachdem, was
     * [line] als Position bekommt). Zeilen ohne Zeilenende übergeben.
     */
    internal class Scanner(private val onMail: (MboxEntry) -> Unit) {
        private var blockStart = -1L
        private var blockLaravel = false
        private var blockAfterBlank = false
        private val blockNames = HashSet<String>()
        private val blockText = StringBuilder()

        private var mailStart = -1L
        private var mailHeader = ""
        private var mailBoundary: String? = null
        private var mailLaravel = false
        private var attachments = 0
        private var previousBlank = true

        /** [start]/[end]: Lage der Zeile samt Zeilenende. */
        fun line(text: String, start: Long, end: Long) {
            val blank = text.isBlank()
            if (mailStart >= 0) {
                when {
                    mailBoundary != null && text.trimEnd() == "--$mailBoundary--" -> {
                        finish(end)
                        resetBlock()
                        previousBlank = false
                        return
                    }
                    endsBefore(text, mailBoundary != null) -> finish(start)
                    ATTACHMENT.containsMatchIn(text) -> attachments++
                }
            }
            if (blockStart >= 0) {
                when {
                    blank -> { evaluate(); resetBlock() }
                    text[0] == ' ' || text[0] == '\t' -> append(text, null)
                    else -> HEADER.find(text)?.let { append(text, it.groupValues[1]) } ?: resetBlock()
                }
            }
            if (blockStart < 0 && !blank) tryStart(text, start)
            previousBlank = blank
        }

        fun eof(end: Long) {
            if (blockStart >= 0) evaluate()
            if (mailStart >= 0) finish(end)
        }

        private fun tryStart(text: String, start: Long) {
            val prefix = LOG_PREFIX.find(text)?.value
            val rest = if (prefix != null) text.substring(prefix.length) else text
            val name = HEADER.find(rest)?.groupValues?.get(1) ?: return
            blockStart = start + (prefix?.length ?: 0)
            blockLaravel = prefix != null
            blockAfterBlank = previousBlank || prefix != null
            append(rest, name)
        }

        private fun append(text: String, name: String?) {
            name?.let { blockNames += it.lowercase() }
            if (blockText.length < MAX_HEADER) blockText.append(text).append('\n')
        }

        private fun resetBlock() {
            blockStart = -1L
            blockNames.clear()
            blockText.setLength(0)
        }

        private fun evaluate() {
            if (blockNames.count { it in KNOWN } < 3 || blockNames.none { it in STRONG }) return
            when {
                mailStart < 0 -> start()
                // Zwischen zwei Mails ohne Trenner (Rails `:file`): Eine Mail mit MIME-Trennern endet erst an deren
                // Ende, darin stehen auch Köpfe angehängter Mails.
                mailBoundary == null && blockAfterBlank -> { finish(blockStart); start() }
            }
        }

        private fun start() {
            mailStart = blockStart
            mailHeader = blockText.toString()
            mailLaravel = blockLaravel
            mailBoundary = boundary(mailHeader)
            attachments = 0
        }

        private fun finish(end: Long) {
            if (end > mailStart) onMail(MboxIndex.entry(mailStart, end, mailHeader.toByteArray(Charsets.ISO_8859_1), attachments, mailLaravel))
            mailStart = -1L
            mailBoundary = null
        }
    }

    /**
     * Zeile gehört nicht mehr zur Mail davor: Django-Trenner, nächste Logzeile; bei Mails ohne MIME-Trenner auch typische
     * Rails-Logzeilen.
     */
    internal fun endsBefore(text: String, multipart: Boolean): Boolean =
        text == DJANGO_SEPARATOR || LOG_LINE.containsMatchIn(text) || !multipart && RAILS_LINE.containsMatchIn(text)

    /** Trenner einer Mail mit `Content-Type: multipart/…`, sonst null. */
    internal fun boundary(header: String): String? {
        val headers = runCatching { InternetHeaders(ByteArrayInputStream(header.toByteArray(Charsets.ISO_8859_1))) }.getOrNull() ?: return null
        val type = headers.getHeader("Content-Type", null) ?: return null
        val parsed = runCatching { ContentType(MimeUtility.unfold(type).trim()) }.getOrNull() ?: return null
        return if (parsed.primaryType.equals("multipart", ignoreCase = true)) parsed.getParameter("boundary") else null
    }

    /** Mails im Strom (Bytes ab Dateianfang); auch für Logs von mehreren hundert MB, gelesen wird nur zeilenweise. */
    fun scan(input: InputStream, isCancelled: () -> Boolean = { false }): List<MboxEntry> {
        val entries = ArrayList<MboxEntry>()
        val scanner = Scanner { entries += it }
        val stream = BufferedInputStream(input, 1 shl 16)
        val line = ByteArrayOutputStream()
        var offset = 0L
        var count = 0
        while (true) {
            line.reset()
            var b = stream.read()
            if (b < 0) break
            while (b >= 0) {
                line.write(b)
                if (b == '\n'.code) break
                b = stream.read()
            }
            val start = offset
            offset += line.size()
            scanner.line(line.toString(Charsets.ISO_8859_1).trimEnd('\n', '\r'), start, offset)
            if (++count % 10_000 == 0 && isCancelled()) return entries
        }
        scanner.eof(offset)
        return entries
    }

    fun scan(path: Path, isCancelled: () -> Boolean = { false }): List<MboxEntry> =
        path.toFile().inputStream().use { scan(it, isCancelled) }

    /** Mails in einem Text (Lage in Zeichen); für den Balken über einer geöffneten Logdatei. */
    fun scan(text: CharSequence): List<MboxEntry> {
        if (StringUtil.indexOfIgnoreCase(text, "MIME-Version:", 0) < 0 && StringUtil.indexOfIgnoreCase(text, "Message-ID:", 0) < 0) return emptyList()
        val entries = ArrayList<MboxEntry>()
        val scanner = Scanner { entries += it }
        var start = 0
        while (start < text.length) {
            var end = StringUtil.indexOf(text, '\n', start)
            end = if (end < 0) text.length else end + 1
            scanner.line(text.subSequence(start, end).toString().trimEnd('\n', '\r'), start.toLong(), end.toLong())
            start = end
        }
        scanner.eof(text.length.toLong())
        return entries
    }

    fun read(path: Path, entry: MboxEntry): ByteArray {
        val data = ByteArray(entry.size.toInt())
        RandomAccessFile(path.toFile(), "r").use { it.seek(entry.start); it.readFully(data) }
        return finish(data, entry.decodedQp)
    }

    fun read(bytes: ByteArray, entry: MboxEntry): ByteArray =
        finish(bytes.copyOfRange(entry.start.toInt(), minOf(entry.end, bytes.size.toLong()).toInt()), entry.decodedQp)

    private fun finish(data: ByteArray, decodedQp: Boolean): ByteArray {
        var end = data.size
        while (end > 0 && data[end - 1].toInt().toChar() in "\r\n \t") end--
        val trimmed = if (end == data.size) data else data.copyOf(end)
        return if (decodedQp) undoQuotedPrintable(trimmed) else trimmed
    }

    /**
     * Laravel dekodiert quoted-printable vor dem Loggen, lässt den Header aber stehen; ein zweites Dekodieren würde
     * `=` im HTML zerstören. Der Inhalt ist also schon 8bit.
     */
    internal fun undoQuotedPrintable(data: ByteArray): ByteArray =
        undoQuotedPrintable(String(data, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)

    internal fun undoQuotedPrintable(text: String): String = QP_HEADER.replace(text) { it.groupValues[1] + "8bit" }

    /** Textdateien, in denen Logs mit Mails stecken können (keine Quelltexte, die Mails als Testdaten enthalten). */
    fun logLike(file: VirtualFile): Boolean {
        if (file.isDirectory || isTextMail(file) || isMbox(file) || file is LightVirtualFile) return false
        // Rails `:file`: tmp/mails/<Empfänger>, also mit „Endung“ wie .org oder .com.
        if (file.parent?.name == "mails" && file.parent?.parent?.name == "tmp") return true
        if (file.fileType.isBinary) return false
        val extension = file.extension?.lowercase()
        return extension == null || extension in LOG_EXTENSIONS || file.fileType == PlainTextFileType.INSTANCE
    }

    private val LOG_EXTENSIONS = setOf("log", "txt", "out", "err")

    /** Mails im (geöffneten) Dokument; leer für große oder nicht geladene Dateien. */
    fun mailsInDocument(file: VirtualFile): List<MboxEntry> {
        if (!logLike(file) || file.length > MAX_DOCUMENT) return emptyList()
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
        return scan(document.immutableCharSequence)
    }

    private const val MAX_DOCUMENT = 20L * 1024 * 1024

    /** Liste der Mails in [source] als eigener Tab (ein vorhandener wird nach vorn geholt). */
    fun open(project: Project, source: VirtualFile) {
        // project.service statt FileEditorManager.getInstance: gegen 2026.3 kompiliert fehlte sonst in 2026.1 der Companion.
        val file = project.service<FileEditorManager>().openFiles.firstOrNull { it.getUserData(SOURCE) == source }
            ?: LightVirtualFile(MailViewBundle.message("log.tab", source.name), MboxFileType, "").apply {
                putUserData(SOURCE, source)
                isWritable = false
            }
        OpenFileDescriptor(project, file).navigate(true)
    }
}

/** Tab mit der Liste der Mails einer Logdatei (wie bei mbox, ohne Textreiter). */
class MailLogEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file.getUserData(MailLog.SOURCE) != null
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = MboxEditor(project, file)
    override fun getEditorTypeId() = "mail-view-log"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
