package app.heckit.ide.mailview

import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.HtmlFileType
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.components.BorderLayoutPanel
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

/** Reiter „Inhalt“: der dekodierte HTML- oder Textteil, schreibgeschützt. */
class MailContentEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private enum class Kind { HTML, TEXT }

    private val document = EditorFactory.getInstance().createDocument("").apply { setReadOnly(true) }
    private val editor = EditorFactory.getInstance().createViewer(document, project) as EditorEx
    private val root = BorderLayoutPanel()
    private var mail: ParsedMail? = null
    private var kind = Kind.HTML

    init {
        editor.settings.apply {
            isLineNumbersShown = true
            isUseSoftWraps = true
            isFoldingOutlineShown = true
        }
        editor.setContextMenuGroupId(MailEditorPopupGroup.ID)
        val group = DefaultActionGroup(KindAction(Kind.HTML, "content.html", AllIcons.FileTypes.Html),
            KindAction(Kind.TEXT, "content.text", AllIcons.FileTypes.Text))
        val toolbar = ActionManager.getInstance().createActionToolbar("MailView.Content", group, true)
        toolbar.targetComponent = editor.component
        root.addToTop(toolbar.component)
        root.addToCenter(editor.component)
        MailEditMode.apply(file)
        MailSource(file, this) { result ->
            mail = result.getOrNull()
            if (mail?.html == null && mail?.text != null) kind = Kind.TEXT
            if (mail?.text == null && mail?.html != null) kind = Kind.HTML
            update(result.exceptionOrNull())
        }
    }

    private fun update(error: Throwable? = null) {
        val parsed = mail
        val (content, type) = when {
            error != null -> MailViewBundle.message("preview.error", error.message ?: error.javaClass.simpleName) to PlainTextFileType.INSTANCE
            kind == Kind.HTML -> (parsed?.html ?: MailViewBundle.message("content.noHtml")) to
                (if (parsed?.html != null) HtmlFileType.INSTANCE else PlainTextFileType.INSTANCE)
            else -> (parsed?.text ?: MailViewBundle.message("content.noText")) to PlainTextFileType.INSTANCE
        }
        setHighlighter(type)
        runWriteAction {
            document.setReadOnly(false)
            document.setText(StringUtil.convertLineSeparators(content))
            document.setReadOnly(true)
        }
        editor.scrollingModel.scrollVertically(0)
    }

    private fun setHighlighter(type: FileType) {
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, type)
    }

    private inner class KindAction(private val target: Kind, key: String, icon: Icon) :
        DumbAwareToggleAction(MailViewBundle.message(key), null, icon) {
        override fun isSelected(e: AnActionEvent) = kind == target
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state && kind != target) {
                kind = target
                update()
            }
        }
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            e.presentation.isEnabled = when (target) {
                Kind.HTML -> mail?.html != null
                Kind.TEXT -> mail?.text != null
            }
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent
    override fun getName(): String = MailViewBundle.message("editor.content")
    override fun setState(state: FileEditorState) = Unit
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = EditorFactory.getInstance().releaseEditor(editor)
}
