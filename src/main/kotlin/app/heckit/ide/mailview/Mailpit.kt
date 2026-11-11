package app.heckit.ide.mailview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Date

/** Eine Mail in der Liste von Mailpit (`GET /api/v1/messages`). */
data class MailpitMessage(
    val id: String,
    val subject: String?,
    val from: String?,
    val to: String?,
    val created: Date?,
    val attachments: Int,
    val read: Boolean,
    val snippet: String?,
)

/** Mailpit (oder ein Proxy davor) verlangt eine Anmeldung bzw. hat sie abgelehnt. */
class MailpitAuthException(val status: Int, val withCredentials: Boolean) : java.io.IOException("HTTP $status") {
    override val message: String
        get() = runCatching {
            MailViewBundle.message(if (withCredentials) "mailpit.auth.wrong" else "mailpit.auth.missing", status)
        }.getOrDefault("HTTP $status")
}

/** Mailpit-Instanz unter [baseUrl] (z. B. `http://localhost:8025`), über deren REST-API. */
class MailpitClient(baseUrl: String, user: String? = null, password: String? = null, private val cookie: String? = null) {
    val baseUrl = baseUrl.trim().trimEnd('/')
    private val api = "${this.baseUrl}/api/v1"
    /** Basic Auth (Mailpit `--ui-auth` oder ein Proxy davor), nur wenn ein Benutzer gesetzt ist. */
    private val authorization = user?.takeIf { it.isNotEmpty() }?.let {
        "Basic " + java.util.Base64.getEncoder().encodeToString("$it:${password.orEmpty()}".toByteArray(Charsets.UTF_8))
    }

    /** Neueste Mails; mit [query] die Volltextsuche von Mailpit (Betreff, Adressen, Text, auch `from:`, `subject:` …). */
    fun list(limit: Int = LIMIT, query: String? = null): List<MailpitMessage> {
        val url = if (query.isNullOrBlank()) "$api/messages?limit=$limit"
            else "$api/search?limit=$limit&query=" + java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8)
        val json = JsonParser.parseString(send(get(url)).toString(Charsets.UTF_8)).asJsonObject
        return json.getAsJsonArray("messages")?.map { parse(it.asJsonObject) }.orEmpty()
    }

    /** Unveränderter Quelltext der Mail (.eml). */
    fun raw(id: String): ByteArray = send(get("$api/message/${encode(id)}/raw"))

    fun markRead(ids: List<String>, read: Boolean = true) =
        send(request("$api/messages", "PUT", """{"IDs":${ids.toJson()},"Read":$read}"""))

    fun delete(ids: List<String>) = send(request("$api/messages", "DELETE", """{"IDs":${ids.toJson()}}"""))

    fun deleteAll() = send(request("$api/messages", "DELETE", "{}"))

    /** Weboberfläche zu einer Mail. */
    fun webUrl(id: String) = "$baseUrl/view/${encode(id)}"

    private fun builder(url: String) = HttpRequest.newBuilder(URI(url)).timeout(TIMEOUT)
        .apply { authorization?.let { header("Authorization", it) } }
        .apply { cookie?.let { header("Cookie", it) } }

    private fun get(url: String) = builder(url).GET().build()

    private fun request(url: String, method: String, body: String) = builder(url)
        .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build()

    private fun send(request: HttpRequest): ByteArray {
        // Nur zum Testen der Ladeanzeige: ./gradlew runIde -PmailpitSlow=2500 verzögert jede Anfrage.
        Integer.getInteger("mailview.mailpit.slowMs")?.let { Thread.sleep(it.toLong()) }
        val response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() == 401 || response.statusCode() == 403)
            throw MailpitAuthException(response.statusCode(), authorization != null || cookie != null)
        if (response.statusCode() !in 200..299) throw java.io.IOException("HTTP ${response.statusCode()} ${request.uri()}")
        return response.body()
    }

    private fun parse(o: JsonObject) = MailpitMessage(
        id = o.str("ID") ?: "",
        subject = o.str("Subject")?.takeIf { it.isNotBlank() },
        from = o.get("From")?.takeIf { it.isJsonObject }?.asJsonObject?.let(::address),
        to = o.get("To")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(::address) }
            ?.joinToString(", ")?.takeIf { it.isNotEmpty() },
        created = o.str("Created")?.let { runCatching { Date.from(Instant.parse(it)) }.getOrNull() },
        attachments = o.get("Attachments")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
        read = o.get("Read")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        snippet = o.str("Snippet"),
    )

    private fun address(o: JsonObject): String? {
        val name = o.str("Name")?.takeIf { it.isNotBlank() }
        val address = o.str("Address")?.takeIf { it.isNotBlank() }
        return name ?: address
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun List<String>.toJson() = joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

    private fun encode(id: String) = java.net.URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")

    companion object {
        const val LIMIT = 200
        private val TIMEOUT: Duration = Duration.ofSeconds(5)
        private val HTTP: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
    }
}

/**
 * Eine Mailpit-Instanz im Tool Window (ein Reiter). [global]: für alle Projekte, sonst nur dieses. [auto]: nicht
 * gespeichert, sondern erkannt (DDEV-Projekt bzw. localhost, solange nichts eingerichtet ist).
 */
data class MailpitInstance(val id: String, val name: String, val url: String, val global: Boolean = false, val auto: Boolean = false)

/** Gespeicherte Instanzen (global in den IDE-Einstellungen, sonst im Projekt) plus die automatisch erkannte. */
object MailpitInstances {
    private const val KEY = "app.heckit.ide.mailview.mailpitInstances"
    private const val OLD_URL_KEY = "app.heckit.ide.mailview.mailpitUrl"
    private val gson = com.google.gson.Gson()

    private class Stored(val id: String = "", val name: String = "", val url: String = "")

    private fun read(props: PropertiesComponent, global: Boolean): List<MailpitInstance> =
        runCatching { gson.fromJson(props.getValue(KEY) ?: "[]", Array<Stored>::class.java).toList() }.getOrDefault(emptyList())
            .filter { it.url.isNotBlank() }
            .map { MailpitInstance(it.id.ifEmpty { java.util.UUID.randomUUID().toString() }, it.name.ifBlank { hostOf(it.url) }, it.url, global) }

    private fun write(props: PropertiesComponent, list: List<MailpitInstance>) =
        props.setValue(KEY, gson.toJson(list.map { Stored(it.id, it.name, it.url) }), "[]")

    fun all(project: Project): List<MailpitInstance> {
        val projectProps = PropertiesComponent.getInstance(project)
        // Adresse aus der ersten Fassung (eine je Projekt) übernehmen.
        projectProps.getValue(OLD_URL_KEY)?.takeIf { it.isNotBlank() }?.let { old ->
            write(projectProps, read(projectProps, false) + MailpitInstance(java.util.UUID.randomUUID().toString(), hostOf(old), old))
            projectProps.unsetValue(OLD_URL_KEY)
        }
        val stored = read(PropertiesComponent.getInstance(), true) + read(projectProps, false)
        val ddev = MailpitSettings.detectDdev(project)
        val auto = when {
            ddev != null && stored.none { it.url == ddev } -> MailpitInstance("auto", "DDEV", ddev, auto = true)
            stored.isEmpty() -> MailpitInstance("auto", "localhost", MailpitSettings.DEFAULT_URL, auto = true)
            else -> null
        }
        return listOfNotNull(auto) + stored
    }

    /** Neu anlegen oder ändern (gleiche [MailpitInstance.id]); ein Wechsel global ↔ Projekt verschiebt sie. */
    fun save(project: Project, instance: MailpitInstance) {
        val app = PropertiesComponent.getInstance()
        val proj = PropertiesComponent.getInstance(project)
        val saved = instance.copy(auto = false, id = if (instance.auto) java.util.UUID.randomUUID().toString() else instance.id)
        val globals = read(app, true).filter { it.id != saved.id }
        val locals = read(proj, false).filter { it.id != saved.id }
        write(app, if (saved.global) upsert(read(app, true), saved) else globals)
        write(proj, if (!saved.global) upsert(read(proj, false), saved) else locals)
    }

    private fun upsert(list: List<MailpitInstance>, item: MailpitInstance) =
        if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item

    fun remove(project: Project, instance: MailpitInstance) {
        write(PropertiesComponent.getInstance(), read(PropertiesComponent.getInstance(), true).filter { it.id != instance.id })
        write(PropertiesComponent.getInstance(project), read(PropertiesComponent.getInstance(project), false).filter { it.id != instance.id })
    }

    fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
}

/** Erkennung, Zugangsdaten und Client je Adresse. */
object MailpitSettings {
    const val DEFAULT_URL = "http://localhost:8025"

    /** Zugangsdaten je Adresse im Passwort-Speicher der IDE (nicht im Projekt). Nicht im EDT aufrufen. */
    private fun attributes(url: String) = CredentialAttributes(generateServiceName("Mail View", "Mailpit $url"))

    fun credentials(url: String): Credentials? = PasswordSafe.instance.get(attributes(url))

    fun setCredentials(url: String, user: String, password: String) =
        PasswordSafe.instance.set(attributes(url), if (user.isEmpty()) null else Credentials(user, password))

    /** Client mit Zugangsdaten bzw. Sitzungs-Cookie zu [url]. Nicht im EDT aufrufen (Passwort-Speicher). */
    fun client(url: String): MailpitClient {
        val credentials = credentials(url)
        return MailpitClient(url, credentials?.userName, credentials?.getPasswordAsString(), MailpitSessions.cookie(url))
    }

    /** DDEV-Projekt (`.ddev/config.yaml`): `http://<name>.<tld>:<port>` wie in `ddev describe`. */
    fun detectDdev(project: Project): String? {
        val config = project.guessProjectDir()?.findFileByRelativePath(".ddev/config.yaml") ?: return null
        val text = runCatching { String(config.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: return null
        return ddevUrl(text, config.parent.parent.name)
    }

    fun ddevUrl(configYaml: String, folderName: String): String {
        fun value(key: String) = Regex("^$key:\\s*[\"']?([^\"'#\\s]+)", RegexOption.MULTILINE).find(configYaml)?.groupValues?.get(1)
        val name = value("name") ?: folderName
        val tld = value("project_tld") ?: "ddev.site"
        val port = value("mailpit_http_port") ?: "8025"
        return "http://$name.$tld:$port"
    }
}
