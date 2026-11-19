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
    /** Schlagwörter (Mailpit `X-Tags`, Regeln oder von Hand), schon von Mailpit sortiert. */
    val tags: List<String> = emptyList(),
    /** Adressen aus To, Cc und Bcc - Vorschlag fürs Weiterleiten über SMTP. */
    val recipients: List<String> = emptyList(),
)

/** Weiterleiten über einen in Mailpit eingerichteten SMTP-Server (`--smtp-relay-config`, „Release“ in der Weboberfläche). */
data class MailpitRelay(
    val smtpServer: String,
    /** Regex der erlaubten bzw. gesperrten Empfänger, leer: keine Einschränkung. */
    val allowedRecipients: String,
    val blockedRecipients: String,
    /** Absender, den Mailpit beim Weiterleiten einsetzt, leer: unverändert. */
    val overrideFrom: String,
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

    /** Alle Schlagwörter dieser Instanz. */
    fun tags(): List<String> = JsonParser.parseString(send(get("$api/tags")).toString(Charsets.UTF_8)).let { json ->
        if (json.isJsonArray) json.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } else emptyList()
    }

    /** Weiterleiten über SMTP, falls in Mailpit eingerichtet; sonst null (auch bei älteren Mailpit-Versionen ohne diese Angabe). */
    fun relay(): MailpitRelay? {
        val json = JsonParser.parseString(send(get("$api/webui")).toString(Charsets.UTF_8)).asJsonObject
        val relay = json.get("MessageRelay")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (relay.get("Enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) return null
        // Ältere Mailpit-Versionen nennen die Erlaubnisliste „RecipientAllowlist“.
        return MailpitRelay(relay.str("SMTPServer").orEmpty(), relay.str("AllowedRecipients")?.ifEmpty { null } ?: relay.str("RecipientAllowlist").orEmpty(),
            relay.str("BlockedRecipients").orEmpty(), relay.str("OverrideFrom").orEmpty())
    }

    /** Mail über den SMTP-Server aus [relay] an [to] schicken. */
    fun release(id: String, to: List<String>) =
        send(request("$api/message/${encode(id)}/release", "POST", """{"To":${to.toJson()}}"""))

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
        if (response.statusCode() !in 200..299) {
            // Mailpit erklärt Fehler (z. B. beim Weiterleiten: Empfänger nicht erlaubt) als Text im Rumpf.
            val detail = response.body().toString(Charsets.UTF_8).trim().take(300).takeIf { it.isNotEmpty() && !it.startsWith("<") }
            throw java.io.IOException(detail ?: "HTTP ${response.statusCode()} ${request.uri()}")
        }
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
        tags = o.get("Tags")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
        recipients = listOf("To", "Cc", "Bcc").flatMap { key ->
            o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                e.takeIf { it.isJsonObject }?.asJsonObject?.str("Address")?.takeIf { it.isNotBlank() }
            }.orEmpty()
        }.distinct(),
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

    fun all(project: Project, containers: List<MailpitDiscovery.Container> = emptyList()): List<MailpitInstance> {
        val projectProps = PropertiesComponent.getInstance(project)
        // Adresse aus der ersten Fassung (eine je Projekt) übernehmen.
        projectProps.getValue(OLD_URL_KEY)?.takeIf { it.isNotBlank() }?.let { old ->
            write(projectProps, read(projectProps, false) + MailpitInstance(java.util.UUID.randomUUID().toString(), hostOf(old), old))
            projectProps.unsetValue(OLD_URL_KEY)
        }
        val stored = read(PropertiesComponent.getInstance(), true) + read(projectProps, false)
        val auto = detected(project, containers)
        val seen = stored.map { normalize(it.url) }.toMutableSet()
        val fresh = auto.filter { seen.add(normalize(it.url)) }
        val fallback = if (stored.isEmpty() && fresh.isEmpty())
            listOf(MailpitInstance("auto", "localhost", MailpitSettings.DEFAULT_URL, auto = true)) else emptyList()
        return fallback + fresh + stored
    }

    /**
     * Erkannte Instanzen: DDEV, Mailpit-Dienste der Compose-Datei (mit dem echten Port, wenn der Container läuft) und
     * laufende Mailpit-Container ohne Compose-Projekt oder mit einem in diesem Projekt (`docker run`, Testcontainers).
     */
    private fun detected(project: Project, containers: List<MailpitDiscovery.Container>): List<MailpitInstance> {
        val result = mutableListOf<MailpitInstance>()
        MailpitSettings.detectDdev(project)?.let { result += MailpitInstance("auto", "DDEV", it, auto = true) }
        val base = project.basePath?.let { java.io.File(it).canonicalPath }
        fun inProject(dir: String) = base != null && dir.isNotEmpty() &&
            runCatching { java.io.File(dir).canonicalPath }.getOrDefault(dir).let { it == base || it.startsWith(base + java.io.File.separator) }
        val used = mutableSetOf<MailpitDiscovery.Container>()
        MailpitDiscovery.compose(project)?.let { (file, services) ->
            val dir = file.parent.path
            for (service in services) {
                val running = containers.firstOrNull { it.composeService == service.name && inProject(it.composeDir) &&
                    runCatching { java.io.File(it.composeDir).canonicalPath == java.io.File(dir).canonicalPath }.getOrDefault(false) }
                running?.let { used += it }
                val url = running?.url ?: service.url ?: continue
                val name = if (services.size == 1) "Compose" else "Compose: ${service.name}"
                result += MailpitInstance("auto:compose:${service.name}", name, url, auto = true)
            }
        }
        for (container in containers) {
            if (container in used || (container.composeDir.isNotEmpty() && !inProject(container.composeDir))) continue
            val name = when {
                container.testcontainers -> "Testcontainers"
                container.composeService.isNotEmpty() -> container.composeService
                else -> container.name
            }
            result += MailpitInstance("auto:docker:${container.name}", name, container.url, auto = true)
        }
        return result
    }

    /** Gleiche Instanz trotz anderer Schreibweise (127.0.0.1/localhost, Schrägstrich am Ende). */
    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()
        .replace("://127.0.0.1", "://localhost").replace("://[::1]", "://localhost")

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
