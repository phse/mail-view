package app.heckit.ide.mailview

import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MailDateFormat
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimePartDataSource
import jakarta.mail.internet.MimeUtility
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Ein Formathinweis; [key] aus dem Bundle (lint.*), [args] für die Platzhalter. */
class MailWarning(val key: String, vararg val args: Any) {
    val message: String get() = MailViewBundle.message(key, *args)
    override fun toString() = key + args.joinToString(prefix = "(", postfix = ")")
}

/**
 * Formatprüfung einer MIME-Mail für den Reiter „Header“: nur Hinweise, nichts wird an der Datei geändert.
 * Fehlende Pflicht-Header, unlesbares Datum, nicht abgeschlossene MIME-Grenzen und Zeichensätze, die nicht zum Inhalt passen.
 */
object MailLint {
    fun check(message: MimeMessage): List<MailWarning> {
        val warnings = ArrayList<MailWarning>()
        fun header(name: String) = runCatching { message.getHeader(name, null) }.getOrNull()?.let(MimeUtility::unfold)?.trim()
        if (header("From").isNullOrEmpty()) warnings += MailWarning("lint.missing", "From")
        val date = header("Date")
        when {
            date.isNullOrEmpty() -> warnings += MailWarning("lint.missing", "Date")
            runCatching { MailDateFormat().parse(date) }.getOrNull() == null -> warnings += MailWarning("lint.date", date)
        }
        if (header("Message-ID").isNullOrEmpty()) warnings += MailWarning("lint.missing", "Message-ID")
        if (rawHeaderNonAscii(message)) warnings += MailWarning("lint.rawHeader")
        runCatching { walk(message, "", warnings) }
        return warnings
    }

    private fun rawHeaderNonAscii(message: MimeMessage) =
        message.allHeaderLines.toList().any { line -> line.any { it.code > 127 } }

    private fun walk(part: MimePart, path: String, warnings: MutableList<MailWarning>) {
        val type = runCatching { ContentType(part.contentType) }.getOrNull()
        val label = if (path.isEmpty()) MailViewBundle.message("lint.message") else MailViewBundle.message("lint.part", path)
        if (part.isMimeType("multipart/*")) {
            val boundary = type?.getParameter("boundary")
            if (boundary.isNullOrEmpty()) {
                warnings += MailWarning("lint.boundary.missing", label)
                return
            }
            val raw = rawBytes(part)
            if (raw != null && !contains(raw, "--$boundary--".toByteArray(Charsets.ISO_8859_1))) {
                warnings += MailWarning("lint.boundary.open", label, boundary)
            }
            val multipart = MimeMultipart(MimePartDataSource(part))
            for (i in 0 until multipart.count) {
                walk(multipart.getBodyPart(i) as MimePart, if (path.isEmpty()) "${i + 1}" else "$path.${i + 1}", warnings)
            }
            return
        }
        if (!part.isMimeType("text/*")) return
        val declared = type?.getParameter("charset")
        val bytes = runCatching { part.inputStream.use { it.readBytes() } }.getOrNull() ?: return
        if (declared == null) {
            if (bytes.any { it < 0 }) warnings += MailWarning("lint.charset.none", label)
            return
        }
        // Erst den angegebenen Namen: javaCharset bildet us-ascii auf ISO-8859-1 ab, dann fiele 8-Bit-Text nicht auf.
        val charset = runCatching { Charset.forName(declared) }.getOrNull() ?: runCatching { Charset.forName(MimeUtility.javaCharset(declared)) }.getOrNull()
        if (charset == null) {
            warnings += MailWarning("lint.charset.unknown", label, declared)
            return
        }
        if (!decodes(bytes, charset)) {
            val utf8 = charset != Charsets.UTF_8 && decodes(bytes, Charsets.UTF_8) && bytes.any { it < 0 }
            warnings += if (utf8) MailWarning("lint.charset.utf8", label, declared) else MailWarning("lint.charset.invalid", label, declared)
        }
    }

    private fun decodes(bytes: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun rawBytes(part: MimePart): ByteArray? = runCatching {
        when (part) {
            is MimeMessage -> part.rawInputStream.use { it.readBytes() }
            is MimeBodyPart -> part.rawInputStream.use { it.readBytes() }
            else -> null
        }
    }.getOrNull()

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
