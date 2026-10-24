package app.heckit.ide.mailview

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent

/**
 * Mails sind standardmäßig schreibgeschützt (nur lesen und markieren), damit sie nicht versehentlich
 * verändert werden. Der Bearbeitungsmodus gilt pro Datei und nur bis zum Neustart der IDE.
 */
object MailEditMode {
    private val EDITING = Key.create<Boolean>("mailview.editing")

    fun isEditing(file: VirtualFile) = file.getUserData(EDITING) == true

    fun apply(file: VirtualFile) {
        FileDocumentManager.getInstance().getDocument(file)?.setReadOnly(!isEditing(file))
    }

    fun set(project: Project, file: VirtualFile, editing: Boolean) {
        file.putUserData(EDITING, editing)
        apply(file)
        EditorNotifications.getInstance(project).updateNotifications(file)
    }
}

/** Balken über dem Reiter „Text“: zeigt den Schreibschutz und schaltet den Bearbeitungsmodus um. */
class MailEditModeNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!isTextMail(file)) return null
        return Function { editor ->
            if (editor !is TextEditor) return@Function null
            MailEditMode.apply(file)
            val editing = MailEditMode.isEditing(file)
            EditorNotificationPanel(editor, if (editing) EditorNotificationPanel.Status.Warning else EditorNotificationPanel.Status.Info).apply {
                text = MailViewBundle.message(if (editing) "edit.on.text" else "edit.off.text")
                createActionLabel(MailViewBundle.message(if (editing) "edit.on.action" else "edit.off.action")) {
                    MailEditMode.set(project, file, !editing)
                }
            }
        }
    }
}
