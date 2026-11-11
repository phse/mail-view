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
}
