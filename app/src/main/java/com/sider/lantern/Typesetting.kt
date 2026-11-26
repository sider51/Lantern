package com.sider.lantern

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreakConfig
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.StyleSpan
import androidx.text.vertical.EmphasisSpan
import androidx.text.vertical.FontShearSpan
import androidx.text.vertical.RubySpan
import androidx.text.vertical.TextOrientationSpan
import androidx.text.vertical.VerticalTextLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import java.io.File

internal fun Document.applySpans(
    target: Spannable,
    start: Int = 0,
    end: Int = body.length,
    vertical: Boolean = false,
    verticalDigits: List<IntRange> = emptyList(),
    installed: MutableList<Any>? = null,
) {
    fun add(span: Any, from: Int, until: Int) {
        target.setSpan(span, from - start, until - start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        installed?.add(span)
    }
    val ruby = annotations.filter { it.format == Format.Ruby }
    val emphasis = annotations.filter { it.format == Format.Emphasis }
    for (span in annotations) {
        val from = maxOf(start, span.start)
        val until = minOf(end, span.end)
        if (from >= until) continue
        when (span.format) {
            Format.Ruby -> {
                check(from == span.start && until == span.end)
                add(RubySpan(checkNotNull(span.reading)), from, until)
            }
            Format.Emphasis -> {
                val edges = boundaries(body.substring(from, until))
                var clusterStart = from
                for (offset in from + 1..until) {
                    if (!edges[offset - from]) continue
                    val overlapsRuby = ruby.any { it.start < offset && it.end > clusterStart }
                    if (vertical || !overlapsRuby) {
                        add(EmphasisSpan(), clusterStart, offset)
                    }
                    clusterStart = offset
                }
            }
            Format.Bold -> add(StyleSpan(Typeface.BOLD), from, until)
            Format.Italic -> add(if (vertical) FontShearSpan() else StyleSpan(Typeface.ITALIC), from, until)
        }
    }
    for (range in verticalDigits) {
        val from = range.first
        val until = range.last + 1
        if (from < start) continue
        if (until > end) break
        if (ruby.none { it.start < until && it.end > from } && emphasis.none { it.start < until && it.end > from }) {
            add(TextOrientationSpan.CombineUpright(), from, until)
        }
    }
}

internal class PageRenderer(
    private val document: Document,
    private val vertical: Boolean,
    private val typeface: Typeface,
    private val options: ImageOptions = ImageOptions(vertical = vertical),
    private val watermark: Bitmap? = null,
) {
    init { options.validated() }
    val width = options.width
    val height = options.height
    private val header = (if (options.title || options.author.isNotBlank()) options.size else 0)
    private val footer = if (options.pageNumber) options.size else 0
    private val contentWidth = width - options.left - options.right
    private val contentHeight = height - options.top - options.bottom - header - footer
    private val digits = if (vertical) uprightRanges(document.body) else emptyList()
    private val renderBody =
        if (vertical && Build.VERSION.SDK_INT >= 36) {
            val glyphProbe = TextPaint().apply {
                this.typeface = this@PageRenderer.typeface
                textLocale = japaneseStrict
            }
            verticalPunctuation(document.body, glyphProbe::hasGlyph)
        } else {
            document.body
        }

    fun pages(): List<PageSlice> {
        require(contentHeight >= options.size) { "余白を減らすか解像度を上げてください" }
        return paginate(document) { start, end -> layout(start, end).fits }
    }

    fun render(page: PageSlice, number: Int = 1): Bitmap {
        val layout = layout(page.start, page.end)
        check(layout.fits) { "組版がページ寸法を超えます" }
        return createBitmap(width, height).apply {
            val canvas = Canvas(this)
            canvas.drawColor(options.background)
            layout.draw(canvas)
            drawFurniture(canvas, number)
        }
    }

    private fun drawFurniture(canvas: Canvas, number: Int) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = options.foreground
            typeface = this@PageRenderer.typeface
            textSize = options.size * .53f
        }
        if (header > 0) {
            val title = listOf(document.title.takeIf { options.title }.orEmpty(), options.author)
                .map { it.replace(Regex("[\\r\\n]+"), " ") }.filter { it.isNotBlank() }.joinToString("　")
            canvas.drawText(TextUtils.ellipsize(title, paint, contentWidth.toFloat(), TextUtils.TruncateAt.END).toString(),
                options.left.toFloat(), options.top - paint.ascent(), paint)
        }
        if (footer > 0) {
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(number.toString(), width / 2f, height - options.bottom - paint.descent(), paint)
        }
        if (options.watermark.isEmpty() && watermark == null) return
        val side = minOf(width, height) * options.watermarkSize / 100f
        val right = options.watermarkPosition % 2 == 1
        val bottom = options.watermarkPosition >= 2
        val inset = minOf(width, height) / 32f
        paint.alpha = options.watermarkOpacity * 255 / 100
        paint.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        val x = if (right) width - inset else inset
        val y = if (bottom) height - inset else inset
        watermark?.let {
            val scale = side / maxOf(it.width, it.height)
            val w = it.width * scale
            val h = it.height * scale
            val left = if (right) x - w else x
            val top = if (bottom) y - h else y
            canvas.drawBitmap(it, null, RectF(left, top, left + w, top + h), paint)
        }
        if (options.watermark.isNotEmpty()) {
            paint.textSize = (side * .24f).coerceAtLeast(12f)
            val label = TextUtils.ellipsize(options.watermark.replace(Regex("[\\r\\n]+"), " "), paint,
                (width - 2 * inset).coerceAtLeast(1f), TextUtils.TruncateAt.END)
            canvas.drawText(label.toString(), x, if (bottom) y - paint.descent() else y - paint.ascent(), paint)
        }
    }

    private data class PageLayout(val fits: Boolean, val draw: (Canvas) -> Unit)

    @SuppressLint("RestrictedApi")
    private fun layout(start: Int, end: Int): PageLayout {
        if (start == end) return PageLayout(true) {}
        val text = SpannableString(renderBody.substring(start, end))
        document.applySpans(text, start, end, vertical, digits)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = options.size.toFloat()
            color = options.foreground
            textLocale = japaneseStrict
            typeface = this@PageRenderer.typeface
        }
        if (vertical) {
            val right = (width - options.right).toFloat()
            val top = (options.top + header).toFloat()
            if (options.letterSpacing == 0 && options.lineSpacing == 0) {
                val layout = VerticalTextLayout(text = text, paint = paint, height = contentHeight.toFloat())
                return PageLayout(layout.width <= contentWidth && layout.contentHeight <= contentHeight + .01f) {
                    layout.draw(it, right, top)
                }
            }
            val layout = spacedVerticalPage(text, paint, contentHeight,
                options.size * options.letterSpacing / 100f, options.size * options.lineSpacing / 100f)
                ?: return PageLayout(false) {}
            return PageLayout(layout.width <= contentWidth) { layout.draw(it, right, top) }
        }
        if (Build.VERSION.SDK_INT >= 35) paint.letterSpacing = options.letterSpacing / 100f
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setLineSpacing(options.size * options.lineSpacing / 100f, 1f)
        if (Build.VERSION.SDK_INT >= 33) builder.setLineBreakConfig(
            LineBreakConfig.Builder().setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT).build(),
        )
        val layout = builder.build()
        val fits = layout.height <= contentHeight &&
            (0 until layout.lineCount).all { layout.getLineMax(it) <= contentWidth }
        return PageLayout(fits) { canvas ->
            canvas.withTranslation(options.left.toFloat(), (options.top + header).toFloat()) { layout.draw(this) }
        }
    }
}

internal fun savePng(bitmap: Bitmap, destination: File) {
    check(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
    destination.outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG を生成できませんでした" }
    }
}
