package app.heckit.ide.mailview

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Mailpit-Instanzen ohne Einrichtung finden: in der Compose-Datei des Projekts (Laravel Sail, Spring Boot Docker
 * Compose …) und unter den laufenden Docker-Containern (`docker ps`, z. B. Testcontainers mit zufälligem Port).
 */
object MailpitDiscovery {
    /** Weboberfläche von Mailpit im Container (`MP_UI_BIND_ADDR`, Vorgabe `0.0.0.0:8025`); 1025 ist SMTP, 1110 POP3. */
    private const val WEB_PORT = 8025
    private val OTHER_PORTS = setOf(1025, 1110)
    private val COMPOSE_FILES = listOf("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml")

    /** Ein Mailpit-Dienst aus der Compose-Datei; [url] null, wenn der Port nicht feststeht (zufällig, nicht veröffentlicht). */
    data class ComposeService(val name: String, val url: String?)

    /** Ein laufender Mailpit-Container mit veröffentlichter Weboberfläche. */
    data class Container(
        val name: String,
        val url: String,
        /** Ordner der Compose-Datei (Label `com.docker.compose.project.working_dir`), leer bei `docker run`. */
        val composeDir: String = "",
        val composeService: String = "",
        val testcontainers: Boolean = false,
    )

    // ---- Compose-Datei ----

    /** Compose-Datei im Projektordner und deren Mailpit-Dienste (Variablen aus der `.env` daneben). */
    fun compose(project: Project): Pair<VirtualFile, List<ComposeService>>? {
        val dir = project.guessProjectDir() ?: return null
        val file = COMPOSE_FILES.firstNotNullOfOrNull { dir.findChild(it) } ?: return null
        val text = runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull() ?: return null
        val env = dir.findChild(".env")?.let { runCatching { dotEnv(String(it.contentsToByteArray(), Charsets.UTF_8)) }.getOrNull() }.orEmpty()
        return file to composeServices(text, env)
    }

    /** `KEY=VALUE` je Zeile (wie Docker Compose die `.env` liest), Kommentare und `export` ignoriert. */
    fun dotEnv(text: String): Map<String, String> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim().removePrefix("export ").trim()
        if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
        val key = line.substringBefore('=').trim()
        var value = line.substringAfter('=').trim()
        value = if (value.length >= 2 && value[0] == value.last() && value[0] in "\"'") value.substring(1, value.length - 1)
            else value.substringBefore(" #")
        key to value
    }.toMap()

    /**
     * Dienste mit einem Mailpit-Image (`axllent/mailpit`, auch aus einer anderen Registry) und deren Adresse.
     * Bewusst kein YAML-Parser: Es zählen nur `services:` → Dienst → `image:` und `ports:` (kurze und lange Schreibweise).
     */
    fun composeServices(yaml: String, env: Map<String, String> = emptyMap()): List<ComposeService> {
        val lines = yaml.lines().map(::stripComment).filter { it.isNotBlank() && !it.trimStart().startsWith("---") }
        val start = lines.indexOfFirst { indent(it) == 0 && it.trimEnd() == "services:" }
        if (start < 0) return emptyList()
        val block = lines.drop(start + 1).takeWhile { indent(it) > 0 }
        if (block.isEmpty()) return emptyList()
        val serviceIndent = indent(block[0])
        val result = mutableListOf<ComposeService>()
        var i = 0
        while (i < block.size) {
            val header = block[i]
            val name = KEY.matchEntire(header.trim())?.takeIf { indent(header) == serviceIndent && it.groupValues[2].isEmpty() }?.groupValues?.get(1)
            val body = block.drop(i + 1).takeWhile { indent(it) > serviceIndent }
            i += 1 + body.size
            if (name == null) continue
            val service = parseService(body)
            if (service.image?.contains("mailpit", ignoreCase = true) != true) continue
            val ports = service.ports.mapNotNull { parsePort(it, env) }
            val web = ports.firstOrNull { it.container == WEB_PORT } ?: ports.firstOrNull { it.container !in OTHER_PORTS }
            result += ComposeService(unquote(name), web?.host?.let { url(web.ip, it) })
        }
        return result
    }

    private class ServiceBody(val image: String?, val ports: List<Map<String, String>>)

    /** Port-Eintrag: kurze Schreibweise unter dem Schlüssel „“, lange als `target`/`published`/`host_ip`. */
    private fun parseService(body: List<String>): ServiceBody {
        if (body.isEmpty()) return ServiceBody(null, emptyList())
        val keyIndent = indent(body[0])
        var image: String? = null
        val ports = mutableListOf<MutableMap<String, String>>()
        var j = 0
        while (j < body.size) {
            val line = body[j]
            val match = KEY.matchEntire(line.trim())
            j++
            if (indent(line) != keyIndent || match == null) continue
            val (key, value) = match.destructured
            when (key) {
                "image" -> image = unquote(value)
                "ports" -> {
                    if (value.startsWith("[")) {
                        value.removePrefix("[").substringBeforeLast("]").split(',').map { unquote(it.trim()) }
                            .filter { it.isNotEmpty() }.forEach { ports += mutableMapOf("" to it) }
                        continue
                    }
                    // Listeneinträge dürfen in YAML auch auf Höhe des Schlüssels stehen.
                    while (j < body.size && (indent(body[j]) > keyIndent || (indent(body[j]) == keyIndent && body[j].trimStart().startsWith("-")))) {
                        val item = body[j].trim()
                        if (item.startsWith("-")) {
                            val rest = item.removePrefix("-").trim()
                            val pair = KEY.matchEntire(rest)
                            ports += if (pair != null && !rest.startsWith("\"") && !rest.startsWith("'"))
                                mutableMapOf(pair.groupValues[1] to unquote(pair.groupValues[2])) else mutableMapOf("" to unquote(rest))
                        } else {
                            KEY.matchEntire(item)?.let { ports.lastOrNull()?.put(it.groupValues[1], unquote(it.groupValues[2])) }
                        }
                        j++
                    }
                }
            }
        }
        return ServiceBody(image, ports)
    }

    private class Port(val ip: String, val host: Int?, val container: Int)

    private fun parsePort(entry: Map<String, String>, env: Map<String, String>): Port? {
        entry[""]?.let { short ->
            val spec = substitute(short, env).substringBefore('/')
            // [::1]:8025:8025 - IPv6-Adresse in Klammern abtrennen.
            val ip = if (spec.startsWith("[")) spec.substringBefore("]") + "]" else ""
            val parts = spec.removePrefix(ip).removePrefix(":").split(':')
            val container = parts.last().toIntOrNull() ?: return null
            return when (parts.size) {
                1 -> Port(ip, null, container)
                2 -> Port(ip, parts[0].toIntOrNull(), container)
                else -> Port(parts[0], parts[1].toIntOrNull(), container)
            }
        }
        val container = entry["target"]?.let { substitute(it, env).toIntOrNull() } ?: return null
        return Port(entry["host_ip"]?.let { substitute(it, env) } ?: "", entry["published"]?.let { substitute(it, env).toIntOrNull() }, container)
    }

    /** `${VAR}`, `${VAR:-vorgabe}`, `${VAR-vorgabe}` und `$VAR` wie Docker Compose (Werte aus der `.env`, dann der Umgebung). */
    fun substitute(text: String, env: Map<String, String>): String {
        fun lookup(name: String) = env[name] ?: System.getenv(name)
        return VARIABLE.replace(text) { m ->
            val name = m.groups[1]?.value ?: m.groups[4]?.value ?: return@replace m.value
            val value = lookup(name)
            val op = m.groups[2]?.value
            val fallback = m.groups[3]?.value.orEmpty()
            when {
                op == ":-" -> value?.takeIf { it.isNotEmpty() } ?: fallback
                op == "-" -> value ?: fallback
                else -> value.orEmpty()
            }
        }
    }

    private val VARIABLE = Regex("""\$\{(\w+)(?:(:?-)([^}]*)|:?\?[^}]*)?}|\$(\w+)""")
    /** `schlüssel: wert` - in YAML nur mit Leerzeichen (oder Zeilenende) nach dem Doppelpunkt, sonst ist `8025:8025` ein Wert. */
    private val KEY = Regex("""^("[^"]+"|'[^']+'|[\w.-]+)\s*:(?:\s+(.*))?$""")

    private fun indent(line: String) = line.length - line.trimStart().length

    /** `#` leitet einen Kommentar ein, wenn davor ein Leerzeichen steht und er nicht in Anführungszeichen liegt. */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        for ((i, c) in line.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' && (i == 0 || line[i - 1].isWhitespace()) -> return line.substring(0, i).trimEnd()
            }
        }
        return line.trimEnd()
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && t[0] == t.last() && t[0] in "\"'") t.substring(1, t.length - 1) else t
    }

    /** Leere oder Wildcard-Adresse: auf diesem Rechner unter localhost erreichbar. */
    private fun url(ip: String, port: Int): String {
        val host = when (ip.trim('[', ']')) {
            "", "0.0.0.0", "::", "127.0.0.1", "::1", "localhost" -> "localhost"
            else -> if (':' in ip) "[${ip.trim('[', ']')}]" else ip
        }
        return "http://$host:$port"
    }

    // ---- Laufende Container ----

    private const val FORMAT = "{{.Names}}\t{{.Image}}\t{{.Ports}}\t{{.Label \"com.docker.compose.project.working_dir\"}}\t" +
        "{{.Label \"com.docker.compose.service\"}}\t{{.Label \"org.testcontainers\"}}"

    @Volatile private var dockerPath: String? = null
    @Volatile private var dockerChecked = false

    /** Docker-CLI im PATH der IDE oder an den üblichen Orten (Docker Desktop, OrbStack, Rancher, Homebrew). */
    private fun docker(): String? {
        if (dockerChecked) return dockerPath
        val home = System.getProperty("user.home")
        // PATH der IDE (unter macOS aus der Login-Shell übernommen); PathEnvironmentVariableUtil ist ab 2026.3 umgebaut.
        val names = if (SystemInfo.isWindows) listOf("docker.exe") else listOf("docker")
        val found = (com.intellij.util.EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH")).orEmpty()
            .split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .flatMap { dir -> names.map { File(dir, it) } }.firstOrNull { it.isFile && it.canExecute() }?.path
            ?: listOf("/usr/local/bin/docker", "/opt/homebrew/bin/docker", "$home/.docker/bin/docker", "$home/.orbstack/bin/docker",
                "$home/.rd/bin/docker", "/Applications/Docker.app/Contents/Resources/bin/docker", "/usr/bin/docker")
                .takeUnless { SystemInfo.isWindows }.orEmpty().firstOrNull { File(it).canExecute() }
        dockerPath = found
        dockerChecked = true
        return found
    }

    /** Laufende Mailpit-Container; leer ohne Docker oder wenn der Daemon nicht läuft. Nicht im EDT aufrufen. */
    fun runningContainers(): List<Container> {
        val docker = docker() ?: return emptyList()
        val output = runCatching {
            ExecUtil.execAndGetOutput(GeneralCommandLine(docker, "ps", "--filter", "status=running", "--format", FORMAT)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE), 5000)
        }.getOrNull() ?: return emptyList()
        if (output.exitCode != 0 || output.isTimeout) return emptyList()
        return parseDockerPs(output.stdout)
    }

    /** Zeilen von `docker ps --format` ([FORMAT]) → Mailpit-Container mit veröffentlichter Weboberfläche. */
    fun parseDockerPs(output: String): List<Container> = output.lineSequence().mapNotNull { line ->
        val cols = line.split('\t')
        if (cols.size < 3 || !cols[1].contains("mailpit", ignoreCase = true)) return@mapNotNull null
        val ports = parseDockerPorts(cols[2])
        val web = ports[WEB_PORT] ?: ports.entries.firstOrNull { it.key !in OTHER_PORTS }?.value ?: return@mapNotNull null
        Container(cols[0], url(web.first, web.second), cols.getOrElse(3) { "" }.noValue(), cols.getOrElse(4) { "" }.noValue(),
            cols.getOrElse(5) { "" }.noValue() == "true")
    }.toList()

    private fun String.noValue() = trim().takeUnless { it == "<no value>" }.orEmpty()

    /** `0.0.0.0:32768->8025/tcp, [::]:32768->8025/tcp, 127.0.0.1:8025-8026->8025-8026/tcp` → Container-Port → (IP, Host-Port). */
    fun parseDockerPorts(ports: String): Map<Int, Pair<String, Int>> {
        val result = linkedMapOf<Int, Pair<String, Int>>()
        for (entry in ports.split(",").map { it.trim() }) {
            if ("->" !in entry || !entry.endsWith("/tcp")) continue
            val hostPart = entry.substringBefore("->")
            val containerPart = entry.substringAfter("->").removeSuffix("/tcp")
            val ip = hostPart.substringBeforeLast(':')
            val hosts = range(hostPart.substringAfterLast(':')) ?: continue
            val containers = range(containerPart) ?: continue
            for ((offset, container) in containers.withIndex()) {
                val host = hosts.getOrNull(offset) ?: break
                // IPv4 vor IPv6, falls beide gemeldet werden.
                if (container !in result || result[container]!!.first.startsWith("[")) result[container] = ip to host
            }
        }
        return result
    }

    private fun range(spec: String): List<Int>? {
        val from = spec.substringBefore('-').toIntOrNull() ?: return null
        val to = if ('-' in spec) spec.substringAfter('-').toIntOrNull() ?: return null else from
        return if (to - from in 0..100) (from..to).toList() else null
    }
}
