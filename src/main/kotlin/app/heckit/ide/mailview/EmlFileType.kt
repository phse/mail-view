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

object MailIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/eml.svg", MailIcons::class.java)
}
