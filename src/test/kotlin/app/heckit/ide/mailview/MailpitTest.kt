package app.heckit.ide.mailview

import org.junit.Assert.assertEquals
import org.junit.Test

class MailpitTest {
    @Test
    fun ddevDefaults() = assertEquals("http://shop.ddev.site:8025", MailpitSettings.ddevUrl("type: php\n", "shop"))

    @Test
    fun ddevConfigValues() = assertEquals(
        "http://my-app.example.test:8125",
        MailpitSettings.ddevUrl("name: my-app\nproject_tld: \"example.test\"\n# mailpit_http_port: \"9999\"\nmailpit_http_port: \"8125\"\n", "folder"),
    )

    /** Nur mit laufendem Mailpit samt `MP_UI_AUTH=demo:geheim`, z. B. MAILPIT_AUTH_URL=http://localhost:18026. */
    @Test
    fun basicAuth() {
        val url = System.getenv("MAILPIT_AUTH_URL") ?: return
        MailpitClient(url, "demo", "geheim").list()
        val error = runCatching { MailpitClient(url).list() }.exceptionOrNull() as? MailpitAuthException
        assertEquals(401, error?.status)
        assertEquals(false, error?.withCredentials)
        val wrong = runCatching { MailpitClient(url, "demo", "falsch").list() }.exceptionOrNull() as? MailpitAuthException
        assertEquals(true, wrong?.withCredentials)
    }

    @Test
    fun trailingSlash() = assertEquals("http://localhost:8025", MailpitClient("http://localhost:8025/ ").baseUrl)

    /** Compose-Datei von Laravel Sail: Port aus der .env, sonst die Vorgabe in `${VAR:-…}`. */
    @Test
    fun composeSail() {
        val yaml = """
            services:
                laravel.test:
                    image: 'sail-8.4/app'
                    ports:
                        - '${'$'}{APP_PORT:-80}:80'
                mailpit:
                    image: 'axllent/mailpit:latest'
                    ports:
                        - '${'$'}{FORWARD_MAILPIT_PORT:-1025}:1025'
                        - '${'$'}{FORWARD_MAILPIT_DASHBOARD_PORT:-8025}:8025' # Weboberfläche
            networks:
                sail:
                    driver: bridge
        """.trimIndent()
        assertEquals(listOf(MailpitDiscovery.ComposeService("mailpit", "http://localhost:8025")), MailpitDiscovery.composeServices(yaml))
        assertEquals("http://localhost:18025",
            MailpitDiscovery.composeServices(yaml, MailpitDiscovery.dotEnv("FORWARD_MAILPIT_DASHBOARD_PORT=18025\n")).single().url)
    }

    /** Lange Schreibweise, Liste auf Höhe des Schlüssels, eigene IP; nur Container-Port = zufälliger Host-Port (unbekannt). */
    @Test
    fun composeVariants() {
        val yaml = """
            services:
              mail:
                image: ghcr.io/axllent/mailpit:v1.33
                ports:
                - target: 8025
                  published: "18030"
                  host_ip: 127.0.0.1
                - 1025
              random:
                image: "axllent/mailpit"
                ports: ["1025", "8025"]
              db:
                image: mariadb
                ports:
                  - 3306:3306
        """.trimIndent()
        assertEquals(listOf(
            MailpitDiscovery.ComposeService("mail", "http://localhost:18030"),
            MailpitDiscovery.ComposeService("random", null),
        ), MailpitDiscovery.composeServices(yaml))
    }

    @Test
    fun composeShortSyntax() {
        val yaml = "services:\n  mailpit:\n    image: axllent/mailpit\n    ports:\n      - 192.168.1.5:9025:8025/tcp\n      - 1025:1025\n"
        assertEquals("http://192.168.1.5:9025", MailpitDiscovery.composeServices(yaml).single().url)
    }

    @Test
    fun dotEnvValues() = assertEquals(
        mapOf("A" to "1", "B" to "zwei drei", "C" to "x"),
        MailpitDiscovery.dotEnv("# Kommentar\nA=1\nexport B=\"zwei drei\"\nC=x # Rest\n\nkaputt\n"),
    )

    @Test
    fun substitute() {
        val env = mapOf("SET" to "1", "EMPTY" to "")
        assertEquals("1-x-x-", MailpitDiscovery.substitute("${'$'}{SET:-9}-${'$'}{EMPTY:-x}-${'$'}{MISSING_VAR_FOR_TEST-x}-${'$'}{EMPTY-y}", env))
    }

    /** `docker ps` mit Testcontainers (zufällige Ports, IPv4 und IPv6), Compose-Container und anderen Images. */
    @Test
    fun dockerPs() {
        val output = listOf(
            "busy_turing\taxllent/mailpit:latest\t0.0.0.0:32769->1025/tcp, [::]:32769->1025/tcp, 0.0.0.0:32770->8025/tcp, [::]:32770->8025/tcp\t\t\ttrue",
            "shop-mailpit-1\taxllent/mailpit\t127.0.0.1:8025->8025/tcp\t/home/dev/shop\tmailpit\t",
            "ddev-shop-web\tddev/ddev-webserver:v1.25\t127.0.0.1:62653->8025/tcp\t/home/dev/shop/.ddev\tweb\t",
            "hidden\taxllent/mailpit\t8025/tcp, 1025/tcp\t\t\t",
            "router\taxllent/mailpit\t127.0.0.1:8025-8026->8025-8026/tcp\t<no value>\t<no value>\t<no value>",
        ).joinToString("\n")
        assertEquals(listOf(
            MailpitDiscovery.Container("busy_turing", "http://localhost:32770", testcontainers = true),
            MailpitDiscovery.Container("shop-mailpit-1", "http://localhost:8025", "/home/dev/shop", "mailpit"),
            MailpitDiscovery.Container("router", "http://localhost:8025"),
        ), MailpitDiscovery.parseDockerPs(output))
    }

    @Test
    fun dockerPortRanges() = assertEquals(
        mapOf(8025 to ("127.0.0.3" to 9026), 8026 to ("127.0.0.3" to 9027)),
        MailpitDiscovery.parseDockerPorts("127.0.0.3:9026-9027->8025-8026/tcp, 0.0.0.0:53->53/udp"),
    )

    /**
     * Nur mit laufendem Mailpit samt Relay (z. B. `MP_SMTP_RELAY_HOST`, `MP_SMTP_RELAY_ALLOWED_RECIPIENTS=@example\.(org|net)$`)
     * und mindestens einer Mail an eine erlaubte Adresse: MAILPIT_RELAY_URL=http://localhost:18031.
     */
    @Test
    fun relayAndTags() {
        val url = System.getenv("MAILPIT_RELAY_URL") ?: return
        val client = MailpitClient(url)
        val relay = client.relay() ?: error("relay not enabled")
        assertEquals("@example\\.(org|net)$", relay.allowedRecipients)
        val message = client.list().first()
        client.release(message.id, message.recipients)
        val error = runCatching { client.release(message.id, listOf("someone@elsewhere.test")) }.exceptionOrNull()
        assertEquals(true, error?.message?.contains("allowlist"))
        assertEquals(true, client.tags().isNotEmpty())
    }
}
