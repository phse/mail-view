package app.heckit.ide.mailview

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.JBColor

/** Einstellungen der Mail-Ansicht, für alle Projekte gleich. */
object MailViewSettings {
    private const val DARK_KEY = "app.heckit.ide.mailview.dark"

    /** Mails dunkel zeigen; solange nichts gewählt wurde, wie das IDE-Theme. */
    var dark: Boolean
        get() = PropertiesComponent.getInstance().getValue(DARK_KEY)?.toBoolean() ?: !JBColor.isBright()
        set(value) = PropertiesComponent.getInstance().setValue(DARK_KEY, value.toString())
}
