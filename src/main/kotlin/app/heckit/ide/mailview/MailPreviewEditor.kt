package app.heckit.ide.mailview

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import com.intellij.util.ui.components.BorderLayoutPanel
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.CefSettings
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.FlowLayout
import java.awt.Rectangle
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Reiter „Mail“: die Mail so, wie ein Mailprogramm sie zeigt. */
class MailPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val root = BorderLayoutPanel()
    private val bannerHolder = BorderLayoutPanel()
    private val attachmentsPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(4)))
    // Seit 2026.x rendert JCEF immer offscreen; scrollt Chromium dann selbst, ruckelt es. Deshalb bekommt der
    // Browser die volle Seitenhöhe und Swing scrollt (wie in den anderen Reitern), nur sehr lange Mails nicht.
    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser.createBuilder().build() else null
    private var pageHeight = 0
    private val browserHost = object : JPanel(null) {
        // Browser und Mausrad-Fläche liegen übereinander und füllen beide die ganze Fläche.
        override fun doLayout() = components.forEach { it.setBounds(0, 0, width, height) }
        override fun isOptimizedDrawingEnabled() = false

        override fun getPreferredSize(): Dimension {
            val extent = (parent?.parent as? JViewport)?.extentSize?.height ?: 0
            val viewport = maxOf(0, extent - (parent?.height ?: 0) + height)
            return Dimension(0, if (pageHeight in 1..MAX_SWING_HEIGHT) maxOf(pageHeight, viewport) else viewport)
        }

        // Bis die Seite ihre Höhe gemeldet hat, ist der Browser noch falsch bemessen (schmal und hoch) - nicht zeigen.
        override fun paintChildren(g: Graphics) {
            if (!loading) super.paintChildren(g)
        }
    }
    private var loading = true
    private val revealTimer = Timer(120) {
        loading = false
        browserHost.repaint()
    }.apply { isRepeats = false }
    private var dark = MailViewSettings.dark
    // Anhänge stehen über der Mail und scrollen mit weg.
    private val scrollContent = object : BorderLayoutPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.addToTop(attachmentsPanel).addToCenter(browserHost)
    private val scrollPane = JBScrollPane(scrollContent).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val fallback = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private var mail: ParsedMail? = null
    private var allowRemote = false
    private var attachmentsExpanded = false

    init {
        if (browser != null) {
            Disposer.register(this, browser)
            installLinkHandling(browser)
            installHeightTracking(browser)
            browserHost.add(wheelCatcher(browser))
            browserHost.add(browser.component)
            root.addToCenter(scrollPane)
            val toolbar = ActionManager.getInstance().createActionToolbar("MailView.Mail", DefaultActionGroup(DarkAction()), true)
            toolbar.targetComponent = scrollPane
            root.addToTop(BorderLayoutPanel().addToTop(toolbar.component).addToCenter(bannerHolder))
            attachmentsPanel.border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
        } else {
            root.addToCenter(JBScrollPane(fallback))
            attachmentsPanel.border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
            root.addToBottom(attachmentsPanel)
        }
        MailEditMode.apply(file)
        MailSource(file, this) { show(it) }
    }

    private fun show(result: Result<ParsedMail>) {
        val parsed = result.getOrElse {
            mail = null
            attachmentsPanel.isVisible = false
            showText(MailViewBundle.message("preview.error", it.message ?: it.javaClass.simpleName))
            return
        }
        mail = parsed
        render()
        updateRemoteBanner(parsed)
        updateAttachments(parsed)
    }

    private fun render() {
        val parsed = mail ?: return
        if (browser != null) {
            pageHeight = 0
            loading = true
            browserHost.background = if (dark) DARK_BACKGROUND else Color.WHITE
            scrollPane.verticalScrollBar.value = 0
            browser.loadHTML(MailHtmlRenderer.render(parsed, allowRemote, "console.log('$HEIGHT_PREFIX' + h)", dark))
        }
        else showText(parsed.text ?: StringUtil.stripHtml(parsed.html ?: "", true))
    }

    private fun showText(text: String) {
        if (browser != null) browser.loadHTML("<pre>${StringUtil.escapeXmlEntities(text)}</pre>") else fallback.text = text
    }

    private fun updateRemoteBanner(parsed: ParsedMail) {
        bannerHolder.removeAll()
        if (!allowRemote && browser != null && MailHtmlRenderer.hasRemoteContent(parsed)) {
            val banner = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
            banner.text = MailViewBundle.message("preview.remoteBlocked")
            banner.createActionLabel(MailViewBundle.message("preview.remoteLoad")) {
                allowRemote = true
                updateRemoteBanner(parsed)
                render()
            }
            bannerHolder.addToCenter(banner)
        }
        root.revalidate()
        root.repaint()
    }

    private fun updateAttachments(parsed: ParsedMail) {
        attachmentsPanel.removeAll()
        val all = parsed.attachments + MailHtmlRenderer.unreferencedInline(parsed)
        attachmentsPanel.isVisible = all.isNotEmpty()
        if (all.isNotEmpty()) {
            val toggle = JBLabel(MailViewBundle.message("attachments.label", all.size))
            toggle.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toggle.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    attachmentsExpanded = !attachmentsExpanded
                    updateAttachments(parsed)
                }
            })
            toggle.icon = if (attachmentsExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            attachmentsPanel.add(toggle)
            if (attachmentsExpanded) for (attachment in all) {
                val link = ActionLink("${attachment.name} (${StringUtil.formatFileSize(attachment.data.size.toLong())})") { open(attachment) }
                link.toolTipText = MailViewBundle.message("attachments.open.tooltip")
                attachmentsPanel.add(link)
                val save = ActionLink(MailViewBundle.message("attachments.save")) { save(attachment) }
                attachmentsPanel.add(save)
            }
        }
        attachmentsPanel.revalidate()
        attachmentsPanel.repaint()
    }

    /** Für die Marketplace-Screenshots. */
    internal fun expandAttachments() {
        attachmentsExpanded = true
        mail?.let { updateAttachments(it) }
    }

    private fun open(attachment: MailAttachment) {
        val dir = FileUtil.createTempDirectory("mail-view", null, true)
        val target = File(dir, FileUtil.sanitizeFileName(attachment.name, false))
        target.writeBytes(attachment.data)
        RevealFileAction.openFile(target)
    }

    private fun save(attachment: MailAttachment) {
        val descriptor = FileSaverDescriptor(MailViewBundle.message("attachments.save.title"), "")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(file.parent, FileUtil.sanitizeFileName(attachment.name, false)) ?: return
        wrapper.file.writeBytes(attachment.data)
        wrapper.virtualFile?.refresh(true, false)
    }

    /**
     * Die Seite meldet ihre Höhe; der Browser wird so hoch und die Scrollpane scrollt. Ist die Seite zu hoch für
     * einen Offscreen-Puffer, bleibt der Browser so hoch wie der Sichtbereich und scrollt selbst.
     */
    private fun installHeightTracking(browser: JBCefBrowser) {
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(b: CefBrowser?, level: CefSettings.LogSeverity?, message: String?, source: String?, line: Int): Boolean {
                val height = message?.removePrefix(HEIGHT_PREFIX)?.takeIf { it != message }?.toDoubleOrNull()?.toInt() ?: return false
                ApplicationManager.getApplication().invokeLater { updatePageHeight(height) }
                return true
            }
        }, browser.cefBrowser)
    }

    /**
     * Unsichtbare Fläche über dem Browser, die nur Mausrad-Ereignisse annimmt (Klicks gehen weiter an den Browser):
     * Das Offscreen-Bauteil würde das Mausrad sonst selbst verarbeiten. Solange Swing scrollt, geht es an die
     * Scrollpane, sonst (lange Mails, Zoomen mit Cmd/Ctrl) an den Browser.
     */
    private fun wheelCatcher(browser: JBCefBrowser) = object : JComponent() {
        init {
            addMouseWheelListener { e ->
                val swing = pageHeight in 1..MAX_SWING_HEIGHT && !e.isControlDown && !e.isMetaDown
                val target = if (swing) scrollPane else browser.component
                target.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, target))
            }
        }

        // Mauszeiger des Browsers zeigen (Text, Link), nicht den eigenen.
        override fun getCursor(): Cursor = browser.component.cursor
    }

    private fun updatePageHeight(height: Int) {
        // Erst zeigen, wenn die Höhe kurz stabil ist und Chromium in der neuen Größe gezeichnet hat.
        if (loading) revealTimer.restart()
        if (height == pageHeight) return
        pageHeight = height
        val selfScroll = height > MAX_SWING_HEIGHT
        browser?.cefBrowser?.executeJavaScript(
            "document.documentElement.style.overflow = document.body.style.overflow = '${if (selfScroll) "auto" else "hidden"}'", "", 0)
        browserHost.revalidate()
    }

    /** Mail hell oder dunkel zeigen; Vorgabe ist das IDE-Theme, eine Wahl gilt danach für alle Mails. */
    private inner class DarkAction : DumbAwareToggleAction(MailViewBundle.message("preview.dark"), null, AllIcons.Actions.Lightning) {
        override fun isSelected(e: AnActionEvent) = dark
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            dark = state
            MailViewSettings.dark = state
            render()
        }
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Links aus der Mail im System-Browser öffnen, nie in der Vorschau selbst. */
    private fun installLinkHandling(browser: JBCefBrowser) {
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(b: CefBrowser?, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                val url = request.url ?: return false
                if (isExternal(url)) {
                    BrowserUtil.browse(url)
                    return true
                }
                return false
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(b: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
                if (targetUrl != null && isExternal(targetUrl)) BrowserUtil.browse(targetUrl)
                return true
            }
        }, browser.cefBrowser)
    }

    private fun isExternal(url: String) = url.startsWith("http:") || url.startsWith("https:") || url.startsWith("mailto:")

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent? = browser?.component ?: fallback
    override fun getName(): String = MailViewBundle.message("editor.mail")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = revealTimer.stop()

    private companion object {
        /** Höher wird der Offscreen-Puffer zu groß (Retina: Breite × Höhe × 16 Byte). */
        const val MAX_SWING_HEIGHT = 8000
        val DARK_BACKGROUND = Color(0x1a1a1a)
        const val HEIGHT_PREFIX = "mail-view:height="
    }
}
