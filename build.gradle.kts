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

// ./gradlew runIde -Pscreenshots=<ordner> baut den Screenshot-Helfer mit ein (nie für Releases verwenden).
val screenshotDir = providers.gradleProperty("screenshots").orNull

sourceSets {
    main {
        if (screenshotDir != null) kotlin.srcDir("src/screenshot/kotlin")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// slf4j bringt die IDE selbst mit; eine zweite Kopie im Plugin führt zu Classloader-Konflikten.
configurations.runtimeClasspath {
    exclude(group = "org.slf4j")
}

dependencies {
    // MIME-Parser (RFC 5322/2045 ff.), wird ins Plugin gepackt.
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    // RTF → HTML für Outlook-.msg ohne HTML-Teil (.msg selbst liest der eigene CfbReader).
    implementation("com.github.bbottema:rtf-to-html:1.1.1")
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
            <h3>0.5.0</h3>
            <ul>
              <li><b>winmail.dat</b> (Outlook TNEF): instead of the single winmail.dat attachment, the files inside are listed
                  as normal attachments; the formatted text packed into it is shown when the mail has no HTML part.
                  A winmail.dat file can also be opened on its own.</li>
              <li><b>.mbox</b> archives (Thunderbird, Google Takeout, mailing list archives): a <i>Mails</i> tab lists
                  attachments, sender, subject, date and size with a search field. Click a mail to read it below the list
                  (↑/↓ to browse, with a smooth transition), double-click or Enter opens it in its own tab. Column
                  widths fit the content; widths you set by hand are kept per file, column order and sorting for all files. In the <i>Text</i> tab a line separates the mails. Large archives are read in the
                  background, the file is never changed.</li>
              <li>Attachments unpacked from a winmail.dat are listed under <i>From winmail.dat</i>.</li>
            </ul>
            <h3>0.4.0</h3>
            <ul>
              <li>More formats: Apple Mail <b>.emlx</b>, saved web pages <b>.mht/.mhtml</b> (with their images) and
                  Outlook templates <b>.oft</b>.</li>
              <li>Mails attached to a .msg are listed as attachments and open in Mail View again.</li>
              <li>Headers tab: SPF, DKIM and DMARC results as badges and the delivery path (Received chain) as a table
                  with time and delay per hop above the header lines; header names are highlighted.</li>
              <li>Dark mode: no white area below short mails after switching.</li>
            </ul>
            <h3>0.3.1</h3>
            <ul>
              <li>No deprecated API anymore.</li>
            </ul>
            <h3>0.3.0</h3>
            <ul>
              <li>Right-click an attachment for more: open, show in file manager, open with the system application, save.
                  ZIP attachments can be extracted (protected against path traversal); executables and installers only run
                  after a warning.</li>
              <li>Attached mails are named after their subject instead of <i>message.eml</i>.</li>
              <li>.msg from Exchange sent items: the sender address is found even without transport headers.</li>
              <li>New plugin icon.</li>
            </ul>
            <h3>0.2.0</h3>
            <ul>
              <li>Outlook <b>.msg</b> files open like .eml: header, rendered message (HTML, plain text or RTF),
                  embedded images and attachments. Own lightweight reader, the plugin stays below 1 MB.</li>
              <li>New <b>Headers</b> tab with all header lines of the mail (Received chain, authentication results, ...).</li>
              <li>Clearer description: read-only, so your mails stay untouched.</li>
            </ul>
            <h3>0.1.1</h3>
            <ul>
              <li>No deprecated API anymore; softer dark display of mails.</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
              <li>First release: .eml files open in a rendered mail view (subject, sender, recipients, CC, BCC,
                  date, body, attachments), a content view (HTML or plain text part) and the raw text.</li>
              <li>Read-only by default; remote content is blocked until you load it.</li>
              <li>Optional dark display of the mail (follows the IDE theme by default).</li>
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

// Für Screenshots wird das Demo-Projekt an einen neutralen Ort kopiert, damit kein lokaler
// Benutzerpfad im Bild erscheint.
val screenshotProjectDir = "/Users/Shared/mail-demo"

val prepareScreenshotProject = tasks.register<Sync>("prepareScreenshotProject") {
    from("marketplace/demo-project")
    into(screenshotProjectDir)
}

tasks.runIde {
    if (screenshotDir != null) {
        dependsOn(prepareScreenshotProject)
        args(screenshotProjectDir)
        jvmArgs("-Dmailview.screenshots=${file(screenshotDir).absolutePath}")
    } else {
        // ./gradlew runIde -PopenProject=/pfad/zum/projekt öffnet direkt ein Projekt in der Sandbox.
        providers.gradleProperty("openProject").orNull?.let { args(it) }
    }
}

// Ohne -Pscreenshots bleibt aus dem Build-Cache sonst ein leeres screenshot/-Verzeichnis in den Jars.
tasks.withType<Zip>().configureEach {
    includeEmptyDirs = false
}
