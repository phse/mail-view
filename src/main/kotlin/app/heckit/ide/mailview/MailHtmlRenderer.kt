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

    // Das iframe bekommt die Höhe seines Inhalts; die Seitenhöhe geht über reportHeight(h, top) an Swing (top: Beginn des Mailtexts, dort setzt Swing „Nur Text“/„Dunkel“ an), das scrollt.
    private const val FIT_SCRIPT = """
        const f = document.querySelector('iframe');
        let loaded = false;
        function fit() {
          const d = f.contentDocument;
          if (!loaded || !d || !d.documentElement) return;
          // Das iframe scrollt nicht seitlich: Zu breite Bilder (Outlook gibt width/height fest vor) auf die Breite
          // verkleinern, sonst ist ihr rechter Teil abgeschnitten. Nur diese, damit Abstandshalter ihre Höhe behalten.
          const w = d.documentElement.clientWidth;
          for (const i of d.images) {
            if (i.getBoundingClientRect().right <= w + 1) continue;
            i.style.setProperty('max-width', '100%', 'important');
            i.style.setProperty('height', 'auto', 'important');
          }
          const h = Math.max(d.documentElement.scrollHeight, d.body ? d.body.scrollHeight : 0);
          if (Math.abs(f.offsetHeight - h) > 1) f.style.height = h + 'px';
          // Nicht documentElement.scrollHeight: Das ist mindestens so hoch wie das Fenster, und das hat noch die Höhe der
          // vorigen Fassung - nach „Nur Text“ bliebe unten leerer Raum.
          const c = document.querySelector('.content').getBoundingClientRect();
          reportHeight(Math.ceil(c.bottom + scrollY), Math.round(c.top + scrollY));
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

    /**
     * Antworten/Weiterleiten: Die Auswahl im Mailtext (iframe) oder im Kopf mitschicken. mousedown ohne Standard,
     * damit der Klick die Auswahl nicht aufhebt; Meldung per console.log wie die Seitenhöhe.
     */
    private const val ACTION_SCRIPT = """
        document.querySelectorAll('.actions a').forEach(a => {
          a.addEventListener('mousedown', e => e.preventDefault());
          a.addEventListener('click', e => {
            e.preventDefault();
            const inner = f.contentWindow ? String(f.contentWindow.getSelection()) : '';
            const selection = inner || String(getSelection());
            console.log('mail-view:action=' + a.dataset.action + ':' + encodeURIComponent(selection));
          });
        });
    """

    const val ACTION_PREFIX = "mail-view:action="

    /**
     * Suchen (Cmd+F): Treffer in Kopf und Mailtext (iframe) markieren, zum aktuellen springen und „Nr/Anzahl/Höhe“
     * melden. Chromiums eigene Suche meldet über JCEF weder Treffer noch Lage; Swing muss aber selbst scrollen.
     */
    private const val FIND_SCRIPT = """
        let hits = [], current = -1;
        function clearFind() {
          for (const m of hits) { const t = m.ownerDocument.createTextNode(m.textContent); m.replaceWith(t); t.parentNode.normalize(); }
          hits = []; current = -1;
        }
        function mark(root, needle) {
          const doc = root.ownerDocument;
          if (!doc.getElementById('mv-find-style')) {
            const s = doc.createElement('style'); s.id = 'mv-find-style';
            s.textContent = 'mark.mv-hit{background:#ffe066;color:#000;border-radius:2px}mark.mv-cur{background:#ff9632}';
            (doc.head || doc.documentElement).appendChild(s);
          }
          const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, { acceptNode: n =>
            n.parentNode && /^(SCRIPT|STYLE|TITLE)$/.test(n.parentNode.nodeName) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT });
          const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode);
          for (let node of nodes) {
            let i;
            while ((i = node.nodeValue.toLowerCase().indexOf(needle)) >= 0) {
              const hit = node.splitText(i); node = hit.splitText(needle.length);
              const m = doc.createElement('mark'); m.className = 'mv-hit'; hit.replaceWith(m); m.appendChild(hit); hits.push(m);
            }
          }
        }
        window.mvFind = (query, step) => {
          const needle = query.toLowerCase();
          if (step === 0 || !hits.length) {
            clearFind();
            if (needle) { mark(document.querySelector('header'), needle); if (f.contentDocument && f.contentDocument.body) mark(f.contentDocument.body, needle); }
            current = hits.length ? 0 : -1;
          } else {
            hits[current]?.classList.remove('mv-cur');
            current = (current + step + hits.length) % hits.length;
          }
          let top = -1;
          const m = hits[current];
          if (m) {
            m.classList.add('mv-cur');
            const inner = m.ownerDocument !== document;
            top = m.getBoundingClientRect().top + (inner ? f.getBoundingClientRect().top : 0) + scrollY;
            if (document.documentElement.style.overflow === 'auto') m.scrollIntoView({ block: 'center' });
          }
          console.log('mail-view:find=' + (current + 1) + '/' + hits.length + '/' + Math.round(top));
        };
    """

    const val FIND_PREFIX = "mail-view:find="

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
    fun render(mail: ParsedMail, allowRemote: Boolean, heightCallback: String = "", dark: Boolean = false, plainText: Boolean = false): String {
        val bg = ColorUtil.toHtmlColor(UIUtil.getPanelBackground())
        val fg = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val border = ColorUtil.toHtmlColor(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        val font = UIUtil.getLabelFont()
        val remote = if (allowRemote) " https: http:" else ""
        // Das Skript gilt nur für die äußere Seite; im iframe verhindert sandbox (ohne allow-scripts) jedes Skript.
        val csp = "default-src 'none'; script-src 'unsafe-inline'; frame-src 'self' about:; img-src data:$remote; " +
            "style-src 'unsafe-inline' data:$remote; font-src data:$remote"

        val people = contacts(mail)
        val rows = buildString {
            row(MailViewBundle.message("header.from"), mail.from, people)
            row(MailViewBundle.message("header.to"), mail.to, people)
            row(MailViewBundle.message("header.cc"), mail.cc, people)
            row(MailViewBundle.message("header.bcc"), mail.bcc, people)
            row(MailViewBundle.message("header.replyTo"), mail.replyTo, people)
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
              a.contact { color: inherit; text-decoration: none; cursor: pointer; padding: 1px 5px 3px; margin: 0 -5px; border-radius: 5px; }
              a.contact:hover, a.contact.active { background: rgba(128, 128, 128, 0.16); box-shadow: inset 0 0 0 1px rgba(128, 128, 128, 0.35); }
              .top { display: flex; align-items: flex-start; gap: 12px; }
              .top h1 { flex: 1; }
              .actions { display: flex; gap: 6px; white-space: nowrap; }
              .actions a { display: inline-flex; align-items: center; gap: 5px; padding: 3px 9px; border-radius: 6px; color: inherit;
                           text-decoration: none; box-shadow: inset 0 0 0 1px rgba(128, 128, 128, 0.35); font-size: 0.92em; }
              .actions a:hover { background: rgba(128, 128, 128, 0.16); }
              .actions svg { width: 14px; height: 14px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
              iframe { display: block; border: 0; width: 100%; height: 0; background: #fff; }
              .content { position: relative; }
              @media print {
                html, header { background: #fff !important; }
                body, header, th, .addr { color: #000 !important; }
                iframe { filter: none !important; }
              }
              ${if (dark) DARK_FRAME else ""}
            </style></head><body>
            <header><div class="top"><h1>${esc(subject)}</h1>${if (SHOW_COMPOSE) actions() else ""}</div><table>$rows</table></header>
            <div class="content"><iframe scrolling="no" sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox" srcdoc="${attr(body(mail, dark, plainText))}"></iframe></div>
            <script>function reportHeight(h, top) { $heightCallback }
$FIT_SCRIPT
$ACTION_SCRIPT
$FIND_SCRIPT</script>
            </body></html>
        """.trimIndent()
    }

    /** Inhalt für das iframe: HTML-Teil mit eingebetteten cid:-Bildern, sonst (oder auf Wunsch) der Textteil. */
    private fun body(mail: ParsedMail, dark: Boolean, plainText: Boolean): String {
        val base = "<base target=\"_blank\">$BODY_STYLE" + if (dark) DARK_MEDIA else ""
        val html = mail.html?.takeUnless { plainText && mail.text != null }
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

    /**
     * Antworten/Weiterleiten vorerst ausgeblendet (2026-10-25): Über mailto: kommt nur reiner Text an - kein echtes
     * Zitat (Apple Mail zeigt keine Zitatlinie), keine Anhänge, Länge begrenzt. „In Mail-App öffnen“ deckt das besser
     * ab: Dort antwortet man mit den Mitteln des Mailprogramms. Code (Compose, Einstellungen) bleibt für später.
     */
    internal const val SHOW_COMPOSE = false

    /** Antworten, Allen antworten, Weiterleiten - als Links, die die Vorschau abfängt (wie die Adressen). */
    private fun actions(): String {
        fun button(action: String, key: String, path: String) =
            "<a href=\"#\" data-action=\"$action\" title=\"${esc(MailViewBundle.message(key))}\"><svg viewBox=\"0 0 16 16\">$path</svg>${esc(MailViewBundle.message(key))}</a>"
        return "<div class=\"actions\">" +
            button("reply", "action.reply", "<path d=\"M6.5 4 2.5 8l4 4\"/><path d=\"M2.5 8h7a4 4 0 0 1 4 4v1\"/>") +
            button("replyAll", "action.replyAll", "<path d=\"M8.5 4l-4 4 4 4\"/><path d=\"M5 4 1 8l4 4\"/><path d=\"M4.5 8h5a4 4 0 0 1 4 4v1\"/>") +
            button("forward", "action.forward", "<path d=\"M9.5 4l4 4-4 4\"/><path d=\"M13.5 8h-7a4 4 0 0 0-4 4v1\"/>") +
            "</div>"
    }

    /** Alle Adressen im Kopf in fester Reihenfolge; der Index steht im Link und führt zurück zur Adresse. */
    fun contacts(mail: ParsedMail): List<MailAddress> = mail.from + mail.to + mail.cc + mail.bcc + mail.replyTo

    /**
     * Ziel der Adress-Links. Eine https-Adresse unter .invalid (gibt es nie), weil Chromium eigene Schemata als
     * externes Protokoll behandelt und onBeforeBrowse dafür nicht aufruft; die Vorschau fängt die Navigation ab.
     */
    const val CONTACT_LINK = "https://mail-view.invalid/contact/"


    private fun StringBuilder.row(label: String, addresses: List<MailAddress>, people: List<MailAddress>) {
        if (addresses.isEmpty()) return
        val value = addresses.joinToString(", ") { a ->
            val text = when {
                a.name != null && a.address != null -> "${esc(a.name)} <span class=\"addr\">&lt;${esc(a.address)}&gt;</span>"
                else -> esc(a.name ?: a.address ?: "")
            }
            // Klick öffnet ein Menü (Kontakt anlegen, Adresse kopieren, Mail schreiben).
            if (a.address == null) text else people.indexOfFirst { it === a }.let { "<a class=\"contact\" data-i=\"$it\" href=\"$CONTACT_LINK$it\">$text</a>" }
        }
        append("<tr><th>${esc(label)}</th><td>$value</td></tr>")
    }

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)
    private fun attr(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;")
}
