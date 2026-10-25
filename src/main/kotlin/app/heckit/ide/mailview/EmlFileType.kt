package app.heckit.ide.mailview

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/** .eml bleibt Text, damit der normale Editor die Rohdaten zeigt; die Ansichten kommen als eigene Reiter dazu. */
object EmlFileType : LanguageFileType(PlainTextLanguage.INSTANCE, true) {
    override fun getName(): String = "EML"
    override fun getDescription(): String = MailViewBundle.message("filetype.description")
    override fun getDefaultExtension(): String = "eml"
    override fun getDisplayName(): String = "EML"
    override fun getIcon(): Icon = MailIcons.FILE
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}

/** mbox: viele Mails hintereinander in einer Textdatei; die Liste kommt als eigener Reiter dazu. */
object MboxFileType : LanguageFileType(PlainTextLanguage.INSTANCE, true) {
    override fun getName(): String = "MBOX"
    override fun getDescription(): String = MailViewBundle.message("filetype.mbox.description")
    override fun getDefaultExtension(): String = "mbox"
    override fun getDisplayName(): String = "MBOX"
    override fun getIcon(): Icon = MailIcons.FILE
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}

/** Outlook-.msg ist binär (OLE2); angezeigt wird es nur über die Mail-Ansichten. */
object MsgFileType : FileType {
    override fun getName(): String = "Outlook MSG"
    override fun getDescription(): String = MailViewBundle.message("filetype.msg.description")
    override fun getDefaultExtension(): String = "msg"
    override fun getIcon(): Icon = MailIcons.FILE
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = true
}

/** Text-Mailformate (MIME): .eml, Apple-Mail-.emlx, gespeicherte Webseiten .mht/.mhtml. */
val TEXT_MAIL_EXTENSIONS = setOf("eml", "emlx", "mht", "mhtml")
/** Binäre Outlook-Formate (OLE2): Mail und Vorlage. */
val MSG_EXTENSIONS = setOf("msg", "oft")

fun isTextMail(file: VirtualFile) = file.extension?.lowercase() in TEXT_MAIL_EXTENSIONS

/** winmail.dat (TNEF) von Outlook: binär, nur über die Mail-Ansichten. */
object TnefFileType : FileType {
    override fun getName(): String = "Outlook TNEF"
    override fun getDescription(): String = MailViewBundle.message("filetype.tnef.description")
    override fun getDefaultExtension(): String = "tnef"
    override fun getIcon(): Icon = MailIcons.FILE
    override fun isBinary(): Boolean = true
    override fun isReadOnly(): Boolean = true
}

fun isTnefFile(file: VirtualFile) = file.name.equals("winmail.dat", ignoreCase = true) || file.extension.equals("tnef", ignoreCase = true)

object MailIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/eml.svg", MailIcons::class.java)

    @JvmField
    val ATTACHMENT: Icon = IconLoader.getIcon("/icons/attachment.svg", MailIcons::class.java)
}
