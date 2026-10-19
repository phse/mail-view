package app.heckit.ide.mailview.screenshot

import app.heckit.ide.mailview.MailPreviewEditor
import app.heckit.ide.mailview.MailViewSettings
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
