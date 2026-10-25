package app.heckit.ide.mailview

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Mails und Anhänge aus Mail View herausschreiben: einzelne Mail als .eml, aus einer mbox gesammelt in einen Ordner. */
object MailExport {
    /** Dateiname aus dem Betreff, ohne Zeichen, die Dateisysteme nicht mögen. */
    fun emlName(subject: String?): String = baseName(subject) + ".eml"

    private fun baseName(subject: String?): String =
        (subject?.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), " ")?.trim()?.take(80)?.takeIf { it.isNotEmpty() } ?: "message")

    /** Speichern-Dialog für eine Mail als .eml. */
    fun saveEml(project: Project, near: VirtualFile?, subject: String?, data: ByteArray) {
        val descriptor = FileSaverDescriptor(MailViewBundle.message("export.eml.title"), "", "eml")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(near, emlName(subject)) ?: return
        wrapper.file.writeBytes(data)
        wrapper.virtualFile?.refresh(true, false)
    }

    /** Unveränderte Kopie einer Mail-Datei (.eml, .emlx, .mht) unter anderem Namen. */
    fun saveCopy(project: Project, file: VirtualFile) {
        val descriptor = FileSaverDescriptor(MailViewBundle.message("export.copy.title"), "", file.extension ?: "eml")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(file.parent, file.name) ?: return
        wrapper.file.writeBytes(file.contentsToByteArray())
        wrapper.virtualFile?.refresh(true, false)
    }

    /** Ordner wählen (Vorschlag: neben der mbox). */
    fun chooseFolder(project: Project, near: VirtualFile?, titleKey: String): File? {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(MailViewBundle.message(titleKey))
        return FileChooser.chooseFile(descriptor, project, near)?.toNioPath()?.toFile()
    }

    /**
     * Schreibt alle Anhänge der Mails in [dir], im Hintergrund mit Fortschritt. Gleiche Namen bekommen „(2)“ usw.,
     * es wird nichts überschrieben. [read] liefert die Rohdaten einer Mail.
     */
    fun exportAttachments(project: Project, entries: List<MboxEntry>, read: (MboxEntry) -> ByteArray, dir: File) =
        run(project, "export.attachments.progress", entries, dir) { entry, indicator ->
            val mail = runCatching { MailParser.parse(read(entry)) }.getOrNull() ?: return@run 0
            if (indicator.isCanceled) return@run 0
            val all = mail.attachments + MailHtmlRenderer.unreferencedInline(mail)
            all.forEach { unique(dir, it.name).writeBytes(it.data) }
            all.size
        }

    /** Schreibt die Mails als einzelne .eml-Dateien (Rohdaten, verlustfrei) in [dir]. */
    fun exportEml(project: Project, entries: List<MboxEntry>, read: (MboxEntry) -> ByteArray, dir: File) =
        run(project, "export.eml.progress", entries, dir) { entry, _ ->
            unique(dir, emlName(entry.subject)).writeBytes(read(entry))
            1
        }

    private fun run(project: Project, titleKey: String, entries: List<MboxEntry>, dir: File, write: (MboxEntry, ProgressIndicator) -> Int) {
        object : Task.Backgroundable(project, MailViewBundle.message(titleKey), true) {
            private var files = 0

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                dir.mkdirs()
                entries.forEachIndexed { i, entry ->
                    indicator.checkCanceled()
                    indicator.fraction = i.toDouble() / entries.size
                    indicator.text2 = entry.subject ?: ""
                    files += write(entry, indicator)
                }
            }

            override fun onSuccess() = notifyDone(project, dir, files)
            override fun onCancel() = notifyDone(project, dir, files)
        }.queue()
    }

    private fun notifyDone(project: Project, dir: File, files: Int) {
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)?.refresh(true, true)
        NotificationGroupManager.getInstance().getNotificationGroup("Mail View")
            .createNotification(MailViewBundle.message("export.done", files, dir.name), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(MailViewBundle.message("attachments.menu.reveal")) { RevealFileAction.openDirectory(dir) })
            .notify(project)
    }

    /**
     * Dateiname ohne Zeichen, die Dateisysteme nicht mögen; Punkte bleiben. FileUtil.sanitizeFileName ersetzt auch
     * den Punkt vor der Endung („Bericht_pdf“) - dann erkennt weder die IDE noch das System den Dateityp.
     */
    fun safeName(name: String): String =
        name.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().trimStart('.').ifEmpty { "attachment" }

    /** Freier Dateiname in [dir]: „Bericht.pdf“, sonst „Bericht (2).pdf“ usw. */
    internal fun unique(dir: File, name: String): File {
        val clean = safeName(name)
        val base = clean.substringBeforeLast('.', clean)
        val ext = if ('.' in clean) "." + clean.substringAfterLast('.') else ""
        return generateSequence(1) { it + 1 }.map { File(dir, if (it == 1) clean else "$base ($it)$ext") }.first { !it.exists() }
    }
}
