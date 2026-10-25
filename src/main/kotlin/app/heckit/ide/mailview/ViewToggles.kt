package app.heckit.ide.mailview

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Darstellung der Mail rechts oben über dem Mailtext: HTML/Text und Hell/Dunkel, untereinander und gleich breit.
 * Jeder Knopf zeigt den aktiven Zustand. Beide sind stark verblasst, damit sie die Mail nicht verdecken; fährt die
 * Maus über den Bereich (Knöpfe samt Rand), kommen beide nach vorn. Die Farben folgen der Mail (hell/dunkel), auf
 * der die Knöpfe liegen - in IDE-Farben wirkte ein dunkler Knopf auf heller Mail verblasst nur grau und verwaschen.
 */
class ViewToggles(plainText: () -> Unit, dark: () -> Unit) : JPanel(null) {
    private val plainButton = Pill(
        listOf(MailViewBundle.message("preview.html"), MailViewBundle.message("preview.plainText")), listOf(Glyph.HTML, Glyph.TEXT), plainText)
    private val darkButton = Pill(
        listOf(MailViewBundle.message("preview.light"), MailViewBundle.message("preview.dark")), listOf(Glyph.SUN, Glyph.MOON), dark)
    private var hover = false
    private var darkMail = false
    /** Austritte gehen gelegentlich verloren (schnelle Bewegung über den Rand); solange aktiv, nachsehen. */
    private val hoverCheck = Timer(150) { updateHover() }
    /** Deckkraft, gleitet bei Hover in etwa 150 ms zwischen verblasst und voll. */
    private var alpha = FADED
    private val fade = Timer(16) { e ->
        val target = if (hover) 1f else FADED
        alpha = if (alpha < target) minOf(target, alpha + STEP) else maxOf(target, alpha - STEP)
        if (alpha == target) (e.source as Timer).stop()
        repaintArea()
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(PAD)
        add(plainButton)
        add(darkButton)
        val tracker = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = updateHover()
            override fun mouseExited(e: MouseEvent) = updateHover()
        }
        listOf(this, plainButton, darkButton).forEach { it.addMouseListener(tracker) }
    }

    /** Ein- und Austritt der Kinder melden auch Wechsel innerhalb des Bereichs; deshalb die Mausposition prüfen. */
    private fun updateHover() {
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        SwingUtilities.convertPointFromScreen(pointer, this)
        val inside = isShowing && contains(pointer)
        if (inside != hover) { hover = inside; fade.start() }
        if (hover) hoverCheck.start() else hoverCheck.stop()
    }

    /**
     * Immer über das Elternteil neu zeichnen: Es malt erst die Mail darunter. Zeichnet ein halb durchsichtiger Knopf
     * allein neu, legt er sich über sein altes Bild statt über die Mail.
     */
    private fun repaintArea() = parent?.repaint(x, y, width, height) ?: repaint()

    /** Zustand übernehmen; [canPlain]: Die Mail hat HTML- und Textfassung. */
    fun update(plain: Boolean, canPlain: Boolean, dark: Boolean) {
        plainButton.isVisible = canPlain
        plainButton.state = if (plain) 1 else 0
        darkButton.state = if (dark) 1 else 0
        darkMail = dark
        revalidate()
        repaintArea()
    }

    private val visibleButtons get() = listOf(plainButton, darkButton).filter { it.isVisible }
    private val gap get() = JBUI.scale(6)

    override fun getPreferredSize(): Dimension {
        val buttons = visibleButtons
        val width = listOf(plainButton, darkButton).maxOf { it.preferredSize.width }
        val pad = JBUI.scale(PAD) * 2
        return Dimension(width + pad, buttons.sumOf { it.preferredSize.height } + gap * (buttons.size - 1) + pad)
    }

    override fun doLayout() {
        val pad = JBUI.scale(PAD)
        var y = pad
        for (b in visibleButtons) {
            val h = b.preferredSize.height
            b.setBounds(pad, y, width - 2 * pad, h)
            y += h + gap
        }
    }

    override fun paintChildren(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        super.paintChildren(g2)
        g2.dispose()
    }

    private enum class Glyph { HTML, TEXT, SUN, MOON }

    /** Knopf mit Icon und Text des aktiven Zustands ([state] 0/1); Breite reicht für beide Texte. */
    private inner class Pill(private val labels: List<String>, private val glyphs: List<Glyph>, onClick: () -> Unit) : JComponent() {
        var state = 0
        private var hover = false

        init {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = JBUI.Fonts.smallFont()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
                override fun mouseEntered(e: MouseEvent) { hover = true; repaintArea() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaintArea() }
            })
        }

        private val icon get() = JBUI.scale(13)

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font)
            return Dimension(JBUI.scale(8) + icon + JBUI.scale(6) + labels.maxOf { fm.stringWidth(it) } + JBUI.scale(10), fm.height + JBUI.scale(8))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val arc = JBUI.scale(12)
            g2.color = when {
                darkMail -> if (hover) Color(0x45, 0x47, 0x4a) else Color(0x2b, 0x2d, 0x30)
                else -> if (hover) Color(0xEB, 0xEC, 0xF0) else Color(0xFF, 0xFF, 0xFF)
            }
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = if (darkMail) Color(0x5a, 0x5d, 0x63) else Color(0xC9, 0xCC, 0xD6)
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = if (darkMail) Color(0xDF, 0xE1, 0xE5) else Color(0x27, 0x28, 0x2E)
            val x = JBUI.scale(8).toFloat()
            val y = ((height - icon) / 2).toFloat()
            val s = icon.toFloat()
            g2.stroke = BasicStroke(JBUIScale.scale(1.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            when (glyphs[state]) {
                Glyph.TEXT -> {
                    val path = Path2D.Float()
                    for ((i, len) in listOf(1f, 1f, 0.7f, 0.5f).withIndex()) {
                        val ly = y + s * (0.2f + i * 0.2f)
                        path.moveTo(x, ly)
                        path.lineTo(x + s * len, ly)
                    }
                    g2.draw(path)
                }
                Glyph.HTML -> {
                    val path = Path2D.Float()
                    path.moveTo(x + s * 0.3f, y + s * 0.2f); path.lineTo(x, y + s * 0.5f); path.lineTo(x + s * 0.3f, y + s * 0.8f)
                    path.moveTo(x + s * 0.7f, y + s * 0.2f); path.lineTo(x + s, y + s * 0.5f); path.lineTo(x + s * 0.7f, y + s * 0.8f)
                    path.moveTo(x + s * 0.58f, y + s * 0.1f); path.lineTo(x + s * 0.42f, y + s * 0.9f)
                    g2.draw(path)
                }
                Glyph.MOON -> {
                    val moon = Area(Ellipse2D.Float(x + 0.5f, y + 0.5f, s - 1f, s - 1f))
                    moon.subtract(Area(Ellipse2D.Float(x + s * 0.38f, y - s * 0.2f, s * 0.85f, s * 0.85f)))
                    g2.draw(moon)
                }
                Glyph.SUN -> {
                    val c = s / 2
                    g2.draw(Ellipse2D.Float(x + c - s * 0.2f, y + c - s * 0.2f, s * 0.4f, s * 0.4f))
                    for (i in 0 until 8) {
                        val a = Math.PI / 4 * i
                        val (dx, dy) = Math.cos(a).toFloat() to Math.sin(a).toFloat()
                        g2.draw(Line2D.Float(x + c + dx * s * 0.34f, y + c + dy * s * 0.34f, x + c + dx * s * 0.48f, y + c + dy * s * 0.48f))
                    }
                }
            }
            val fm = g2.fontMetrics
            g2.drawString(labels[state], x.toInt() + icon + JBUI.scale(6), (height - fm.height) / 2 + fm.ascent)
            g2.dispose()
        }
    }

    private companion object {
        const val PAD = 8
        const val FADED = 0.4f
        const val STEP = (1f - FADED) / 9
    }
}
