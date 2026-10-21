package org.polyfrost.evergreenhud.client.script

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.jetbrains.skia.Font as SkiaFont
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.polyfrost.compose.composables.PolyBox
import org.polyfrost.compose.composables.PolyCanvas
import org.polyfrost.compose.composables.PolyModifier
import org.polyfrost.compose.composables.absoluteAt
import org.polyfrost.compose.composables.align
import org.polyfrost.compose.composables.padding
import org.polyfrost.compose.composables.size
import org.polyfrost.compose.layout.PolyInsets
import org.polyfrost.compose.mc.McFontQueue
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.RenderContext
import org.polyfrost.oneconfig.api.hud.v1.Font
import org.polyfrost.oneconfig.api.hud.v1.Hud
import org.polyfrost.oneconfig.api.ui.v1.item.PolyItemIcon

private const val POPPINS_SIZE = 8f

/** Minecraft glyphs reach 7 pixels above the baseline, and its lines are 9 tall */
private const val MC_ASCENT = 7f
private const val MC_ICON = 8f
private const val MC_UNDERLINE = 8f
private const val MC_STRIKETHROUGH = 3.5f

private val iconPaint = Paint()

private class PlacedText(
    val text: String,
    val x: Float,
    val baseline: Float,
    val width: Float,
    val scale: Float,
    val style: ScriptStyle,
    val font: SkiaFont?,
)

private class PlacedIcon(val source: IconSource, val x: Float, val y: Float, val size: Float)

private class ScriptLayout(
    val width: Float,
    val height: Float,
    val texts: List<PlacedText>,
    val icons: List<PlacedIcon>,
)

private class Measured(val segment: ScriptSegment, val width: Float, val ascent: Float, val descent: Float, val font: SkiaFont?, val text: String, val iconSize: Float)

private class LineMetrics(val cells: List<List<Measured>>, val ascent: Float, val descent: Float)

@Composable
fun Hud.ScriptContent(output: ScriptOutput, lineGap: Float) {
    val layout = layout(output, lineGap * textScale)
    val padInsets = PolyInsets(padLeft, padTop, padRight, padBottom)
    val isStaticValid = staticWidth && staticW > 0f && staticH > 0f
    val bgModifier = hudBackground()
    val outerModifier =
        if (isStaticValid) bgModifier.size(staticW, staticH).padding(padInsets)
        else bgModifier.padding(padInsets)
    val contentModifier = PolyModifier.size(layout.width, layout.height)

    PolyBox(modifier = outerModifier) {
        PolyBox(modifier = if (isStaticValid) contentModifier.align(alignment) else contentModifier) {
            PolyCanvas(contentModifier.absoluteAt(0f, 0f)) { x, y, _, _ -> draw(this@ScriptContent, layout, x, y) }
            for ((index, icon) in layout.icons.withIndex()) {
                val source = icon.source as? ItemIconSource ?: continue
                key(index) {
                    val modifier = PolyModifier.absoluteAt(icon.x, icon.y)
                    val stack = source.stack
                    if (stack != null) PolyItemIcon(stack, icon.size, modifier, decorations = false)
                    else PolyItemIcon(source.id, icon.size, modifier)
                }
            }
        }
    }
}

private fun Hud.textAscent(scale: Float, font: SkiaFont?): Float = font?.let { -it.metrics.ascent } ?: (MC_ASCENT * scale)

private fun Hud.textDescent(scale: Float, font: SkiaFont?): Float =
    font?.metrics?.descent ?: (McFontQueue.measureTextHeight(scale) - MC_ASCENT * scale)

private fun Hud.poppins(scale: Float, bold: Boolean, italic: Boolean): SkiaFont? {
    if (font != Font.Poppins) return null
    val base = if (bold) "poppins-bold" else getPoppinsFontName().removeSuffix("-italic")
    return FontManager.getFont(POPPINS_SIZE * scale, if (italic) "$base-italic" else base)
}

private fun Hud.lineHeight(scale: Float): Float {
    val font = poppins(scale, textBold, textItalic)
    return textAscent(scale, font) + textDescent(scale, font)
}

private fun Hud.mcText(text: String, style: ScriptStyle): String = buildString {
    if (style.bold || textBold) append("§l")
    if (style.italic || textItalic) append("§o")
    append(text)
}

private fun Hud.measure(segment: ScriptSegment, gap: Float): Measured? = when (segment) {
    is ScriptSegment.Text -> {
        val scale = textScale * segment.style.size
        val text = when (caseType) {
            1 -> segment.text.uppercase()
            2 -> segment.text.lowercase()
            else -> segment.text
        }
        val font = poppins(scale, segment.style.bold || textBold, segment.style.italic || textItalic)
        val width = font?.measureTextWidth(text) ?: McFontQueue.measureTextWidth(mcText(text, segment.style), scale)
        Measured(segment, width, textAscent(scale, font), textDescent(scale, font), font, text, 0f)
    }
    is ScriptSegment.Icon -> {
        val scale = textScale * segment.size
        val font = poppins(scale, bold = false, italic = false)
        val ascent = textAscent(scale, font)
        val single = if (font == null) MC_ICON * scale else ascent
        val size = single + (segment.lines - 1f).coerceAtLeast(0f) * (lineHeight(scale) + gap)
        Measured(segment, size, ascent, minOf(size - ascent, textDescent(scale, font)), null, "", size)
    }
    ScriptSegment.Column -> null
}

private fun Hud.layout(output: ScriptOutput, gap: Float): ScriptLayout {
    val baseFont = poppins(textScale, textBold, textItalic)

    val lines = output.lines.map { segments ->
        val cells = ArrayList<MutableList<Measured>>()
        cells.add(ArrayList())
        for (segment in segments) {
            if (segment == ScriptSegment.Column) cells.add(ArrayList())
            else measure(segment, gap)?.let(cells.last()::add)
        }
        val all = cells.flatten()
        LineMetrics(
            cells,
            all.maxOfOrNull { it.ascent } ?: textAscent(textScale, baseFont),
            all.maxOfOrNull { it.descent } ?: textDescent(textScale, baseFont),
        )
    }

    val columns = FloatArray(lines.maxOfOrNull { it.cells.size } ?: 0)
    for (line in lines) {
        for ((i, cell) in line.cells.withIndex()) columns[i] = maxOf(columns[i], cell.sumOf { it.width.toDouble() }.toFloat())
    }
    val columnStarts = columns.runningFold(0f) { acc, w -> acc + w }

    class Row(val metrics: LineMetrics, val top: Float, val indent: Float, val width: Float)

    val rows = ArrayList<Row>(lines.size)
    var top = 0f
    var floatIndent = 0f
    var floatBottom = 0f
    var height = 0f
    for (metrics in lines) {
        val indent = if (top < floatBottom) floatIndent else 0f
        val cells = metrics.cells
        val width = indent + if (cells.size > 1) columnStarts[cells.size - 1] + cells.last().sumOf { it.width.toDouble() }.toFloat()
        else cells[0].sumOf { it.width.toDouble() }.toFloat()
        rows.add(Row(metrics, top, indent, width))

        val first = cells[0].firstOrNull()
        val leading = first?.segment as? ScriptSegment.Icon
        if (leading != null && leading.lines > 1f) {
            floatBottom = top + metrics.ascent - first.ascent + first.iconSize
            floatIndent = indent + hangingIndent(cells[0], gap)
        }

        val bottom = top + metrics.ascent + metrics.descent
        height = maxOf(height, bottom, floatBottom)
        top = bottom + gap
    }

    val width = rows.maxOfOrNull { it.width } ?: 0f
    val align = when (textAlign) {
        1 -> 0.5f
        2 -> 1f
        else -> 0f
    }

    val texts = ArrayList<PlacedText>()
    val icons = ArrayList<PlacedIcon>()
    for (row in rows) {
        val shift = row.indent + (width - row.width) * align
        val baseline = row.top + row.metrics.ascent
        for ((i, cell) in row.metrics.cells.withIndex()) {
            var x = shift + columnStarts[i]
            for (measured in cell) {
                when (val segment = measured.segment) {
                    is ScriptSegment.Text -> texts.add(
                        PlacedText(measured.text, x, baseline, measured.width, textScale * segment.style.size, segment.style, measured.font)
                    )
                    is ScriptSegment.Icon -> icons.add(PlacedIcon(segment.source, x, baseline - measured.ascent, measured.iconSize))
                    ScriptSegment.Column -> {}
                }
                x += measured.width
            }
        }
    }
    return ScriptLayout(width, height, texts, icons)
}

/**
 * How far lines next to a tall leading icon are pushed in, so they line up with the text written after the icon
 */
private fun Hud.hangingIndent(cell: List<Measured>, gap: Float): Float {
    var indent = cell[0].width
    for (measured in cell.drop(1)) {
        val segment = measured.segment as? ScriptSegment.Text ?: break
        if (measured.text.isBlank()) {
            indent += measured.width
            continue
        }
        val space = segment.text.takeWhile { it.isWhitespace() }
        if (space.isNotEmpty()) indent += measure(ScriptSegment.Text(space, segment.style), gap)?.width ?: 0f
        break
    }
    return indent
}

private fun mcShadow(argb: Int): Int = (argb and 0xFCFCFC shr 2) or (argb and 0xFF000000.toInt())

private fun RenderContext.draw(hud: Hud, layout: ScriptLayout, x: Float, y: Float) {
    val fallback = PolyColor(hud.textColor, hud.textChroma, hud.textChromaSpeed)
    val shadow = if (hud.showShadow) PolyColor(hud.shadowColor, hud.shadowChroma, hud.shadowChromaSpeed) else null

    for (icon in layout.icons) {
        val image = (icon.source as? ImageIconSource)?.image ?: continue
        val src = Rect.makeWH(image.width.toFloat(), image.height.toFloat())
        val downscaled = icon.size * canvas.localToDeviceAsMatrix33.mat[0] < src.right
        canvas.drawImageRect(
            image, src, Rect.makeXYWH(x + icon.x, y + icon.y, icon.size, icon.size),
            if (downscaled) SamplingMode.LINEAR else SamplingMode.DEFAULT, iconPaint, true,
        )
    }

    for (text in layout.texts) {
        val style = text.style
        val color = style.color?.let { PolyColor(it, style.chroma, style.chromaSpeed) } ?: fallback
        val underline = style.underline || hud.textUnderline
        val left = x + text.x
        val baseline = y + text.baseline
        val font = text.font

        if (font != null) {
            val size = POPPINS_SIZE * text.scale
            val underlineAt = font.metrics.underlinePosition ?: (size * 0.08f)
            val thickness = font.metrics.underlineThickness ?: (size * 0.06f)
            val strikeAt = -(font.metrics.capHeight / 2f)
            fun decorate(dx: Float, dy: Float, c: PolyColor) {
                if (underline) line(left + dx, baseline + dy + underlineAt, left + dx + text.width, baseline + dy + underlineAt, c, thickness)
                if (style.strikethrough) line(left + dx, baseline + dy + strikeAt, left + dx + text.width, baseline + dy + strikeAt, c, thickness)
            }
            if (shadow != null) {
                text(text.text, left + hud.shadowOffsetX, baseline + hud.shadowOffsetY, shadow, font)
                decorate(hud.shadowOffsetX, hud.shadowOffsetY, shadow)
            }
            text(text.text, left, baseline, color, font)
            decorate(0f, 0f, color)
        } else {
            val renderer = McFontQueue.renderer ?: continue
            val scale = text.scale
            val top = baseline - MC_ASCENT * scale
            val argb = color.argb
            fun decorate(offset: Float, c: Int) {
                val colorOf = PolyColor(c)
                if (underline) rect(left + offset, top + offset + MC_UNDERLINE * scale, text.width, scale, colorOf)
                if (style.strikethrough) rect(left + offset, top + offset + MC_STRIKETHROUGH * scale, text.width, scale, colorOf)
            }
            if (hud.showShadow) decorate(scale, mcShadow(argb))
            // the font renderer slants italics by the absolute y, so draw each run from its own origin
            save()
            translate(left, top)
            renderer(canvas, hud.mcText(text.text, style), 0f, 0f, argb, hud.showShadow, scale)
            restore()
            decorate(0f, argb)
        }
    }
}
