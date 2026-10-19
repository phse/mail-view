import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

// Rechnerspezifische Einstellungen (nicht eingecheckt), z. B. platformLocalPath=/Applications/PhpStorm.app
val localProperties = Properties().apply {
    file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val platformLocalPath: String? = localProperties.getProperty("platformLocalPath")?.takeIf { file(it).exists() }

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // MIME-Parser (RFC 5322/2045 ff.), wird ins Plugin gepackt.
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        if (platformLocalPath != null) {
            local(platformLocalPath)
        } else {
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("platformVersion").get())
        }
        // Eingebetteter Browser (seit 2026.x ein eigenes, mitgeliefertes Plugin).
        bundledPlugin("com.intellij.modules.jcef")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>0.1.0</h3>
            <ul>
              <li>First release: .eml files open in a rendered mail view (subject, sender, recipients, CC, BCC,
                  date, body, attachments), a content view (HTML or plain text part) and the raw text.</li>
            </ul>
        """.trimIndent()
    }

    // Optional: Plugin signieren (Zertifikat siehe README). Ohne Umgebungsvariablen wird nicht signiert.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Für ./gradlew publishPlugin (erst nach dem ersten manuellen Upload möglich).
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.gradleProperty("jetbrainsMarketplaceToken"))
    }

    // ./gradlew verifyPlugin prüft gegen die lokale IDE und die älteste unterstützte Version.
    pluginVerification {
        ides {
            platformLocalPath?.let { local(it) }
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyOldestVersion").get())
        }
    }
}

// Apache 2.0 verlangt, dass LICENSE und NOTICE mit dem Plugin verteilt werden.
tasks.jar {
    from(files("LICENSE", "NOTICE")) { into("META-INF") }
}

// ./gradlew test -Psamples=<ordner mit .eml> parst echte Mails und gibt die Teile aus.
tasks.test {
    providers.gradleProperty("samples").orNull?.let { systemProperty("mailview.samples", it) }
    testLogging { showStandardStreams = true }
}

tasks.runIde {
    // ./gradlew runIde -PopenProject=/pfad/zum/projekt öffnet direkt ein Projekt in der Sandbox.
    providers.gradleProperty("openProject").orNull?.let { args(it) }
}
