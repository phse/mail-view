package app.heckit.ide.mailview

import com.intellij.openapi.util.text.StringUtil
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MailDateFormat
import jakarta.mail.internet.MimeUtility
import java.io.ByteArrayInputStream
import java.util.Date

/** Ergebnis einer Prüfung aus Authentication-Results, z. B. dkim=pass für example.com. */
class AuthResult(val method: String, val result: String, val detail: String?)

/** Eine Station der Received-Kette, älteste zuerst; [delayMs] seit der vorherigen Station. */
class DeliveryHop(val from: String?, val by: String?, val with: String?, val date: Date?, val delayMs: Long?)

class HeaderAnalysis(val auth: List<AuthResult>, val hops: List<DeliveryHop>) {
    /** Zeit von der ersten bis zur letzten Station mit Datum. */
    val totalMs: Long?
        get() {
            val dates = hops.mapNotNull { it.date }
            return if (dates.size < 2) null else dates.last().time - dates.first().time
        }

    companion object {
        /** Ab hier gilt eine Station als langsam und wird hervorgehoben. */
        const val SLOW_HOP_MS = 5 * 60_000L

        /** Dauer lesbar: Uhren der Server gehen teils falsch, negative Werte deshalb als „-2 s“. */
        fun formatDuration(ms: Long): String {
            val abs = Math.abs(ms)
            if (abs < 1000) return "<1 s"
            return (if (ms < 0) "-" else "") + StringUtil.formatDuration(abs / 1000 * 1000)
        }

        private val AUTH = Regex("""(?i)\b(spf|dkim|dmarc|arc)\s*=\s*([a-z]+)([^;]*)""")
        private val DETAIL = Regex("""(?i)\b(header\.d|header\.i|header\.from|smtp\.mailfrom|header\.s)\s*=\s*"?([^\s;"]+)""")
        private val FROM = Regex("""(?i)(?:^|\s)from\s+(\S+)""")
        private val BY = Regex("""(?i)\sby\s+(\S+)""")
        private val WITH = Regex("""(?i)\swith\s+(\S+)""")

        fun of(mail: ParsedMail): HeaderAnalysis? {
            val raw = mail.headers ?: return null
            val headers = runCatching { InternetHeaders(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))) }.getOrNull() ?: return null
            fun all(name: String) = headers.getHeader(name)?.map { MimeUtility.unfold(it) }.orEmpty()
            // Nur der oberste Eintrag: den hat der empfangende Server gesetzt; tiefere können vom Absender stammen.
            val authHeader = all("Authentication-Results").firstOrNull() ?: all("ARC-Authentication-Results").firstOrNull()
            val auth = authHeader?.let(::parseAuth).orEmpty().ifEmpty {
                all("Received-SPF").firstOrNull()?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(AuthResult("spf", it.lowercase(), null)) }.orEmpty()
            }
            val hops = parseHops(all("Received"))
            return if (auth.isEmpty() && hops.isEmpty()) null else HeaderAnalysis(auth, hops)
        }

        internal fun parseAuth(value: String): List<AuthResult> = AUTH.findAll(value).map { m ->
            val details = DETAIL.findAll(m.groupValues[3]).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val detail = details["header.d"] ?: details["header.from"] ?: details["smtp.mailfrom"] ?: details["header.i"]
            AuthResult(m.groupValues[1].lowercase(), m.groupValues[2].lowercase(), detail)
        }.distinctBy { Triple(it.method, it.result, it.detail) }.toList()

        /** Received-Zeilen stehen neueste zuerst; umgedreht ergibt sich der Weg vom Absender zum Empfänger. */
        internal fun parseHops(received: List<String>): List<DeliveryHop> {
            var previous: Date? = null
            return received.reversed().map { line ->
                val main = line.substringBeforeLast(';')
                val date = line.substringAfterLast(';', "").trim().takeIf { it.isNotEmpty() }
                    ?.let { runCatching { MailDateFormat().parse(it) }.getOrNull() }
                val delay = if (date != null && previous != null) date.time - previous!!.time else null
                if (date != null) previous = date
                DeliveryHop(
                    from = FROM.find(main)?.groupValues?.get(1),
                    by = BY.find(main)?.groupValues?.get(1),
                    with = WITH.find(main)?.groupValues?.get(1),
                    date = date,
                    delayMs = delay,
                )
            }
        }
    }
}
