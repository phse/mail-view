package app.heckit.ide.mailview

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile

/**
 * Beim Öffnen einer Mail mit dem Reiter aus den Einstellungen beginnen (Vorgabe „Mail“, bei einer mbox die Liste).
 * Die IDE merkt sich sonst den zuletzt gewählten Reiter je Datei - das bleibt nur, wenn „Letzten Reiter merken“ an ist.
 */
class MailTabSelector : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (MailViewSettings.rememberTab) return
        val id = when {
            isMbox(file) -> "mail-view-mbox"
            isTextMail(file) || file.extension?.lowercase() in MSG_EXTENSIONS || isTnefFile(file) -> MailViewSettings.startTab.editorId
            else -> return
        }
        if (source.getAllEditors(file).any { it is MailPreviewEditor || it is MboxEditor }) source.setSelectedEditor(file, id)
    }
}
