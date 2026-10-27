package app.heckit.ide.mailview

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Point
import java.awt.RenderingHints
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.Timer
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Ein Anhang als Kachel: Dateityp-Icon, Name und Größe. Klick zeigt das Menü; mit Sondertaste
 * direkt öffnen, im Dateimanager zeigen oder mit dem Systemprogramm öffnen (siehe [ClickModifier]).
 * Ziehen nimmt die ganze Kachel als Bild mit; eine Vorschau beim Darüberfahren ist vorbereitet, aber aus (SHOW_PREVIEW).
 */
internal class AttachmentChip(
    private val attachment: MailAttachment,
    private val menu: JPopupMenu,
    private val open: () -> Unit,
    private val reveal: () -> Unit,
    private val openWithSystem: () -> Unit,
    transferable: () -> Transferable,
) : JPanel(BorderLayout(JBUI.scale(6), 0)) {
    private var hover = false
    private var pressedAt: Point? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val name = JBLabel(attachment.name, icon(attachment.name), JBLabel.LEFT).apply { iconTextGap = JBUI.scale(6) }
        val size = JBLabel(StringUtil.formatFileSize(attachment.data.size.toLong())).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBFont.small()
        }
        add(name, BorderLayout.CENTER)
        add(size, BorderLayout.EAST)

        transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent) = COPY
            override fun createTransferable(c: JComponent): Transferable = transferable()
        }
        val mouse = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                setHover(true)
                // Ohne Vorschau zeigt der Tooltip den vollen (ggf. abgeschnittenen) Namen.
                if (SHOW_PREVIEW && preview != null) previewTimer.restart() else if (!menu.isVisible) toolTipText = attachment.name
            }
            override fun mouseExited(e: MouseEvent) {
                setHover(contains(e.point))
                if (!hover) hidePreview()
            }
            override fun mousePressed(e: MouseEvent) {
                pressedAt = e.point
                hidePreview()
            }
            // Klick zeigt das Menü (wie bei den Adressen), mit Sondertaste geht es direkt. Das Menü hängt nicht als
            // componentPopupMenu an der Kachel: Ctrl+Klick wäre auf dem Mac sonst immer Rechtsklick.
            override fun mouseClicked(e: MouseEvent) {
                val action = when {
                    ClickModifier.OPEN.matches(e) -> open
                    ClickModifier.REVEAL.matches(e) -> reveal
                    ClickModifier.SYSTEM.matches(e) -> openWithSystem
                    else -> return menu.show(this@AttachmentChip, e.x, e.y)
                }
                menu.isVisible = false
                ApplicationManager.getApplication().invokeLater(action)
            }
            override fun mouseDragged(e: MouseEvent) {
                val handler = transferHandler
                // Die ganze Kachel als Ziehbild, an der Stelle gegriffen, an der die Maus sie gepackt hat.
                handler.dragImage = snapshot()
                pressedAt?.let { handler.dragImageOffset = Point(-it.x, -it.y) }
                handler.exportAsDrag(this@AttachmentChip, e, TransferHandler.COPY)
            }
        }
        // Nur die Kachel hört auf die Maus: Labels ohne eigene Listener lassen Klick, Rechtsklick und Tooltip
        // (Vorschau) zur Kachel durch; mit eigenen Listenern käme die Vorschau nur am Rand an.
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        // Solange das Menü offen ist, kein Tooltip mit dem Namen über dem Menü.
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) { toolTipText = null }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { if (hover) toolTipText = attachment.name }
            override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
        })
    }

    override fun getMaximumSize(): Dimension = preferredSize
    override fun getPreferredSize(): Dimension =
        super.getPreferredSize().let { Dimension(minOf(it.width, JBUI.scale(MAX_WIDTH)), it.height) }

    private fun setHover(on: Boolean) {
        if (hover != on) { hover = on; repaint() }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            g2.color = if (hover) JBUI.CurrentTheme.ActionButton.hoverBackground() else JBColor.namedColor("Panel.background", UIUtil.getPanelBackground())
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = if (hover) JBUI.CurrentTheme.ActionButton.hoverBorder() else JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Ziehbild in Originalgröße. Ein HiDPI-Bild von ImageUtil nähme der Mac mit seinen Gerätepixeln (doppelt so groß);
     * ein MultiResolutionImage aus 1x und Bildschirmmaßstab bleibt gleich groß und auf Retina scharf.
     */
    private fun snapshot(): Image {
        val hovered = hover
        hover = false
        val scale = graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        fun render(factor: Double) = BufferedImage(maxOf(1, (width * factor).toInt()), maxOf(1, (height * factor).toInt()), BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            try { g.scale(factor, factor); paint(g) } finally { g.dispose() }
        }
        try {
            return if (scale > 1.0) BaseMultiResolutionImage(render(1.0), render(scale)) else render(1.0)
        } finally {
            hover = hovered
        }
    }

    // Vorschau beim Darüberfahren: Bilder verkleinert, Text mit den ersten Zeilen; sonst nur der Name.
    private val preview: JComponent? by lazy { imagePreview() ?: textPreview() }

    // Die IDE ersetzt Swing-Tooltips durch eigene (nur Text, createToolTip wird nie gefragt) - die Vorschau ist
    // deshalb ein eigenes Popup, das nach kurzer Verzögerung erscheint und beim Verlassen oder Klicken verschwindet.
    private var previewPopup: JBPopup? = null
    private val previewTimer = Timer(PREVIEW_DELAY) { showPreview() }.apply { isRepeats = false }

    private fun showPreview() {
        val content = preview ?: return
        if (!isShowing || previewPopup?.isVisible == true) return
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(
            JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6); add(content) }, null)
            .setFocusable(false).setRequestFocus(false).setCancelOnClickOutside(true).createPopup()
        previewPopup = popup
        popup.show(RelativePoint(this, Point(0, height + JBUI.scale(4))))
    }

    private fun hidePreview() {
        previewTimer.stop()
        previewPopup?.cancel()
        previewPopup = null
    }

    override fun removeNotify() {
        hidePreview()
        super.removeNotify()
    }

    private fun imagePreview(): JComponent? {
        if (!attachment.mimeType.startsWith("image/") && FileTypeManager.getInstance().getFileTypeByFileName(attachment.name).name != "Image") return null
        if (attachment.data.size > MAX_PREVIEW_BYTES) return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(attachment.data)) }.getOrNull() ?: return null
        val box = JBUI.scale(PREVIEW_SIZE).toDouble()
        val factor = minOf(box / image.width, box / image.height, 1.0)
        val w = maxOf(1, (image.width * factor).toInt())
        val h = maxOf(1, (image.height * factor).toInt())
        return object : JComponent() {
            override fun getPreferredSize() = Dimension(w, h)
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.drawImage(image, 0, 0, w, h, null)
            }
        }
    }

    private fun textPreview(): JComponent? {
        val type = FileTypeManager.getInstance().getFileTypeByFileName(attachment.name)
        val text = attachment.mimeType.startsWith("text/") || (type != UnknownFileType.INSTANCE && !type.isBinary)
        if (!text || attachment.data.size > MAX_PREVIEW_BYTES) return null
        val lines = String(attachment.data.copyOf(minOf(attachment.data.size, 4096)), Charsets.UTF_8).lines()
            .take(PREVIEW_LINES).joinToString("\n") { it.take(80) }
        return JBLabel("<html><pre>${StringUtil.escapeXmlEntities(lines)}</pre></html>").apply { font = JBFont.small() }
    }

    private companion object {
        const val MAX_WIDTH = 280
        /** Vorschau beim Darüberfahren (Bilder, Text) - vorerst aus, der Code bleibt für später. */
        const val SHOW_PREVIEW = false
        const val PREVIEW_DELAY = 500
        const val PREVIEW_SIZE = 320
        const val PREVIEW_LINES = 12
        const val MAX_PREVIEW_BYTES = 15 * 1024 * 1024

        fun icon(name: String): Icon {
            val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
            return if (type == UnknownFileType.INSTANCE) AllIcons.FileTypes.Any_type else type.icon ?: AllIcons.FileTypes.Any_type
        }
    }
}

/** Sondertasten für einen Klick auf einen Anhang; auf dem Mac ⌘/⌥/⌃, sonst Ctrl/Alt/Shift. */
internal enum class ClickModifier(private val mac: Int, private val other: Int, private val macSymbol: String, private val otherName: String) {
    OPEN(InputEvent.META_DOWN_MASK, InputEvent.CTRL_DOWN_MASK, "⌘", "Ctrl"),
    REVEAL(InputEvent.ALT_DOWN_MASK, InputEvent.ALT_DOWN_MASK, "⌥", "Alt"),
    SYSTEM(InputEvent.CTRL_DOWN_MASK, InputEvent.SHIFT_DOWN_MASK, "⌃", "Shift");

    private val mask get() = if (SystemInfo.isMac) mac else other

    fun matches(e: MouseEvent): Boolean {
        val modifiers = e.modifiersEx and (InputEvent.META_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
        // Ctrl+Klick meldet der Mac als Rechtsklick (Popup-Trigger) - deshalb nicht nach der Taste der Maus fragen.
        return modifiers == mask && (SystemInfo.isMac && this == SYSTEM || SwingUtilities.isLeftMouseButton(e))
    }

    /** „⌘-Klick“ bzw. „Ctrl+Click“ für den Hinweis im Menü. */
    val hint: String get() = MailViewBundle.message("attachments.click", if (SystemInfo.isMac) macSymbol else otherName)
}

/**
 * Menüeintrag mit rechtsbündigem Hinweis wie ein Tastenkürzel („⌘-Klick“). Ein echtes Accelerator-Kürzel geht nicht:
 * Das wäre eine Taste, kein Klick.
 */
internal class HintMenuItem(text: String, private val hint: String?) : JBMenuItem(text) {
    override fun getPreferredSize(): Dimension = super.getPreferredSize().let { size ->
        if (hint == null) size else Dimension(size.width + getFontMetrics(font).stringWidth(hint) + JBUI.scale(GAP), size.height)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        hint ?: return
        val g2 = g.create() as Graphics2D
        try {
            UISettings.setupAntialiasing(g2)
            g2.font = font
            g2.color = if (isArmed || isSelected) UIManager.getColor("MenuItem.selectionForeground") ?: foreground
                else UIManager.getColor("MenuItem.acceleratorForeground") ?: UIUtil.getContextHelpForeground()
            val metrics = g2.fontMetrics
            val x = width - insets.right - JBUI.scale(RIGHT) - metrics.stringWidth(hint)
            g2.drawString(hint, x, (height - metrics.height) / 2 + metrics.ascent)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val GAP = 24
        const val RIGHT = 12
    }
}
