package com.sider.lantern

import java.util.UUID
import java.util.regex.Pattern
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class Format { Ruby, Emphasis, Bold, Italic }

@Serializable
data class Annotation(val format: Format, val start: Int, val end: Int, val reading: String? = null)

@Serializable
data class Document(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val body: String,
    val annotations: List<Annotation>,
    val pageBreaks: List<Int>,
) {
    fun validated(): Document {
        require(schemaVersion == 1)
        require(UUID.fromString(id).toString() == id)
        require(title.isWellFormed() && body.isWellFormed())
        annotations.forEach {
            require(it.start >= 0 && it.start < it.end && it.end <= body.length)
            require(isGraphemeBoundary(body, it.start) && isGraphemeBoundary(body, it.end))
            if (it.format == Format.Ruby) {
                require(!it.reading.isNullOrBlank() && it.reading.isWellFormed() && '\n' !in it.reading && '\r' !in it.reading)
            } else {
                require(it.reading == null)
            }
            if (it.format == Format.Ruby || it.format == Format.Emphasis) {
                require(!body.substring(it.start, it.end).any { c -> c == '\r' || c == '\n' })
            }
        }
        for (format in Format.entries) {
            require(annotations.filter { it.format == format }.sortedBy { it.start }
                .zipWithNext().none { (a, b) -> a.end > b.start })
        }
        require(pageBreaks == pageBreaks.distinct().sorted())
        require(pageBreaks.all { it in 1 until body.length && isGraphemeBoundary(body, it) })
        require(annotations.none { span ->
            span.format == Format.Ruby && pageBreaks.any { it > span.start && it < span.end }
        })
        return this
    }

    companion object {
        fun create(title: String, body: String) =
            Document(1, UUID.randomUUID().toString(), title, body, emptyList(), emptyList())
    }
}

private val grapheme = Pattern.compile("\\X")

internal fun boundaries(text: String): BooleanArray = BooleanArray(text.length + 1).also { result ->
    result[0] = true
    val matcher = grapheme.matcher(text)
    while (matcher.find()) result[matcher.end()] = true
}

internal fun isGraphemeBoundary(text: String, offset: Int): Boolean =
    offset in 0..text.length && graphemeOffset(text, offset) == offset

internal fun graphemeOffset(text: CharSequence, offset: Int, forward: Boolean = false): Int {
    val target = offset.coerceIn(0, text.length)
    if (target == 0 || target == text.length) return target
    // LF ends a grapheme, including CRLF. Matching from just after it retains all context
    // for this paragraph, including combining sequences, ZWJ and regional indicators.
    val start = text.lastIndexOf('\n', target - 1) + 1
    if (start == target) return target
    val newline = text.indexOf('\n', target)
    val end = if (newline == -1) text.length else newline + 1
    val matcher = grapheme.matcher(text).region(start, end)
    var previous = start
    while (matcher.find()) {
        val boundary = matcher.end()
        if (boundary >= target) return if (boundary == target || forward) boundary else previous
        previous = boundary
    }
    return previous
}

internal fun CharSequence.isWellFormed(start: Int = 0, end: Int = length): Boolean = (start until end).all { index ->
    val char = this[index]
    when {
        char.isHighSurrogate() -> index + 1 < end && this[index + 1].isLowSurrogate()
        char.isLowSurrogate() -> index > start && this[index - 1].isHighSurrogate()
        else -> true
    }
}

internal fun CharSequence.splitsSurrogatePair(offset: Int): Boolean =
    offset in 1 until length && this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate()

internal object DocumentJson {
    private val json = Json { encodeDefaults = true }

    fun encode(document: Document): ByteArray =
        json.encodeToString(Document.serializer(), document.validated()).encodeToByteArray()

    fun decode(bytes: ByteArray): Document =
        json.decodeFromString(Document.serializer(), bytes.decodeToString(throwOnInvalidSequence = true)).validated()
}

internal data class TextChange(val start: Int, val end: Int, val inserted: String)

internal fun Document.edit(change: TextChange): Document {
    val (start, end, inserted) = change
    if (body.regionMatches(start, inserted, 0, inserted.length) && end - start == inserted.length) return this
    val updated = body.replaceRange(start, end, inserted)
    require(updated.isWellFormed())
    val delta = inserted.length - (end - start)
    if (annotations.isEmpty() && pageBreaks.isEmpty()) return copy(body = updated)
    val adjusted = annotations.mapNotNull { span ->
        val moved = when {
            end <= span.start -> span.copy(start = span.start + delta, end = span.end + delta)
            start >= span.end -> span
            span.format == Format.Ruby -> null
            start <= span.start && end >= span.end -> null
            else -> span.copy(
                start = if (span.start < start) span.start else start + inserted.length,
                end = if (span.end > end) span.end + delta else start,
            )
        }
        moved?.takeIf {
            it.start < it.end && isGraphemeBoundary(updated, it.start) && isGraphemeBoundary(updated, it.end) &&
                (it.format != Format.Emphasis || updated.substring(it.start, it.end).none { c -> c == '\r' || c == '\n' })
        }
    }
    val breaks = pageBreaks.mapNotNull { offset ->
        when {
            offset < start -> offset
            offset >= end -> offset + delta
            else -> null
        }
    }.filter { it in 1 until updated.length && isGraphemeBoundary(updated, it) }.distinct()
    return copy(body = updated, annotations = adjusted, pageBreaks = breaks)
}

internal fun Document.reconcile(body: String): Document {
    if (this.body == body) return this
    val prefix = this.body.commonPrefixWith(body).length
    val suffix = this.body.commonSuffixWith(body).length
    // Repeated text can make a snapshot diff ambiguous. Invalidate the entire ambiguous range.
    val start = minOf(prefix, this.body.length - suffix, body.length - suffix)
    val tail = minOf(suffix, this.body.length - prefix, body.length - prefix)
    return edit(TextChange(start, this.body.length - tail, body.substring(start, body.length - tail)))
}

internal data class EditorPosition(val start: Int = 0, val end: Int = 0)
internal data class Revision(val document: Document, val position: EditorPosition = EditorPosition())

internal class EditorHistory(initial: Revision) {
    private val past = ArrayDeque<Revision>()
    private val future = ArrayDeque<Revision>()
    var current = initial
        private set
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun update(next: Revision) {
        if (next.document != current.document) {
            if (past.size == 40) past.removeFirst()
            past.addLast(current)
            future.clear()
        }
        current = next
    }

    fun undo(): Revision = restore(from = past, into = future)
    fun redo(): Revision = restore(from = future, into = past)

    private fun restore(from: ArrayDeque<Revision>, into: ArrayDeque<Revision>): Revision {
        val previous = from.removeLastOrNull() ?: return current
        into.addLast(current)
        current = previous
        return current
    }
}

internal fun sampleDocument(minLength: Int = 0): Document {
    val sample = "「薄明の空に、銀河が見えた……」——灯りを消す。\n" +
        "星の数は12。夜のCafe\u0301に、手紙と絵文字👩‍🚀。\n\n" +
        "（本当に？）ページをめくる。まず「文字組み」を確かめる。\n"
    val body = sample.repeat(maxOf(1, (minLength + sample.length - 1) / sample.length))
    fun span(word: String, format: Format, reading: String? = null): Annotation {
        val start = body.indexOf(word)
        return Annotation(format, start, start + word.length, reading)
    }
    return Document.create("灯りの向こう", body).copy(
        annotations = listOf(
            span("薄明", Format.Ruby, "はくめい"),
            span("銀河", Format.Emphasis),
            span("手紙", Format.Bold),
            span("Cafe\u0301", Format.Italic),
        ),
        pageBreaks = listOf(body.indexOf("（")),
    )
}
