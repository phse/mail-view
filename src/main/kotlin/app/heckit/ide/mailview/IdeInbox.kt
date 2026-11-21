package app.heckit.ide.mailview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.concurrency.AppExecutorUtil
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.InternetHeaders
import jakarta.mail.internet.MailDateFormat
import jakarta.mail.internet.MimeUtility
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.name

/** Absender und Empfänger, wie sie der SMTP-Client angegeben hat (nicht die Kopfzeilen der Mail). */
data class SmtpEnvelope(val helo: String, val from: String, val recipients: List<String>, val remote: String)

/**
 * Kleiner SMTP-Server, der nur annimmt: EHLO/HELO, AUTH (PLAIN, LOGIN; jede Anmeldung gilt), MAIL, RCPT, DATA, RSET,
 * NOOP, QUIT. Kein TLS (wird nicht angeboten, damit Clients unverschlüsselt bleiben), kein Weiterleiten. Jede Mail geht
 * an [deliver].
 */
internal class SmtpServer(private val deliver: (SmtpEnvelope, ByteArray) -> String) {
    private var socket: ServerSocket? = null

    val port: Int get() = socket?.localPort ?: -1

    /** [network]: auch Verbindungen von außen (Docker-Container, andere Rechner), sonst nur 127.0.0.1. */
    fun start(port: Int, network: Boolean) {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(if (network) InetSocketAddress(port) else InetSocketAddress(InetAddress.getLoopbackAddress(), port))
        } catch (e: IOException) {
            server.close()
            throw e
        }
        socket = server
        Thread({ accept(server) }, "Mail View SMTP :${server.localPort}").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try { server.accept() } catch (_: IOException) { break }
            AppExecutorUtil.getAppExecutorService().execute {
                client.use { runCatching { session(it) }.onFailure { e -> if (e !is IOException) thisLogger().warn(e) } }
            }
        }
    }

    private fun session(client: Socket) {
        client.soTimeout = TIMEOUT_MS
        val remote = (client.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
        Session(BufferedInputStream(client.getInputStream()), BufferedOutputStream(client.getOutputStream()), remote).run()
    }

    /** Ein Gespräch; auch für Tests direkt mit Streams. */
    inner class Session(private val input: InputStream, private val output: OutputStream, private val remote: String) {
        private var helo = ""
        private var from: String? = null
        private val recipients = ArrayList<String>()

        fun run() {
            reply("220 $HOST Mail View ESMTP ready")
            while (true) {
                val line = readLine(MAX_COMMAND)?.toString(Charsets.UTF_8) ?: return
                val verb = line.substringBefore(' ').uppercase()
                val argument = line.substringAfter(' ', "").trim()
                when (verb) {
                    "EHLO" -> {
                        helo = argument
                        reset()
                        reply("250-$HOST Hello $argument", "250-SIZE $MAX_SIZE", "250-8BITMIME", "250-SMTPUTF8", "250-AUTH PLAIN LOGIN", "250 HELP")
                    }
                    "HELO" -> { helo = argument; reset(); reply("250 $HOST Hello $argument") }
                    "AUTH" -> auth(argument)
                    "MAIL" -> {
                        val sender = address(argument, "FROM:")
                        if (sender == null) reply("501 5.5.4 Syntax: MAIL FROM:<address>")
                        else {
                            from = sender
                            recipients.clear()
                            reply("250 2.1.0 Ok")
                        }
                    }
                    "RCPT" -> {
                        val to = address(argument, "TO:")?.takeIf { it.isNotEmpty() }
                        when {
                            from == null -> reply("503 5.5.1 MAIL first")
                            to == null -> reply("501 5.5.4 Syntax: RCPT TO:<address>")
                            recipients.size >= MAX_RECIPIENTS -> reply("452 4.5.3 Too many recipients")
                            else -> { recipients += to; reply("250 2.1.5 Ok") }
                        }
                    }
                    "DATA" -> if (from == null || recipients.isEmpty()) reply("503 5.5.1 RCPT first") else data() ?: return
                    "RSET" -> { reset(); reply("250 2.0.0 Ok") }
                    "NOOP" -> reply("250 2.0.0 Ok")
                    "VRFY" -> reply("252 2.0.0 Cannot verify, but will accept")
                    "STARTTLS" -> reply("454 4.7.0 TLS not available")
                    "HELP" -> reply("214 2.0.0 Mail View accepts mails for viewing in the IDE")
                    "QUIT" -> { reply("221 2.0.0 Bye"); return }
                    else -> reply("502 5.5.2 Command not implemented")
                }
            }
        }

        /** Mail bis zur Zeile mit dem einzelnen Punkt; null: Verbindung abgerissen. */
        private fun data(): Unit? {
            reply("354 End data with <CR><LF>.<CR><LF>")
            val data = ByteArrayOutputStream()
            var tooBig = false
            while (true) {
                val line = readLine(MAX_SIZE) ?: return null
                if (line.size() == 1 && line.toByteArray()[0] == '.'.code.toByte()) break
                val bytes = line.toByteArray()
                val start = if (bytes.isNotEmpty() && bytes[0] == '.'.code.toByte()) 1 else 0
                if (data.size() + bytes.size + 2 > MAX_SIZE) tooBig = true
                if (!tooBig) {
                    data.write(bytes, start, bytes.size - start)
                    data.write(CRLF)
                }
            }
            val envelope = SmtpEnvelope(helo, from.orEmpty(), recipients.toList(), remote)
            reset()
            if (tooBig) return reply("552 5.3.4 Message too big")
            val id = runCatching { deliver(envelope, data.toByteArray()) }
                .getOrElse { return reply("451 4.3.0 Could not store the mail: ${it.message}") }
            reply("250 2.0.0 Ok: queued as $id")
            return Unit
        }

        /** Jede Anmeldung gilt; die Daten werden nur gelesen, nicht geprüft. */
        private fun auth(argument: String) {
            val parts = argument.split(' ')
            when (parts[0].uppercase()) {
                "PLAIN" -> if (parts.size < 2) { reply("334 "); readLine(MAX_COMMAND) ?: return }
                "LOGIN" -> {
                    if (parts.size < 2) { reply("334 VXNlcm5hbWU6"); readLine(MAX_COMMAND) ?: return }
                    reply("334 UGFzc3dvcmQ6")
                    readLine(MAX_COMMAND) ?: return
                }
                else -> return reply("504 5.5.4 Use AUTH PLAIN or LOGIN")
            }
            reply("235 2.7.0 Authentication successful")
        }

        private fun reset() {
            from = null
            recipients.clear()
        }

        /** Adresse aus `FROM:<a@b> SIZE=…` bzw. `TO:<a@b>`; leerer Absender `<>` ergibt "". */
        private fun address(argument: String, keyword: String): String? {
            if (!argument.startsWith(keyword, ignoreCase = true)) return null
            val rest = argument.substring(keyword.length).trim()
            return if (rest.startsWith("<")) rest.substringAfter('<').substringBefore('>').trim() else rest.substringBefore(' ').trim()
        }

        private fun reply(vararg lines: String) {
            for (line in lines) output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        /** Eine Zeile ohne CRLF bzw. LF; längere als [max] werden abgeschnitten. null am Ende des Stroms. */
        private fun readLine(max: Int): ByteArrayOutputStream? {
            val out = ByteArrayOutputStream()
            var any = false
            while (true) {
                val b = input.read()
                if (b < 0) return if (any) out else null
                any = true
                if (b == '\n'.code) break
                if (out.size() < max) out.write(b)
            }
            val bytes = out.toByteArray()
            if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                out.reset()
                out.write(bytes, 0, bytes.size - 1)
            }
            return out
        }
    }

    companion object {
        const val HOST = "localhost"
        const val MAX_SIZE = 50 * 1024 * 1024
        private const val MAX_COMMAND = 4096
        private const val MAX_RECIPIENTS = 1000
        private const val TIMEOUT_MS = 5 * 60 * 1000
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}

/**
 * Angekommene Mails als .eml in [dir] (Name: Zeitstempel, also nach Ankunft sortiert), gelesene in `read.txt`.
 * Liste, Suche und Schlagwörter (`X-Tags`) wie bei Mailpit; höchstens [MAX_MAILS], ältere fallen weg.
 */
internal class InboxStore(private val dir: Path) {
    private class Meta(val subject: String?, val from: String?, val to: String?, val created: Date?, val attachments: Int,
                       val snippet: String?, val tags: List<String>, val recipients: List<String>, val text: String)

    private val meta = ConcurrentHashMap<String, Meta>()
    private val counter = AtomicInteger()
    private val lock = Any()
    private var read: MutableSet<String>? = null

    /** Speichert die Mail mit Return-Path und Received wie ein zustellender Server; nur im Umschlag genannte Empfänger als Bcc. */
    fun deliver(envelope: SmtpEnvelope, data: ByteArray): String {
        Files.createDirectories(dir)
        val id = "%013d-%04d".format(System.currentTimeMillis(), counter.incrementAndGet() % 10_000)
        val target = dir.resolve("$id.eml")
        val temp = dir.resolve("$id.tmp")
        Files.write(temp, trace(envelope, id, data) + data)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        prune()
        return id
    }

    private fun trace(envelope: SmtpEnvelope, id: String, data: ByteArray): ByteArray {
        val headers = runCatching { InternetHeaders(ByteArrayInputStream(data)) }.getOrNull()
        fun addresses(name: String) = headers?.getHeader(name, ",")?.let { raw ->
            runCatching { InternetAddress.parseHeader(raw, false).mapNotNull { it.address?.lowercase() } }.getOrNull()
        }.orEmpty()
        val visible = (addresses("To") + addresses("Cc") + addresses("Bcc")).toSet()
        val hidden = envelope.recipients.filter { it.lowercase() !in visible }
        val target = envelope.recipients.singleOrNull()?.let { " for <$it>" } ?: ""
        val trace = StringBuilder()
            .append("Return-Path: <").append(envelope.from).append(">\r\n")
            .append("Received: from ").append(envelope.helo.ifBlank { "unknown" }).append(" (").append(envelope.remote).append(")\r\n")
            .append("\tby Mail View (IDE) with ESMTP id ").append(id).append(target).append(";\r\n")
            .append("\t").append(MailDateFormat().format(Date())).append("\r\n")
        if (hidden.isNotEmpty()) trace.append("Bcc: ").append(hidden.joinToString(", ")).append("\r\n")
        return trace.toString().toByteArray(Charsets.UTF_8)
    }

    private fun files(): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream -> stream.filter { it.name.endsWith(".eml") }.toList() }.sortedByDescending { it.name }
    }

    private fun prune() {
        files().drop(MAX_MAILS).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    fun list(limit: Int, query: String?): List<MailpitMessage> {
        val (tag, words) = parseQuery(query)
        val read = readIds()
        val files = files()
        val ids = files.map { it.name.removeSuffix(".eml") }.toSet()
        meta.keys.retainAll(ids)
        val result = ArrayList<MailpitMessage>()
        for (file in files) {
            val id = file.name.removeSuffix(".eml")
            val m = meta[id] ?: runCatching { readMeta(file) }.getOrNull()?.also { meta[id] = it } ?: continue
            if (tag != null && m.tags.none { it.equals(tag, ignoreCase = true) }) continue
            if (words.isNotEmpty() && words.any { it !in m.text }) continue
            result += MailpitMessage(id, m.subject, m.from, m.to, m.created, m.attachments, id in read, m.snippet, m.tags, m.recipients)
            if (result.size >= limit) break
        }
        return result
    }

    /** `tag:name` bzw. `tag:"mit Leerzeichen"` (so baut das Tool Window den Schlagwort-Filter) und Suchwörter. */
    private fun parseQuery(query: String?): Pair<String?, List<String>> {
        var rest = query.orEmpty()
        var tag: String? = null
        TAG.find(rest)?.let { match ->
            tag = match.groupValues[1].ifEmpty { match.groupValues[2] }
            rest = rest.removeRange(match.range)
        }
        return tag to rest.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    private fun readMeta(file: Path): Meta {
        val bytes = Files.readAllBytes(file)
        val mail = MailParser.parse(bytes)
        fun name(a: MailAddress) = a.name?.takeIf { it.isNotBlank() } ?: a.address
        val headers = runCatching { InternetHeaders(ByteArrayInputStream(bytes)) }.getOrNull()
        val tags = headers?.getHeader("X-Tags", ",")?.let { runCatching { MimeUtility.decodeText(MimeUtility.unfold(it)) }.getOrDefault(it) }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.sorted().orEmpty()
        val body = mail.text ?: mail.html?.let { StringUtil.stripHtml(it, " ") } ?: ""
        val snippet = body.replace(Regex("\\s+"), " ").trim().take(SNIPPET).takeIf { it.isNotEmpty() }
        val recipients = (mail.to + mail.cc + mail.bcc).mapNotNull { it.address }.distinct()
        val from = mail.from.firstOrNull()?.let(::name)
        val to = mail.to.mapNotNull(::name).joinToString(", ").takeIf { it.isNotEmpty() }
        val text = listOfNotNull(mail.subject, from, to, mail.from.firstOrNull()?.address, recipients.joinToString(" "), tags.joinToString(" "), body.take(MAX_TEXT))
            .joinToString(" ").lowercase()
        val created = mail.date ?: Date(Files.getLastModifiedTime(file).toMillis())
        return Meta(mail.subject?.takeIf { it.isNotBlank() }, from, to, created, mail.attachments.size, snippet, tags, recipients, text)
    }

    fun raw(id: String): ByteArray = Files.readAllBytes(dir.resolve("${safe(id)}.eml"))

    fun markRead(ids: List<String>, read: Boolean) = synchronized(lock) {
        val set = readIds()
        if (read) set += ids else set -= ids.toSet()
        saveRead(set)
    }

    fun delete(ids: List<String>) {
        ids.forEach { Files.deleteIfExists(dir.resolve("${safe(it)}.eml")) }
        synchronized(lock) { readIds().removeAll(ids.toSet()); saveRead(readIds()) }
    }

    fun deleteAll() = delete(files().map { it.name.removeSuffix(".eml") })

    fun tags(): List<String> {
        list(MAX_MAILS, null)
        return meta.values.flatMap { it.tags }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun readIds(): MutableSet<String> = synchronized(lock) {
        read ?: runCatching { Files.readAllLines(dir.resolve(READ_FILE)).filter { it.isNotBlank() } }.getOrDefault(emptyList())
            .toMutableSet().also { read = it }
    }

    private fun saveRead(set: Set<String>) {
        val existing = files().map { it.name.removeSuffix(".eml") }.toSet()
        Files.createDirectories(dir)
        Files.write(dir.resolve(READ_FILE), set.filter { it in existing })
    }

    /** Nur Namen, die das Postfach selbst vergibt (keine Pfade). */
    private fun safe(id: String): String = id.takeIf { ID.matches(it) } ?: throw IOException("Unknown mail: $id")

    companion object {
        const val MAX_MAILS = 1000
        private const val SNIPPET = 200
        private const val MAX_TEXT = 200_000
        private const val READ_FILE = "read.txt"
        private val ID = Regex("""\d{13}-\d{4}""")
        private val TAG = Regex("""(?i)\btag:(?:"([^"]*)"|(\S+))""")
    }
}

/**
 * Mailserver in der IDE (Settings | Tools | Mail View): nimmt Mails der eigenen Anwendung per SMTP an, ohne Docker und
 * ohne Mailpit, und zeigt sie als Reiter im Mailpit-Tool-Window. Die Mails liegen im Systemordner der IDE.
 */
@Service(Service.Level.APP)
class IdeInbox : MailBox, Disposable {
    sealed interface State {
        data object Off : State
        data class Running(val port: Int) : State
        data class Failed(val port: Int, val message: String) : State
    }

    private val store = InboxStore(Path.of(PathManager.getSystemPath(), "mail-view", "inbox"))
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var server: SmtpServer? = null
    private var network = false

    @Volatile var state: State = State.Off
        private set

    /** Bei neuer Mail und wenn sich der Zustand ändert (gestartet, angehalten, Port belegt); aus beliebigem Thread. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun changed() = listeners.forEach { runCatching(it) }

    /** Nach den Einstellungen starten, neu starten (anderer Port) oder anhalten. */
    @Synchronized
    fun apply() {
        val enabled = MailViewSettings.inboxEnabled
        val port = MailViewSettings.inboxPort
        val wantNetwork = MailViewSettings.inboxNetwork
        if (enabled && state == State.Running(port) && network == wantNetwork) return
        server?.stop()
        server = null
        state = if (!enabled) State.Off else try {
            SmtpServer(::deliver).also { it.start(port, wantNetwork) }.let { started ->
                server = started
                network = wantNetwork
                State.Running(started.port)
            }
        } catch (e: IOException) {
            State.Failed(port, e.message ?: e.javaClass.simpleName)
        }
        changed()
    }

    private fun deliver(envelope: SmtpEnvelope, data: ByteArray): String = store.deliver(envelope, data).also { changed() }

    val port: Int get() = MailViewSettings.inboxPort

    val address: String get() = "${if (MailViewSettings.inboxNetwork) "0.0.0.0" else "127.0.0.1"}:$port"

    override val baseUrl: String get() = "smtp://$address"
    override val label: String get() = "SMTP $address"

    override fun list(limit: Int, query: String?): List<MailpitMessage> {
        (state as? State.Failed)?.let { throw IOException(MailViewBundle.message("inbox.failed", it.port, it.message)) }
        return store.list(limit, query)
    }

    override fun raw(id: String): ByteArray = store.raw(id)
    override fun markRead(ids: List<String>, read: Boolean) = store.markRead(ids, read)
    override fun delete(ids: List<String>) = store.delete(ids).also { changed() }
    override fun deleteAll() = store.deleteAll().also { changed() }
    override fun tags(): List<String> = store.tags()

    override fun dispose() {
        server?.stop()
        server = null
    }

    companion object {
        /** Kennung des Reiters im Mailpit-Tool-Window. */
        const val INSTANCE_ID = "ide-inbox"
        const val DEFAULT_PORT = 2525

        fun getInstance(): IdeInbox = ApplicationManager.getApplication().getService(IdeInbox::class.java)
    }
}

/** Beim Start der IDE (erstes Projekt) den Mailserver starten, falls eingeschaltet. */
class IdeInboxStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (MailViewSettings.inboxEnabled) IdeInbox.getInstance().apply()
    }
}
