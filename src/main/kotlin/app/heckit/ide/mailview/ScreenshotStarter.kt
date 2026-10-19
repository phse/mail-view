package app.heckit.ide.mailview

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Startet die Marketplace-Screenshots; nur in Builds mit -Pscreenshots enthalten (siehe build.gradle.kts). */
class ScreenshotStarter : ProjectActivity {
    override suspend fun execute(project: Project) {
        val dir = System.getProperty("mailview.screenshots") ?: return
        runCatching { Class.forName("app.heckit.ide.mailview.screenshot.ScreenshotMaker") }.getOrNull()
            ?.getMethod("start", Project::class.java, String::class.java)?.invoke(null, project, dir)
    }
}
