package dev.unrabbit.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Display-only CommonMark. Keep the original message for storage and VM context.
 * Links/images render their labels; no URLs are launched and no remote assets are loaded. */
internal object ChatMarkdown {
    private val parser = Parser.builder().build()

    fun render(source: String): Spanned {
        val output = SpannableStringBuilder()
        fun gap(lines: Int) {
            if (output.isEmpty()) return
            val existing = output.takeLastWhile { it == '\n' }.length
            repeat((lines - existing).coerceAtLeast(0)) { output.append('\n') }
        }
        fun span(value: Any, body: () -> Unit) {
            val start = output.length
            body()
            if (output.length > start) output.setSpan(value, start, output.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun children(node: Node, render: (Node) -> Unit) {
            var child = node.firstChild
            while (child != null) { render(child); child = child.next }
        }
        fun render(node: Node, depth: Int = 0) {
            // Extremely nested model output should remain readable without exhausting the UI stack.
            if (depth > 100) { output.append("…"); return }
            val body = { children(node) { render(it, depth + 1) } }
            when (node) {
                is Text -> output.append(node.literal)
                is SoftLineBreak -> output.append(' ')
                is HardLineBreak -> output.append('\n')
                is StrongEmphasis -> span(StyleSpan(Typeface.BOLD), body)
                is Emphasis -> span(StyleSpan(Typeface.ITALIC), body)
                is Code -> span(TypefaceSpan("monospace")) { output.append(node.literal) }
                is Heading -> { gap(2); span(StyleSpan(Typeface.BOLD), body); gap(2) }
                is Paragraph -> {
                    // A list marker is already present before its first paragraph.
                    if (node.previous != null) gap(2)
                    body()
                    if (node.parent !is ListItem) gap(2)
                }
                is BulletList, is OrderedList -> {
                    gap(if (node.parent is ListItem) 1 else 2)
                    var number = (node as? OrderedList)?.markerStartNumber ?: 1
                    children(node) { item ->
                        gap(1)
                        var ancestor = node.parent
                        var indent = 0
                        while (ancestor != null) {
                            if (ancestor is ListItem) indent++
                            ancestor = ancestor.parent
                        }
                        output.append("  ".repeat(indent.coerceAtMost(8)))
                        output.append(if (node is OrderedList) "${number++}. " else "• ")
                        render(item, depth + 1)
                    }
                    gap(if (node.parent is ListItem) 1 else 2)
                }
                is BlockQuote -> { gap(2); span(StyleSpan(Typeface.ITALIC), body); gap(2) }
                is FencedCodeBlock -> {
                    gap(2); span(TypefaceSpan("monospace")) { output.append(node.literal.trimEnd('\n')) }; gap(2)
                }
                is IndentedCodeBlock -> {
                    gap(2); span(TypefaceSpan("monospace")) { output.append(node.literal.trimEnd('\n')) }; gap(2)
                }
                is ThematicBreak -> { gap(2); output.append("—"); gap(2) }
                // HTML is inert, literal text. It cannot create hidden content or active controls.
                is HtmlInline -> output.append(node.literal)
                is HtmlBlock -> { gap(2); output.append(node.literal.trimEnd('\n')); gap(2) }
                else -> body() // Includes links, reference links and image alt text.
            }
        }
        render(parser.parse(source))
        while (output.endsWith("\n")) output.delete(output.length - 1, output.length)
        return SpannedString(output)
    }
}
