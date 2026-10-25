package app.heckit.ide.mailview

import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MailDateFormat
import jakarta.mail.internet.MimeUtility
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.Date

/** Eine Mail in der mbox: Lage in der Datei (ohne die „From “-Trennzeile) und die Angaben für die Liste. */
class MboxEntry(val start: Long, val end: Long, val subject: String?, val from: String?, val date: Date?, val attachments: Int = 0, val to: String? = null) {
    val size: Long get() = end - start
}

/**
 * Liest mbox-Dateien (Thunderbird, Google Takeout, Mailman-Archive). Die Datei wird einmal gestreamt und nur
 * die Kopfzeilen jeder Mail gelesen, damit auch Archive mit mehreren GB gehen; der Inhalt kommt erst beim Öffnen.
 */
object MboxIndex {
    /** Mehr Kopfzeilen braucht die Liste nicht; schützt vor kaputten Dateien ohne Leerzeile. */
    private const val MAX_HEADER_BYTES = 64 * 1024

    fun scan(input: InputStream, isCancelled: () -> Boolean = { false }): List<MboxEntry> {
        val entries = ArrayList<MboxEntry>()
        val stream = BufferedInputStream(input, 1 shl 16)
        val line = ByteArrayOutputStream()
        val header = ByteArrayOutputStream()
        var offset = 0L
        var start = -1L
        var inHeader = false
        var previousBlank = true
        var attachments = 0

        fun finish(end: Long) {
            if (start >= 0) entries += entry(start, end, header.toByteArray(), attachments)
            header.reset()
            attachments = 0
        }

        while (true) {
            line.reset()
            var b = stream.read()
            if (b < 0) break
            while (b >= 0) {
                line.write(b)
                if (b == '\n'.code) break
                b = stream.read()
            }
            val bytes = line.toByteArray()
            val lineStart = offset
            offset += bytes.size
            // Neue Mail: „From “ am Zeilenanfang nach einer Leerzeile (oder am Dateianfang).
            if (previousBlank && startsWithFrom(bytes)) {
                finish(lineStart)
                start = offset
                inHeader = true
                if (entries.size % 500 == 0 && isCancelled()) return entries
            } else if (inHeader) {
                if (isBlank(bytes)) inHeader = false
                else if (header.size() < MAX_HEADER_BYTES) header.write(bytes)
            } else if (start >= 0 && isAttachmentLine(bytes)) {
                attachments++
            }
            previousBlank = isBlank(bytes)
        }
        finish(offset)
        return entries
    }

    fun scan(path: Path, isCancelled: () -> Boolean = { false }): List<MboxEntry> =
        path.toFile().inputStream().use { scan(it, isCancelled) }

    /** Rohdaten einer Mail; „>From “-Zeilen werden zurückgewandelt (mboxrd: eine Ebene weniger). */
    fun read(path: Path, entry: MboxEntry): ByteArray {
        val data = ByteArray(entry.size.toInt())
        RandomAccessFile(path.toFile(), "r").use { it.seek(entry.start); it.readFully(data) }
        return unescape(data)
    }

    fun read(bytes: ByteArray, entry: MboxEntry): ByteArray =
        unescape(bytes.copyOfRange(entry.start.toInt(), entry.end.toInt()))

    internal fun unescape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var lineStart = true
        var i = 0
        while (i < data.size) {
            if (lineStart) {
                var j = i
                while (j < data.size && data[j] == '>'.code.toByte()) j++
                if (j > i && startsWithFrom(data, j)) i++ // ein „>“ weglassen
            }
            out.write(data[i].toInt())
            lineStart = data[i] == '\n'.code.toByte()
            i++
        }
        // Die Leerzeile vor der nächsten „From “-Zeile gehört zum Trenner, nicht zur Mail.
        val result = out.toByteArray()
        var end = result.size
        if (end > 0 && result[end - 1] == '\n'.code.toByte()) end--
        if (end > 0 && result[end - 1] == '\r'.code.toByte()) end--
        return if (end == result.size) result else result.copyOf(end)
    }

    private val DISPOSITION = "content-disposition:".toByteArray()
    private val ATTACHMENT = Regex("""(?i)^content-disposition:\s*attachment""")

    /** Anhänge zählen ohne die Mail zu parsen: Teile mit „Content-Disposition: attachment“ im Inhalt. */
    private fun isAttachmentLine(bytes: ByteArray): Boolean {
        if (bytes.size < DISPOSITION.size || (bytes[0] != 'C'.code.toByte() && bytes[0] != 'c'.code.toByte())) return false
        if (!DISPOSITION.indices.all { Character.toLowerCase(bytes[it].toInt()) == DISPOSITION[it].toInt() }) return false
        return ATTACHMENT.containsMatchIn(String(bytes, Charsets.US_ASCII))
    }

    private fun entry(start: Long, end: Long, header: ByteArray, attachments: Int): MboxEntry {
        val headers = runCatching { InternetHeaders(ByteArrayInputStream(header)) }.getOrNull()
        fun value(name: String) = headers?.getHeader(name, null)?.let { runCatching { MimeUtility.decodeText(MimeUtility.unfold(it)) }.getOrDefault(it) }
        val from = headers?.getHeader("From", null)?.let { raw ->
            runCatching { InternetAddress.parseHeader(raw, false).firstOrNull()?.let { it.personal?.takeIf(String::isNotBlank) ?: it.address } }
                .getOrNull() ?: value("From")
        }
        // Empfänger: Namen (sonst Adressen) aller An-Adressen, für die einblendbare Spalte „An“.
        val to = headers?.getHeader("To", ", ")?.let { raw ->
            runCatching { InternetAddress.parseHeader(raw, false).joinToString(", ") { it.personal?.takeIf(String::isNotBlank) ?: it.address } }
                .getOrNull() ?: value("To")
        }
        val date = headers?.getHeader("Date", null)?.let { runCatching { MailDateFormat().parse(MimeUtility.unfold(it).trim()) }.getOrNull() }
        return MboxEntry(start, end, value("Subject")?.trim(), from?.trim(), date, attachments, to?.trim())
    }

    private val FROM = "From ".toByteArray()

    private fun startsWithFrom(bytes: ByteArray, at: Int = 0): Boolean =
        bytes.size - at >= FROM.size && FROM.indices.all { bytes[at + it] == FROM[it] }

    private fun isBlank(bytes: ByteArray): Boolean =
        bytes.isEmpty() || bytes.all { it == '\n'.code.toByte() || it == '\r'.code.toByte() }
}
