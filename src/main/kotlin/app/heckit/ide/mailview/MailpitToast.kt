package app.heckit.ide.mailview

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Kleiner Hinweis, der über der Liste schwebt: selbst gezeichnet (runde Ecken, heller Grund), langsam ein-,
 * schneller ausgeblendet. Kein Popup-Fenster – das hätte eckige Inhalte in einem runden Fenster.
 */
class MailpitToast : JPanel() {
    private val label = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val animation = HkLoading(HkLoading.Style.ENVELOPE, 16)
    private val gap = Box.createHorizontalStrut(JBUI.scale(8))
    private var alpha = 0f
    private var phase = Phase.HIDDEN
    private var phaseStart = 0L
    private var holdUntilDone = false
    private val ticker = Timer(16) { tick() }
    var onResize: () -> Unit = {}

    private enum class Phase { HIDDEN, IN, HOLD, OUT }

    init {
        isOpaque = false
        isVisible = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(7, 14)
        add(animation); add(gap); add(label)
    }

    /** Einblenden mit [text]; [hold]: nach [HOLD_MS] wieder ausblenden, sonst stehen lassen bis zum nächsten Aufruf. */
    fun show(text: String, loading: Boolean, hold: Boolean) {
        label.text = text
        animation.isVisible = loading
        gap.isVisible = loading
        holdUntilDone = !hold
        if (phase == Phase.HIDDEN || phase == Phase.OUT) start(Phase.IN) else start(Phase.HOLD)
        isVisible = true
        onResize()
    }

    fun hideNow() {
        ticker.stop()
        phase = Phase.HIDDEN
        alpha = 0f
        isVisible = false
    }

    private fun start(next: Phase) {
        phase = next
        phaseStart = System.currentTimeMillis()
        ticker.start()
    }

    private fun tick() {
        val t = System.currentTimeMillis() - phaseStart
        when (phase) {
            Phase.IN -> { alpha = ease(t / FADE_IN_MS.toFloat()); if (t >= FADE_IN_MS) start(Phase.HOLD) }
            Phase.HOLD -> { alpha = 1f; if (!holdUntilDone && t >= HOLD_MS) start(Phase.OUT) }
            Phase.OUT -> { alpha = 1 - ease(t / FADE_OUT_MS.toFloat()); if (t >= FADE_OUT_MS) hideNow() }
            Phase.HIDDEN -> ticker.stop()
        }
        repaint()
    }

    private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    override fun paint(g: Graphics) {
        if (alpha <= 0f) return
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            super.paint(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(16).toDouble()
        val shape = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc)
        g2.color = BACKGROUND
        g2.fill(shape)
        g2.color = BORDER
        g2.draw(shape)
    }

    companion object {
        private const val FADE_IN_MS = 320
        private const val HOLD_MS = 1300
        private const val FADE_OUT_MS = 180
        private val BACKGROUND = JBColor(0xFFFFFF, 0x3C3E42)
        private val BORDER = JBColor(0xDFE1E5, 0x4E5157)
    }
}
