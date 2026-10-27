package app.heckit.ide.mailview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardMailTest {
    private val mail = "Subject: Hallo\nFrom: anna@example.org\nTo: bob@example.com\n\nText"

    @Test
    fun acceptsMailSource() = assertEquals(mail, ClipboardMail.extract("\n  $mail"))

    @Test
    fun dropsMboxFromLine() = assertEquals(mail, ClipboardMail.extract("From anna@example.org Mon Oct  5 10:00:00 2026\n$mail"))

    @Test
    fun rejectsOtherText() {
        assertNull(ClipboardMail.extract("Hallo Bob,\nwie geht's?"))
        assertNull(ClipboardMail.extract("Note: one header only\n\nText"))
        assertNull(ClipboardMail.extract("key: value\nother: value"))
    }
}
