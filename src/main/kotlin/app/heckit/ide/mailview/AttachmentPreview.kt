package app.heckit.ide.mailview

import com.intellij.DynamicBundle
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** Kurzinfo zu einem Anhang, der sich ohne Öffnen zusammenfassen lässt: Termin (.ics) oder Kontakt (.vcf). */
class AttachmentSummary(val kind: Kind, val text: String) {
    enum class Kind { EVENT, CONTACT }
}

/**
 * Liest .ics (RFC 5545) und .vcf (RFC 6350) gerade so weit, wie es für eine Zeile über der Mail reicht:
 * Termin mit Titel, Zeit und Ort bzw. Kontakt mit Name, Firma, Mail und Telefon.
 */
object AttachmentPreview {
    fun summaries(mail: ParsedMail): List<Pair<MailAttachment, AttachmentSummary>> =
        (mail.attachments + mail.inlineParts.values).mapNotNull { a -> summarize(a)?.let { a to it } }.take(MAX)

    fun summarize(attachment: MailAttachment): AttachmentSummary? {
        val name = attachment.name.lowercase()
        val type = attachment.mimeType
        return when {
            type == "text/calendar" || name.endsWith(".ics") -> runCatching { event(String(attachment.data, Charsets.UTF_8)) }.getOrNull()
            type == "text/vcard" || type == "text/x-vcard" || name.endsWith(".vcf") ->
                runCatching { contact(String(attachment.data, Charsets.UTF_8)) }.getOrNull()
            else -> null
        }
    }

    internal fun event(ics: String): AttachmentSummary? {
        val lines = unfold(ics)
        val start = lines.indexOfFirst { it.equals("BEGIN:VEVENT", ignoreCase = true) }.takeIf { it >= 0 } ?: return null
        val end = lines.drop(start).indexOfFirst { it.equals("END:VEVENT", ignoreCase = true) }.let { if (it < 0) lines.size else start + it }
        val props = properties(lines.subList(start, end))
        val summary = props["SUMMARY"]?.second?.let(::unescape)
        val from = props["DTSTART"]?.let { (params, value) -> date(params, value) }
        val to = props["DTEND"]?.let { (params, value) -> date(params, value) }
        val location = props["LOCATION"]?.second?.let(::unescape)?.takeIf { it.isNotBlank() }
        val organizer = props["ORGANIZER"]?.let { (params, value) -> param(params, "CN") ?: value.removePrefix("mailto:").removePrefix("MAILTO:") }
        val cancelled = lines.any { it.equals("METHOD:CANCEL", ignoreCase = true) } || props["STATUS"]?.second.equals("CANCELLED", ignoreCase = true)
        val parts = listOfNotNull(
            (if (cancelled) MailViewBundle.message("preview.event.cancelled") + " " else "") + (summary ?: MailViewBundle.message("header.noSubject")),
            from?.let { time(it, to) },
            location,
            organizer?.let { MailViewBundle.message("preview.event.organizer", it) },
        )
        return AttachmentSummary(AttachmentSummary.Kind.EVENT, parts.joinToString(" · "))
    }

    internal fun contact(vcf: String): AttachmentSummary? {
        val props = properties(unfold(vcf))
        val name = props["FN"]?.second?.let(::unescape)
            ?: props["N"]?.second?.split(';')?.let { n -> listOfNotNull(n.getOrNull(1), n.getOrNull(0)).filter { it.isNotBlank() }.joinToString(" ") }
            ?: return null
        val parts = listOfNotNull(
            name,
            props["ORG"]?.second?.split(';')?.firstOrNull()?.let(::unescape)?.takeIf { it.isNotBlank() },
            props["EMAIL"]?.second?.takeIf { it.isNotBlank() },
            props["TEL"]?.second?.removePrefix("tel:")?.takeIf { it.isNotBlank() },
        )
        return AttachmentSummary(AttachmentSummary.Kind.CONTACT, parts.joinToString(" · "))
    }

    private const val MAX = 3

    /** Fortsetzungszeilen beginnen mit Leerzeichen oder Tab und gehören an die vorige Zeile. */
    private fun unfold(text: String): List<String> {
        val result = ArrayList<String>()
        for (line in text.split("\r\n", "\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) result[result.size - 1] += line.substring(1)
            else if (line.isNotEmpty()) result += line
        }
        return result
    }

    /** Name (ohne Parameter, ohne „item1.“-Gruppe) → (Parameter, Wert); die erste Angabe gewinnt. */
    private fun properties(lines: List<String>): Map<String, Pair<String, String>> {
        val result = LinkedHashMap<String, Pair<String, String>>()
        for (line in lines) {
            val colon = colonIndex(line).takeIf { it > 0 } ?: continue
            val head = line.substring(0, colon)
            val name = head.substringBefore(';').substringAfterLast('.').uppercase()
            result.putIfAbsent(name, head.substringAfter(';', "") to line.substring(colon + 1))
        }
        return result
    }

    /** Doppelpunkt nach dem Namen, nicht in einem Parameter in Anführungszeichen (CN="Doe: John"). */
    private fun colonIndex(line: String): Int {
        var quoted = false
        line.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) return i
        }
        return -1
    }

    private fun param(params: String, name: String): String? =
        params.split(';').firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }?.substringAfter('=')?.trim('"')

    private fun unescape(value: String) = value.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()

    private class When(val date: Date, val allDay: Boolean)

    /** 20261103T100000Z (UTC), 20261103T100000 mit TZID oder lokal, 20261103 (ganztägig). */
    private fun date(params: String, value: String): When? {
        val v = value.trim()
        return when {
            v.length == 8 -> SimpleDateFormat("yyyyMMdd").parse(v)?.let { When(it, true) }
            v.endsWith("Z") -> SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v)?.let { When(it, false) }
            else -> SimpleDateFormat("yyyyMMdd'T'HHmmss").apply {
                param(params, "TZID")?.let { timeZone = TimeZone.getTimeZone(it) }
            }.parse(v)?.let { When(it, false) }
        }
    }

    private fun time(from: When, to: When?): String {
        val locale = DynamicBundle.getLocale()
        val day = DateFormat.getDateInstance(DateFormat.FULL, locale)
        if (from.allDay) return day.format(from.date)
        val clock = DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        val start = day.format(from.date) + ", " + clock.format(from.date)
        if (to == null) return start
        val sameDay = day.format(from.date) == day.format(to.date)
        return start + " – " + (if (sameDay) clock.format(to.date) else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(to.date))
    }
}
