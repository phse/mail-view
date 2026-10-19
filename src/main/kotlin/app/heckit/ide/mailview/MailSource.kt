package app.heckit.ide.mailview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm

/** Liefert die geparste Mail und meldet Änderungen am Text (Roh-Ansicht oder auf der Platte). */
class MailSource(private val file: VirtualFile, parent: Disposable, private val onChange: (Result<ParsedMail>) -> Unit) {
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)

    init {
        FileDocumentManager.getInstance().getDocument(file)?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule(400)
        }, parent)
        schedule(0)
    }

    private fun schedule(delay: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            val result = runCatching { MailParser.parse(readBytes()) }
            ApplicationManager.getApplication().invokeLater({ onChange(result) }, { !file.isValid })
        }, delay)
    }

    /** Ungespeicherte Änderungen aus dem Dokument, sonst die Bytes der Datei (verlustfrei bei 8-Bit-Inhalten). */
    private fun readBytes(): ByteArray = ApplicationManager.getApplication().runReadAction<ByteArray> {
        val manager = FileDocumentManager.getInstance()
        val document = manager.getCachedDocument(file)
        if (document != null && manager.isDocumentUnsaved(document)) document.text.toByteArray(file.charset)
        else file.contentsToByteArray()
    }
}
