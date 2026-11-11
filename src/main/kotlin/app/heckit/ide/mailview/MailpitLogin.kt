package app.heckit.ide.mailview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.util.ui.JBUI
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.Action
import javax.swing.JComponent

/** Sitzungs-Cookies nach einer Anmeldung im Browser (Single Sign-on vor Mailpit), je Adresse, bis zum IDE-Neustart. */
object MailpitSessions {
    private val cookies = ConcurrentHashMap<String, String>()

    fun cookie(url: String): String? = cookies[url]

    fun set(url: String, cookie: String?) { if (cookie == null) cookies.remove(url) else cookies[url] = cookie }

    /** Cookies, die der eingebettete Browser für [url] hat (auch HttpOnly), als `Cookie`-Header. Nicht im EDT. */
    fun fromBrowser(url: String): String? = runCatching {
        JBCefBrowserBase.getGlobalJBCefCookieManager().getCookies(url, true).get(5, TimeUnit.SECONDS)
            .joinToString("; ") { "${it.name}=${it.value}" }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * Anmeldung im eingebetteten Browser, wenn ein Proxy mit Single Sign-on (OIDC o. Ä.) vor Mailpit sitzt. Sobald die
 * Mailpit-Seite selbst geladen ist, werden die Cookies übernommen und die API damit geprüft.
 */
class MailpitLoginDialog(project: Project, private val url: String) : DialogWrapper(project) {
    private val browser = JBCefBrowser.createBuilder().setUrl(url).build()
    private val host = runCatching { URI(url).host }.getOrNull()
    @Volatile private var checking = false

    init {
        title = MailViewBundle.message("mailpit.login.title")
        Disposer.register(disposable, browser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                val current = runCatching { URI(cefBrowser.url) }.getOrNull()
                LOG.info("Mailpit login: loaded ${current?.host}${current?.path} ($httpStatusCode)")
                // Zur Diagnose: Kann diese Seite Passkeys (WebAuthn) nutzen?
                cefBrowser.executeJavaScript("""
                    (async () => {
                      const pk = !!window.PublicKeyCredential;
                      let platform = 'n/a', hybrid = 'n/a';
                      try { platform = await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable(); } catch (e) { platform = 'error ' + e.name; }
                      try { const c = await PublicKeyCredential.getClientCapabilities(); hybrid = c.hybridTransport; } catch (e) { hybrid = 'error ' + e.name; }
                      console.log('[mailpit-login] webauthn=' + pk + ' platform=' + platform + ' hybrid=' + hybrid);
                    })();
                """.trimIndent(), cefBrowser.url, 0)
                if (current?.host == host && !checking) check()
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(b: CefBrowser, level: CefSettings.LogSeverity, message: String, source: String, line: Int): Boolean {
                if (message.startsWith("[mailpit-login]")) LOG.info("Mailpit login: $message")
                return false
            }
        }, browser.cefBrowser)
        init()
    }

    /** Cookies übernehmen und `/api/v1/info` damit abfragen; klappt es, schließt der Dialog. */
    private fun check() {
        checking = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val cookie = MailpitSessions.fromBrowser(url)
            val result = runCatching { MailpitClient(url, cookie = cookie).list(1) }
            LOG.info("Mailpit login: cookies ${if (cookie == null) "none" else cookie.split("; ").map { it.substringBefore('=') }}, API " +
                (result.exceptionOrNull()?.let { "failed: ${it.message}" } ?: "ok"))
            ApplicationManager.getApplication().invokeLater({
                checking = false
                if (result.isSuccess) {
                    MailpitSessions.set(url, cookie)
                    close(OK_EXIT_CODE)
                }
            }, ModalityState.any(), { isDisposed })
        }
    }

    override fun createCenterPanel(): JComponent = browser.component.apply { preferredSize = JBUI.size(820, 680) }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    companion object {
        private val LOG = logger<MailpitLoginDialog>()
    }
}
