package app.heckit.ide.mailview

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.Alarm

/** Reiter „Text“ einer mbox: Trennlinie über jeder Mail; im Bearbeitungsmodus nicht, dort stören sie beim Tippen. */
class MboxSeparators : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!isMbox(file)) return
        val disposable = Disposer.newDisposable("Mail View mbox separators")
        editor.putUserData(DISPOSABLE, disposable)
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                alarm.cancelAllRequests()
                alarm.addRequest({ update(editor, file) }, 500)
            }
        }, disposable)
        update(editor, file)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        event.editor.getUserData(DISPOSABLE)?.let(Disposer::dispose)
    }

    companion object {
        private val DISPOSABLE = Key.create<Disposable>("mailview.mbox.separators.disposable")
        private val HIGHLIGHTERS = Key.create<List<RangeHighlighter>>("mailview.mbox.separators")
        private val COLOR = JBColor(0x9AA0A6, 0x6F737A)

        /** Nach dem Umschalten des Bearbeitungsmodus für alle offenen Editoren der Datei. */
        fun refresh(file: VirtualFile) {
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return
            EditorFactory.getInstance().getEditors(document).forEach { update(it, file) }
        }

        private fun update(editor: Editor, file: VirtualFile) {
            if (editor.isDisposed) return
            val markup = editor.markupModel
            editor.getUserData(HIGHLIGHTERS)?.forEach(markup::removeHighlighter)
            editor.putUserData(HIGHLIGHTERS, null)
            if (MailEditMode.isEditing(file)) return
            val document = editor.document
            val text = document.immutableCharSequence
            val result = ArrayList<RangeHighlighter>()
            var previousBlank = true
            for (line in 0 until document.lineCount) {
                val start = document.getLineStartOffset(line)
                val end = document.getLineEndOffset(line)
                // Die erste Mail braucht keinen Strich darüber.
                if (line > 0 && previousBlank && end - start >= 5 && text.startsWith("From ", start)) {
                    result += markup.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null).apply {
                        lineSeparatorColor = COLOR
                        lineSeparatorPlacement = SeparatorPlacement.TOP
                    }
                }
                previousBlank = (start until end).all { text[it] == '\r' || text[it] == ' ' || text[it] == '\t' }
            }
            editor.putUserData(HIGHLIGHTERS, result)
        }

        private fun CharSequence.startsWith(prefix: String, at: Int) = regionMatches(at, prefix, 0, prefix.length)
    }
}
