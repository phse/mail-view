package app.heckit.ide.mailview

import com.intellij.DynamicBundle
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.text.DateFormat
import java.util.Base64

/** Baut die HTML-Seite der Mail-Ansicht: Kopf mit Absender, Empfängern usw., darunter der Inhalt in einem iframe. */
object MailHtmlRenderer {
    /** Verweise in .mht-Seiten: Attribute und CSS-url(), Wert in Gruppe 2 bzw. 3. */
    private val REFERENCE = Regex("""(?i)\b(src|href|background)\s*=\s*["']([^"']+)["']|url\(\s*["']?([^"')]+)["']?\s*\)""")
    private val REMOTE = Regex("""(?i)(src|background|href)\s*=\s*["']?\s*https?:|url\(\s*["']?\s*https?:""")

    // Das iframe bekommt die Höhe seines Inhalts; die Seitenhöhe geht über reportHeight(h) an Swing, das scrollt.
    private const val FIT_SCRIPT = """
        const f = document.querySelector('iframe');
        let loaded = false;
        function fit() {
          const d = f.contentDocument;
          if (!loaded || !d || !d.documentElement) return;
          const h = Math.max(d.documentElement.scrollHeight, d.body ? d.body.scrollHeight : 0);
          if (Math.abs(f.offsetHeight - h) > 1) f.style.height = h + 'px';
          reportHeight(document.documentElement.scrollHeight);
        }
        f.addEventListener('load', () => {
          loaded = true;
          fit();
          new ResizeObserver(fit).observe(f.contentDocument.documentElement);
          f.contentDocument.querySelectorAll('img').forEach(i => i.addEventListener('load', fit));
        });
        addEventListener('resize', fit);
        new ResizeObserver(fit).observe(document.querySelector('header'));
    """

    private const val FONT_STACK = "-apple-system, BlinkMacSystemFont, \"SF Pro Text\", \"Segoe UI Variable Text\", \"Segoe UI\", " +
        "Inter, Roboto, \"Helvetica Neue\", Arial, sans-serif"

    /**
     * Grundschrift für die Mail. Outlook-Mails nennen meist Calibri, Aptos oder Cambria, die es auf dem Mac
     * oft nicht gibt; per @font-face zeigen diese Namen auf die Systemschrift statt auf eine Ersatz-Serifenschrift.
     */
    private val BODY_STYLE = buildString {
        append("<style>")
        for (name in listOf("Calibri", "Aptos", "Cambria", "Segoe UI", "Tahoma", "Verdana")) {
            append("@font-face { font-family: \"$name\"; src: local(\"$name\"), local(\".AppleSystemUIFont\"), local(\"SF Pro Text\"), local(\"Helvetica Neue\"); }")
        }
        append("body { font-family: $FONT_STACK; font-size: 14px; line-height: 1.5; color: #1f1f1f; margin: 16px 18px; ")
        append("-webkit-font-smoothing: antialiased; }")
        append("</style>")
    }

    /**
     * Dunkle Darstellung: Mails geben ihre Farben fest vor (meist Schwarz auf Weiß), deshalb wird der ganze Inhalt
     * invertiert und der Farbton zurückgedreht. contrast(0.8) macht daraus Dunkelgrau (#1a1a1a) statt Schwarz und
     * Hellgrau statt Weiß. Bilder werden vorab gegenläufig behandelt und sehen danach wieder normal aus.
     */
    private const val DARK_FRAME = "iframe { filter: invert(1) hue-rotate(180deg) contrast(0.8); }"
    private const val DARK_MEDIA = "<style>img, video, picture, svg, [background] { filter: contrast(1.25) invert(1) hue-rotate(180deg); }</style>"

    fun hasRemoteContent(mail: ParsedMail): Boolean =
        mail.html?.let { html -> REMOTE.findAll(html).any { !it.value.startsWith("href", ignoreCase = true) } } == true

    /** [heightCallback] ist JavaScript, das mit der Seitenhöhe in `h` aufgerufen wird. */
    fun render(mail: ParsedMail, allowRemote: Boolean, heightCallback: String = "", dark: Boolean = false): String {
        val bg = ColorUtil.toHtmlColor(UIUtil.getPanelBackground())
        val fg = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val border = ColorUtil.toHtmlColor(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        val font = UIUtil.getLabelFont()
        val remote = if (allowRemote) " https: http:" else ""
        // Das Skript gilt nur für die äußere Seite; im iframe verhindert sandbox (ohne allow-scripts) jedes Skript.
        val csp = "default-src 'none'; script-src 'unsafe-inline'; frame-src 'self' about:; img-src data:$remote; " +
            "style-src 'unsafe-inline' data:$remote; font-src data:$remote"

        val rows = buildString {
            row(MailViewBundle.message("header.from"), mail.from)
            row(MailViewBundle.message("header.to"), mail.to)
            row(MailViewBundle.message("header.cc"), mail.cc)
            row(MailViewBundle.message("header.bcc"), mail.bcc)
            row(MailViewBundle.message("header.replyTo"), mail.replyTo)
            mail.date?.let {
                val format = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT, DynamicBundle.getLocale())
                append("<tr><th>${esc(MailViewBundle.message("header.date"))}</th><td>${esc(format.format(it))}</td></tr>")
            }
        }
        val subject = mail.subject?.takeIf { it.isNotBlank() } ?: MailViewBundle.message("header.noSubject")

        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="$csp">
            <style>
              html, body { margin: 0; }
              html { background: ${if (dark) "#1a1a1a" else "#fff"}; }
              header { background: $bg; }
              body { color: $fg;
                     font-family: $FONT_STACK; font-size: ${maxOf(font.size, 13)}px; -webkit-font-smoothing: antialiased; }
              header { padding: 14px 18px 10px; border-bottom: 1px solid $border; }
              h1 { font-size: 1.45em; font-weight: 600; margin: 0 0 10px; }
              table { border-collapse: collapse; }
              th { text-align: left; font-weight: normal; color: $muted; padding: 2px 14px 2px 0; vertical-align: top; white-space: nowrap; }
              td { padding: 2px 0; }
              .addr { color: $muted; }
              iframe { display: block; border: 0; width: 100%; height: 0; background: #fff; }
              ${if (dark) DARK_FRAME else ""}
            </style></head><body>
            <header><h1>${esc(subject)}</h1><table>$rows</table></header>
            <iframe scrolling="no" sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox" srcdoc="${attr(body(mail, dark))}"></iframe>
            <script>function reportHeight(h) { $heightCallback }
$FIT_SCRIPT</script>
            </body></html>
        """.trimIndent()
    }

    /** Inhalt für das iframe: HTML-Teil mit eingebetteten cid:-Bildern, sonst der Textteil. */
    private fun body(mail: ParsedMail, dark: Boolean): String {
        val base = "<base target=\"_blank\">$BODY_STYLE" + if (dark) DARK_MEDIA else ""
        val html = mail.html
        if (html != null) {
            var result: String = html
            for ((cid, part) in mail.inlineParts) {
                val uri = "data:${part.mimeType};base64," + Base64.getEncoder().encodeToString(part.data)
                result = result.replace("cid:$cid", uri, ignoreCase = true)
            }
            if (mail.locationParts.isNotEmpty()) result = REFERENCE.replace(result) { match ->
                val group = match.groups[2] ?: match.groups[3] ?: return@replace match.value
                val part = locationPart(mail, group.value) ?: return@replace match.value
                val uri = "data:${part.mimeType};base64," + Base64.getEncoder().encodeToString(part.data)
                match.value.replaceRange(group.range.first - match.range.first, group.range.last - match.range.first + 1, uri)
            }
            return base + result
        }
        val text = mail.text ?: ""
        val linked = esc(text).replace(Regex("""https?://[^\s<>"]+""")) { "<a href=\"${it.value}\">${it.value}</a>" }
        return "$base<div style=\"white-space: pre-wrap; word-wrap: break-word;\">$linked</div>"
    }

    fun unreferencedInline(mail: ParsedMail): List<MailAttachment> {
        val html = mail.html ?: return mail.inlineParts.values.toList() + mail.locationParts.values
        val used = REFERENCE.findAll(html).mapNotNull { m -> (m.groups[2] ?: m.groups[3])?.let { locationPart(mail, it.value) } }.toSet()
        return mail.inlineParts.filterKeys { !html.contains("cid:$it", ignoreCase = true) }.values.toList() +
            mail.locationParts.values.filter { it !in used }
    }

    /** Teil zu einem Verweis: gleiche Adresse oder relativer Pfad, der auf die Adresse passt (Word: „Doc_files/image001.png“). */
    private fun locationPart(mail: ParsedMail, reference: String): MailAttachment? {
        val ref = reference.trim().removePrefix("./")
        if (ref.isEmpty() || ref.startsWith("data:") || ref.startsWith("#")) return null
        mail.locationParts[ref]?.let { return it }
        return mail.locationParts.entries.firstOrNull { (location, _) -> location.endsWith("/$ref") }?.value
    }

    private fun StringBuilder.row(label: String, addresses: List<MailAddress>) {
        if (addresses.isEmpty()) return
        val value = addresses.joinToString(", ") { a ->
            when {
                a.name != null && a.address != null -> "${esc(a.name)} <span class=\"addr\">&lt;${esc(a.address)}&gt;</span>"
                else -> esc(a.name ?: a.address ?: "")
            }
        }
        append("<tr><th>${esc(label)}</th><td>$value</td></tr>")
    }

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)
    private fun attr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;")
}
