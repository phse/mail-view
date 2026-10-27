package app.heckit.ide.mailview

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.NativeFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.beans.PropertyChangeListener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextArea
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.callback.CefPdfPrintCallback
import org.cef.handler.CefContextMenuHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.CefPdfPrintSettings
import org.cef.network.CefRequest

/**
 * Reiter „Mail“: die Mail so, wie ein Mailprogramm sie zeigt. Mit [controls] ist es die Vorschau unter der
 * mbox-Liste: Die Mail kommt dann über [showMail] statt aus der Datei, und oben stehen Blättern und „Im Tab öffnen“.
 */
class MailPreviewEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val controls: MailListControls? = null,
) : UserDataHolderBase(), FileEditor {
    /** Beim Blättern liegt das Bild der vorigen Mail über der Ansicht, bis die neue steht, und blendet dann aus. */
    private var snapshot: BufferedImage? = null
    private var snapshotAlpha = 1f
    /** Bild nur vom sichtbaren Teil der Mail (Anhänge auf-/zuklappen): wandert mit ihr, statt die Ansicht zu decken. */
    private var snapshotArea: Rectangle? = null
    private val fadeTimer = Timer(16) { fadeStep() }
    private val root = BorderLayoutPanel()
    /**
     * Bild der vorigen Mail beim Überblenden. Liegt als eigene Ebene zwischen Mail und Knöpfen: Swing zeichnet bei
     * überlappenden Geschwistern jedes Neuzeichnen der Mail mitsamt dieser Ebene neu. Über das Elternteil gemalt
     * (paintChildren) rutschte der Browser teils daran vorbei - als Streifen in der neuen Farbe.
     */
    private val snapshotLayer = object : JComponent() {
        init { isVisible = false }
        override fun paintComponent(g: Graphics) {
            val image = snapshot ?: return
            val g2 = g.create() as Graphics2D
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, snapshotAlpha)
            val area = snapshotArea
            if (area == null) g2.drawImage(image, 0, 0, width, height, null)
            else SwingUtilities.convertPoint(browserHost, area.x, area.y, this).let { g2.drawImage(image, it.x, it.y, area.width, area.height, null) }
            g2.dispose()
        }
    }
    private val bannerHolder = JPanel(VerticalLayout(0))
    // Suchen in der Mail (Cmd+F): Leiste über der Mail, Treffer markiert die Seite selbst (FIND_SCRIPT).
    private val findField = SearchTextField(false)
    private val findCount = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(0, 8) }
    private val findBar = BorderLayoutPanel().apply {
        isVisible = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()), JBUI.Borders.empty(3, 6))
    }

    private val attachmentsPanel = JPanel(VerticalLayout(JBUI.scale(4)))
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
    /** Nach dem Neuzeichnen wieder hierhin scrollen (Umschalten hell/dunkel), sonst -1. */
    private var pendingScroll = -1
    private val revealTimer = Timer(120) {
        loading = false
        if (findBar.isVisible && findField.text.isNotEmpty()) runFind(0)
        if (pendingScroll >= 0) {
            scrollContent.validate()
            scrollPane.verticalScrollBar.value = pendingScroll
            pendingScroll = -1
        }
        if (snapshot != null) fadeTimer.start()
        // Nach einem Umschalten (hell/dunkel) bleibt unter kurzen Seiten sonst das alte Offscreen-Bild stehen.
        browser?.cefBrowser?.invalidate()
        browserHost.repaint()
    }.apply { isRepeats = false }
    private var dark = MailViewSettings.darkOnOpen
    /** Textfassung statt HTML zeigen; gilt für diese Ansicht, auch beim Blättern in der mbox. */
    private var plainText = MailViewSettings.preferText
    /** Beginn des Mailtexts in der Seite (unter dem Kopf); bis dorthin wandern „Nur Text“ und „Dunkel“ mit. */
    private var contentTop = 0
    private val toggles = ViewToggles({ togglePlainText() }, { toggleDark() })
    /**
     * „Nur Text“ und „Dunkel“ liegen in Swing über der Mail, nicht in der Seite: So scrollen sie mit, bis sie oben
     * ankommen, und bleiben dann stehen (CSS sticky geht nicht, weil Swing scrollt und nicht die Seite).
     */
    private val togglesHost = object : JPanel(null) {
        override fun doLayout() {
            val content = components.last()
            content.setBounds(0, 0, width, height)
            snapshotLayer.setBounds(0, 0, width, height)
            val size = toggles.preferredSize
            val bar = scrollPane.verticalScrollBar.let { if (it.isVisible) it.width else 0 }
            // Die Knöpfe haben selbst einen Rand (Hover-Bereich), der den Abstand zur Ecke mitbringt.
            val margin = JBUI.scale(2)
            val top = SwingUtilities.convertPoint(browserHost, 0, contentTop, this).y + margin
            // Beim Umschalten springt die Scrollposition kurz auf 0 und zurück; solange die alte Mail darüber liegt,
            // bleiben die Knöpfe stehen, sonst hüpfen sie.
            val y = if (snapshot != null && toggles.height > 0) toggles.y else maxOf(margin, top)
            toggles.setBounds(width - bar - size.width - JBUI.scale(4), y, size.width, size.height)
        }
        override fun isOptimizedDrawingEnabled() = false
    }
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
    private var attachmentsExpanded = MailViewSettings.expandAttachments

    init {
        if (browser != null) {
            Disposer.register(this, browser)
            installLinkHandling(browser)
            installContextMenu(browser)
            installHeightTracking(browser)
            browserHost.add(wheelCatcher(browser))
            browserHost.add(browser.component)
            togglesHost.add(toggles)
            togglesHost.add(snapshotLayer)
            togglesHost.add(scrollPane)
            scrollPane.verticalScrollBar.addAdjustmentListener { togglesHost.doLayout() }
            root.addToCenter(togglesHost)
            val toolbar = ActionManager.getInstance().createActionToolbar("MailView.Mail", DefaultActionGroup(SaveEmlAction(), SavePdfAction(), OpenInMailAppAction()), true)
            toolbar.targetComponent = scrollPane
            val bar = BorderLayoutPanel().addToCenter(toolbar.component)
            controls?.let { controls ->
                // Rechts: „Im Tab öffnen“ und ganz außen das Blättern.
                val previous = NavigateAction(controls, -1)
                val next = NavigateAction(controls, 1)
                val right = ActionManager.getInstance().createActionToolbar("MailView.MailList",
                    DefaultActionGroup(OpenInTabAction(controls), Separator.getInstance(), previous, PositionAction(controls), next), true)
                right.targetComponent = scrollPane
                bar.addToRight(right.component)
                // ↑/↓ blättern auch, wenn der Fokus in der Vorschau liegt (in der Liste macht das die Tabelle selbst).
                previous.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)), root, this)
                next.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0)), root, this)
            }
            installFind(browser)
            root.addToTop(JPanel(VerticalLayout(0)).apply { add(bar); add(findBar); add(bannerHolder) })
            attachmentsPanel.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()), JBUI.Borders.empty(6, 10))
        } else {
            root.addToCenter(JBScrollPane(fallback))
            attachmentsPanel.border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()), JBUI.Borders.empty(6, 10))
            root.addToBottom(attachmentsPanel)
        }
        if (controls == null) {
            MailEditMode.apply(file)
            MailSource(file, this) { show(it) }
        }
    }

    /** Vorschau unter der mbox-Liste: neue Mail zeigen, mit Überblendung von der vorigen. */
    fun showMail(result: Result<ParsedMail>) {
        takeSnapshot()
        show(result)
    }

    /** Bild der aktuellen Ansicht festhalten; es liegt über der neuen, bis diese steht, und blendet dann aus. */
    private fun takeSnapshot() {
        if (browser != null && mail != null && scrollPane.width > 0 && scrollPane.height > 0) {
            val image = UIUtil.createImage(scrollPane, scrollPane.width, scrollPane.height, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            scrollPane.paint(g)
            g.dispose()
            snapshot = image
            snapshotArea = null
            snapshotAlpha = 1f
            snapshotLayer.isVisible = true
            fadeTimer.stop()
            // Falls die neue Seite keine Höhe meldet (Fehlertext), trotzdem ausblenden.
            Timer(800) { if (snapshot != null && !fadeTimer.isRunning) fadeTimer.start() }.apply { isRepeats = false; start() }
        }
    }

    private fun takeBrowserSnapshot() {
        val area = browserHost.visibleRect
        if (browser == null || loading || area.isEmpty) return
        val image = UIUtil.createImage(browserHost, area.width, area.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.translate(-area.x, -area.y)
        browserHost.paint(g)
        g.dispose()
        snapshot = image
        snapshotArea = area
        snapshotAlpha = 1f
        snapshotLayer.isVisible = true
        fadeTimer.stop()
        Timer(SNAPSHOT_HOLD) { if (snapshot != null && !fadeTimer.isRunning) fadeTimer.start() }.apply { isRepeats = false; start() }
    }

    private fun fadeStep() {
        snapshotAlpha -= 0.12f
        if (snapshotAlpha <= 0f) {
            snapshot = null
            snapshotArea = null
            snapshotLayer.isVisible = false
            togglesHost.doLayout()
            fadeTimer.stop()
        }
        // Über das Elternteil neu zeichnen, sonst legt sich die halb durchsichtige Ebene über ihr eigenes altes Bild.
        togglesHost.repaint()
    }

    private fun show(result: Result<ParsedMail>) {
        val parsed = result.getOrElse {
            mail = null
            attachmentsPanel.isVisible = false
            showText(MailViewBundle.message("preview.error", it.message ?: it.javaClass.simpleName))
            return
        }
        if (parsed !== mail) allowRemote = MailViewSettings.loadRemote(parsed)
        mail = parsed
        render()
        updateBanners(parsed)
        updateAttachments(parsed)
    }

    private fun render(keepScroll: Boolean = false) {
        val parsed = mail ?: return
        if (browser != null) {
            toggles.update(plainText, parsed.html != null && parsed.text != null, dark)
            pendingScroll = if (keepScroll) scrollPane.verticalScrollBar.value else -1
            pageHeight = 0
            loading = true
            // Alles unter der Seite mit einfärben: Ist das Offscreen-Bild des Browsers kürzer als die Fläche (kurze
            // Mails), schimmert sonst der helle Hintergrund von Browser-Bauteil oder Viewport durch.
            val background = if (dark) DARK_BACKGROUND else Color.WHITE
            listOf(browserHost, browser.component, scrollContent, scrollPane.viewport).forEach { it.background = background }
            scrollPane.verticalScrollBar.value = 0
            browser.loadHTML(MailHtmlRenderer.render(parsed, allowRemote, "console.log('$HEIGHT_PREFIX' + h + ',' + top)", dark, plainText))
        }
        else showText(parsed.text ?: StringUtil.stripHtml(parsed.html ?: "", true))
    }

    private fun showText(text: String) {
        if (browser != null) browser.loadHTML("<pre>${StringUtil.escapeXmlEntities(text)}</pre>") else fallback.text = text
    }

    /** Hinweise über der Mail: Termine und Kontakte aus Anhängen, blockierte externe Inhalte. */
    private fun updateBanners(parsed: ParsedMail) {
        bannerHolder.removeAll()
        for ((attachment, summary) in AttachmentPreview.summaries(parsed)) {
            val banner = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
            banner.icon(if (summary.kind == AttachmentSummary.Kind.EVENT) AllIcons.Vcs.History else AllIcons.General.User)
            banner.text = summary.text
            banner.toolTipText = attachment.name
            banner.createActionLabel(MailViewBundle.message("attachments.menu.open")) { open(attachment) }
            banner.createActionLabel(MailViewBundle.message("attachments.save")) { save(attachment) }
            bannerHolder.add(banner)
        }
        if (!allowRemote && browser != null && MailHtmlRenderer.hasRemoteContent(parsed)) {
            val banner = EditorNotificationPanel(EditorNotificationPanel.Status.Info)
            banner.text = MailViewBundle.message("preview.remoteBlocked")
            banner.createActionLabel(MailViewBundle.message("preview.remoteLoad")) {
                allowRemote = true
                updateBanners(parsed)
                render()
            }
            parsed.from.firstNotNullOfOrNull { it.address?.substringAfter('@', "")?.takeIf(String::isNotEmpty) }?.let { domain ->
                banner.createActionLabel(MailViewBundle.message("preview.remoteTrust", domain)) {
                    MailViewSettings.trust(domain)
                    allowRemote = true
                    updateBanners(parsed)
                    render()
                }
            }
            bannerHolder.add(banner)
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
                    // Rückt die Mail nach unten, zeichnet der Offscreen-Browser kurz an der alten Stelle bzw.
                    // verschoben (Ruckeln) - sein Bild wandert deshalb mit, bis er neu gezeichnet hat.
                    takeBrowserSnapshot()
                    attachmentsExpanded = !attachmentsExpanded
                    updateAttachments(parsed)
                }
            })
            toggle.icon = if (attachmentsExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            attachmentsPanel.add(toggle)
            // Kacheln je Herkunft; aus einer winmail.dat ausgepackte Anhänge stehen gesammelt dahinter, mit Überschrift.
            if (attachmentsExpanded) for ((source, group) in all.groupBy { it.source }.entries.sortedBy { it.key != null }) {
                if (source != null) attachmentsPanel.add(JBLabel(MailViewBundle.message("attachments.from", source)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = MailViewBundle.message("attachments.from.tooltip", source)
                })
                attachmentsPanel.add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(3))).apply {
                    isOpaque = false
                    // WrapLayout setzt außen denselben Abstand; links bündig mit dem Pfeil oben.
                    border = JBUI.Borders.empty(0, -6, 0, 0)
                    for (attachment in group) add(AttachmentChip(attachment, attachmentMenu(attachment), { open(attachment) }, { reveal(attachment) }, { openWithSystem(attachment) },
                        { FileListTransferable(listOf(materialize(attachment))) }))
                })
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

    private fun installFind(browser: JBCefBrowser) {
        val previous = object : DumbAwareAction(MailViewBundle.message("find.previous"), null, AllIcons.Actions.PreviousOccurence) {
            override fun actionPerformed(e: AnActionEvent) = runFind(-1)
        }
        val next = object : DumbAwareAction(MailViewBundle.message("find.next"), null, AllIcons.Actions.NextOccurence) {
            override fun actionPerformed(e: AnActionEvent) = runFind(1)
        }
        val close = object : DumbAwareAction(MailViewBundle.message("find.close"), null, AllIcons.Actions.Close) {
            override fun actionPerformed(e: AnActionEvent) = closeFind()
        }
        val tools = ActionManager.getInstance().createActionToolbar("MailView.Find", DefaultActionGroup(previous, next, close), true)
        tools.targetComponent = findField
        findBar.addToCenter(findField).addToRight(BorderLayoutPanel().addToLeft(findCount).addToCenter(tools.component))
        findField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = runFind(0)
        })
        findField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER -> { runFind(if (e.isShiftDown) -1 else 1); e.consume() }
                    e.keyCode == KeyEvent.VK_ESCAPE -> { closeFind(); e.consume() }
                }
            }
        })
        // Cmd+F/Ctrl+F wie im Editor (Tastenbelegung aus der Keymap).
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = openFind()
        }.registerCustomShortcutSet(ActionManager.getInstance().getAction(IdeActions.ACTION_FIND).shortcutSet, root, this)
    }

    private fun openFind() {
        findBar.isVisible = true
        root.revalidate()
        findField.textEditor.selectAll()
        IdeFocusManager.getGlobalInstance().requestFocus(findField.textEditor, true)
        if (findField.text.isNotEmpty()) runFind(0)
    }

    private fun closeFind() {
        findBar.isVisible = false
        browser?.cefBrowser?.executeJavaScript("window.mvFind && mvFind('', 0)", "", 0)
        findCount.text = ""
        root.revalidate()
        IdeFocusManager.getGlobalInstance().requestFocus(browser?.component ?: root, true)
    }

    /** [step] 0 = neu suchen, 1/-1 = nächster/voriger Treffer. */
    private fun runFind(step: Int) {
        val query = findField.text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        browser?.cefBrowser?.executeJavaScript("window.mvFind && mvFind('$query', $step)", "", 0)
    }

    private fun showFindResult(parts: List<String>) {
        val current = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val count = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val top = parts.getOrNull(2)?.toIntOrNull() ?: -1
        findCount.text = when {
            findField.text.isEmpty() -> ""
            count == 0 -> MailViewBundle.message("find.none")
            else -> "$current / $count"
        }
        // Treffer in den sichtbaren Bereich holen (Swing scrollt, solange der Browser die volle Höhe hat).
        if (top >= 0 && pageHeight in 1..MAX_SWING_HEIGHT) {
            val bar = scrollPane.verticalScrollBar
            val y = browserHost.y + top
            val view = scrollPane.viewport.extentSize.height
            if (y < bar.value || y > bar.value + view - JBUI.scale(40)) bar.value = maxOf(0, y - view / 3)
        }
    }

    /** Die Mail als PDF, wie sie im Mail-Reiter steht (Kopf und Inhalt, für den Druck immer hell). */
    private inner class SavePdfAction : DumbAwareAction(MailViewBundle.message("export.pdf"), null, AllIcons.FileTypes.Any_type) {
        override fun actionPerformed(e: AnActionEvent) {
            val parsed = mail ?: return
            val cef = browser?.cefBrowser ?: return
            val descriptor = FileSaverDescriptor(MailViewBundle.message("export.pdf.title"), "", "pdf")
            val name = MailExport.emlName(if (file.extension?.lowercase() in MSG_EXTENSIONS) file.nameWithoutExtension else parsed.subject)
                .removeSuffix(".eml") + ".pdf"
            val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(file.parent, name) ?: return
            val settings = CefPdfPrintSettings().apply { print_background = true }
            cef.printToPDF(target.file.path, settings, CefPdfPrintCallback { _, ok ->
                ApplicationManager.getApplication().invokeLater {
                    if (ok) LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target.file)
                    else Messages.showErrorDialog(project, MailViewBundle.message("export.pdf.error"), MailViewBundle.message("export.pdf.title"))
                }
            })
        }
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = mail != null && browser != null
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Menü zu einer Adresse im Kopf, an der Mausposition. */
    private fun showContactMenu(index: Int) {
        val parsed = mail ?: return
        val people = MailHtmlRenderer.contacts(parsed)
        val person = people.getOrNull(index) ?: return
        val address = person.address ?: return
        val menu = JBPopupMenu()
        fun item(text: String, action: () -> Unit) = menu.add(JBMenuItem(text).apply { addActionListener { action() } })
        item(MailViewBundle.message("contact.add")) { Contacts.open(listOf(person)) }
        val all = people.filter { it.address != null }.distinctBy { it.address!!.lowercase() }
        if (all.size > 1) item(MailViewBundle.message("contact.addAll", all.size)) { Contacts.open(all) }
        menu.addSeparator()
        item(MailViewBundle.message("contact.copy")) { CopyPasteManager.getInstance().setContents(StringSelection(address)) }
        item(MailViewBundle.message("contact.write")) { BrowserUtil.browse("mailto:$address") }
        val component = browser?.component ?: return
        // Die Adresse bleibt als Knopf hervorgehoben, solange das Menü offen ist.
        fun mark(on: Boolean) = browser.cefBrowser.executeJavaScript(
            "document.querySelectorAll('a.contact[data-i=\"$index\"]').forEach(a => a.classList.toggle('active', $on))", "", 0)
        mark(true)
        menu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = mark(false)
            override fun popupMenuCanceled(e: PopupMenuEvent) = Unit
        })
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        SwingUtilities.convertPointFromScreen(pointer, component)
        menu.show(component, pointer.x, pointer.y)
    }

    /** Rechtsklick: alle Wege, auch die, die ein Klick bewusst nicht nimmt (Ausführen, Entpacken). */
    private fun attachmentMenu(attachment: MailAttachment): JPopupMenu {
        val menu = JBPopupMenu()
        // Swing-Menüs laufen ohne Schreib-Lock der IDE; refreshAndFindFile (Öffnen, Speichern) braucht ihn seit 2026.x.
        // Mit Sondertaste geht es per Klick direkt; die Taste steht rechts im Eintrag wie ein Tastenkürzel.
        fun item(key: String, click: ClickModifier? = null, action: () -> Unit) {
            menu.add(HintMenuItem(MailViewBundle.message(key), click?.hint).apply { addActionListener { ApplicationManager.getApplication().invokeLater(action) } })
        }
        item("attachments.menu.open", ClickModifier.OPEN) { open(attachment) }
        item("attachments.menu.reveal", ClickModifier.REVEAL) { reveal(attachment) }
        val extension = File(attachment.name).extension.lowercase()
        if (extension == "zip") item("attachments.menu.unzip") { unzip(attachment) }
        item(if (isRisky(attachment.name)) "attachments.menu.run" else "attachments.menu.system", ClickModifier.SYSTEM) { openWithSystem(attachment) }
        menu.addSeparator()
        item("attachments.save") { save(attachment) }
        item("attachments.saveToProject") { saveToProject(attachment) }
        return menu
    }

    private fun reveal(attachment: MailAttachment) = RevealFileAction.openFile(materialize(attachment))

    /** Mit dem Systemprogramm öffnen; Ausführbares und Archive nur nach Warnung. */
    private fun openWithSystem(attachment: MailAttachment) =
        if (isRisky(attachment.name)) run(attachment) else BrowserUtil.open(materialize(attachment).path)

    /** Schreibt den Anhang in einen eigenen Temp-Ordner. */
    private fun materialize(attachment: MailAttachment): File {
        val dir = FileUtil.createTempDirectory("mail-view", null, true)
        return File(dir, MailExport.safeName(attachment.name)).apply { writeBytes(attachment.data) }
    }

    private fun isRisky(name: String): Boolean {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
        return File(name).extension.lowercase() in REVEAL_EXTENSIONS || fileType is ArchiveFileType || fileType is NativeFileType
    }

    private fun open(attachment: MailAttachment) {
        val target = materialize(attachment)
        // Archive und Ausführbares nur im Finder/Explorer zeigen, was die IDE kennt (Mails, Bilder, Text, PDF mit
        // Plugin) im Editor, den Rest mit dem Standardprogramm.
        if (isRisky(target.name)) return RevealFileAction.openFile(target)
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(target.name)
        val virtualFile = if (fileType != UnknownFileType.INSTANCE)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target) else null
        if (virtualFile != null) OpenFileDescriptor(project, virtualFile).navigate(true)
        else if (MailViewSettings.revealUnknown) RevealFileAction.openFile(target)
        else BrowserUtil.open(target.path)
    }

    private fun run(attachment: MailAttachment) {
        val answer = Messages.showYesNoDialog(project, MailViewBundle.message("attachments.run.warning", attachment.name),
            MailViewBundle.message("attachments.menu.run"), Messages.getWarningIcon())
        if (answer == Messages.YES) BrowserUtil.open(materialize(attachment).path)
    }

    /** Entpackt in einen neuen Ordner neben der Mail (oder einen gewählten) und zeigt ihn im Finder/Explorer. */
    private fun unzip(attachment: MailAttachment) {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(MailViewBundle.message("attachments.unzip.title"))
        val parent = FileChooser.chooseFile(descriptor, project, file.parent)?.toNioPath()?.toFile() ?: return
        val base = MailExport.safeName(File(attachment.name).nameWithoutExtension)
        val target = generateSequence(0) { it + 1 }.map { File(parent, if (it == 0) base else "$base ($it)") }.first { !it.exists() }
        try {
            ZipInputStream(ByteArrayInputStream(attachment.data)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(target, entry.name).canonicalFile
                    // Zip-Slip: Einträge wie ../../x dürfen nicht aus dem Zielordner heraus.
                    if (!out.path.startsWith(target.canonicalPath + File.separator)) continue
                    if (entry.isDirectory) out.mkdirs()
                    else { out.parentFile.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
        } catch (e: IOException) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), MailViewBundle.message("attachments.unzip.title"))
            return
        }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent)?.refresh(true, true)
        RevealFileAction.openDirectory(target)
    }

    private fun save(attachment: MailAttachment) {
        val descriptor = FileSaverDescriptor(MailViewBundle.message("attachments.save.title"), "")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(file.parent ?: project.guessProjectDir(), MailExport.safeName(attachment.name)) ?: return
        wrapper.file.writeBytes(attachment.data)
        wrapper.virtualFile?.refresh(true, false)
    }

    /**
     * Ordner im Projekt wählen (Vorschlag: der zuletzt gewählte), Anhang dort ablegen, ohne zu überschreiben, und im
     * Projektbaum zeigen.
     */
    private fun saveToProject(attachment: MailAttachment) {
        val base = project.guessProjectDir() ?: return save(attachment)
        val properties = PropertiesComponent.getInstance(project)
        val last = properties.getValue(LAST_PROJECT_DIR)?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(MailViewBundle.message("attachments.saveToProject.title")).withRoots(base)
        val dir = FileChooser.chooseFile(descriptor, project, last ?: base) ?: return
        properties.setValue(LAST_PROJECT_DIR, dir.path)
        val target = MailExport.unique(dir.toNioPath().toFile(), attachment.name).apply { writeBytes(attachment.data) }
        val saved = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target) ?: return
        ProjectView.getInstance(project).select(null, saved, true)
    }

    private class FileListTransferable(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
    }

    /**
     * Die Seite meldet ihre Höhe; der Browser wird so hoch und die Scrollpane scrollt. Ist die Seite zu hoch für
     * einen Offscreen-Puffer, bleibt der Browser so hoch wie der Sichtbereich und scrollt selbst.
     */
    private fun installHeightTracking(browser: JBCefBrowser) {
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(b: CefBrowser?, level: CefSettings.LogSeverity?, message: String?, source: String?, line: Int): Boolean {
                if (message != null && message.startsWith(MailHtmlRenderer.ACTION_PREFIX)) {
                    val (action, selection) = message.removePrefix(MailHtmlRenderer.ACTION_PREFIX).split(':', limit = 2) + ""
                    val text = runCatching { URLDecoder.decode(selection, Charsets.UTF_8) }.getOrDefault("")
                    ApplicationManager.getApplication().invokeLater { mail?.let { Compose.open(project, it, action, text.trim()) } }
                    return true
                }
                if (message != null && message.startsWith(MailHtmlRenderer.FIND_PREFIX)) {
                    val parts = message.removePrefix(MailHtmlRenderer.FIND_PREFIX).split('/')
                    ApplicationManager.getApplication().invokeLater { showFindResult(parts) }
                    return true
                }
                val values = message?.removePrefix(HEIGHT_PREFIX)?.takeIf { it != message }?.split(',')?.map { it.toDoubleOrNull()?.toInt() ?: 0 } ?: return false
                ApplicationManager.getApplication().invokeLater {
                    contentTop = values.getOrElse(1) { 0 }
                    updatePageHeight(values[0])
                    togglesHost.revalidate()
                }
                return true
            }
        }, browser.cefBrowser)
    }

    /**
     * Unsichtbare Fläche über dem Browser für das Mausrad: Das Offscreen-Bauteil würde es sonst selbst verarbeiten.
     * Solange Swing scrollt, geht es an die Scrollpane, sonst (lange Mails, Zoomen mit Cmd/Ctrl) an den Browser.
     * Swing schickt einem Bauteil mit Mausrad-Listener auch alle anderen Maus-Ereignisse; die werden deshalb an den
     * Browser weitergereicht, sonst kämen Klicks, Hover und Textauswahl dort nie an.
     */
    private fun wheelCatcher(browser: JBCefBrowser): JComponent = object : JComponent() {
        // Das Bauteil, das die Eingaben wirklich verarbeitet: browser.component ist nur ein Rahmen darum (gemessen per
        // DevTools - dorthin weitergereichte Ereignisse kamen nie an).
        private val input: java.awt.Component get() = browser.cefBrowser.uiComponent ?: browser.component

        init {
            addMouseWheelListener { e ->
                val swing = pageHeight in 1..MAX_SWING_HEIGHT && !e.isControlDown && !e.isMetaDown
                val target = if (swing) scrollPane else input
                target.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, target))
            }
            val forward = object : MouseAdapter() {
                private fun pass(e: MouseEvent) = input.let { it.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, it)) }
                override fun mousePressed(e: MouseEvent) = pass(e)
                override fun mouseReleased(e: MouseEvent) = pass(e)
                override fun mouseClicked(e: MouseEvent) = pass(e)
                override fun mouseEntered(e: MouseEvent) = pass(e)
                override fun mouseExited(e: MouseEvent) = pass(e)
                override fun mouseMoved(e: MouseEvent) = pass(e)
                override fun mouseDragged(e: MouseEvent) = pass(e)
            }
            addMouseListener(forward)
            addMouseMotionListener(forward)
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

    /**
     * „Als .eml speichern“: Outlook-Dateien (.msg/.oft/winmail.dat) werden umgewandelt, Mails aus der mbox-Vorschau
     * unverändert gespeichert. Bei .eml selbst nicht nötig.
     */
    private inner class SaveEmlAction : DumbAwareAction(MailViewBundle.message("export.eml.save"), null, AllIcons.Actions.MenuSaveall) {
        private val converts = file.extension?.lowercase() in MSG_EXTENSIONS || isTnefFile(file)
        // Aus der Zwischenablage: gibt es noch nicht als Datei.
        private val unsaved = file is LightVirtualFile

        override fun actionPerformed(e: AnActionEvent) {
            val parsed = mail ?: return
            when {
                controls != null -> MailExport.saveEml(project, null, parsed.subject, controls.currentMail() ?: return)
                unsaved -> MailExport.saveEml(project, project.guessProjectDir(), parsed.subject, file.contentsToByteArray())
                // Outlook-Datei: Name der Datei übernehmen (winmail.dat sagt nichts, dort den Betreff).
                converts -> MailExport.saveEml(project, file.parent,
                    if (isTnefFile(file)) parsed.subject else file.nameWithoutExtension, EmlWriter.write(parsed))
                // .eml/.emlx/.mht: unveränderte Kopie unter eigenem Namen.
                else -> MailExport.saveCopy(project, file)
            }
        }
        override fun update(e: AnActionEvent) {
            e.presentation.text = MailViewBundle.message(if (controls == null && !converts && !unsaved) "export.copy" else "export.eml.save")
            e.presentation.isEnabled = mail != null
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /**
     * Mail im Standard-Mailprogramm öffnen (dort z. B. mit echtem Zitat antworten): .eml/.emlx direkt, Outlook-Dateien
     * und Mails aus der mbox als .eml in einem Temp-Ordner.
     */
    private inner class OpenInMailAppAction : DumbAwareAction(MailViewBundle.message("action.openMailApp"), null, AllIcons.Actions.MoveToWindow) {
        override fun actionPerformed(e: AnActionEvent) {
            val parsed = mail ?: return
            val path = when {
                controls != null -> temp(parsed.subject, controls.currentMail() ?: return)
                file.extension?.lowercase() in setOf("eml", "emlx") && file.isInLocalFileSystem -> file.path
                else -> temp(if (file.extension?.lowercase() in MSG_EXTENSIONS) file.nameWithoutExtension else parsed.subject, EmlWriter.write(parsed))
            }
            BrowserUtil.open(path)
        }
        private fun temp(name: String?, data: ByteArray): String =
            File(FileUtil.createTempDirectory("mail-view", null, true), MailExport.emlName(name)).apply { writeBytes(data) }.path
        override fun update(e: AnActionEvent) {
            // .mht ist eine Webseite, kein Fall fürs Mailprogramm.
            e.presentation.isVisible = file.extension?.lowercase() !in setOf("mht", "mhtml")
            e.presentation.isEnabled = mail != null
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun togglePlainText() {
        plainText = !plainText
        takeSnapshot()
        render(keepScroll = true)
    }

    /** Mail hell oder dunkel zeigen; Vorgabe ist das IDE-Theme, eine Wahl gilt danach für alle Mails. */
    private fun toggleDark() {
        dark = !dark
        MailViewSettings.dark = dark
        // Überblenden und an derselben Stelle bleiben: Es ändert sich nur die Farbe.
        takeSnapshot()
        render(keepScroll = true)
    }

    /** Vorige/nächste Mail der mbox-Liste, in deren aktueller Reihenfolge. */
    private class NavigateAction(private val controls: MailListControls, private val delta: Int) : DumbAwareAction(
        MailViewBundle.message(if (delta < 0) "mbox.previous" else "mbox.next"), null,
        if (delta < 0) AllIcons.General.ArrowUp else AllIcons.General.ArrowDown,
    ) {
        override fun actionPerformed(e: AnActionEvent) = controls.move(delta)
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = controls.canMove(delta)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private class OpenInTabAction(private val controls: MailListControls) :
        DumbAwareAction(MailViewBundle.message("mbox.openTab"), null, AllIcons.Actions.MoveToWindow) {
        override fun actionPerformed(e: AnActionEvent) = controls.openInTab()
        override fun update(e: AnActionEvent) {
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** „3 / 303“ zwischen den Pfeilen; nur Anzeige. */
    private class PositionAction(private val controls: MailListControls) : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) = Unit
        override fun update(e: AnActionEvent) {
            e.presentation.text = controls.position()
            e.presentation.isEnabled = false
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Statt des Chromium-Menüs (Zurück, Neu laden, …): Kopieren der Auswahl und die Einstellungen. */
    private fun installContextMenu(browser: JBCefBrowser) {
        browser.jbCefClient.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
            override fun onBeforeContextMenu(b: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams, model: CefMenuModel) {
                model.clear()
                val selection = params.selectionText.orEmpty()
                val x = params.xCoord
                val y = params.yCoord
                ApplicationManager.getApplication().invokeLater { showPageMenu(browser, selection, x, y) }
            }
        }, browser.cefBrowser)
    }

    private fun showPageMenu(browser: JBCefBrowser, selection: String, x: Int, y: Int) {
        val menu = JBPopupMenu()
        if (selection.isNotBlank()) {
            menu.add(JBMenuItem(MailViewBundle.message("action.copy"), AllIcons.Actions.Copy).apply {
                addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(selection)) }
            })
            menu.addSeparator()
        }
        menu.add(JBMenuItem(MailViewBundle.message("action.settings"), AllIcons.General.Settings).apply {
            addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, MailViewConfigurable::class.java) }
        })
        val component = browser.component
        if (component.isShowing) menu.show(component, x, y)
    }

    /** Links aus der Mail im System-Browser öffnen, nie in der Vorschau selbst. */
    private fun installLinkHandling(browser: JBCefBrowser) {
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(b: CefBrowser?, frame: CefFrame?, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                val url = request.url ?: return false
                if (url.startsWith(MailHtmlRenderer.CONTACT_LINK)) {
                    val index = url.removePrefix(MailHtmlRenderer.CONTACT_LINK).toIntOrNull()
                    ApplicationManager.getApplication().invokeLater { index?.let(::showContactMenu) }
                    return true
                }
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
    override fun dispose() {
        revealTimer.stop()
        fadeTimer.stop()
    }

    private companion object {
        /** Höher wird der Offscreen-Puffer zu groß (Retina: Breite × Höhe × 16 Byte). */
        const val MAX_SWING_HEIGHT = 8000
        /** So lange liegt das Bild der Mail nach dem Auf-/Zuklappen der Anhänge, bis der Browser neu gezeichnet hat. */
        const val SNAPSHOT_HOLD = 250
        /** Zuletzt gewählter Ordner für „In Projekt speichern“, pro Projekt. */
        const val LAST_PROJECT_DIR = "mailview.attachments.projectDir"
        /** Archive und alles, was beim Öffnen ausgeführt oder installiert würde. */
        val REVEAL_EXTENSIONS = setOf(
            "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "jar", "war", "dmg", "iso",
            "exe", "msi", "com", "scr", "bat", "cmd", "ps1", "vbs", "wsf", "hta", "lnk", "url", "reg",
            "app", "pkg", "command", "sh", "apk",
        )
        val DARK_BACKGROUND = Color(0x1a1a1a)
        const val HEIGHT_PREFIX = "mail-view:height="
    }
}
