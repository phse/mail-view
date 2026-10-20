package app.heckit.ide.mailview

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** Reiter „Header“: alle Kopfzeilen der Mail (Received-Kette, Authentifizierung …), schreibgeschützt. */
class MailHeaderEditor(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val document = EditorFactory.getInstance().createDocument("").apply { setReadOnly(true) }
    private val editor = EditorFactory.getInstance().createViewer(document, project)

    init {
        editor.settings.apply {
            isLineNumbersShown = true
            isUseSoftWraps = true
        }
        MailEditMode.apply(file)
        MailSource(file, this) { result ->
            val content = result.fold(
                { it.headers ?: MailViewBundle.message("headers.none") },
                { MailViewBundle.message("preview.error", it.message ?: it.javaClass.simpleName) },
            )
            runWriteAction {
                document.setReadOnly(false)
                document.setText(StringUtil.convertLineSeparators(content))
                document.setReadOnly(true)
            }
        }
    }

    override fun getComponent(): JComponent = editor.component
    override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent
    override fun getName(): String = MailViewBundle.message("editor.headers")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = EditorFactory.getInstance().releaseEditor(editor)
}
