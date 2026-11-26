package com.sider.lantern

import java.text.BreakIterator
import java.util.Locale

internal val japaneseStrict: Locale = Locale.forLanguageTag("ja-JP-u-lb-strict")
private val uprightDigits = Regex("(?<![0-9])[0-9]{2}(?![0-9])")
internal fun uprightRanges(body: String) = uprightDigits.findAll(body).map { it.range }.toList()

// The Unicode vertical forms have the same UTF-16 length and closing-punctuation line break class.
// The manuscript, annotation offsets, and page boundaries all continue to use the original text.
internal fun verticalPunctuation(body: String, hasGlyph: (String) -> Boolean): String {
    val comma = if (hasGlyph("︑")) '︑' else '、'
    val stop = if (hasGlyph("︒")) '︒' else '。'
    return body.replace('、', comma).replace('。', stop)
}

internal data class PageSlice(val start: Int, val end: Int, val forcedWrap: Boolean = false)

internal fun paginate(document: Document, fits: (start: Int, end: Int) -> Boolean): List<PageSlice> {
    document.validated()
    if (document.body.isEmpty()) return listOf(PageSlice(0, 0))
    val body = document.body
    val edges = boundaries(body)
    for (span in document.annotations.filter { it.format == Format.Ruby }) {
        for (offset in span.start + 1 until span.end) edges[offset] = false
    }
    for (range in uprightRanges(body)) {
        val midpoint = range.first + 1
        if (midpoint !in document.pageBreaks) edges[midpoint] = false
    }
    val lineBreaks = BooleanArray(edges.size)
    val iterator = BreakIterator.getLineInstance(japaneseStrict).apply { setText(body) }
    var offset = iterator.first()
    while (offset != BreakIterator.DONE) {
        lineBreaks[offset] = true
        offset = iterator.next()
    }
    return buildList {
        var start = 0
        for (sectionEnd in document.pageBreaks + body.length) {
            val candidates = (start + 1..sectionEnd).filter { edges[it] }
            var first = 0
            while (start < sectionEnd) {
                fun fitting(index: Int) = fits(start, candidates[index])
                check(fitting(first)) { "ページに収まらないルビ範囲または文字があります（UTF-16 $start）" }
                var low = first
                var step = 32
                var high = (first + step).coerceAtMost(candidates.lastIndex)
                while (high > low && fitting(high)) {
                    low = high
                    step *= 2
                    high = (first + step).coerceAtMost(candidates.lastIndex)
                }
                while (high - low > 1) {
                    val middle = (low + high) / 2
                    if (fitting(middle)) low = middle else high = middle
                }
                val preferred = (low downTo first).firstOrNull {
                    candidates[it] == sectionEnd || (lineBreaks[candidates[it]] && fitting(it))
                }
                val chosen = preferred ?: low
                val end = candidates[chosen]
                add(PageSlice(start, end, preferred == null))
                start = end
                first = chosen + 1
            }
        }
    }
}
