package app.heckit.ide.mailview

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

object MailIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/eml.svg", MailIcons::class.java)
}
