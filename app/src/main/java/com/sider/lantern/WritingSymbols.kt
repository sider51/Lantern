package com.sider.lantern

internal enum class WritingCategory(val label: Int) {
    Symbols(R.string.symbols), Formatting(R.string.formatting), Editing(R.string.editing),
}

internal val bracketPairs = linkedMapOf('「' to '」', '『' to '』', '（' to '）', '【' to '】')

// Null means this was not an unambiguous direct opener. Returning only the opener reuses a close.
internal fun completedBracket(input: CharSequence, body: CharSequence, start: Int, end: Int): String? {
    val opener = input.singleOrNull() ?: return null
    val close = bracketPairs[opener] ?: return null
    if (start !in 0..end || end > body.length || graphemeOffset(body, start) != start || graphemeOffset(body, end) != end) {
        return null
    }
    return if (body.getOrNull(end) == close) opener.toString() else "$opener$close"
}
