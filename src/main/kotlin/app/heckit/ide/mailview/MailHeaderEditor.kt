package app.heckit.ide.mailview

import com.intellij.DynamicBundle
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.beans.PropertyChangeListener
import java.text.DateFormat
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Reiter „Header“: oben die Auswertung (SPF/DKIM/DMARC und der Zustellweg mit Verzögerung je Station),
 * darunter alle Kopfzeilen der Mail, schreibgeschützt, mit hervorgehobenen Namen.
 */
class MailHeaderEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val document = EditorFactory.getInstance().createDocument("").apply { setReadOnly(true) }
    private val editor = EditorFactory.getInstance().createViewer(document, project)
    private val summary = object : JPanel() {
        // Breite geändert: Kennzeichen-Zeile neu umbrechen lassen.
        override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
            val changed = width != this.width
            super.setBounds(x, y, width, height)
            if (changed) javax.swing.SwingUtilities.invokeLater { revalidate() }
        }
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
            JBUI.Borders.empty(8, 12),
        )
    }
    private val root = BorderLayoutPanel().addToTop(summary).addToCenter(editor.component)

    init {
        editor.settings.apply {
            isLineNumbersShown = true
            isUseSoftWraps = true
        }
        (editor as EditorEx).setContextMenuGroupId(MailEditorPopupGroup.ID)
        MailEditMode.apply(file)
        MailSource(file, this) { result ->
            val content = result.fold(
                { it.headers ?: MailViewBundle.message("headers.none") },
                { MailViewBundle.message("preview.error", it.message ?: it.javaClass.simpleName) },
            )
            runWriteAction {
                document.setReadOnly(false)
                document.setText(StringUtil.convertLineSeparators(content))
                document.setReadOnly(true)
            }
            highlightNames()
            showSummary(result.getOrNull()?.let(HeaderAnalysis::of))
        }
    }

    /** Namen der Kopfzeilen (am Zeilenanfang bis zum Doppelpunkt) wie Schlüsselwörter einfärben. */
    private fun highlightNames() {
        val markup = editor.markupModel
        markup.removeAllHighlighters()
        Regex("""(?m)^[!-9;-~]+(?=:)""").findAll(document.charsSequence).forEach {
            markup.addRangeHighlighter(DefaultLanguageHighlighterColors.KEYWORD, it.range.first, it.range.last + 1,
                HighlighterLayer.SYNTAX, HighlighterTargetArea.EXACT_RANGE)
        }
    }

    private fun showSummary(analysis: HeaderAnalysis?) {
        summary.removeAll()
        summary.isVisible = analysis != null
        if (analysis != null) {
            if (analysis.auth.isNotEmpty()) {
                val row = BadgeRow().apply { alignmentX = 0f; isOpaque = false }
                row.add(JBLabel(MailViewBundle.message("header.auth")).apply { foreground = UIUtil.getContextHelpForeground() })
                analysis.auth.forEach { row.add(Badge(it)) }
                summary.add(row)
            }
            if (analysis.mailer != null || analysis.unsubscribe.isNotEmpty()) {
                val row = BadgeRow().apply { alignmentX = 0f; isOpaque = false }
                analysis.mailer?.let {
                    row.add(JBLabel(MailViewBundle.message("header.mailer")).apply { foreground = UIUtil.getContextHelpForeground() })
                    row.add(JBLabel(it))
                }
                if (analysis.unsubscribe.isNotEmpty()) {
                    row.add(JBLabel(MailViewBundle.message("header.unsubscribe")).apply { foreground = UIUtil.getContextHelpForeground() })
                    for (target in analysis.unsubscribe) {
                        val mail = target.startsWith("mailto:", true)
                        row.add(ActionLink(MailViewBundle.message(if (mail) "header.unsubscribe.mail" else "header.unsubscribe.web")) { BrowserUtil.browse(target) }
                            .apply { toolTipText = target })
                    }
                    if (analysis.oneClick) row.add(JBLabel(MailViewBundle.message("header.unsubscribe.oneClick")).apply { foreground = UIUtil.getContextHelpForeground() })
                }
                summary.add(row)
            }
            if (analysis.hops.isNotEmpty()) {
                val total = analysis.totalMs
                val title = MailViewBundle.message("header.delivery") + ": " + MailViewBundle.message("delivery.summary", analysis.hops.size) +
                    (total?.let { ", " + MailViewBundle.message("delivery.total", HeaderAnalysis.formatDuration(it)) } ?: "")
                summary.add(JBLabel(title).apply { alignmentX = 0f; border = JBUI.Borders.empty(10, 0, 4, 0) })
                val table = hopTable(analysis.hops)
                summary.add(table.tableHeader.apply { alignmentX = 0f })
                summary.add(table.apply { alignmentX = 0f })
            }
        }
        summary.revalidate()
        summary.repaint()
    }

    /** Received-Kette vom Absender zum Empfänger; langsame Stationen rot. */
    private fun hopTable(hops: List<DeliveryHop>): JBTable {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, DynamicBundle.getLocale())
        val columns = listOf("#", "delivery.from", "delivery.by", "delivery.with", "delivery.time", "delivery.delay")
            .map { if (it == "#") it else MailViewBundle.message(it) }.toTypedArray()
        val rows = hops.mapIndexed { i, hop ->
            arrayOf<Any>(i + 1, hop.from ?: "", hop.by ?: "", hop.with ?: "", hop.date?.let(time::format) ?: "", hop.delayMs ?: "")
        }.toTypedArray()
        val model = object : DefaultTableModel(rows, columns) {
            override fun isCellEditable(row: Int, column: Int) = false
        }
        return JBTable(model).apply {
            setShowGrid(false)
            columnModel.getColumn(0).maxWidth = JBUI.scale(32)
            columnModel.getColumn(3).preferredWidth = JBUI.scale(70)
            columnModel.getColumn(5).preferredWidth = JBUI.scale(70)
            setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): Component {
                    val delay = value as? Long
                    val text = if (delay != null) "+" + HeaderAnalysis.formatDuration(delay) else value?.toString() ?: ""
                    super.getTableCellRendererComponent(t, text, selected, false, row, column)
                    horizontalAlignment = if (column == 0 || column == 5) SwingConstants.RIGHT else SwingConstants.LEFT
                    if (!selected) foreground = if (delay != null && delay > HeaderAnalysis.SLOW_HOP_MS) JBColor.RED else t.foreground
                    toolTipText = text.takeIf { it.isNotEmpty() }
                    return this
                }
            })
        }
    }

    /**
     * Kennzeichen nebeneinander, bei Platzmangel umbrechend. WrapLayout allein meldet vor dem ersten Layout (Breite 0)
     * eine Höhe für eine Spalte untereinander - in der BoxLayout-Spalte entstand so viel Leerraum. Deshalb rechnet die
     * Zeile ihre Höhe selbst mit der Breite der umgebenden Fläche.
     */
    private inner class BadgeRow : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))) {
        override fun getPreferredSize(): Dimension {
            val available = (summary.width - summary.insets.left - summary.insets.right).takeIf { it > 0 } ?: return super.getPreferredSize()
            val gap = JBUI.scale(6)
            var x = 0
            var lineHeight = 0
            var height = 0
            for (c in components) {
                val size = c.preferredSize
                if (x > 0 && x + gap + size.width > available) { height += lineHeight + JBUI.scale(3); x = 0; lineHeight = 0 }
                x += (if (x > 0) gap else 0) + size.width
                lineHeight = maxOf(lineHeight, size.height)
            }
            return Dimension(available, height + lineHeight + JBUI.scale(6))
        }
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    /** Ergebnis einer Prüfung als abgerundetes Kennzeichen: grün bestanden, rot durchgefallen, sonst grau. */
    private class Badge(result: AuthResult) : JBLabel(
        "${result.method.uppercase()} ${result.result}" + (result.detail?.let { "  $it" } ?: "")) {
        private val color: Color = when (result.result) {
            "pass" -> JBColor(Color(0xD7F0DC), Color(0x29452F))
            "fail", "softfail", "permerror" -> JBColor(Color(0xF8D7D6), Color(0x5A2A2A))
            // Spam-Bewertung: „no“ ist das gute Ergebnis.
            "no" -> if (result.method == "spam") JBColor(Color(0xD7F0DC), Color(0x29452F)) else JBColor(Color(0xE6E6E6), Color(0x3C3F41))
            "yes" -> if (result.method == "spam") JBColor(Color(0xF8D7D6), Color(0x5A2A2A)) else JBColor(Color(0xE6E6E6), Color(0x3C3F41))
            else -> JBColor(Color(0xE6E6E6), Color(0x3C3F41))
        }

        init {
            border = JBUI.Borders.empty(1, 8)
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillRoundRect(0, 0, width, height, height, height)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent
    override fun getName(): String = MailViewBundle.message("editor.headers")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = EditorFactory.getInstance().releaseEditor(editor)
}
