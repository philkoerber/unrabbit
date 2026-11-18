package dev.unrabbit.app

import android.graphics.Typeface
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatMarkdownTest {
    @Test fun linksWithNestedParenthesesAndReferencesUseOnlyTheirReadableLabels() {
        val text = ChatMarkdown.render("See [the **artwork**](https://example.com/art_(IV) \"Source\") and [details][ref].\n\n[ref]: https://example.com/a_long_path")
        assertEquals("See the artwork and details.", text.toString())
        val bold = text.getSpans(0, text.length, StyleSpan::class.java).single()
        assertEquals("artwork", text.subSequence(text.getSpanStart(bold), text.getSpanEnd(bold)).toString())
        assertEquals(0, text.getSpans(0, text.length, ClickableSpan::class.java).size)
    }

    @Test fun headingsEmphasisListsAndLineBreaksStayCompact() {
        val text = ChatMarkdown.render("## Answer\n\n**Bold** and *quiet*.\nAnother line.\n\n- First\n- Second\n\n3. Third\n4. Fourth")
        assertEquals("Answer\n\nBold and quiet. Another line.\n\n• First\n• Second\n\n3. Third\n4. Fourth", text.toString())
        assertEquals(listOf(Typeface.BOLD, Typeface.BOLD, Typeface.ITALIC),
            text.getSpans(0, text.length, StyleSpan::class.java).map { it.style })
        assertEquals("Line one\nLine two", ChatMarkdown.render("Line one  \nLine two").toString())
    }

    @Test fun nestedListsKeepOrderAndIndentation() {
        assertEquals("• Parent\n  • Child\n• Next", ChatMarkdown.render("- Parent\n  - Child\n- Next").toString())
    }

    @Test fun codeKeepsLiteralSyntaxAndEscapesAreNotMistakenForFormatting() {
        val text = ChatMarkdown.render("Use `snake_case * 2`.\n\n```python\na = '[label](url)'\n  b = **value**\n```\n\n\\*literal\\* &amp; done")
        assertEquals("Use snake_case * 2.\n\na = '[label](url)'\n  b = **value**\n\n*literal* & done", text.toString())
        assertEquals(2, text.getSpans(0, text.length, TypefaceSpan::class.java).size)
        assertEquals(0, text.getSpans(0, text.length, StyleSpan::class.java).size)
    }

    @Test fun imagesAndHtmlCannotFetchContentOrCreateActiveLinks() {
        val text = ChatMarkdown.render("![a drawing](https://example.com/private.png) [open](javascript:alert(1))\n\n<script>doSomething()</script>")
        assertEquals("a drawing open\n\n<script>doSomething()</script>", text.toString())
        assertEquals(0, text.getSpans(0, text.length, ClickableSpan::class.java).size)
        assertEquals("Unfinished [link]( and **bold", ChatMarkdown.render("Unfinished [link]( and **bold").toString())
    }
}
