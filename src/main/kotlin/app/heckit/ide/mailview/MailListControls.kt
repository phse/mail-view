package app.heckit.ide.mailview

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Steuerung der Mail-Vorschau unter der mbox-Liste: blättern (↑/↓) und die Mail als eigenen Tab öffnen. */
interface MailListControls {
    fun canMove(delta: Int): Boolean
    fun move(delta: Int)
    fun position(): String
    fun openInTab()
    /** Rohdaten der gezeigten Mail, zum Speichern als .eml. */
    fun currentMail(): ByteArray?
}

/** Mails aus einer mbox als .eml in eigenen Temp-Ordnern, um sie als Tab zu öffnen. */
object MailTempFiles {
    fun create(entry: MboxEntry, data: ByteArray): VirtualFile? {
        val target = File(FileUtil.createTempDirectory("mail-view", null, true), MailExport.emlName(entry.subject)).apply { writeBytes(data) }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
    }
}
