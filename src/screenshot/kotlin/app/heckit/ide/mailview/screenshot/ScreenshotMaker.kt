package app.heckit.ide.mailview.screenshot

import com.intellij.ide.util.PropertiesComponent
import app.heckit.ide.mailview.IdeInbox
import app.heckit.ide.mailview.MailLog
import app.heckit.ide.mailview.MailPreviewEditor
import app.heckit.ide.mailview.MailViewSettings
import app.heckit.ide.mailview.MailWidth
import app.heckit.ide.mailview.MboxEditor
import app.heckit.ide.mailview.MailpitClient
import app.heckit.ide.mailview.MailpitInstance
import app.heckit.ide.mailview.MailpitInstances
import app.heckit.ide.mailview.MailpitPanel
import app.heckit.ide.mailview.MailpitTabs
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame

/**
 * Erzeugt die Marketplace-Screenshots in der Sandbox-IDE (./gradlew runIde -Pscreenshots=...).
 * Zeichnet den Frame selbst in ein Bild, braucht also keine Bildschirmaufnahme-Rechte.
 * Ist nur in Builds mit -Pscreenshots enthalten.
 */
object ScreenshotMaker {
    private const val SCALE = 2.0
    private const val MAIL = "summer-meetup.eml"
    private const val MBOX = "riverside-makers.mbox"
    /** Reiter „Text“: kodierter Betreff und base64-Anhänge, eingeklappt. */
    private const val TEXT_MAIL = "workshop-files.eml"
    private val log = logger<ScreenshotMaker>()

    @JvmStatic
    fun start(project: Project, dir: String) {
        val out = File(dir).apply { mkdirs() }
        Thread({
            try {
                run(project, out)
            } catch (e: Throwable) {
                log.error("Screenshots failed", e)
            }
        }, "Mail View screenshots").start()
    }

    private fun run(project: Project, out: File) {
        val frame = edt { WindowManager.getInstance().getFrame(project) } ?: error("no frame")
        val previousDark = MailViewSettings.dark
        val laf = LafManager.getInstance()
        val previousTheme = laf.currentUIThemeLookAndFeel
        val file = edt {
            theme(laf, dark = false)
            MailViewSettings.dark = false
            // Vorgaben, egal was in der Sandbox zuvor eingestellt wurde.
            MailViewSettings.appearance = MailViewSettings.Appearance.LAST
            MailViewSettings.preferText = false
            MailViewSettings.expandAttachments = false
            MailViewSettings.remoteContent = MailViewSettings.RemoteContent.BLOCK
            MailViewSettings.rememberTab = false
            MailViewSettings.startTab = MailViewSettings.StartTab.MAIL
            PropertiesComponent.getInstance().setValue("mailview.mbox.showTo", false, false)
            frame.extendedState = JFrame.NORMAL
            frame.setBounds(60, 40, 1280, 860)
            project.guessProjectDir()?.findChild(MAIL)?.also { FileEditorManager.getInstance(project).closeFile(it) }
                ?: error("$MAIL missing")
        }
        Thread.sleep(1500)
        open(project, file)
        Thread.sleep(6000)
        shot(frame, File(out, "01-mail.png"))

        edt { mailEditor(project, file).expandAttachments() }
        Thread.sleep(1500)
        shot(frame, File(out, "02-attachments.png"))

        edt { FileEditorManager.getInstance(project).setSelectedEditor(file, "mail-view-content") }
        Thread.sleep(2500)
        shot(frame, File(out, "03-content.png"))

        edt { FileEditorManager.getInstance(project).setSelectedEditor(file, "mail-view-headers") }
        Thread.sleep(2500)
        shot(frame, File(out, "05-headers.png"))

        // Breitenvorschau: dieselbe Mail in Handybreite (Knopf rechts oben zeigt „Handy“).
        edt {
            FileEditorManager.getInstance(project).closeFile(file)
            MailViewSettings.width = MailWidth.PHONE
        }
        Thread.sleep(1500)
        open(project, file)
        Thread.sleep(6000)
        shot(frame, File(out, "09-phone.png"))
        edt {
            MailViewSettings.width = MailWidth.DESKTOP
            FileEditorManager.getInstance(project).closeFile(file)
        }
        Thread.sleep(1000)
        open(project, file)
        Thread.sleep(3000)

        // Reiter „Text“: Quelltext mit eingefärbten Header-Namen und eingeklappten Teilen.
        val textMail = edt { project.guessProjectDir()?.findChild(TEXT_MAIL) ?: error("$TEXT_MAIL missing") }
        edt { FileEditorManager.getInstance(project).openFile(textMail, true) }
        Thread.sleep(2500)
        // Erst nach dem Öffnen umschalten: MailTabSelector setzt beim Öffnen den Start-Reiter.
        edt { FileEditorManager.getInstance(project).setSelectedEditor(textMail, "text-editor") }
        Thread.sleep(4000)
        shot(frame, File(out, "07-text.png"))
        edt { FileEditorManager.getInstance(project).closeFile(textMail) }

        // mbox: Liste mit Vorschau der zweiten Mail.
        val mbox = edt { project.guessProjectDir()?.findChild(MBOX) ?: error("$MBOX missing") }
        edt { FileEditorManager.getInstance(project).openFile(mbox, true) }
        Thread.sleep(2500)
        edt {
            FileEditorManager.getInstance(project).setSelectedEditor(mbox, "mail-view-mbox")
            FileEditorManager.getInstance(project).getEditors(mbox).filterIsInstance<MboxEditor>().first().selectRow(1)
        }
        Thread.sleep(5000)
        shot(frame, File(out, "06-mbox.png"))
        edt { FileEditorManager.getInstance(project).closeFile(mbox) }

        mailpit(project, frame, out)
        logList(project, frame, out)
        inbox(project, frame, out)
        console(project, frame, out)

        // Dunkles Theme und dunkle Mail; danach alles wiederherstellen.
        edt {
            theme(laf, dark = true)
            MailViewSettings.dark = true
            FileEditorManager.getInstance(project).closeFile(file)
        }
        Thread.sleep(2000)
        open(project, file)
        Thread.sleep(6000)
        shot(frame, File(out, "04-dark.png"))

        edt {
            previousTheme?.let { laf.setCurrentLookAndFeel(it, false); laf.updateUI() }
            MailViewSettings.dark = previousDark
        }
        log.warn("Mail View: screenshots written to $out")
    }

    /**
     * Tool Window „Mailpit“ mit den Demo-Mails aus `marketplace/mailpit-demo/start.sh` (Port 18030), eine Mail geöffnet.
     * Eigene Instanzen der Sandbox (global, evtl. mit echten Adressen) werden dafür ausgeblendet und danach zurückgelegt.
     */
    private fun mailpit(project: Project, frame: JFrame, out: File) {
        val url = System.getProperty("mailview.screenshots.mailpit", "http://localhost:18030")
        if (runCatching { MailpitClient(url).list(1) }.isFailure) {
            log.warn("Mail View: Mailpit demo not reachable at $url - run marketplace/mailpit-demo/start.sh; skipping 08-mailpit.png")
            return
        }
        val key = "app.heckit.ide.mailview.mailpitInstances"
        val app = PropertiesComponent.getInstance()
        val proj = PropertiesComponent.getInstance(project)
        val savedApp = app.getValue(key)
        val savedProj = proj.getValue(key)
        val toolWindow = edt {
            app.unsetValue(key)
            proj.unsetValue(key)
            MailpitInstances.save(project, MailpitInstance("screenshot", "Riverside Makers", url))
            val tw = ToolWindowManager.getInstance(project).getToolWindow("Mailpit") ?: error("no Mailpit tool window")
            tw.setAnchor(ToolWindowAnchor.RIGHT, null)
            tw.show()
            tw
        }
        Thread.sleep(1000)
        val panel = edt {
            // Inhalt neu aufbauen, falls das Tool Window schon mit anderen Instanzen offen war.
            MailpitTabs(project, toolWindow).rebuild("screenshot")
            toolWindow.contentManager.selectedContent?.getUserData(MailpitPanel.KEY) ?: error("no Mailpit panel")
        }
        var opened = false
        repeat(20) { if (!opened) { Thread.sleep(500); opened = edt { panel.selectAndOpen(2) } } }
        Thread.sleep(5000)
        shot(frame, File(out, "08-mailpit.png"))
        edt {
            toolWindow.hide()
            FileEditorManager.getInstance(project).allEditors.map { it.file }.filter { it.path.contains("mail-view") && it.path != project.guessProjectDir()?.path }
                .forEach { FileEditorManager.getInstance(project).closeFile(it) }
            if (savedApp != null) app.setValue(key, savedApp) else app.unsetValue(key)
            if (savedProj != null) proj.setValue(key, savedProj) else proj.unsetValue(key)
        }
    }

    /** Liste der Mails in einem Laravel-Log (`laravel.log` im Demo-Projekt), neueste Mail ausgewählt. */
    private fun logList(project: Project, frame: JFrame, out: File) {
        val source = edt { project.guessProjectDir()?.findChild("laravel.log") ?: error("laravel.log missing") }
        edt { MailLog.open(project, source) }
        Thread.sleep(2500)
        edt { (FileEditorManager.getInstance(project).selectedEditor as? MboxEditor)?.selectRow(0) }
        Thread.sleep(5000)
        shot(frame, File(out, "10-log.png"))
        edt { FileEditorManager.getInstance(project).openFiles.filter { it.getUserData(MailLog.SOURCE) != null }
            .forEach { FileEditorManager.getInstance(project).closeFile(it) } }
    }

    /** Mailserver in der IDE (eigener Port, damit er keinem laufenden in die Quere kommt) mit drei Mails per SMTP. */
    private fun inbox(project: Project, frame: JFrame, out: File) {
        val saved = Triple(MailViewSettings.inboxEnabled, MailViewSettings.inboxPort, MailViewSettings.inboxNetwork)
        // Der Standardport wie in der Doku; nur falls belegt (z. B. Mailserver der echten IDE) ein anderer.
        val port = if (runCatching { java.net.ServerSocket(IdeInbox.DEFAULT_PORT, 1, java.net.InetAddress.getLoopbackAddress()).close() }.isSuccess)
            IdeInbox.DEFAULT_PORT else 25250
        // Eigene Mailpit-Instanzen der Sandbox ausblenden (wie bei 08-mailpit), danach zurücklegen.
        val key = "app.heckit.ide.mailview.mailpitInstances"
        val app = PropertiesComponent.getInstance()
        val proj = PropertiesComponent.getInstance(project)
        val savedApp = app.getValue(key)
        val savedProj = proj.getValue(key)
        edt {
            app.unsetValue(key)
            proj.unsetValue(key)
            MailViewSettings.inboxEnabled = true
            MailViewSettings.inboxPort = port
            MailViewSettings.inboxNetwork = false
            IdeInbox.getInstance().apply()
        }
        IdeInbox.getInstance().deleteAll()
        // Feste Uhrzeiten (Date-Header): Die Liste zeigt sonst, wann die Screenshots entstanden.
        smtp(port, listOf(
            "alex@example.org" to demoMail("Welcome to Riverside Makers", "Alex Morgan <alex@example.org>", "account",
                "Fri, 20 Nov 2026 09:13:02 +0100", "<h1>Welcome, Alex!</h1><p>Your account is ready. Confirm your address to start ordering.</p>"),
            "alex@example.org" to demoMail("Order #1042 received", "Alex Morgan <alex@example.org>", "order",
                "Fri, 20 Nov 2026 09:21:37 +0100", "<h2>Thanks for your order</h2><p>2 x PLA white, 1 x PETG black.</p>"),
            "sam@example.net" to demoMail("Reset your password", "Sam Lee <sam@example.net>", "account",
                "Fri, 20 Nov 2026 09:40:15 +0100",
                "<p>Hi Sam,</p><p>Someone asked to reset your password.</p><p><a href=\"https://example.com/reset\">Reset password</a></p>"),
        ))
        val toolWindow = edt {
            val tw = ToolWindowManager.getInstance(project).getToolWindow("Mailpit") ?: error("no Mailpit tool window")
            tw.setAnchor(ToolWindowAnchor.RIGHT, null)
            tw.show()
            tw
        }
        Thread.sleep(1000)
        val panel = edt {
            MailpitTabs(project, toolWindow).rebuild(IdeInbox.INSTANCE_ID)
            toolWindow.contentManager.selectedContent?.getUserData(MailpitPanel.KEY) ?: error("no inbox panel")
        }
        var opened = false
        repeat(20) { if (!opened) { Thread.sleep(500); opened = edt { panel.selectAndOpen(0) } } }
        Thread.sleep(5000)
        shot(frame, File(out, "11-inbox.png"))
        edt {
            toolWindow.hide()
            FileEditorManager.getInstance(project).allEditors.map { it.file }.filter { it.path.contains("mail-view") && it.path != project.guessProjectDir()?.path }
                .forEach { FileEditorManager.getInstance(project).closeFile(it) }
            MailViewSettings.inboxEnabled = saved.first
            MailViewSettings.inboxPort = saved.second
            MailViewSettings.inboxNetwork = saved.third
            IdeInbox.getInstance().apply()
            if (savedApp != null) app.setValue(key, savedApp) else app.unsetValue(key)
            if (savedProj != null) proj.setValue(key, savedProj) else proj.unsetValue(key)
        }
    }

    private fun demoMail(subject: String, to: String, tags: String, date: String, html: String) = """
        From: Riverside Makers <shop@example.com>
        To: $to
        Subject: $subject
        Date: $date
        Message-ID: <${System.nanoTime()}@example.com>
        MIME-Version: 1.0
        X-Tags: $tags
        Content-Type: text/html; charset=utf-8

        <div style="font-family:sans-serif">HTML<p>Riverside Makers</p></div>
    """.trimIndent().replace("HTML", html)

    /** Kleiner SMTP-Client wie eine Anwendung: EHLO, MAIL, RCPT, DATA. */
    private fun smtp(port: Int, mails: List<Pair<String, String>>) {
        java.net.Socket("127.0.0.1", port).use { socket ->
            val input = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()
            fun reply() { var line = input.readLine(); while (line != null && line.length > 3 && line[3] == '-') line = input.readLine() }
            fun send(text: String) { output.write("$text\r\n".toByteArray()); output.flush(); reply() }
            reply()
            send("EHLO demo.example.com")
            for ((recipient, mail) in mails) {
                send("MAIL FROM:<shop@example.com>")
                send("RCPT TO:<$recipient>")
                send("DATA")
                send(mail.replace("\n", "\r\n") + "\r\n.")
                Thread.sleep(1100)
            }
            send("QUIT")
        }
    }

    /** Run-Konsole mit der Ausgabe von `manage.py runserver` samt Mail (Django console-Backend); der Betreff ist ein Link. */
    private fun console(project: Project, frame: JFrame, out: File) {
        val dashes = "-".repeat(79)
        val output = """
            Watching for file changes with StatReloader
            Starting development server at http://127.0.0.1:8000/
            [20/Nov/2026 09:40:12] "GET /accounts/password/reset/ HTTP/1.1" 200 2817
            Content-Type: text/plain; charset="utf-8"
            MIME-Version: 1.0
            Content-Transfer-Encoding: 7bit
            Subject: Password reset on example.com
            From: webmaster@example.com
            To: sam@example.net
            Date: Fri, 20 Nov 2026 09:40:15 -0000
            Message-ID: <176245201528.4711.1290@example.com>

            You're receiving this email because you requested a password reset.

            Please go to the following page and choose a new password:
            http://127.0.0.1:8000/accounts/reset/Mw/c41d-7f3a/

            Thanks for using our site!
            DASHES
            [20/Nov/2026 09:40:15] "POST /accounts/password/reset/ HTTP/1.1" 302 0
        """.trimIndent().replace("DASHES", dashes) + "\n"
        val console = edt {
            val view = com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            val descriptor = com.intellij.execution.ui.RunContentDescriptor(view, null, view.component, "manage.py runserver")
            com.intellij.execution.ui.RunContentManager.getInstance(project)
                .showRunContent(com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(), descriptor)
            view.print(output, com.intellij.execution.ui.ConsoleViewContentType.NORMAL_OUTPUT)
            view
        }
        Thread.sleep(3000)
        // Ab der Zugriffszeile vor der Mail zeigen, damit der Betreff (Link) zu sehen ist.
        edt { console.scrollTo(output.indexOf("[20/Nov/2026 09:40:12]")) }
        Thread.sleep(1500)
        shot(frame, File(out, "12-console.png"))
        // Link anklicken (wie mit der Maus): Die Mail öffnet sich in Mail View.
        edt {
            val editor = (console as com.intellij.execution.impl.ConsoleViewImpl).editor ?: error("no console editor")
            val line = output.lines().indexOfFirst { it.startsWith("Subject:") }
            val info = com.intellij.execution.impl.EditorHyperlinkSupport.get(editor).getHyperlinkInfoByLineAndCol(line, 12)
                ?: error("no mail link in the console")
            info.navigate(project)
        }
        Thread.sleep(3000)
        // Nur Prüfung, kein Bild: Die Mail aus der Konsole ist offen.
        val opened = edt { FileEditorManager.getInstance(project).selectedFiles.any { it.name.startsWith("Password reset") } }
        if (!opened) log.error("Mail View: console link did not open the mail")
        edt { FileEditorManager.getInstance(project).selectedFiles.filter { it.name.startsWith("Password reset") }
            .forEach { FileEditorManager.getInstance(project).closeFile(it) } }
    }

    private fun theme(laf: LafManager, dark: Boolean) {
        val target = laf.installedThemes.firstOrNull { it.isDark == dark } ?: return
        if (laf.currentUIThemeLookAndFeel?.isDark == dark) return
        laf.setCurrentLookAndFeel(target, false)
        laf.updateUI()
    }

    private fun open(project: Project, file: VirtualFile) = edt {
        FileEditorManager.getInstance(project).openFile(file, true)
        FileEditorManager.getInstance(project).setSelectedEditor(file, "mail-view-preview")
    }

    private fun mailEditor(project: Project, file: VirtualFile) =
        FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<MailPreviewEditor>().first()

    private fun shot(frame: JFrame, file: File) = edt {
        val root = frame.rootPane
        val image = BufferedImage((root.width * SCALE).toInt(), (root.height * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(SCALE, SCALE)
        root.paint(g)
        g.dispose()
        ImageIO.write(image, "png", file)
    }

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
