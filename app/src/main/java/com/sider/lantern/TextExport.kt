package com.sider.lantern

import kotlinx.serialization.Serializable

internal enum class TextTarget(val label: String) {
    Plain(""), Pixiv("pixiv"), X("X"), Kakuyomu("カクヨム"),
    Narou("小説家になろう"), Hameln("ハーメルン"), Everystar("エブリスタ"),
}

@Serializable
internal data class TextExportOptions(
    val target: TextTarget = TextTarget.Plain,
    val title: Boolean = true,
    val indent: Boolean = false,
    val compactBlanks: Boolean = false,
)

internal data class ExportedText(val text: String, val omitted: Set<OmittedStyle>)

// Narou also treats parenthesized kana after kanji as implicit ruby.
internal enum class OmittedStyle(val label: Int) {
    Ruby(R.string.ruby), Emphasis(R.string.emphasis), Bold(R.string.bold),
    Italic(R.string.italic), PageBreak(R.string.page_break)
}

private val narouLiteralParentheses = Regex("(?<=[\\p{IsHan}々])(?=[（(][ぁ-ゖァ-ヺー]+[）)])")

internal fun exportText(document: Document, options: TextExportOptions): ExportedText {
    document.validated()
    val target = options.target
    val missing = linkedSetOf<OmittedStyle>()
    val annotations = document.annotations.sortedWith(compareBy({ it.start }, { it.end }))
    val ranges = sortedSetOf(0, document.body.length).apply {
        addAll(document.pageBreaks)
        annotations.forEach { add(it.start); add(it.end) }
        document.body.forEachIndexed { index, char -> if (char == '\n' || char == '\r') { add(index); add(index + 1) } }
    }.toList()
    val rubyAt = annotations.filter { it.format == Format.Ruby }.associateBy(Annotation::start)
    val formats = Format.entries.associateWith { format -> annotations.filter { it.format == format } }
    val offsets = mutableMapOf<Format, Int>()
    val breaks = document.pageBreaks.toSet()
    val hasHamelnTags = target == TextTarget.Hameln && annotations.any { it.format != Format.Emphasis }
    fun fallback(span: Annotation) {
        missing += when (span.format) {
            Format.Ruby -> OmittedStyle.Ruby
            Format.Emphasis -> OmittedStyle.Emphasis
            Format.Bold -> OmittedStyle.Bold
            Format.Italic -> OmittedStyle.Italic
        }
    }
    fun ruby(base: String, reading: String): String? {
        if (target == TextTarget.Pixiv && (base + reading).any { it in "[]>[" }) return null
        if (target != TextTarget.Pixiv && (base + reading).any { it in "｜|《》" }) return null
        val baseLength = base.codePointCount(0, base.length)
        val readingLength = reading.codePointCount(0, reading.length)
        return when (target) {
            TextTarget.Pixiv -> "[[rb:$base > $reading]]"
            TextTarget.Kakuyomu, TextTarget.Everystar -> if (baseLength <= 20 && readingLength <= 50) "｜$base《$reading》" else null
            TextTarget.Narou -> if (baseLength <= 10 && readingLength <= 10 && (base + reading).none { it in "&\"<>" }) "｜$base《$reading》" else null
            TextTarget.Hameln -> "《ruby》$base《rt》$reading《/rt》《/ruby》"
            else -> null
        }
    }
    fun plain(value: String) = when (target) {
        TextTarget.Kakuyomu, TextTarget.Everystar -> value.replace("《", "｜《")
        TextTarget.Narou -> value.replace(narouLiteralParentheses, "｜")
        else -> value
    }
    fun style(span: Annotation, value: String): String? = when (span.format) {
        Format.Bold, Format.Italic -> {
            val tag = if (span.format == Format.Bold) "b" else "i"
            when {
                target == TextTarget.Pixiv && value.none { it == '[' || it == ']' } -> "[$tag:$value]"
                target == TextTarget.Hameln && '《' !in value -> "《$tag》$value《/$tag》"
                else -> null
            }
        }
        Format.Emphasis -> when {
            target == TextTarget.Pixiv && value.none { it in "[]>[" } -> "[[emphasismark:$value>﹅]]"
            target in setOf(TextTarget.Kakuyomu, TextTarget.Everystar) && value.none { it in "《》" } -> "《《$value》》"
            target == TextTarget.Hameln && !hasHamelnTags && value.none { it in "《》" } -> "《《$value》》"
            else -> null
        }
        Format.Ruby -> null
    }
    val text = buildString {
        if (options.title && document.title.isNotEmpty()) append(plain(document.title)).append("\n\n")
        var index = 0
        var atBeginning = true
        while (index < ranges.lastIndex) {
            val start = ranges[index]
            var end = ranges[index + 1]
            if (start in breaks) {
                if (target == TextTarget.Pixiv) append("\n[newpage]\n") else missing += OmittedStyle.PageBreak
            }
            val rubySpan = rubyAt[start]
            val rubyText = rubySpan?.let { ruby(document.body.substring(start, it.end), checkNotNull(it.reading)) }
            if (rubyText != null) end = checkNotNull(rubySpan).end
            val body = document.body.substring(start, end)
            val active = formats.mapNotNull { (format, spans) ->
                var cursor = offsets[format] ?: 0
                while (cursor < spans.size && spans[cursor].end <= start) cursor++
                offsets[format] = cursor
                spans.getOrNull(cursor)?.takeIf { it.start < end }
            }
            if (options.indent && atBeginning && body.firstOrNull()?.let { it !in "　 \t\r\n「『" } == true) append('　')
            if (rubyText != null) {
                append(rubyText)
                active.filter { it.format != Format.Ruby }.forEach(::fallback)
            } else if (body.any { it == '\n' || it == '\r' }) {
                append(body)
            } else {
                val styled = active.map { span -> span to style(span, body) }
                val chosen = styled.firstOrNull { it.second != null }
                styled.filter { it != chosen }.forEach { fallback(it.first) }
                append(chosen?.second ?: plain(body))
            }
            atBeginning = body.endsWith('\n') || body.endsWith('\r')
            do { index++ } while (ranges[index] < end)
        }
    }.let { if (options.compactBlanks) it.replace(Regex("(?:\\r\\n|\\r|\\n)(?:[ \t　]*(?:\\r\\n|\\r|\\n)){2,}"), "\n\n") else it }
    return ExportedText(text, missing)
}
