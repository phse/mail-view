package app.heckit.ide.mailview

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.MailViewBundle"

object MailViewBundle : DynamicBundle(MailViewBundle::class.java, BUNDLE) {
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
