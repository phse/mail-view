package app.heckit.ide.mailview

import com.intellij.DynamicBundle
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.Component
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

object MailpitIcons {
    @JvmField
    val TOOL_WINDOW = IconLoader.getIcon("/icons/mailpit.svg", MailpitIcons::class.java)
}

class MailpitToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        MailpitTabs(project, toolWindow).rebuild()
    }
}

/**
 * Ein Reiter je Mailpit-Instanz. Passen nicht alle in die Breite, sammelt die IDE den Rest hinter einem Pfeil; die
 * Zahl ungelesener Mails steht im Reiter-Namen (auch in dieser Liste) und als Summe im Tool-Window-Titel.
 */
class MailpitTabs(private val project: Project, private val toolWindow: ToolWindow) {
    private val unread = mutableMapOf<String, Int>()
    /** Laufende Mailpit-Container (`docker ps`), zuletzt gesehen. */
    private var containers: List<MailpitDiscovery.Container> = emptyList()
    private val discovery = Alarm(Alarm.ThreadToUse.POOLED_THREAD, toolWindow.disposable)

    init {
        // Kopfzeilen-Symbole (Pfeil, +, ⋮, −) immer zeigen: Sonst tauchen sie erst beim Darüberfahren auf, nehmen
        // den Reitern Platz weg, und Reiter springen hinter den Pfeil.
        com.intellij.ui.ClientProperty.put(toolWindow.component,
            com.intellij.openapi.wm.impl.content.ToolWindowContentUi.DONT_HIDE_TOOLBAR_IN_HEADER, true)
        toolWindow.setTitleActions(listOf(object : DumbAwareAction(MailViewBundle.message("mailpit.add"), null, AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) = edit(null)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }))
        discover(0)
    }

    /**
     * Docker-Container nachsehen, solange das Tool Window sichtbar ist alle [DISCOVERY_MS], sonst seltener; ändert
     * sich etwas (Container gestartet/gestoppt, Compose-Datei geändert), kommen Reiter dazu bzw. fallen weg.
     */
    private fun discover(delay: Int) {
        if (discovery.isDisposed) return
        discovery.addRequest({
            val found = MailpitDiscovery.runningContainers()
            ApplicationManager.getApplication().invokeLater({
                containers = found
                rebuild()
            }, { project.isDisposed || discovery.isDisposed })
            discover(if (toolWindow.isVisible) DISCOVERY_MS else DISCOVERY_HIDDEN_MS)
        }, delay)
    }

    /**
     * Reiter an die Instanzen angleichen: Unveränderte bleiben stehen (keine neue Ladeanzeige), neue kommen an ihre
     * Stelle dazu, weggefallene und geänderte (andere Adresse oder Name) werden ersetzt. [select]: diesen Reiter wählen.
     */
    fun rebuild(select: String? = null) {
        val manager = toolWindow.contentManager
        val wanted = MailpitInstances.all(project, containers)
        fun instanceOf(content: com.intellij.ui.content.Content) = content.getUserData(MailpitPanel.KEY)?.instance
        for (content in manager.contents.toList()) {
            val instance = instanceOf(content)
            if (instance !in wanted) {
                instance?.let { unread.remove(it.id) }
                manager.removeContent(content, true)
            }
        }
        wanted.forEachIndexed { index, instance ->
            val existing = manager.contents.firstOrNull { instanceOf(it) == instance }
            when {
                existing == null -> manager.addContent(createContent(instance), index)
                manager.getIndexOfContent(existing) != index -> {
                    manager.removeContent(existing, false)
                    manager.addContent(existing, index)
                }
            }
        }
        select?.let { id -> manager.contents.firstOrNull { instanceOf(it)?.id == id }?.let(manager::setSelectedContent) }
        updateTitle()
    }

    private fun createContent(instance: MailpitInstance): com.intellij.ui.content.Content {
        val panel = MailpitPanel(project, toolWindow, instance, this)
        val content = ContentFactory.getInstance().createContent(panel.component, instance.name, false)
        content.setDisposer(panel)
        content.putUserData(MailpitPanel.KEY, panel)
        content.isCloseable = false
        content.description = instance.url
        panel.content = content
        return content
    }

    fun unreadChanged(instance: MailpitInstance, count: Int) {
        unread[instance.id] = count
        updateTitle()
    }

    private fun updateTitle() {
        val total = unread.values.sum()
        val title = MailViewBundle.message("mailpit.title")
        toolWindow.stripeTitle = if (total > 0) "$title ($total)" else title
    }

    /** Neue Instanz ([instance] null) bzw. Instanz ändern; Zugangsdaten aus dem Passwort-Speicher im Hintergrund. */
    fun edit(instance: MailpitInstance?) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val credentials = instance?.let { MailpitSettings.credentials(it.url) }
            ApplicationManager.getApplication().invokeLater({ edit(instance, credentials) }, { project.isDisposed })
        }
    }

    private fun edit(instance: MailpitInstance?, credentials: com.intellij.credentialStore.Credentials?) {
        val dialog = MailpitDialog(project, instance, credentials?.userName.orEmpty(), credentials?.getPasswordAsString().orEmpty())
        if (!dialog.showAndGet()) return
        val saved = (instance ?: MailpitInstance(java.util.UUID.randomUUID().toString(), "", ""))
            .copy(name = dialog.name, url = dialog.url, global = dialog.global)
        val user = dialog.user
        val password = dialog.password
        ApplicationManager.getApplication().executeOnPooledThread {
            MailpitSettings.setCredentials(saved.url, user, password)
            ApplicationManager.getApplication().invokeLater({
                MailpitInstances.save(project, saved)
                val id = MailpitInstances.all(project, containers).firstOrNull { it.url == saved.url && it.name == saved.name }?.id
                rebuild(id)
            }, { project.isDisposed })
        }
    }

    fun remove(instance: MailpitInstance) {
        val ok = Messages.showYesNoDialog(project, MailViewBundle.message("mailpit.remove.question", instance.name, instance.url),
            MailViewBundle.message("mailpit.remove"), Messages.getQuestionIcon()) == Messages.YES
        if (!ok) return
        MailpitInstances.remove(project, instance)
        rebuild()
    }

    companion object {
        private const val DISCOVERY_MS = 10_000
        private const val DISCOVERY_HIDDEN_MS = 60_000
    }
}

/**
 * Liste der Mails in Mailpit (lokaler Test-Mailserver, z. B. in DDEV). Solange das Tool Window offen ist, wird alle
 * paar Sekunden nachgesehen; ein Klick öffnet die Mail in Mail View.
 */
class MailpitPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
    val instance: MailpitInstance,
    private val tabs: MailpitTabs,
) : Disposable {
    var content: com.intellij.ui.content.Content? = null
    private val model = CollectionListModel<MailpitMessage>()
    /** Immer so breit wie das Tool Window: lange Betreffs enden mit „…“, Uhrzeit bleibt rechts sichtbar. */
    private val list = object : JBList<MailpitMessage>(model) {
        override fun getScrollableTracksViewportWidth() = true
    }.apply {
        setExpandableItemsEnabled(false)
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        cellRenderer = Renderer()
        emptyText.text = MailViewBundle.message("mailpit.loading")
    }
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(4, 8)
    }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    /** Schon geöffnete Mails: ein zweiter Klick holt den vorhandenen Tab nach vorn. */
    private val opened = mutableMapOf<String, VirtualFile>()
    private var signature: List<Triple<String, Boolean, List<String>>>? = null
    @Volatile private var client = MailpitClient(instance.url)
    /** Client neu aufbauen (Zugangsdaten aus dem Passwort-Speicher, daher im Hintergrund). */
    @Volatile private var reload = true
    private var lastError: String? = null
    @Volatile private var lastPoll = 0L
    /** Weiterleiten über SMTP, falls in Mailpit eingerichtet (ab und zu neu abgefragt). */
    @Volatile private var relay: MailpitRelay? = null
    @Volatile private var relayChecked = 0L

    val component = BorderLayoutPanel()
    /** Bis zum ersten eindeutigen Ergebnis die Lade-Animation statt der (noch leeren) Liste. */
    private val loading = HkLoadingPanel(HkLoading.Style.ENVELOPE, MailViewBundle.message("mailpit.loading"))
    private val toast = MailpitToast()
    private val cards = java.awt.CardLayout()
    private val center = JPanel(cards)
    /** Erste Anmeldefehler nach dem Öffnen: Der Cookie-Speicher des eingebetteten Browsers ist evtl. noch nicht bereit. */
    private var authRetries = 0
    @Volatile private var loaded = false
    /** Suchbegriff (Volltextsuche von Mailpit); leer: alle Mails. */
    @Volatile private var query = ""
    /** Gewähltes Schlagwort (Trichter-Knopf); getrennt vom Suchfeld, beides zusammen geht als Suche an Mailpit. */
    @Volatile private var tagFilter: String? = null
    private val searchAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val search = com.intellij.ui.SearchTextField(false).apply {
        textEditor.emptyText.text = MailViewBundle.message("mailpit.search")
        border = JBUI.Borders.empty(0, 6, 4, 6)
        addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                searchAlarm.cancelAllRequests()
                searchAlarm.addRequest({ query = text; refresh(force = true) }, 300)
            }
        })
    }

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar("MailView.Mailpit", DefaultActionGroup(
            action("mailpit.refresh", AllIcons.Actions.Refresh) { feedback.start(); refresh(force = true) },
            action("mailpit.deleteAll", AllIcons.Actions.GC) { deleteAll() },
            TagFilterAction(),
            Separator.getInstance(),
            action("mailpit.openWeb", AllIcons.General.Web) { BrowserUtil.browse(client.baseUrl) },
        ), true)
        toolbar.targetComponent = list
        // Rechts: Einstellungen dieser Instanz (bearbeiten, entfernen), getrennt von den Aktionen auf die Mails.
        val instanceToolbar = ActionManager.getInstance().createActionToolbar("MailView.MailpitInstance", DefaultActionGroup(
            action("mailpit.edit", AllIcons.General.Settings) { tabs.edit(instance) },
            object : DumbAwareAction(MailViewBundle.message("mailpit.remove"), null, AllIcons.General.Remove) {
                override fun actionPerformed(e: AnActionEvent) = tabs.remove(instance)
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = !instance.auto }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
        ), true)
        instanceToolbar.targetComponent = list
        center.add(loading, CARD_LOADING)
        center.add(JBScrollPane(list, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply { border = JBUI.Borders.empty() }, CARD_LIST)
        cards.show(center, CARD_LOADING)
        // Liste und darüber der Hinweis (eigene Ebene, damit er über der Liste schwebt).
        val layered = object : javax.swing.JLayeredPane() {
            override fun doLayout() {
                center.setBounds(0, 0, width, height)
                val size = toast.preferredSize
                toast.setBounds((width - size.width) / 2, height - size.height - JBUI.scale(10), size.width, size.height)
            }
            override fun getPreferredSize(): java.awt.Dimension = center.preferredSize
        }
        // Ebene per setLayer: add(c, POPUP_LAYER) nähme in Kotlin die Überladung mit Index (Hinweis läge unter der Liste).
        layered.setLayer(toast, javax.swing.JLayeredPane.POPUP_LAYER)
        layered.add(center)
        layered.add(toast)
        toast.onResize = { layered.revalidate(); layered.repaint() }
        component.addToTop(BorderLayoutPanel().addToTop(BorderLayoutPanel().addToCenter(toolbar.component).addToRight(instanceToolbar.component)).addToBottom(search)).addToCenter(layered).addToBottom(status)

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1 && list.locationToIndex(e.point) >= 0) openSelected(preview = true)
                if (e.button == MouseEvent.BUTTON1 && e.clickCount == 2) openSelected(preview = false)
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> { openSelected(preview = false); e.consume() }
                    KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> { deleteSelected(); e.consume() }
                }
            }
        })
        PopupHandler.installPopupMenu(list, DefaultActionGroup(
            action("mailpit.open", null) { openSelected(preview = false) },
            action("mailpit.save", AllIcons.Actions.MenuSaveall) { saveSelected() },
            action("mailpit.openWebMail", AllIcons.General.Web) { selected().forEach { BrowserUtil.browse(client.webUrl(it.id)) } },
            object : DumbAwareAction(MailViewBundle.message("mailpit.release"), null, AllIcons.Actions.Forward) {
                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = relay != null
                    e.presentation.isEnabled = selected().isNotEmpty()
                }
                override fun actionPerformed(e: AnActionEvent) = release()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            Separator.getInstance(),
            object : DumbAwareAction() {
                // Ist etwas Ungelesenes ausgewählt: „Als gelesen markieren“, sonst „Als ungelesen markieren“.
                override fun update(e: AnActionEvent) {
                    val unread = selected().any { !it.read }
                    e.presentation.isEnabled = selected().isNotEmpty()
                    e.presentation.text = MailViewBundle.message(if (unread) "mailpit.markRead" else "mailpit.markUnread")
                }
                override fun actionPerformed(e: AnActionEvent) {
                    val messages = selected().ifEmpty { return }
                    val read = messages.any { !it.read }
                    val ids = messages.map { it.id }
                    background { client.markRead(ids, read) }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            },
            action("mailpit.delete", AllIcons.Actions.Cancel) { deleteSelected() },
        ), "MailView.MailpitPopup")
        refresh(force = true)
    }

    private fun action(key: String, icon: javax.swing.Icon?, run: () -> Unit) =
        object : DumbAwareAction(MailViewBundle.message(key), null, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun selected(): List<MailpitMessage> = list.selectedValuesList

    /** Liste neu laden; danach in [POLL_MS] wieder, solange das Tool Window sichtbar ist. */
    private fun refresh(force: Boolean, now: Boolean = false) {
        alarm.cancelAllRequests()
        if (force) signature = null
        alarm.addRequest({
            if (reload) { reload = false; client = MailpitSettings.client(instance.url) }
            val current = client
            val due = force || (toolWindow.isVisible && content?.isSelected == true) ||
                System.currentTimeMillis() - lastPoll >= BACKGROUND_POLL_MS
            var result = if (due) runCatching { current.list(query = effectiveQuery()) } else null
            if (due) lastPoll = System.currentTimeMillis()
            if (result?.isSuccess == true && System.currentTimeMillis() - relayChecked >= RELAY_CHECK_MS) {
                relay = runCatching { current.relay() }.getOrNull()
                relayChecked = System.currentTimeMillis()
            }
            // Abgelaufen oder neu gestartet: Hat der eingebettete Browser (noch) ein Sitzungs-Cookie, damit erneut versuchen.
            if (result?.exceptionOrNull() is MailpitAuthException) {
                val cookie = MailpitSessions.fromBrowser(current.baseUrl)
                if (cookie != null && cookie != MailpitSessions.cookie(current.baseUrl)) {
                    MailpitSessions.set(current.baseUrl, cookie)
                    client = MailpitSettings.client(instance.url)
                    result = runCatching { client.list(query = effectiveQuery()) }
                }
            }
            // Noch nichts gezeigt und Anmeldung verlangt: kurz nachfassen, statt gleich „Anmelden“ anzubieten.
            if (!loaded && result?.exceptionOrNull() is MailpitAuthException && authRetries < AUTH_RETRIES) {
                authRetries++
                if (!disposed) schedule(AUTH_RETRY_MS)
                return@addRequest
            }
            val shown = client
            ApplicationManager.getApplication().invokeLater({ if (result != null && shown === client) show(result) }, { disposed })
            if (!disposed) schedule(POLL_MS)
        }, if (force || now) 0 else POLL_MS)
    }

    private fun schedule(delay: Int) = alarm.addRequest({ refresh(force = false, now = true) }, delay)

    private fun show(result: Result<List<MailpitMessage>>) {
        feedback.done(result.isSuccess)
        loaded = true
        cards.show(center, CARD_LIST)
        result.onSuccess { messages ->
            lastError = null
            val sig = messages.map { Triple(it.id, it.read, it.tags) }
            if (sig != signature) {
                signature = sig
                val keep = selected().map { it.id }.toSet()
                model.replaceAll(messages)
                list.selectedIndices = messages.indices.filter { messages[it].id in keep }.toIntArray()
            }
            val filtered = effectiveQuery().isNotBlank()
            list.emptyText.text = MailViewBundle.message(if (filtered) "mailpit.search.none" else "mailpit.empty")
            val unread = messages.count { !it.read }
            status.text = MailViewBundle.message("mailpit.status", messages.size, unread, MailpitInstances.hostOf(client.baseUrl))
            // Während einer Suche zählt die Liste nur Treffer: Reiter und Titel behalten dann die letzte Gesamtzahl.
            if (!filtered) {
                content?.displayName = if (unread > 0) "${instance.name} ($unread)" else instance.name
                tabs.unreadChanged(instance, unread)
            }
        }.onFailure { error ->
            signature = null
            model.removeAll()
            list.emptyText.clear()
            list.emptyText.appendLine(MailViewBundle.message("mailpit.unreachable", client.baseUrl))
            if (error is MailpitAuthException)
                list.emptyText.appendLine(MailViewBundle.message("mailpit.login"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { login() }
            list.emptyText.appendLine(MailViewBundle.message("mailpit.edit"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { tabs.edit(instance) }
            status.text = error.message ?: error.javaClass.simpleName
            lastError = status.text
            relayChecked = 0L
            content?.displayName = instance.name
            tabs.unreadChanged(instance, 0)
        }
    }

    /** Mail als .eml (Temp-Datei, Bytes unverändert) öffnen und in Mailpit als gelesen markieren. */
    /** Für den Screenshot-Helfer: Zeile [index] auswählen und als festen Tab öffnen; false, solange die Liste leer ist. */
    fun selectAndOpen(index: Int): Boolean {
        if (index >= model.size) return false
        list.selectedIndex = index
        openSelected(preview = false)
        return true
    }

    private fun openSelected(preview: Boolean) {
        val message = selected().singleOrNull() ?: return selected().forEach { open(it, false) }
        open(message, preview)
    }

    private fun open(message: MailpitMessage, preview: Boolean) {
        opened[message.id]?.takeIf { it.isValid }?.let { return navigate(it, preview) }
        val current = client
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = runCatching {
                val data = current.raw(message.id)
                if (!message.read) runCatching { current.markRead(listOf(message.id)) }
                MailTempFiles.create(message.subject, data)
            }
            ApplicationManager.getApplication().invokeLater({
                file.onSuccess { f -> if (f != null) { opened[message.id] = f; navigate(f, preview) } }
                    .onFailure { Messages.showErrorDialog(project, it.message, MailViewBundle.message("mailpit.title")) }
                if (!message.read) refresh(force = true)
            }, { project.isDisposed })
        }
    }

    private fun navigate(file: VirtualFile, preview: Boolean) =
        OpenFileDescriptor(project, file).setUsePreviewTab(preview).navigate(!preview)

    private fun saveSelected() {
        val message = selected().singleOrNull() ?: return
        val current = client
        ApplicationManager.getApplication().executeOnPooledThread {
            val data = runCatching { current.raw(message.id) }
            ApplicationManager.getApplication().invokeLater({
                data.onSuccess { MailExport.saveEml(project, null, message.subject, it) }
                    .onFailure { Messages.showErrorDialog(project, it.message, MailViewBundle.message("mailpit.title")) }
            }, { project.isDisposed })
        }
    }

    private fun deleteSelected() {
        val ids = selected().map { it.id }.ifEmpty { return }
        background { client.delete(ids) }
    }

    private fun deleteAll() {
        if (model.isEmpty) return
        val ok = Messages.showYesNoDialog(project, MailViewBundle.message("mailpit.deleteAll.question", model.size, client.baseUrl),
            MailViewBundle.message("mailpit.deleteAll"), Messages.getWarningIcon()) == Messages.YES
        if (ok) background { client.deleteAll() }
    }

    /** Ausgewählte Mails über den in Mailpit eingerichteten SMTP-Server an echte Adressen schicken. */
    private fun release() {
        val messages = selected().ifEmpty { return }
        val relay = relay ?: return
        val dialog = MailpitReleaseDialog(project, relay, messages)
        if (!dialog.showAndGet()) return
        val to = dialog.recipients
        val current = client
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { messages.forEach { current.release(it.id, to) } }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess { toast.show(MailViewBundle.message("mailpit.release.done", messages.size, to.joinToString(", ")), loading = false, hold = true) }
                    .onFailure { Messages.showErrorDialog(project, it.message, MailViewBundle.message("mailpit.release.title")) }
            }, { project.isDisposed })
        }
    }

    /** Suchfeld und Schlagwort als eine Mailpit-Suche (`tag:name`, mit Leerzeichen in Anführungszeichen). */
    private fun effectiveQuery(): String {
        val tag = tagFilter?.let { if (it.any { c -> c.isWhitespace() || c == '"' }) "tag:\"${it.replace("\"", "")}\"" else "tag:$it" }
        return listOfNotNull(tag, query.trim().takeIf { it.isNotEmpty() }).joinToString(" ")
    }

    private fun filterByTag(tag: String?) {
        if (tag == tagFilter) return
        tagFilter = tag
        com.intellij.ide.ActivityTracker.getInstance().inc()
        refresh(force = true)
    }

    /**
     * Schlagwörter der Instanz als Auswahl mit genau einem Haken („Alle Mails“ oder ein Schlagwort); die Liste kommt beim
     * Klick frisch von Mailpit. Ein aktiver Filter zeigt einen Punkt am Trichter.
     */
    private inner class TagFilterAction : DumbAwareAction(MailViewBundle.message("mailpit.tags"), null, AllIcons.General.Filter) {
        override fun update(e: AnActionEvent) {
            val tag = tagFilter
            e.presentation.icon = if (tag == null) AllIcons.General.Filter
                else DotIcon(AllIcons.General.Filter)
            e.presentation.text = if (tag == null) MailViewBundle.message("mailpit.tags") else MailViewBundle.message("mailpit.tags.active", tag)
        }

        override fun actionPerformed(e: AnActionEvent) {
            val anchor = e.inputEvent?.component ?: list
            val current = client
            ApplicationManager.getApplication().executeOnPooledThread {
                val tags = runCatching { current.tags() }
                ApplicationManager.getApplication().invokeLater({
                    tags.onFailure { Messages.showErrorDialog(project, it.message, MailViewBundle.message("mailpit.title")) }
                    val names = tags.getOrNull() ?: return@invokeLater
                    val active = tagFilter
                    // Gewähltes Schlagwort bleibt wählbar, auch wenn Mailpit es inzwischen nicht mehr kennt.
                    val values = listOf<String?>(null) + (names + listOfNotNull(active)).distinct()
                    val step = object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<String?>(MailViewBundle.message("mailpit.tags"), values) {
                        override fun getTextFor(value: String?) = value ?: MailViewBundle.message("mailpit.tags.all")
                        override fun getIconFor(value: String?): javax.swing.Icon =
                            if (value == active) AllIcons.Actions.Checked else com.intellij.util.ui.EmptyIcon.ICON_16
                        override fun getSeparatorAbove(value: String?) =
                            if (value != null && value == values.getOrNull(1)) com.intellij.openapi.ui.popup.ListSeparator("") else null
                        override fun isSpeedSearchEnabled() = true
                        override fun onChosen(selectedValue: String?, finalChoice: Boolean): com.intellij.openapi.ui.popup.PopupStep<*>? {
                            filterByTag(selectedValue)
                            return FINAL_CHOICE
                        }
                    }
                    step.defaultOptionIndex = values.indexOf(active)
                    val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createListPopup(step)
                    if (names.isEmpty()) popup.setAdText(MailViewBundle.message("mailpit.tags.none"), javax.swing.SwingConstants.LEFT)
                    popup.showUnderneathOf(anchor)
                }, { project.isDisposed })
            }
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    /** Icon mit Punkt rechts oben (wie die Plattform aktive Filter zeigt; deren BadgeIcon ist interne API). */
    private class DotIcon(private val base: javax.swing.Icon) : javax.swing.Icon {
        override fun getIconWidth() = base.iconWidth
        override fun getIconHeight() = base.iconHeight
        override fun paintIcon(c: Component?, g: java.awt.Graphics, x: Int, y: Int) {
            base.paintIcon(c, g, x, y)
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val d = JBUI.scale(7)
                val ring = JBUI.scale(1)
                val left = x + iconWidth - d + ring
                val top = y - ring
                g2.color = c?.background ?: UIUtil.getPanelBackground()
                g2.fillOval(left - ring, top - ring, d + 2 * ring, d + 2 * ring)
                g2.color = JBUI.CurrentTheme.IconBadge.INFORMATION
                g2.fillOval(left, top, d, d)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun background(run: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(run)
            ApplicationManager.getApplication().invokeLater({
                result.onFailure { Messages.showErrorDialog(project, it.message, MailViewBundle.message("mailpit.title")) }
                refresh(force = true)
            }, { project.isDisposed })
        }
    }

    /** Anmeldung im eingebetteten Browser (Single Sign-on vor Mailpit); danach mit dem Sitzungs-Cookie weiter. */
    private fun login() {
        if (!MailpitLoginDialog(project, client.baseUrl).showAndGet()) return
        showLoading()
        reload = true
        refresh(force = true)
    }

    private fun showLoading() {
        loaded = false
        authRetries = 0
        cards.show(center, CARD_LOADING)
    }

    private val feedback = RefreshFeedback()

    /**
     * Rückmeldung nur für den Aktualisieren-Knopf, als [MailpitToast] unten über der Statuszeile. Dauert es länger
     * als [SLOW_MS], erscheint er schon während des Ladens (mit Animation), am Ende kurz „Aktualisiert“.
     */
    private inner class RefreshFeedback {
        private var pending = false
        private val timer = javax.swing.Timer(SLOW_MS) { if (pending) toast.show(MailViewBundle.message("mailpit.refreshing"), loading = true, hold = false) }
            .apply { isRepeats = false }

        fun start() {
            pending = true
            toast.hideNow()
            timer.restart()
        }

        fun done(success: Boolean) {
            if (!pending) return
            pending = false
            timer.stop()
            if (success) toast.show(MailViewBundle.message("mailpit.refreshed"), loading = false, hold = true) else toast.hideNow()
        }
    }

    @Volatile private var disposed = false

    override fun dispose() { disposed = true; feedback.done(false); toast.hideNow() }

    /** Zwei Zeilen: Betreff (fett, solange ungelesen) und Absender, rechts Uhrzeit und Büroklammer. */
    private inner class Renderer : ListCellRenderer<MailpitMessage> {
        private val subject = JBLabel()
        private val from = JBLabel()
        private val time = JBLabel()
        private val tags = TagChips()
        /** Oben Betreff (gekürzt) und rechts daneben Uhrzeit, darunter Absender → Empfänger und rechts die Schlagwörter. */
        private val panel = BorderLayoutPanel().apply {
            border = JBUI.Borders.empty(4, 8)
            addToTop(BorderLayoutPanel().addToCenter(subject).addToRight(time.apply { border = JBUI.Borders.emptyLeft(8) }).andTransparent())
            addToBottom(BorderLayoutPanel().addToCenter(from).addToRight(tags).andTransparent())
        }
        private val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT, DynamicBundle.getLocale())
        private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, DynamicBundle.getLocale())

        override fun getListCellRendererComponent(list: JList<out MailpitMessage>, value: MailpitMessage, index: Int, selected: Boolean, focus: Boolean): Component {
            val fg = UIUtil.getListForeground(selected, list.hasFocus())
            val grey = if (selected) fg else UIUtil.getContextHelpForeground()
            panel.background = UIUtil.getListBackground(selected, list.hasFocus())
            subject.text = value.subject ?: MailViewBundle.message("header.noSubject")
            subject.font = list.font.deriveFont(if (value.read) Font.PLAIN else Font.BOLD)
            subject.foreground = fg
            from.text = listOfNotNull(value.from, value.to?.let { "→ $it" }).joinToString("  ")
            from.foreground = grey
            from.font = JBUI.Fonts.smallFont()
            time.text = value.created?.let(::format) ?: ""
            time.foreground = grey
            time.font = JBUI.Fonts.smallFont()
            time.icon = if (value.attachments > 0) MailIcons.ATTACHMENT else null
            time.horizontalTextPosition = JBLabel.LEFT
            tags.update(value.tags, selected && list.hasFocus())
            panel.toolTipText = value.snippet
            return panel
        }

        private fun format(date: Date): String {
            val today = Calendar.getInstance()
            val day = Calendar.getInstance().apply { time = date }
            val sameDay = today.get(Calendar.YEAR) == day.get(Calendar.YEAR) && today.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR)
            return (if (sameDay) timeFormat else dateFormat).format(date)
        }
    }

    companion object {
        val KEY = com.intellij.openapi.util.Key.create<MailpitPanel>("mailview.mailpit.panel")
        private const val POLL_MS = 3000
        private const val SLOW_MS = 1200
        private const val AUTH_RETRIES = 3
        private const val AUTH_RETRY_MS = 700
        private const val CARD_LOADING = "loading"
        private const val CARD_LIST = "list"
        /** Nicht sichtbare Reiter: nur ab und zu, damit ihre Zahl ungelesener Mails stimmt. */
        private const val BACKGROUND_POLL_MS = 60_000L
        /** Weiterleiten-Einstellung von Mailpit: ändert sich nur mit einem Neustart von Mailpit. */
        private const val RELAY_CHECK_MS = 60_000L
    }
}

/** Schlagwörter einer Mail als kleine, abgerundete Marken (wie in der Weboberfläche von Mailpit, nur ohne eigene Farben). */
private class TagChips : javax.swing.JComponent() {
    private var tags: List<String> = emptyList()
    private var onSelection = false

    init { font = JBUI.Fonts.miniFont() }

    fun update(tags: List<String>, onSelection: Boolean) {
        this.tags = tags.take(MAX)
        this.onSelection = onSelection
        isVisible = tags.isNotEmpty()
    }

    private val gap get() = JBUI.scale(4)
    private val padX get() = JBUI.scale(5)

    override fun getPreferredSize(): java.awt.Dimension {
        val fm = getFontMetrics(font)
        val width = tags.sumOf { fm.stringWidth(it) + 2 * padX } + gap * tags.size
        return java.awt.Dimension(width, fm.height + JBUI.scale(2))
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = font
        val fm = g2.fontMetrics
        val h = fm.height
        val y = (height - h) / 2
        var x = gap
        for (tag in tags) {
            val w = fm.stringWidth(tag) + 2 * padX
            g2.color = if (onSelection) java.awt.Color(255, 255, 255, 60) else BACKGROUND
            g2.fillRoundRect(x, y, w, h, h, h)
            g2.color = if (onSelection) UIUtil.getListForeground(true, true) else UIUtil.getContextHelpForeground()
            g2.drawString(tag, x + padX, y + fm.ascent)
            x += w + gap
        }
        g2.dispose()
    }

    private companion object {
        const val MAX = 4
        val BACKGROUND = com.intellij.ui.JBColor(java.awt.Color(0xE9, 0xEB, 0xF0), java.awt.Color(0x3C, 0x3F, 0x45))
    }
}
