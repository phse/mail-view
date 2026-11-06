package app.heckit.ide.mailview

import com.intellij.lang.ASTNode
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPlainText
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import jakarta.mail.internet.MimeUtility

/**
 * Aufbau des Mail-Quelltexts für den Reiter „Text“: Namen der Kopfzeilen, Grenzzeilen, Kopfzeilen mit
 * RFC-2047-Wörtern (eingeklappt lesbar) und die Inhalte der MIME-Teile (einklappbar, base64 eingeklappt).
 * Rein textbasiert, damit es auch bei kaputten Mails und während des Bearbeitens greift.
 */
class MailTextStructure(
    val headerNames: List<TextRange>,
    val boundaries: List<TextRange>,
    /** Wert einer Kopfzeile mit kodierten Wörtern → dekodierter, entfalteter Wert. */
    val encodedValues: List<Pair<TextRange, String>>,
    val parts: List<Part>,
) {
    class Part(val body: TextRange, val description: String, val collapsed: Boolean)

    companion object {
        /** Größere Dateien nicht zerlegen: der Reiter bleibt bei großen Anhängen flüssig. */
        private const val MAX_LENGTH = 20_000_000
        private val NAME = Regex("""^[!-9;-~]+(?=:)""")
        private val BOUNDARY = Regex("""(?i)boundary\s*=\s*(?:"([^"]+)"|([^\s;]+))""")

        fun of(file: PsiFile): MailTextStructure? {
            val virtualFile = file.originalFile.virtualFile ?: return null
            if (!isTextMail(virtualFile)) return null
            return CachedValuesManager.getCachedValue(file) {
                CachedValueProvider.Result.create(parse(file.viewProvider.contents), file)
            }
        }

        fun parse(text: CharSequence): MailTextStructure {
            if (text.length > MAX_LENGTH) return MailTextStructure(emptyList(), emptyList(), emptyList(), emptyList())
            val lines = lineRanges(text)
            val boundaries = BOUNDARY.findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toSet()
            fun boundaryOf(line: TextRange): Boolean {
                val s = line.subSequence(text).trimEnd()
                if (!s.startsWith("--")) return false
                val name = s.removePrefix("--").removeSuffix("--").toString()
                return name in boundaries
            }

            val names = ArrayList<TextRange>()
            val boundaryLines = ArrayList<TextRange>()
            val encoded = ArrayList<Pair<TextRange, String>>()
            val parts = ArrayList<Part>()
            var i = 0
            // Erster Kopfblock ab Zeile 0 (.emlx: Längenzeile davor überspringen).
            if (lines.isNotEmpty() && lines[0].subSequence(text).trim().all(Char::isDigit) && lines[0].length > 0) i = 1
            var inHeaders = true
            var headers = StringBuilder()
            var bodyStart = -1
            fun closeBody(end: Int) {
                if (bodyStart < 0) return
                val range = TextRange(bodyStart, maxOf(bodyStart, trimTrailing(text, end)))
                describe(headers.toString(), range.length)?.let { (description, collapsed) ->
                    if (range.length > 0 && StringUtil.countNewLines(range.subSequence(text)) >= 1) parts += Part(range, description, collapsed)
                }
                bodyStart = -1
            }
            var headerStart = -1
            fun closeHeader(end: Int) {
                if (headerStart < 0) return
                val range = TextRange(headerStart, end)
                val line = range.subSequence(text)
                val name = NAME.find(line)
                if (name != null) {
                    names += TextRange(headerStart, headerStart + name.value.length)
                    if (line.contains("=?")) {
                        var valueStart = headerStart + name.value.length + 1
                        while (valueStart < end && text[valueStart] == ' ') valueStart++
                        val raw = text.subSequence(valueStart, end).toString()
                        val decoded = runCatching { MimeUtility.decodeText(MimeUtility.unfold(raw)) }.getOrNull()?.replace(Regex("\\s+"), " ")?.trim()
                        if (decoded != null && decoded != raw.trim() && end > valueStart) encoded += TextRange(valueStart, end) to decoded.take(200)
                    }
                }
                headerStart = -1
            }
            while (i < lines.size) {
                val line = lines[i]
                val content = line.subSequence(text)
                if (boundaryOf(line)) {
                    closeHeader(line.startOffset)
                    closeBody(line.startOffset)
                    boundaryLines += TextRange(line.startOffset, line.startOffset + content.trimEnd().length)
                    inHeaders = !content.trimEnd().endsWith("--")
                    headers = StringBuilder()
                } else if (inHeaders) {
                    if (content.isBlank()) {
                        closeHeader(previousEnd(lines, i))
                        inHeaders = false
                        bodyStart = if (i + 1 < lines.size) lines[i + 1].startOffset else -1
                    } else if (content[0] == ' ' || content[0] == '\t') {
                        headers.append(content.trim()).append(' ')
                    } else {
                        closeHeader(previousEnd(lines, i))
                        headerStart = line.startOffset
                        headers.append('\n').append(content)
                    }
                }
                i++
            }
            closeHeader(if (lines.isEmpty()) 0 else lines.last().endOffset)
            closeBody(text.length)
            return MailTextStructure(names, boundaryLines, encoded, parts)
        }

        /** Beschreibung eines Teils aus seinen Kopfzeilen; null für multipart-Container (deren Teile werden einzeln gefaltet). */
        private fun describe(headers: String, length: Int): Pair<String, Boolean>? {
            fun value(name: String) = Regex("""(?im)^$name\s*:\s*(.*)$""").find(headers)?.groupValues?.get(1)?.trim()
            val type = value("Content-Type")
            val baseType = type?.substringBefore(';')?.trim()?.lowercase() ?: "text/plain"
            if (baseType.startsWith("multipart/")) return null
            val charset = type?.let { Regex("""(?i)charset\s*=\s*"?([^";\s]+)""").find(it)?.groupValues?.get(1) }
            val name = (value("Content-Disposition") + " " + type).let { Regex("""(?i)(?:file)?name\*?\s*=\s*"?([^";]+)""").find(it)?.groupValues?.get(1) }
                ?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
            val encoding = value("Content-Transfer-Encoding")?.lowercase()
            val description = listOfNotNull(name, baseType, charset, encoding, StringUtil.formatFileSize(length.toLong())).joinToString(" · ")
            return "… $description …" to (encoding == "base64")
        }

        private fun previousEnd(lines: List<TextRange>, i: Int) = if (i == 0) lines[0].startOffset else lines[i - 1].endOffset

        private fun trimTrailing(text: CharSequence, end: Int): Int {
            var e = end
            while (e > 0 && (text[e - 1] == '\n' || text[e - 1] == '\r')) e--
            return e
        }

        private fun lineRanges(text: CharSequence): List<TextRange> {
            val result = ArrayList<TextRange>()
            var start = 0
            for (i in text.indices) if (text[i] == '\n') {
                result += TextRange(start, if (i > start && text[i - 1] == '\r') i - 1 else i)
                start = i + 1
            }
            if (start < text.length) result += TextRange(start, text.length)
            return result
        }
    }
}

/** Reiter „Text“: MIME-Teile und kodierte Kopfzeilen einklappen (base64-Inhalte und RFC-2047-Werte von vornherein). */
class MailFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root.containingFile ?: return emptyArray()
        val structure = MailTextStructure.of(file) ?: return emptyArray()
        val node = root.node
        val result = ArrayList<FoldingDescriptor>()
        for ((range, decoded) in structure.encodedValues) {
            if (range.endOffset > document.textLength) continue
            result += FoldingDescriptor(node, range, null, decoded, true, emptySet())
        }
        for (part in structure.parts) {
            if (part.body.endOffset > document.textLength) continue
            result += FoldingDescriptor(node, part.body, null, part.description, part.collapsed, emptySet())
        }
        return result.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "…"
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}

/** Reiter „Text“: Namen der Kopfzeilen wie Schlüsselwörter, MIME-Grenzen wie Kommentare. */
class MailTextAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiPlainText) return
        val structure = MailTextStructure.of(element.containingFile) ?: return
        val bounds = element.textRange
        fun mark(range: TextRange, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
            if (!bounds.contains(range)) return
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
        }
        structure.headerNames.forEach { mark(it, DefaultLanguageHighlighterColors.KEYWORD) }
        structure.boundaries.forEach { mark(it, DefaultLanguageHighlighterColors.LINE_COMMENT) }
    }
}
