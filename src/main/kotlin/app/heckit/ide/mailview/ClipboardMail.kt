package app.heckit.ide.mailview

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.editor.Document
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.function.Function
import javax.swing.JComponent

/**
 * „Mail aus Zwischenablage öffnen“: Quelltext einer Mail (Gmail „Original anzeigen“, Outlook „Nachrichtenquelle“,
 * Mail-Logs) als ungespeicherte .eml in Mail View zeigen. Im Finder kopierte Mail-Dateien werden direkt geöffnet.
 */
class OpenMailFromClipboardAction : DumbAwareAction(
    MailViewBundle.message("action.clipboard"), MailViewBundle.message("action.clipboard.description"), MailIcons.FILE) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val clipboard = CopyPasteManager.getInstance()
        val files = runCatching { clipboard.getContents<List<File>>(DataFlavor.javaFileListFlavor) }.getOrNull().orEmpty()
            .mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
            .filter { isTextMail(it) || it.extension?.lowercase() in MSG_EXTENSIONS || isTnefFile(it) || isMbox(it) }
        if (files.isNotEmpty()) return files.forEach { OpenFileDescriptor(project, it).navigate(true) }

        val text = clipboard.getContents<String>(DataFlavor.stringFlavor)?.let(ClipboardMail::extract)
            ?: return ClipboardMail.notifyNoMail(project)
        OpenFileDescriptor(project, ClipboardMail.file(text)).navigate(true)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

object ClipboardMail {
    /** So viel Text reicht, um den Kopf einer Mail zu erkennen. */
    private const val HEAD_LIMIT = 64 * 1024
    /** Größere Dateien werden im Kontextmenü nicht geprüft (müssten erst geladen werden). */
    private const val MAX_FILE_SIZE = 20L * 1024 * 1024
    /** Balken für diese Datei ausgeblendet (bis zum Neustart der IDE); das Kontextmenü bleibt. */
    val DISMISSED = Key.create<Boolean>("mailview.source.dismissed")

    /** Textdateien, die keine Mail-Dateien sind (Scratch, .txt, Logs, …). */
    fun candidate(file: VirtualFile) = file.isValid && !file.isDirectory && !file.fileType.isBinary && file.length <= MAX_FILE_SIZE &&
        !isTextMail(file) && !isMbox(file) && file !is LightVirtualFile

    /** Dokument der Datei, wenn sein Anfang wie eine Mail aussieht. */
    fun mailDocument(file: VirtualFile): Document? {
        if (!candidate(file)) return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return document.takeIf { extract(it.charsSequence.subSequence(0, minOf(it.textLength, HEAD_LIMIT)).toString()) != null }
    }

    fun show(project: Project, document: Document) {
        extract(document.text)?.let { OpenFileDescriptor(project, file(it)).navigate(true) }
    }

    private val HEADER = Regex("^[A-Za-z0-9-]+:")
    private val KNOWN = setOf("from", "to", "subject", "date", "message-id", "received", "return-path", "delivered-to", "mime-version")

    /**
     * Der Mail-Quelltext aus [text] oder null, wenn es keine Mail ist: Der Kopf muss mit Header-Zeilen beginnen
     * (ggf. nach der „From “-Zeile einer mbox) und mindestens zwei bekannte Felder enthalten.
     */
    fun extract(text: String): String? {
        val source = text.trimStart('﻿', ' ', '\t', '\r', '\n').lines()
            .let { if (it.firstOrNull()?.startsWith("From ") == true) it.drop(1) else it }
            .joinToString("\n")
        val head = source.lineSequence().takeWhile { it.isNotBlank() }.toList()
        if (head.isEmpty() || !HEADER.containsMatchIn(head.first())) return null
        val names = head.filter { HEADER.containsMatchIn(it) }.map { it.substringBefore(':').lowercase() }.toSet()
        return source.takeIf { names.count { it in KNOWN } >= 2 }
    }

    /** Ungespeicherte Datei, benannt nach dem Betreff; „Als .eml speichern“ schreibt sie bei Bedarf weg. */
    fun file(text: String): LightVirtualFile {
        val subject = runCatching { MailParser.parse(text.toByteArray(Charsets.UTF_8)).subject }.getOrNull()
        return LightVirtualFile(MailExport.emlName(subject), EmlFileType, text).apply { charset = Charsets.UTF_8 }
    }

    fun notifyNoMail(project: Project) =
        NotificationGroupManager.getInstance().getNotificationGroup("Mail View")
            .createNotification(MailViewBundle.message("action.clipboard.none"), NotificationType.WARNING)
            .setIcon(AllIcons.General.Warning)
            .notify(project)
}

/**
 * Mail-Quelltext in einer normalen Textdatei (z. B. in eine Scratch-Datei eingefügt): Balken darüber zeigt ihn in
 * Mail View. Ausgeblendet bleibt „In Mail View anzeigen“ im Kontextmenü von Editor, Reiter und Projektbaum.
 */
class MailSourceNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.getUserData(ClipboardMail.DISMISSED) == true) return null
        val document = ClipboardMail.mailDocument(file) ?: return null
        return Function { editor ->
            if (editor !is TextEditor) return@Function null
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                icon(MailIcons.FILE)
                text = MailViewBundle.message("source.banner")
                createActionLabel(MailViewBundle.message("source.show")) { ClipboardMail.show(project, document) }
                createActionLabel(MailViewBundle.message("source.dismiss")) {
                    file.putUserData(ClipboardMail.DISMISSED, true)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
        }
    }
}

/** Nach dem Einfügen in eine Textdatei (oder dem Leeren) den Balken neu prüfen. */
class MailSourceDocumentListener : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
        if (event.newLength < 200 && event.document.textLength > 0) return
        val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
        if (!ClipboardMail.candidate(file)) return
        ProjectManager.getInstance().openProjects.forEach { EditorNotifications.getInstance(it).updateNotifications(file) }
    }
}

/** „In Mail View anzeigen“ im Kontextmenü, nur für Textdateien, die wie eine Mail aussehen. */
class ShowInMailViewAction : DumbAwareAction() {
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && ClipboardMail.mailDocument(file) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val document = ClipboardMail.mailDocument(e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return) ?: return
        ClipboardMail.show(project, document)
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
