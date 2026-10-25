package app.heckit.ide.mailview

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.io.FileUtil
import java.io.File

/**
 * Adressen aus dem Mail-Kopf als Kontakt übernehmen: Mail View schreibt eine .vcf und öffnet sie mit dem
 * Adressbuch des Systems. Dort entscheidet man, ob ein neuer Kontakt entsteht oder ein vorhandener ergänzt wird
 * (macOS Kontakte erkennt Dubletten und bietet „Aktualisieren“ an).
 */
object Contacts {
    fun open(people: List<MailAddress>) {
        if (people.isEmpty()) return
        val name = if (people.size == 1) people[0].name ?: people[0].address ?: "contact" else "contacts"
        val file = File(FileUtil.createTempDirectory("mail-view", null, true), MailExport.safeName("$name.vcf"))
        file.writeText(vcard(people), Charsets.UTF_8)
        BrowserUtil.open(file.path)
    }

    /** vCard 3.0 je Adresse; N ist Pflicht, sonst zeigt macOS Kontakte keinen Namen. */
    internal fun vcard(people: List<MailAddress>): String = buildString {
        for (p in people) {
            val (first, last) = splitName(p.name ?: p.address?.substringBefore('@') ?: "")
            append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            append("N:${esc(last)};${esc(first)};;;\r\n")
            append("FN:${esc(listOf(first, last).filter { it.isNotEmpty() }.joinToString(" "))}\r\n")
            p.address?.let { append("EMAIL;TYPE=INTERNET:${esc(it)}\r\n") }
            append("END:VCARD\r\n")
        }
    }

    /** „Lee, Jordan“ oder „Jordan Lee“ → (Jordan, Lee); ein einzelnes Wort gilt als Vorname. */
    private fun splitName(name: String): Pair<String, String> {
        val clean = name.trim().removeSurrounding("\"").trim()
        if (',' in clean) return clean.substringAfter(',').trim() to clean.substringBefore(',').trim()
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (words.size < 2) clean to "" else words.dropLast(1).joinToString(" ") to words.last()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
}
