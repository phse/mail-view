package app.heckit.ide.mailview

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter
import java.awt.RenderingHints
import java.awt.Graphics2D
import java.awt.Graphics
import java.awt.Dimension
import java.awt.BorderLayout
import com.intellij.util.ui.JBFont
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.net.URLDecoder
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * Apple Mail per AppleScript (JXA) fragen. Beim Ziehen liefert Mail nur ein File Promise, das Java nicht annimmt,
 * dazu bei einer Mail den Link „message:<Message-ID>“, bei mehreren nur einen Betreff. Die Mails selbst holt das
 * Plugin deshalb aus der Auswahl in Mail - das braucht die macOS-Erlaubnis „Automation → Mail“, die nur auf
 * ausdrücklichen Klick in den Einstellungen angefragt wird.
 */
object AppleMail {
    private const val BUNDLE_ID = "com.apple.mail"
    const val AUTOMATION_SETTINGS = "x-apple.systempreferences:com.apple.preference.security?Privacy_Automation"

    enum class Permission { GRANTED, DENIED, ASK, NOT_RUNNING, UNKNOWN }

    @Suppress("FunctionName")
    private interface AppleEvents : Library {
        fun AECreateDesc(type: Int, data: ByteArray, size: NativeLong, result: AEDesc): Short
        fun AEDeterminePermissionToAutomateTarget(target: AEDesc, eventClass: Int, eventId: Int, askUserIfNeeded: Byte): Int
        fun AEDisposeDesc(desc: AEDesc): Short
    }

    @Structure.FieldOrder("descriptorType", "dataHandle")
    class AEDesc : Structure() {
        @JvmField var descriptorType = 0
        @JvmField var dataHandle: Pointer? = null
    }

    private fun code(s: String) = s.fold(0) { acc, c -> acc shl 8 or c.code }

    /** Stand der Erlaubnis; mit [ask] zeigt macOS die Abfrage, falls noch nicht entschieden (blockiert bis dahin). */
    fun permission(ask: Boolean): Permission {
        if (!SystemInfo.isMac) return Permission.UNKNOWN
        return runCatching {
            val ae = Native.load("CoreServices", AppleEvents::class.java)
            val id = BUNDLE_ID.toByteArray()
            val desc = AEDesc()
            if (ae.AECreateDesc(code("bund"), id, NativeLong(id.size.toLong()), desc).toInt() != 0) return Permission.UNKNOWN
            try {
                // Dieselbe Art Ereignis, die das Holen der Mails sendet (Daten lesen).
                when (ae.AEDeterminePermissionToAutomateTarget(desc, code("core"), code("getd"), if (ask) 1 else 0)) {
                    0 -> Permission.GRANTED
                    -1743 -> Permission.DENIED
                    -1744 -> Permission.ASK
                    -600 -> Permission.NOT_RUNNING
                    else -> Permission.UNKNOWN
                }
            } finally {
                ae.AEDisposeDesc(desc)
            }
        }.getOrDefault(Permission.UNKNOWN)
    }

    /** Erlaubnis anfragen; Mail muss dafür laufen und wird bei Bedarf im Hintergrund gestartet. */
    fun request(): Permission {
        var state = permission(false)
        if (state == Permission.NOT_RUNNING) {
            runCatching { ExecUtil.execAndGetOutput(GeneralCommandLine("open", "-g", "-a", "Mail"), 10_000) }
            repeat(30) {
                if (permission(false).also { state = it } != Permission.NOT_RUNNING) return@repeat
                Thread.sleep(500)
            }
        }
        if (state == Permission.ASK) state = permission(true)
        MailViewSettings.mailDrop = state == Permission.GRANTED
        return state
    }

    @Suppress("FunctionName")
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
    }

    /**
     * Mail ist die aktive App. Beim Ziehen bleibt die Quelle vorn - so lässt sich ein bloßer Betreff (mehrere Mails)
     * von anderem gezogenem Text unterscheiden. Ohne Erlaubnis abfragbar (NSWorkspace).
     */
    fun isFrontmost(): Boolean {
        if (!SystemInfo.isMac) return false
        return runCatching {
            val objc = Native.load("objc", ObjC::class.java)
            val send = com.sun.jna.Function.getFunction("objc", "objc_msgSend")
            fun msg(target: Pointer?, selector: String): Pointer? =
                target?.let { send.invokePointer(arrayOf(it, objc.sel_registerName(selector))) }
            val app = msg(msg(objc.objc_getClass("NSWorkspace"), "sharedWorkspace"), "frontmostApplication")
            msg(msg(app, "bundleIdentifier"), "UTF8String")?.getString(0) == BUNDLE_ID
        }.getOrDefault(false)
    }

    class NotAllowedException : Exception()

    data class Selected(val id: String, val subject: String, val sender: String, val received: java.time.Instant?)

    /** In Mail ausgewählte Mails (Message-ID ohne spitze Klammern, Betreff, Absender, Empfangszeit). */
    fun selection(): List<Selected> =
        jxa("JSON.stringify(Application('Mail').selection().map(m => [m.messageId(), m.subject(), m.sender(), m.dateReceived() ? m.dateReceived().toISOString() : null]))")
            .map { Selected(it[0], it[1], it[2], runCatching { java.time.Instant.parse(it[3]) }.getOrNull()) }

    /** Quelltext der ausgewählten Mails mit diesen IDs, je ID. */
    fun sources(ids: List<String>): Map<String, String> =
        jxa("JSON.stringify(Application('Mail').selection().filter(m => argv.includes(m.messageId())).map(m => [m.messageId(), m.source()]))", ids)
            .associate { it[0] to it[1] }

    private fun jxa(expression: String, args: List<String> = emptyList()): List<List<String>> {
        val command = GeneralCommandLine(listOf("osascript", "-l", "JavaScript", "-e", "function run(argv) { return $expression }") + args)
            .withCharset(Charsets.UTF_8)
        val output = ExecUtil.execAndGetOutput(command, 60_000)
        if (output.exitCode != 0) {
            if ("-1743" in output.stderr) throw NotAllowedException()
            error("osascript: ${output.stderr.trim()}")
        }
        val json = com.google.gson.JsonParser.parseString(output.stdout.trim())
        return json.asJsonArray.map { row -> row.asJsonArray.map { if (it.isJsonNull) "" else it.asString } }
    }
}

/**
 * Hängt [MailDropTarget] vor die Drop-Behandlung der Bäume im Projekt-Fenster. Die IDE bietet dafür keine Schnittstelle;
 * Bäume entstehen je Ansicht (Project, Packages, Scratches …) und erst beim Anzeigen, daher bei jeder Änderung nachsehen.
 */
class MailDropInstaller(private val project: Project) : ToolWindowManagerListener {
    override fun stateChanged(toolWindowManager: ToolWindowManager) {
        if (SystemInfo.isMac) install(project)
    }

    /** Beim Öffnen des Projekts (das Projekt-Fenster kann schon offen sein). */
    class Startup : com.intellij.openapi.startup.ProjectActivity {
        override suspend fun execute(project: Project) {
            if (SystemInfo.isMac) ApplicationManager.getApplication().invokeLater { install(project) }
        }
    }

    companion object {
        private val LISTENING = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

        fun install(project: Project) {
            if (project.isDisposed) return
            val window = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return
            val contents = window.contentManagerIfCreated ?: return
            if (LISTENING.add(contents)) contents.addContentManagerListener(object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) = ApplicationManager.getApplication().invokeLater { install(project) }
            })
            contents.contents.forEach { content ->
                UIUtil.uiTraverser(content.component).filter(JTree::class.java).forEach { tree ->
                    val original = tree.dropTarget
                    if (original != null && original !is MailDropTarget) MailDropTarget(project, tree, original)
                }
            }
        }
    }
}

/**
 * Nimmt Mails aus Apple Mail an (nach erteilter Erlaubnis) und reicht alle anderen Drops unverändert an die IDE weiter.
 * Abgelegt wird im Ordner unter der Maus (bei einer Datei in deren Ordner), nach Rückfrage.
 */
private class MailDropTarget(private val project: Project, private val tree: JTree, private val original: DropTarget) : DropTarget() {
    private var mail = false
    private var selectionBefore: Array<TreePath>? = null

    init {
        defaultActions = original.defaultActions
        tree.dropTarget = this
    }

    override fun dragEnter(e: DropTargetDragEvent) {
        // Auch ohne Erlaubnis annehmen, um beim Ablegen darauf hinzuweisen (die IDE selbst nimmt Mails nicht an).
        mail = isMailDrag(e.transferable)
        if (!mail) return original.dragEnter(e)
        selectionBefore = tree.selectionPaths
        dragOver(e)
    }

    override fun dragOver(e: DropTargetDragEvent) {
        if (!mail) return original.dragOver(e)
        val path = pathAt(e.location)
        if (path == null || folder(path) == null) return e.rejectDrag()
        tree.selectionPath = path
        e.acceptDrag(DnDConstants.ACTION_COPY)
    }

    override fun dropActionChanged(e: DropTargetDragEvent) = if (mail) dragOver(e) else original.dropActionChanged(e)

    override fun dragExit(e: DropTargetEvent) {
        if (!mail) return original.dragExit(e)
        restoreSelection()
    }

    override fun drop(e: DropTargetDropEvent) {
        if (!mail) return original.drop(e)
        mail = false
        val dir = pathAt(e.location)?.let(::folder)
        restoreSelection()
        if (dir == null) return e.rejectDrop()
        e.acceptDrop(DnDConstants.ACTION_COPY)
        val id = messageId(e.transferable)
        val subject = subject(e.transferable)
        e.dropComplete(true)
        ApplicationManager.getApplication().invokeLater {
            // Erlaubnis evtl. schon erteilt (z. B. in einer anderen IDE oder vor einer Neuinstallation): Mail läuft jetzt, also prüfbar.
            if (!MailViewSettings.mailDrop && AppleMail.permission(false) == AppleMail.Permission.GRANTED) MailViewSettings.mailDrop = true
            if (MailViewSettings.mailDrop) MailDrop.save(project, dir, id, subject) else MailDrop.notifyNoPermission(project)
        }
    }

    private fun restoreSelection() {
        selectionBefore?.let { tree.selectionPaths = it }
        selectionBefore = null
    }

    /** Zeile unter der Maus (über die ganze Breite, nicht nur über dem Text). */
    private fun pathAt(p: Point): TreePath? {
        val row = tree.getClosestRowForLocation(p.x, p.y).takeIf { it >= 0 } ?: return null
        val bounds = tree.getRowBounds(row) ?: return null
        return if (p.y >= bounds.y && p.y < bounds.y + bounds.height) tree.getPathForRow(row) else null
    }

    private fun folder(path: TreePath): VirtualFile? {
        val file = (TreeUtil.getUserObject(path.lastPathComponent) as? ProjectViewNode<*>)?.virtualFile ?: return null
        return (if (file.isDirectory) file else file.parent)?.takeIf { it.isWritable && it.isInLocalFileSystem }
    }

    companion object {
        private fun text(t: Transferable, mime: String): String? = runCatching {
            // Nach Typ suchen: DataFlavor.equals vergleicht bei Text auch den Zeichensatz.
            t.transferDataFlavors.firstOrNull { it.mimeType.startsWith("$mime;") && it.representationClass == String::class.java }
                ?.let { t.getTransferData(it) as? String }
        }.getOrNull()

        private fun has(t: Transferable, mime: String) = t.transferDataFlavors.any { it.mimeType.startsWith("$mime;") }

        /** Eine Mail: Link „message:%3C<id>%3E“. */
        fun messageId(t: Transferable): String? = text(t, "text/uri-list")?.lineSequence()?.firstOrNull { it.startsWith("message:") }
            ?.let { URLDecoder.decode(it.removePrefix("message:").replace("+", "%2B"), Charsets.UTF_8) }?.trim()?.removePrefix("<")?.removeSuffix(">")

        fun subject(t: Transferable): String? = text(t, "text/plain")?.trim()

        /**
         * Link oder Text ohne Dateien, solange Mail die aktive App ist (Quelle eines Drags bleibt vorn). Die Daten selbst
         * liest macOS erst beim Ablegen zuverlässig, daher hier nur die Typen.
         */
        fun isMailDrag(t: Transferable): Boolean =
            !t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && (has(t, "text/uri-list") || has(t, "text/plain")) && AppleMail.isFrontmost()
    }
}

object MailDrop {
    fun save(project: Project, dir: VirtualFile, id: String?, subject: String?) {
        val title = MailViewBundle.message("drop.title")
        try {
            val selected = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<List<AppleMail.Selected>, Exception> { AppleMail.selection() }, MailViewBundle.message("drop.reading"), true, project)
            val offered = when {
                id != null -> selected.filter { it.id == id }
                subject != null && selected.any { it.subject.trim() == subject } -> selected
                else -> emptyList()
            }
            // Gezogen, ohne vorher zu markieren: Mail ändert die Auswahl beim Ziehen nicht.
            if (offered.isEmpty()) return notify(project, MailViewBundle.message("drop.notSelected"), NotificationType.WARNING)

            val where = FileUtil.getLocationRelativeToUserHome(dir.presentableUrl)
            val dialog = MailDropDialog(project, where, offered)
            if (!dialog.showAndGet()) return
            val mails = dialog.chosen

            val sources = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Map<String, String>, Exception> { AppleMail.sources(mails.map { it.id }) }, MailViewBundle.message("drop.reading"), true, project)
            val files = WriteCommandAction.writeCommandAction(project).withName(title).compute<List<VirtualFile>, Exception> {
                mails.mapNotNull { mail ->
                    val source = sources[mail.id] ?: return@mapNotNull null
                    dir.createChildData(MailDrop, freeName(dir, MailExport.emlName(mail.subject))).apply { setBinaryContent(source.toByteArray(Charsets.UTF_8)) }
                }
            }
            when {
                files.size == 1 -> OpenFileDescriptor(project, files[0]).navigate(true)
                files.isNotEmpty() -> ProjectView.getInstance(project).select(null, files[0], true)
            }
            if (files.size < mails.size) notify(project, MailViewBundle.message("drop.missing", mails.size - files.size), NotificationType.WARNING)
        } catch (_: AppleMail.NotAllowedException) {
            MailViewSettings.mailDrop = false
            notify(project, MailViewBundle.message("drop.notAllowed"), NotificationType.WARNING)
        } catch (e: Exception) {
            notify(project, MailViewBundle.message("drop.error", e.message ?: e.javaClass.simpleName), NotificationType.ERROR)
        }
    }

    /** „Betreff.eml“, sonst „Betreff (2).eml“ usw. */
    private fun freeName(dir: VirtualFile, name: String): String {
        val base = name.removeSuffix(".eml")
        return generateSequence(1) { it + 1 }.map { if (it == 1) name else "$base ($it).eml" }.first { dir.findChild(it) == null }
    }

    /** Mail gezogen, aber die Erlaubnis fehlt: Weg zu den Einstellungen zeigen (dort fragt erst der Button macOS). */
    fun notifyNoPermission(project: Project) =
        NotificationGroupManager.getInstance().getNotificationGroup("Mail View")
            .createNotification(MailViewBundle.message("drop.noPermission"), NotificationType.INFORMATION)
            .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(MailViewBundle.message("action.settings")) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, MailViewConfigurable::class.java)
            })
            .notify(project)

    private fun notify(project: Project, text: String, type: NotificationType) =
        NotificationGroupManager.getInstance().getNotificationGroup("Mail View").createNotification(text, type).notify(project)
}

/**
 * Rückfrage vor dem Speichern. Jede Mail als Kachel wie in der Liste von Apple Mail (Absender fett, Zeit rechts, Betreff
 * darunter); bei mehreren Mails (z. B. einer Konversation) lassen sich einzelne per Klick abwählen.
 */
private class MailDropDialog(project: Project, private val where: String, private val mails: List<AppleMail.Selected>) : DialogWrapper(project) {
    private val boxes = mails.map { JBCheckBox(null, true).apply { isOpaque = false; addActionListener { update() } } }
    private val tiles = mails.mapIndexed { i, mail -> MailTile(mail, boxes[i].takeIf { mails.size > 1 }) }
    val chosen: List<AppleMail.Selected> get() = mails.filterIndexed { i, _ -> boxes[i].isSelected }

    init {
        title = MailViewBundle.message("drop.title")
        setOKButtonText(MailViewBundle.message("drop.save"))
        init()
    }

    private fun update() {
        isOKActionEnabled = chosen.isNotEmpty()
        tiles.forEach { it.repaint() }
    }

    override fun createCenterPanel(): JComponent {
        // Breite der Ansicht übernehmen: zu lange Betreffs kürzt das Label mit „…“ (wie Mail), statt seitlich zu scrollen.
        val list = object : JPanel(VerticalLayout(JBUI.scale(6))), javax.swing.Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(16)
            override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height
            override fun getScrollableTracksViewportWidth() = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply { tiles.forEach(::add) }
        val scroll = JBScrollPane(list, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUI.scale(520), minOf(JBUI.scale(380), list.preferredSize.height + JBUI.scale(4)))
        }
        return JPanel(BorderLayout(0, JBUI.scale(10))).apply {
            // Lange Pfade umbrechen statt den Dialog zu verbreitern.
            val question = com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(MailViewBundle.message("drop.question", mails.size, where))
            add(JBLabel("<html><body style='width: ${scroll.preferredSize.width - JBUI.scale(20)}px'>$question</body></html>"), BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private inner class MailTile(mail: AppleMail.Selected, private val box: JBCheckBox?) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
            // „Name <adresse>“: wie Mail nur den Namen zeigen, die Adresse als Tooltip.
            val name = mail.sender.substringBefore('<').trim().trim('"').ifEmpty { mail.sender.removePrefix("<").removeSuffix(">") }
            val sender = JBLabel(name).apply { font = JBFont.label().biggerOn(1f).asBold(); toolTipText = mail.sender; minimumSize = Dimension(0, minimumSize.height) }
            val time = JBLabel(mail.received?.let(::formatTime) ?: "").apply { foreground = UIUtil.getContextHelpForeground() }
            val subject = JBLabel(mail.subject.ifBlank { MailViewBundle.message("drop.noSubject") }).apply {
                toolTipText = mail.subject.takeIf { it.isNotBlank() }
                minimumSize = Dimension(0, minimumSize.height)
            }
            val top = JPanel(BorderLayout(JBUI.scale(12), 0)).apply { isOpaque = false; add(sender, BorderLayout.CENTER); add(time, BorderLayout.EAST) }
            val text = JPanel(VerticalLayout(JBUI.scale(2))).apply { isOpaque = false; add(top); add(subject) }
            box?.let { add(JPanel(BorderLayout()).apply { isOpaque = false; add(it, BorderLayout.NORTH) }, BorderLayout.WEST) }
            add(text, BorderLayout.CENTER)
            if (box != null) addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = box.doClick()
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10)
            g2.color = if (box?.isSelected != false) JBUI.CurrentTheme.List.Selection.background(false) else UIUtil.getPanelBackground()
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.dispose()
        }

        /** Wie in Mail: heute nur die Uhrzeit, sonst das Datum. */
        private fun formatTime(instant: java.time.Instant): String {
            val time = instant.atZone(ZoneId.systemDefault())
            val style = if (time.toLocalDate() == java.time.LocalDate.now()) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            return time.format(style)
        }
    }
}
