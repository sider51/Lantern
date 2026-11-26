package com.sider.lantern

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import androidx.text.vertical.RubySpan
import androidx.text.vertical.TextOrientationSpan
import androidx.text.vertical.VerticalTextLayout
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class SpacedVerticalPage(private val columns: List<VerticalTextLayout>, private val columnGap: Float) {
    val width = columns.sumOf { it.width.toDouble() }.toFloat() + (columns.size - 1).coerceAtLeast(0) * columnGap
    fun draw(canvas: Canvas, right: Float, top: Float) {
        var x = right
        columns.forEach { column ->
            column.draw(canvas, x, top)
            x -= column.width + columnGap
        }
    }
}

@SuppressLint("RestrictedApi")
internal fun spacedVerticalPage(
    text: Spannable,
    paint: TextPaint,
    height: Int,
    characterGap: Float,
    columnGap: Float,
): SpacedVerticalPage? {
    val gap = characterGap.roundToInt()
    var naturalHeight = height.toFloat()
    // Break unmodified text first so spacing characters never take part in Japanese line breaking.
    repeat(6) {
        if (naturalHeight < 1f) return null
        val natural = VerticalTextLayout(text = text, paint = paint, height = naturalHeight)
        val columns = List(natural.lineCount) { index ->
            val from = natural.getLineStart(index)
            val until = natural.getLineEnd(index)
            val column = SpannableStringBuilder(text, from, until)
            if (gap > 0) addVerticalGaps(column, gap)
            VerticalTextLayout(text = column, paint = paint, height = 100_000f)
        }
        val largest = columns.maxOfOrNull { it.contentHeight } ?: 0f
        if (columns.all { it.lineCount == 1 } && largest <= height + .01f) {
            return SpacedVerticalPage(columns, columnGap)
        }
        naturalHeight -= ceil((largest - height).coerceAtLeast(1f))
    }
    return null
}

private fun addVerticalGaps(text: SpannableStringBuilder, gap: Int) {
    val edges = boundaries(text.toString())
    val atomic = text.getSpans(0, text.length, RubySpan::class.java).asList() +
        text.getSpans(0, text.length, TextOrientationSpan.CombineUpright::class.java).asList()
    val ranges = atomic.map { text.getSpanStart(it) until text.getSpanEnd(it) }
    for (offset in text.lastIndex downTo 1) {
        if (!edges[offset] || text[offset].isWhitespace() || text[offset - 1].isWhitespace()) continue
        if (ranges.any { offset > it.first && offset <= it.last }) continue
        // Keep joined scripts and Latin words shaped together.
        val before = Character.UnicodeScript.of(Character.codePointBefore(text, offset))
        val after = Character.UnicodeScript.of(Character.codePointAt(text, offset))
        if (before == after && before !in verticalScripts && before != Character.UnicodeScript.COMMON) continue
        text.insert(offset, "　")
        text.setSpan(AbsoluteSizeSpan(gap), offset, offset + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

private val verticalScripts = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
