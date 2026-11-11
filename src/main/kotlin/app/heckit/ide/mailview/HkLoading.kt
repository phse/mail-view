package app.heckit.ide.mailview

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.FlatteningPathIterator
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Point2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Lade-Animationen von heck\it (Entwürfe: `marketplace/icon-proposals/historie/04-lade-animation.html`).
 * Eigenständige Datei ohne Abhängigkeiten im Plugin, damit andere Plugins sie übernehmen können
 * (nur das Paket anpassen). Läuft nur, solange sie sichtbar ist.
 */
class HkLoading(private val style: Style, private val size: Int = 24) : JComponent() {
    enum class Style {
        /** Mail-View-Umschlag hin und her (A3), Iconfarbe. */
        ENVELOPE,
        /** Wie [ENVELOPE] im heck\it-Verlauf (B3). */
        ENVELOPE_GRADIENT,
        /** Backslash zeichnen und löschen (C). */
        SLASH,
        /** Backslash hin und her (C4). */
        SLASH_PINGPONG,
        /** Drei Punkte in den heck\it-Farben (D). */
        DOTS,
        /** Gleichmäßig drehender Bogen (E). */
        RING,
        /** Bogen wächst und schrumpft beim Drehen (E2). */
        RING_SWING,
    }

    private val start = System.currentTimeMillis()
    private val timer = Timer(16) { repaint() }

    init { isOpaque = false }

    override fun getPreferredSize() = Dimension(JBUI.scale(size), JBUI.scale(size))
    override fun getMinimumSize() = preferredSize

    override fun addNotify() { super.addNotify(); timer.start() }
    override fun removeNotify() { timer.stop(); super.removeNotify() }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val s = JBUI.scale(size).toDouble()
            g2.translate((width - s) / 2, (height - s) / 2)
            g2.scale(s / 16, s / 16)
            paintFrame(g2, System.currentTimeMillis() - start)
        } finally {
            g2.dispose()
        }
    }

    /** Zeichnet ein Bild zur Zeit [t] (ms) im 16er-Raster. */
    private fun paintFrame(g: Graphics2D, t: Long) {
        when (style) {
            Style.ENVELOPE, Style.ENVELOPE_GRADIENT -> {
                val (from, to) = pingPong(t, 4400)
                g.paint = if (style == Style.ENVELOPE) ICON_COLOR else gradient(ENVELOPE_BOUNDS)
                g.stroke = BasicStroke(56f * ENVELOPE_SCALE.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                drawPart(g, ENVELOPE, from, to)
            }
            Style.SLASH, Style.SLASH_PINGPONG -> {
                val (from, to) = if (style == Style.SLASH) drawOnce(t, 1600) else pingPong(t, 2200)
                g.paint = gradient(SLASH.bounds2D)
                g.stroke = BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                drawPart(g, SLASH, from, to)
            }
            Style.DOTS -> HK_COLORS.forEachIndexed { i, color ->
                // Wie CSS: 0/80/100 % blass und klein, 40 % voll; je Punkt 150 ms später.
                val p = (((t - i * 150) % 1200 + 1200) % 1200) / 1200.0
                val k = when {
                    p < 0.4 -> easeInOut(p / 0.4)
                    p < 0.8 -> 1 - easeInOut((p - 0.4) / 0.4)
                    else -> 0.0
                }
                val r = 1.8 * (0.7 + 0.3 * k)
                g.color = alpha(color, 0.25 + 0.75 * k)
                g.fill(Ellipse2D.Double(3.0 + 5 * i - r, 8 - r, 2 * r, 2 * r))
            }
            Style.RING, Style.RING_SWING -> {
                g.color = alpha(ICON_COLOR, 0.2)
                g.stroke = BasicStroke(1.6f)
                g.draw(Ellipse2D.Double(2.0, 2.0, 12.0, 12.0))
                val (tail, head) = if (style == Style.RING) {
                    val a = (t % 1000) / 1000.0 * 2 * PI
                    a to a + 26 / 37.7 * 2 * PI
                } else swingArc(t)
                g.paint = gradient(java.awt.geom.Rectangle2D.Double(2.0, 2.0, 12.0, 12.0))
                g.stroke = BasicStroke(if (style == Style.RING) 1.6f else 1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                // Arc2D zählt gegen den Uhrzeigersinn ab 3 Uhr; hier im Uhrzeigersinn ab 12 Uhr.
                val startDeg = 90 - Math.toDegrees(tail)
                g.draw(Arc2D.Double(2.0, 2.0, 12.0, 12.0, startDeg, -Math.toDegrees(head - tail), Arc2D.OPEN))
            }
        }
    }

    companion object {
        private val HK_COLORS = listOf(Color(0xB04079), Color(0x5A5A8C), Color(0x2A7AA0))
        private val ICON_COLOR = JBColor(0x6C707E, 0xCED0D6)

        /** Symbol aus `pluginIcon.svg` (1024er-Raster), ein Strich mit Lücken am Anfang und Ende. */
        private const val ENVELOPE_SCALE = 0.0234
        private val ENVELOPE: Shape = Path2D.Double().apply {
            val k = 64 * 0.5523
            moveTo(692.0, 405.0); lineTo(512.0, 542.0); lineTo(258.0, 349.0)
            quadTo(232.0, 329.0, 247.0, 314.0); quadTo(259.0, 302.0, 286.0, 302.0)
            lineTo(738.0, 302.0); curveTo(738 + k, 302.0, 802.0, 366 - k, 802.0, 366.0)
            lineTo(802.0, 658.0); curveTo(802.0, 658 + k, 738 + k, 722.0, 738.0, 722.0)
            lineTo(286.0, 722.0); curveTo(286 - k, 722.0, 222.0, 658 + k, 222.0, 658.0)
            lineTo(222.0, 450.0)
        }.let { AffineTransform().apply { translate(8.0, 8.0); scale(ENVELOPE_SCALE, ENVELOPE_SCALE); translate(-512.0, -512.0) }.createTransformedShape(it) }
        private val ENVELOPE_BOUNDS = ENVELOPE.bounds2D
        private val SLASH: Shape = Line2D.Double(4.5, 2.0, 11.5, 14.0)

        private fun gradient(b: java.awt.geom.Rectangle2D) = LinearGradientPaint(
            Point2D.Double(b.minX, b.minY), Point2D.Double(max(b.maxX, b.minX + 0.01), max(b.maxY, b.minY + 0.01)),
            floatArrayOf(0f, 0.5f, 1f), HK_COLORS.toTypedArray())

        private fun alpha(c: Color, a: Double) = Color(c.red, c.green, c.blue, (a * 255).toInt().coerceIn(0, 255))

        /** Ähnlich cubic-bezier(.6,0,.4,1): langsam an, schnell, langsam aus. */
        private fun easeInOut(x: Double) = if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

        /** Sichtbarer Teil (0…1 der Pfadlänge): zeichnen und löschen, beides von A nach B. */
        private fun drawOnce(t: Long, period: Long): Pair<Double, Double> {
            val p = (t % period) / period.toDouble()
            return when {
                p < 0.45 -> 0.0 to easeInOut(p / 0.45)
                p < 0.55 -> 0.0 to 1.0
                else -> easeInOut((p - 0.55) / 0.45) to 1.0
            }
        }

        /** Hin und her: zeichnen A→B, löschen A→B, zeichnen B→A, löschen B→A; voll jeweils kurz stehen. */
        private fun pingPong(t: Long, period: Long): Pair<Double, Double> {
            val p = (t % period) / period.toDouble()
            return when {
                p < 0.22 -> 0.0 to easeInOut(p / 0.22)
                p < 0.28 -> 0.0 to 1.0
                p < 0.50 -> easeInOut((p - 0.28) / 0.22) to 1.0
                p < 0.72 -> 1 - easeInOut((p - 0.5) / 0.22) to 1.0
                p < 0.78 -> 0.0 to 1.0
                else -> 0.0 to 1 - easeInOut((p - 0.78) / 0.22)
            }
        }

        /**
         * Ende und Kopf des Bogens (Bogenmaß ab 12 Uhr, im Uhrzeigersinn). Je Durchgang läuft erst der Kopf voraus,
         * dann holt das Ende auf; über alle Durchgänge stetig, daher ohne Sprung.
         */
        private fun swingArc(t: Long): Pair<Double, Double> {
            val period = 1500.0
            val k = floor(t / period)
            val p = (t % period) / period
            val reach = 0.70 * 2 * PI
            val spin = t * 2 * PI / 2400
            val head = reach * (k + easeInOut(min(p * 2, 1.0))) + 0.08 + spin
            val tail = reach * (k + easeInOut(max(p * 2 - 1, 0.0))) + spin
            return tail to head
        }

        /** Zeichnet den Abschnitt [from]…[to] (Anteile der Länge) von [shape]; leere Abschnitte gar nicht (kein Punkt). */
        private fun drawPart(g: Graphics2D, shape: Shape, from: Double, to: Double) {
            if (to - from < 0.002) return
            val points = mutableListOf<Point2D.Double>()
            val it = FlatteningPathIterator(shape.getPathIterator(null), 0.01)
            val c = DoubleArray(6)
            while (!it.isDone) {
                if (it.currentSegment(c) != PathIterator.SEG_CLOSE) points += Point2D.Double(c[0], c[1])
                it.next()
            }
            val lengths = DoubleArray(points.size)
            for (i in 1 until points.size) lengths[i] = lengths[i - 1] + points[i].distance(points[i - 1])
            val total = lengths.last()
            val a = from * total
            val b = to * total
            fun at(d: Double): Point2D.Double {
                val i = lengths.indexOfFirst { it >= d }.coerceAtLeast(1)
                val seg = lengths[i] - lengths[i - 1]
                val f = if (seg == 0.0) 0.0 else (d - lengths[i - 1]) / seg
                return Point2D.Double(points[i - 1].x + f * (points[i].x - points[i - 1].x), points[i - 1].y + f * (points[i].y - points[i - 1].y))
            }
            val path = Path2D.Double()
            at(a).let { path.moveTo(it.x, it.y) }
            for (i in points.indices) if (lengths[i] > a && lengths[i] < b) path.lineTo(points[i].x, points[i].y)
            at(b).let { path.lineTo(it.x, it.y) }
            g.draw(path)
        }
    }
}

/** Animation mit Text darunter, mittig in der Fläche (z. B. statt einer leeren Liste). */
class HkLoadingPanel(style: HkLoading.Style, text: String) : JPanel(GridBagLayout()) {
    private val label = JBLabel(text, SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }

    var text: String
        get() = label.text
        set(value) { label.text = value }

    init {
        isOpaque = false
        val c = GridBagConstraints().apply { gridx = 0; gridy = 0 }
        add(HkLoading(style, 24), c)
        c.gridy = 1; c.insets = JBUI.insetsTop(8)
        add(label, c)
    }

}
