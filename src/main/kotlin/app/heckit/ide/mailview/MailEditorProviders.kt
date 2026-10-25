package app.heckit.ide.mailview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

// Beide stehen vor dem normalen Texteditor (Rohdaten); so ergeben sich unten die Reiter Mail | Inhalt | Header | Text.
// Bei .msg (binär) gibt es keinen Texteditor, dort bleiben Mail | Inhalt | Header.
private fun isMail(file: VirtualFile) = !file.isDirectory &&
    (isTextMail(file) || file.extension?.lowercase() in MSG_EXTENSIONS || isTnefFile(file))

class MailPreviewEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = isMail(file)
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = MailPreviewEditor(project, file)
    override fun getEditorTypeId() = "mail-view-preview"
    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

class MailHeaderEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = isMail(file)
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = MailHeaderEditor(project, file)
    override fun getEditorTypeId() = "mail-view-headers"
    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

class MailContentEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = isMail(file)
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = MailContentEditor(project, file)
    override fun getEditorTypeId() = "mail-view-content"
    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}
