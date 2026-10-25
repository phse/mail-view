package app.heckit.ide.mailview

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.EditorPopupHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction

/** „Mail View Settings…“: aus den Kontextmenüs direkt zur Einstellungsseite. */
class OpenMailViewSettingsAction : DumbAwareAction(MailViewBundle.message("action.settings"), null, AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) =
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, MailViewConfigurable::class.java)
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/**
 * Schlankes Kontextmenü für die Texte der Mail (Reiter Inhalt, Header, Text): Kopieren und die Einstellungen;
 * Ausschneiden und Einfügen nur im Bearbeitungsmodus. Das volle Editor-Menü (Refactoring, Git, …) passt hier nicht.
 */
class MailEditorPopupGroup : ActionGroup() {
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val manager = ActionManager.getInstance()
        val writable = e?.getData(CommonDataKeys.EDITOR)?.let { !it.isViewer && it.document.isWritable } == true
        val edit = if (writable) listOf(IdeActions.ACTION_EDITOR_CUT, IdeActions.ACTION_EDITOR_COPY, IdeActions.ACTION_EDITOR_PASTE)
            else listOf(IdeActions.ACTION_EDITOR_COPY)
        return (edit.mapNotNull { manager.getAction(it) } + Separator.getInstance() + listOfNotNull(manager.getAction(ID_SETTINGS))).toTypedArray()
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        const val ID = "MailView.EditorPopup"
        const val ID_SETTINGS = "MailView.OpenSettings"
    }
}

/** Reiter „Text“ von Mails und mbox: das schlanke Kontextmenü statt des vollen Editor-Menüs. */
class MailTextContextMenu : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!isMbox(file) && !isTextMail(file)) return
        editor.setContextMenuGroupId(MailEditorPopupGroup.ID)
        // Der Texteditor der IDE bringt einen eigenen Handler fürs volle Menü mit, teils erst nach editorCreated; der
        // zuletzt installierte gewinnt - deshalb erst danach.
        ApplicationManager.getApplication().invokeLater({ editor.installPopupHandler(EditorPopupHandler { e ->
            val group = ActionManager.getInstance().getAction(MailEditorPopupGroup.ID) as? ActionGroup ?: return@EditorPopupHandler false
            val mouse = e.mouseEvent
            ActionManager.getInstance().createActionPopupMenu("MailView.EditorPopup", group).apply { setTargetComponent(editor.contentComponent) }
                .component.show(mouse.component, mouse.x, mouse.y)
            true
        }) }, { editor.isDisposed })
    }
}
