package net.daniellehmann.localchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * A deliberately small Markdown renderer: enough for what chat models emit
 * (headings, paragraphs, bullet/numbered lists, fenced code, inline code,
 * bold/italic, block quotes, tables as monospace, rules, LaTeX math).
 * No HTML, no images. Markdown is parsed in-house; math is drawn by
 * JLaTeXMath (see MathRender.kt).
 */

sealed class Block {
    data class Heading(val level: Int, val text: String) : Block()
    data class Paragraph(val text: String) : Block()
    data class Code(val lang: String, val code: String) : Block()
    data class ListItem(val ordered: Boolean, val marker: String, val text: String, val indent: Int) : Block()
    data class Quote(val text: String) : Block()
    data class Table(val rows: List<List<String>>) : Block()
    data class Math(val text: String) : Block()
    data object Rule : Block()
}

private val fenceRe = Regex("^\\s{0,3}(```|~~~)\\s*([\\w+#.-]*)\\s*$")
private val fenceLineRe = Regex("^\\s{0,3}(```|~~~)", RegexOption.MULTILINE)
private val headingRe = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")
private val bulletRe = Regex("^(\\s*)([-*+])\\s+(.*)$")
private val orderedRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val ruleRe = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val tableSepRe = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

fun parseMarkdown(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = src.lines()
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotEmpty()) {
            out += Block.Paragraph(para.toString().trim())
            para.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        val fence = fenceRe.find(line)
        if (fence != null) {
            flushPara()
            val marker = fence.groupValues[1]
            val lang = fence.groupValues[2]
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                code.append(lines[i]).append('\n')
                i++
            }
            i++ // closing fence (or EOF while streaming)
            out += Block.Code(lang, code.toString().trimEnd('\n'))
            continue
        }
        if (line.isBlank()) { flushPara(); i++; continue }
        val t = line.trim()
        val mathOpen = when { t.startsWith("\\[") -> "\\]"; t.startsWith("$$") -> "$$"; else -> null }
        if (mathOpen != null) {
            flushPara()
            val body = StringBuilder()
            var rest = t.substring(2)
            var closed = false
            while (true) {
                val end = rest.indexOf(mathOpen)
                if (end >= 0) { body.append(rest, 0, end); closed = true; break }
                body.append(rest).append('\n')
                i++
                if (i >= lines.size) break
                rest = lines[i]
            }
            i++
            out += Block.Math(body.toString().trim())
            if (!closed) break
            continue
        }
        val h = headingRe.find(line)
        if (h != null) { flushPara(); out += Block.Heading(h.groupValues[1].length, h.groupValues[2]); i++; continue }
        if (ruleRe.matches(line)) { flushPara(); out += Block.Rule; i++; continue }
        if (line.trimStart().startsWith(">")) {
            flushPara()
            val q = StringBuilder()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                q.append(lines[i].trimStart().removePrefix(">").trimStart()).append('\n'); i++
            }
            out += Block.Quote(q.toString().trim()); continue
        }
        if (line.trimStart().startsWith("|") && i + 1 < lines.size && tableSepRe.matches(lines[i + 1])) {
            flushPara()
            val rows = mutableListOf<List<String>>()
            rows += splitRow(line); i += 2
            while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += splitRow(lines[i]); i++ }
            out += Block.Table(rows); continue
        }
        val b = bulletRe.find(line)
        if (b != null) {
            flushPara()
            out += Block.ListItem(false, "\u2022", b.groupValues[3], b.groupValues[1].length / 2); i++; continue
        }
        val o = orderedRe.find(line)
        if (o != null) {
            flushPara()
            out += Block.ListItem(true, o.groupValues[2] + ".", o.groupValues[3], o.groupValues[1].length / 2); i++; continue
        }
        // continuation of a list item: indented text right after one
        val last = out.lastOrNull()
        if (para.isEmpty() && last is Block.ListItem && line.startsWith("  ")) {
            out[out.size - 1] = last.copy(text = last.text + "\n" + line.trim()); i++; continue
        }
        if (para.isNotEmpty()) para.append('\n')
        para.append(line)
        i++
    }
    flushPara()
    return out
}

private fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

/** Inline formatting: `code`, **bold**, *italic*, _italic_, ~~strike~~, [text](url), \(math\), $math$. */
fun inlineMarkdown(
    text: String,
    codeStyle: SpanStyle,
    /** Given LaTeX source, returns an inline-content id if it can be rendered as an image. */
    renderMath: ((String) -> String?)? = null,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    val mathStyle = SpanStyle(fontStyle = FontStyle.Italic)
    fun math(src: String) {
        val id = renderMath?.invoke(src)
        if (id != null) appendInlineContent(id, latexToText(src))
        else withStyle(mathStyle) { append(latexToText(src)) }
    }
    while (i < n) {
        val c = text[i]
        when {
            text.startsWith("\\(", i) -> {
                val end = text.indexOf("\\)", i + 2)
                if (end > i) {
                    math(text.substring(i + 2, end))
                    i = end + 2; continue
                }
            }
            text.startsWith("\\[", i) -> {
                val end = text.indexOf("\\]", i + 2)
                if (end > i) {
                    math(text.substring(i + 2, end))
                    i = end + 2; continue
                }
            }
            c == '$' && i + 1 < n && !text[i + 1].isWhitespace() && text[i + 1] != '$' -> {
                val end = text.indexOf('$', i + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace() && (end + 1 >= n || !text[end + 1].isDigit())) {
                    math(text.substring(i + 1, end))
                    i = end + 1; continue
                }
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(codeStyle) { append(text, i + 1, end) }
                    i = end + 1; continue
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineMarkdown(text.substring(i + 2, end), codeStyle, renderMath)) }
                    i = end + 2; continue
                }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(text, i + 2, end) }
                    i = end + 2; continue
                }
            }
            (c == '*' || c == '_') && i + 1 < n && !text[i + 1].isWhitespace() -> {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1 && !text[end - 1].isWhitespace() && (c == '*' || end + 1 >= n || !text[end + 1].isLetterOrDigit())) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMarkdown(text.substring(i + 1, end), codeStyle, renderMath)) }
                    i = end + 1; continue
                }
            }
            c == '[' -> {
                val close = text.indexOf("](", i)
                val end = if (close > 0) text.indexOf(')', close) else -1
                if (close > i && end > close) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(text, i + 1, close) }
                    i = end + 1; continue
                }
            }
        }
        append(c)
        i++
    }
}

private fun AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val start = length
    block()
    addStyle(style, start, length)
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier) { blocks.forEach { RenderBlock(it) } }
}

/**
 * While streaming, everything before the last blank line is stable and cached;
 * only the tail is re-parsed on each token.
 */
@Composable
fun StreamingMarkdown(text: String, modifier: Modifier = Modifier) {
    val cut = text.lastIndexOf("\n\n")
    // An odd number of fences means we are inside a code block that spans the cut.
    val inFence = fenceLineRe.findAll(text.substring(0, maxOf(cut, 0))).count() % 2 == 1
    if (cut <= 0 || inFence) {
        MarkdownText(text, modifier)
        return
    }
    val head = text.substring(0, cut)
    val tail = text.substring(cut + 2)
    Column(modifier) {
        MarkdownText(head)
        MarkdownText(tail)
    }
}

@Composable
private fun RenderBlock(block: Block) {
    when (block) {
        is Block.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            RichText(block.text, style = style, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        }
        is Block.Paragraph -> RichText(
            block.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 3.dp),
        )
        is Block.ListItem -> Row(Modifier.padding(start = (16 * block.indent).dp, top = 2.dp, bottom = 2.dp)) {
            Text(block.marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(24.dp))
            RichText(block.text, style = MaterialTheme.typography.bodyLarge)
        }
        is Block.Quote -> Row(Modifier.padding(vertical = 4.dp)) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.width(8.dp))
            RichText(
                block.text,
                style = MaterialTheme.typography.bodyLarge,
                color = LocalContentColor.current.copy(alpha = 0.8f),
            )
        }
        is Block.Code -> CodeBlock(block)
        is Block.Table -> {
            val widths = IntArray(block.rows.maxOf { it.size }) { col -> block.rows.maxOf { it.getOrNull(col)?.length ?: 0 } }
            val rendered = block.rows.joinToString("\n") { row ->
                widths.indices.joinToString("  ") { c -> (row.getOrNull(c) ?: "").padEnd(widths[c]) }
            }
            Text(
                rendered,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
            )
        }
        is Block.Math -> MathBlock(block.text)
        Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun CodeBlock(block: Block.Code) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
            Text(
                block.lang.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp).weight(1f),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) { Text("Copy") }
        }
        Text(
            block.code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}
