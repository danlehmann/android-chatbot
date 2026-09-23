package net.daniellehmann.localchat.ui

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable

/*
 * LaTeX rendering via JLaTeXMath (offline, pure Java, no permissions).
 * Formulas that fail to parse, e.g. half-streamed ones, fall back to the
 * Unicode approximation from latexToText().
 */

private val cache = LruCache<String, Drawable>(256)
private val failed = LruCache<String, Boolean>(256)

/** Returns a rendered formula, or null if JLaTeXMath cannot parse it. */
fun mathDrawable(latex: String, textSizePx: Float, argb: Int): Drawable? {
    val key = "$textSizePx|$argb|$latex"
    cache.get(key)?.let { return it }
    if (failed.get(key) != null) return null
    return try {
        JLatexMathDrawable.builder(latex)
            .textSize(textSizePx)
            .color(argb)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
            .also { cache.put(key, it) }
    } catch (e: Throwable) {
        failed.put(key, true)
        null
    }
}

@Composable
private fun DrawableBox(d: Drawable, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val w = d.intrinsicWidth
    val h = d.intrinsicHeight
    Canvas(modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
        d.setBounds(0, 0, w, h)
        drawIntoCanvas { d.draw(it.nativeCanvas) }
    }
}

@Composable
fun MathBlock(latex: String) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyLarge
    val px = with(density) { style.fontSize.toPx() } * 1.15f
    val argb = LocalContentColor.current.toArgb()
    val d = remember(latex, px, argb) { mathDrawable(latex, px, argb) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (d != null) {
            // Wide formulas scroll sideways instead of being clipped.
            Box(Modifier.horizontalScroll(rememberScrollState())) { DrawableBox(d) }
        } else {
            Text(latexToText(latex), style = style, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center)
        }
    }
}

/** Text with inline markdown and inline math rendered as images. */
@Composable
fun RichText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val density = LocalDensity.current
    val contentColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val argb = contentColor.toArgb()
    val px = with(density) { style.fontSize.toPx() }
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        background = MaterialTheme.colorScheme.surfaceVariant,
    )
    val (annotated, drawables) = remember(text, px, argb, codeStyle) {
        val found = LinkedHashMap<String, Drawable>()
        val a = inlineMarkdown(text, codeStyle) { latex ->
            mathDrawable(latex, px, argb)?.let { d -> "math${found.size}".also { found[it] = d } }
        }
        a to found
    }
    val inline = drawables.mapValues { (_, d) ->
        InlineTextContent(
            // Size relative to the font (em) so it matches the px size the formula was drawn
            // at, even with Android's non-linear font scaling at large system font sizes.
            Placeholder(
                width = (d.intrinsicWidth / px).em,
                height = (d.intrinsicHeight / px).em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) { DrawableBox(d) }
    }
    Text(annotated, style = style, color = contentColor, inlineContent = inline, modifier = modifier)
}
