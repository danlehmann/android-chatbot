package net.daniellehmann.localchat

import net.daniellehmann.localchat.ui.Block
import net.daniellehmann.localchat.ui.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun parsesMixedDocument() {
        val src = """
            # Title
            Some *intro* text
            over two lines.

            - one
            - two
              continued
            1. first
            2) second

            ```kotlin
            val x = 1
            ```
            > quoted

            | a | b |
            |---|---|
            | 1 | 2 |

            ---
            tail
        """.trimIndent()
        val blocks = parseMarkdown(src)
        assertEquals(Block.Heading(1, "Title"), blocks[0])
        assertEquals(Block.Paragraph("Some *intro* text\nover two lines."), blocks[1])
        assertEquals(Block.ListItem(false, "•", "one", 0), blocks[2])
        assertEquals(Block.ListItem(false, "•", "two\ncontinued", 0), blocks[3])
        assertEquals(Block.ListItem(true, "1.", "first", 0), blocks[4])
        assertEquals(Block.ListItem(true, "2.", "second", 0), blocks[5])
        assertEquals(Block.Code("kotlin", "val x = 1"), blocks[6])
        assertEquals(Block.Quote("quoted"), blocks[7])
        assertEquals(Block.Table(listOf(listOf("a", "b"), listOf("1", "2"))), blocks[8])
        assertEquals(Block.Rule, blocks[9])
        assertEquals(Block.Paragraph("tail"), blocks[10])
        assertEquals(11, blocks.size)
    }

    @Test
    fun unterminatedFenceWhileStreaming() {
        val blocks = parseMarkdown("intro\n\n```py\nprint(1)\nprint(")
        assertEquals(2, blocks.size)
        assertEquals(Block.Code("py", "print(1)\nprint("), blocks[1])
    }

    @Test
    fun fenceContentIsNotParsed() {
        val blocks = parseMarkdown("```\n# not a heading\n- not a list\n```")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is Block.Code)
    }
}

class LatexTest {
    private fun tex(s: String) = net.daniellehmann.localchat.ui.latexToText(s)

    @Test
    fun unicodeFallback() {
        assertEquals("h' = h · a", tex("h' = h \\cdot a"))
        assertEquals("α² + β₁ = (a+b)/2", tex("\\alpha^2 + \\beta_1 = \\frac{a+b}{2}"))
        assertEquals("√(x) ≤ ∞", tex("\\sqrt{x} \\le \\infty"))
        assertEquals("xⁿ⁺ᵏ", tex("x^{n+k}"))
        assertEquals("x^(n+q)", tex("x^{n+q}"))
        assertEquals("sin(θ)", tex("\\sin(\\theta)"))
    }

    @Test
    fun blockMathKeepsSource() {
        val dd = "$" + "$"
        val blocks = parseMarkdown("Given \\(h\\) then:\n\n\\[\nh' = h \\cdot a\n\\]\n\n${dd}x_1${dd}\n\ndone")
        assertEquals(Block.Paragraph("Given \\(h\\) then:"), blocks[0])
        assertEquals(Block.Math("h' = h \\cdot a"), blocks[1])
        assertEquals(Block.Math("x_1"), blocks[2])
        assertEquals(Block.Paragraph("done"), blocks[3])
    }

    @Test
    fun inlineMathUsesRendererWhenAvailable() {
        val code = androidx.compose.ui.text.SpanStyle()
        val rendered = net.daniellehmann.localchat.ui.inlineMarkdown("a \\(h\\) b ${'$'}x^2${'$'} c", code) { "id" }
        assertEquals(2, rendered.getStringAnnotations(0, rendered.length).size)
        val plain = net.daniellehmann.localchat.ui.inlineMarkdown("a \\(h\\) b ${'$'}5 and ${'$'}10", code) { null }
        assertEquals("a h b ${'$'}5 and ${'$'}10", plain.text)
    }
}
