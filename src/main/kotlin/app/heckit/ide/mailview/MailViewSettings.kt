package app.heckit.ide.mailview

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.JBColor

/** Einstellungen der Mail-Ansicht, für alle Projekte gleich. */
object MailViewSettings {
    private const val DARK_KEY = "app.heckit.ide.mailview.dark"
    private const val APPEARANCE_KEY = "app.heckit.ide.mailview.appearance"

    /** Wie eine Mail beim Öffnen aussieht. */
    enum class Appearance { LAST, SYSTEM, LIGHT, DARK }

    var appearance: Appearance
        get() = PropertiesComponent.getInstance().getValue(APPEARANCE_KEY)?.let { v -> Appearance.entries.find { it.name == v } } ?: Appearance.LAST
        set(value) = PropertiesComponent.getInstance().setValue(APPEARANCE_KEY, value.name, Appearance.LAST.name)

    /** Zuletzt umgeschaltet (hell/dunkel); solange nie umgeschaltet wurde, wie das IDE-Theme. */
    var dark: Boolean
        get() = PropertiesComponent.getInstance().getValue(DARK_KEY)?.toBoolean() ?: !JBColor.isBright()
        set(value) = PropertiesComponent.getInstance().setValue(DARK_KEY, value.toString())

    /** Dunkel beim Öffnen einer Mail, je nach [appearance]. */
    val darkOnOpen: Boolean
        get() = when (appearance) {
            Appearance.LAST -> dark
            Appearance.SYSTEM -> !JBColor.isBright()
            Appearance.LIGHT -> false
            Appearance.DARK -> true
        }

    /** Reiter beim Öffnen einer Mail; bei einer mbox gibt es nur die Liste („Mail“) und den Text. */
    enum class StartTab(val editorId: String) { MAIL("mail-view-preview"), CONTENT("mail-view-content"), HEADERS("mail-view-headers") }

    var startTab: StartTab
        get() = PropertiesComponent.getInstance().getValue(START_TAB_KEY)?.let { v -> StartTab.entries.find { it.name == v } } ?: StartTab.MAIL
        set(value) = PropertiesComponent.getInstance().setValue(START_TAB_KEY, value.name, StartTab.MAIL.name)

    /** Zuletzt gewählten Reiter je Datei wieder öffnen (wie die IDE es sonst macht) statt [startTab]. */
    var rememberTab: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(REMEMBER_TAB_KEY, false)
        set(value) = PropertiesComponent.getInstance().setValue(REMEMBER_TAB_KEY, value, false)

    /** Bilder und andere Inhalte aus dem Netz: nie, immer oder nur von vertrauten Absendern ([trustedSenders]). */
    enum class RemoteContent { BLOCK, TRUSTED, ALLOW }

    var remoteContent: RemoteContent
        get() = PropertiesComponent.getInstance().getValue(REMOTE_KEY)?.let { v -> RemoteContent.entries.find { it.name == v } } ?: RemoteContent.BLOCK
        set(value) = PropertiesComponent.getInstance().setValue(REMOTE_KEY, value.name, RemoteContent.BLOCK.name)

    /** Adressen oder Domains, eine je Zeile (auch durch Komma getrennt). */
    var trustedSenders: String
        get() = PropertiesComponent.getInstance().getValue(TRUSTED_KEY) ?: ""
        set(value) = PropertiesComponent.getInstance().setValue(TRUSTED_KEY, value, "")

    private fun trustedList() = trustedSenders.split(',', '\n').map { it.trim().lowercase().removePrefix("@") }.filter { it.isNotEmpty() }

    /** Externe Inhalte sofort laden, je nach [remoteContent] und Absender. */
    fun loadRemote(mail: ParsedMail): Boolean = when (remoteContent) {
        RemoteContent.ALLOW -> true
        RemoteContent.BLOCK -> false
        RemoteContent.TRUSTED -> {
            val trusted = trustedList()
            mail.from.mapNotNull { it.address?.lowercase() }.any { a -> a in trusted || a.substringAfter('@') in trusted }
        }
    }

    /** Domain des Absenders zu den vertrauten aufnehmen; ab dann gilt [RemoteContent.TRUSTED] (außer „immer laden“). */
    fun trust(domain: String) {
        if (domain.lowercase() !in trustedList()) trustedSenders = (trustedList() + domain.lowercase()).joinToString("\n")
        if (remoteContent == RemoteContent.BLOCK) remoteContent = RemoteContent.TRUSTED
    }

    /** Textfassung statt HTML zeigen, wenn die Mail beide hat. */
    var preferText: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(PREFER_TEXT_KEY, false)
        set(value) = PropertiesComponent.getInstance().setValue(PREFER_TEXT_KEY, value, false)

    /** Anhangliste über der Mail aufgeklappt zeigen. */
    var expandAttachments: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(EXPAND_KEY, false)
        set(value) = PropertiesComponent.getInstance().setValue(EXPAND_KEY, value, false)

    /** Anhänge, die die IDE nicht kennt: mit dem Standardprogramm öffnen oder nur im Finder/Explorer zeigen. */
    var revealUnknown: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(REVEAL_UNKNOWN_KEY, false)
        set(value) = PropertiesComponent.getInstance().setValue(REVEAL_UNKNOWN_KEY, value, false)

    /** Vor den Betreff einer Antwort. */
    var replyPrefix: String
        get() = PropertiesComponent.getInstance().getValue(REPLY_KEY) ?: MailViewBundle.message("settings.replyPrefix.default")
        set(value) = PropertiesComponent.getInstance().setValue(REPLY_KEY, value, MailViewBundle.message("settings.replyPrefix.default"))

    /** Vor den Betreff einer Weiterleitung. */
    var forwardPrefix: String
        get() = PropertiesComponent.getInstance().getValue(FORWARD_KEY) ?: MailViewBundle.message("settings.forwardPrefix.default")
        set(value) = PropertiesComponent.getInstance().setValue(FORWARD_KEY, value, MailViewBundle.message("settings.forwardPrefix.default"))

    /** Zeile über dem Zitat mit den Platzhaltern {date}, {time} und {sender}. */
    var quoteHeader: String
        get() = PropertiesComponent.getInstance().getValue(QUOTE_KEY) ?: MailViewBundle.message("settings.quoteHeader.default")
        set(value) = PropertiesComponent.getInstance().setValue(QUOTE_KEY, value, MailViewBundle.message("settings.quoteHeader.default"))

    /** Erlaubnis „Automation → Mail“ erteilt: Mails aus Apple Mail in den Projektbaum ziehen (nur macOS). */
    var mailDrop: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(MAIL_DROP_KEY, false)
        set(value) = PropertiesComponent.getInstance().setValue(MAIL_DROP_KEY, value, false)

    private const val MAIL_DROP_KEY = "app.heckit.ide.mailview.mailDrop"
    private const val REMOTE_KEY = "app.heckit.ide.mailview.remoteContent"
    private const val TRUSTED_KEY = "app.heckit.ide.mailview.trustedSenders"
    private const val PREFER_TEXT_KEY = "app.heckit.ide.mailview.preferText"
    private const val EXPAND_KEY = "app.heckit.ide.mailview.expandAttachments"
    private const val REVEAL_UNKNOWN_KEY = "app.heckit.ide.mailview.revealUnknown"
    private const val START_TAB_KEY = "app.heckit.ide.mailview.startTab"
    private const val REMEMBER_TAB_KEY = "app.heckit.ide.mailview.rememberTab"
    private const val REPLY_KEY = "app.heckit.ide.mailview.replyPrefix"
    private const val FORWARD_KEY = "app.heckit.ide.mailview.forwardPrefix"
    private const val QUOTE_KEY = "app.heckit.ide.mailview.quoteHeader"
}
