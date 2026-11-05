package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private fun html(md: String) = ReleaseNotes.markdownToHtml(md)

    @Test
    fun inlineCodeIsMonospaceAndLiteral() {
        assertEquals("• <tt>OpenCfMoto.apk</tt>: most phones", html("- `OpenCfMoto.apk`: most phones"))
        assertEquals("<tt>**not bold**</tt>", html("`**not bold**`"))
    }

    @Test
    fun italicsWithStarOrUnderscore() {
        assertEquals("says <i>not compatible</i> here", html("says *not compatible* here"))
        assertEquals("<i>My moto</i>", html("_My moto_"))
        assertEquals("<b>bold</b> and <i>it</i>", html("**bold** and *it*"))
    }

    @Test
    fun snakeCaseAndLoneStarsStayLiteral() {
        assertEquals("snake_case_name", html("snake_case_name"))
        assertEquals("5 * 3 = 15", html("5 * 3 = 15"))
    }

    @Test
    fun quoteMarkersAreDropped() {
        val out = html("> ✅ **Updating?** Install over it.\n>\n> ⚠️ Note")
        assertFalse(out.contains("&gt;"))
        assertEquals("✅ <b>Updating?</b> Install over it.<br><br>⚠️ Note", out)
    }

    @Test
    fun headingsBulletsLinksStillWork() {
        val out = html("### 🆕 New\n- see [README](https://x/y_z)")
        assertTrue(out.startsWith("<b>🆕 New</b><br>• see <a href=\"https://x/y_z\">README</a>"))
    }
}
