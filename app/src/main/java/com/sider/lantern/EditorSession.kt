package com.sider.lantern

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class RubyTarget(
    val documentId: String,
    val revision: Long,
    val position: EditorPosition,
    val text: String,
    val existing: Annotation?,
)

internal class EditorSession(
    initial: Document = sampleDocument(),
    initialPosition: EditorPosition = EditorPosition(),
    private val onCommitted: (Document) -> Unit = {},
) {
    private data class ReportedEdit(val before: String, val after: String, val changes: List<TextChange>)

    private val edits = ArrayDeque<ReportedEdit>()
    private var history = EditorHistory(Revision(initial, initialPosition))
    private var revision = 0L
    var visible by mutableStateOf(history.current)
        private set
    var rubyTarget by mutableStateOf<RubyTarget?>(null)
        private set
    var composition by mutableStateOf<EditorPosition?>(null)
        private set
    val committed: Revision get() = history.current
    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    var scroll by mutableIntStateOf(0)

    fun record(before: String, after: String, changes: List<TextChange>) {
        if (before != after) edits.addLast(ReportedEdit(before, after, changes))
    }

    fun observe(body: String, position: EditorPosition, composing: EditorPosition?) {
        var next = visible.document
        while (next.body != body && edits.isNotEmpty()) {
            val edit = edits.removeFirst()
            if (edit.before == next.body) {
                next = runCatching {
                    edit.changes.sortedByDescending { it.start }.fold(next) { document, change -> document.edit(change) }
                        .also { check(it.body == edit.after) }
                }.getOrElse { next.reconcile(edit.after) }
            }
        }
        edits.clear()
        if (next.body != body) next = next.reconcile(body)
        if (composing == null && body == history.current.document.body) next = history.current.document
        publish(Revision(next, position))
        composition = composing
        if (composing == null) commit(visible)
    }

    fun load(minLength: Int): Revision {
        check(composition == null)
        edits.clear()
        history = EditorHistory(Revision(sampleDocument(minLength)))
        scroll = 0
        return publish(history.current)
    }

    fun undo(): Revision = restore(history::undo)
    fun redo(): Revision = restore(history::redo)

    private fun restore(action: () -> Revision): Revision {
        val previous = history.current.document
        val next = publish(action())
        if (next.document != previous) onCommitted(next.document)
        return next
    }

    fun surround(): Revision = apply(symbolEdit("「", "」"))

    fun symbolEdit(symbol: String, close: String? = null): WritingEdit {
        val (start, end) = selection()
        if (close == null) {
            val caret = start + symbol.length
            return WritingEdit(listOf(TextChange(start, end, symbol)), EditorPosition(caret, caret))
        }
        val caret = start + symbol.length
        if (start == end) {
            return WritingEdit(listOf(TextChange(start, end, symbol + close)), EditorPosition(caret, caret))
        }
        require(visible.document.annotations.none {
            it.format == Format.Ruby && (start in (it.start + 1) until it.end || end in (it.start + 1) until it.end)
        }) { "ルビ付きの文字は範囲全体を選択してください。" }
        val position = visible.position.let { EditorPosition(it.start + symbol.length, it.end + symbol.length) }
        return WritingEdit(listOf(TextChange(end, end, close), TextChange(start, start, symbol)), position)
    }

    fun apply(edit: WritingEdit): Revision {
        val document = edit.changes.fold(visible.document, Document::edit)
        val (anchor, focus) = edit.position
        return update(document, EditorPosition(graphemeOffset(document.body, anchor), graphemeOffset(document.body, focus)))
    }

    fun moveCursor(forward: Boolean): Revision {
        val (start, end) = selection()
        val body = visible.document.body
        val target = if (forward) end else start
        val caret = if (start != end) target else graphemeOffset(body, target + if (forward) 1 else -1, forward)
        return publish(Revision(visible.document, EditorPosition(caret, caret))).also(::commit)
    }

    fun canApplyRuby(): Boolean {
        val caret = visible.position
        if (caret.start == caret.end && visible.document.annotations.none {
                it.format == Format.Ruby && caret.start in it.start..it.end
            }) return false
        return runCatching { rubySelection() }.isSuccess
    }

    fun format(format: Format, reading: String? = null, remove: Boolean = false): Revision {
        val (start, end) = selection()
        return applyFormat(format, start, end, reading, remove, visible.position)
    }

    fun toggleFormat(format: Format): Revision {
        require(format != Format.Ruby)
        val (start, end) = selection()
        val remove = visible.document.annotations.any { it.format == format && it.start <= start && it.end >= end }
        return applyFormat(format, start, end, null, remove, visible.position)
    }

    fun beginRuby() {
        rubyTarget = rubySelection()
    }

    private fun rubySelection(): RubyTarget {
        val (start, end) = selection()
        val document = visible.document
        val candidates = document.annotations.filter {
            it.format == Format.Ruby && if (start == end) start in it.start..it.end else it.start < end && it.end > start
        }
        val existing = candidates.singleOrNull()
        require(candidates.isEmpty() || (existing != null && start >= existing.start && end <= existing.end)) {
            "ルビを一つずつ選択してください"
        }
        val position = existing?.let { EditorPosition(it.start, it.end) } ?: EditorPosition(start, end)
        require(position.start < position.end) { "ルビを付ける文字を選択してください" }
        val text = document.body.substring(position.start, position.end)
        require('\n' !in text && '\r' !in text) { "ルビは同じ段落内の文字を選択してください" }
        return RubyTarget(document.id, revision, position, text, existing)
    }

    fun applyRuby(reading: String, remove: Boolean): Revision {
        val target = checkNotNull(rubyTarget)
        val document = visible.document
        check(composition == null) { "本文の変換を確定してください" }
        val (start, end) = target.position
        check(document.id == target.documentId && revision == target.revision &&
            document.body.regionMatches(start, target.text, 0, end - start) &&
            document.annotations.singleOrNull { it.format == Format.Ruby && it.start == start && it.end == end } == target.existing
        ) { "原稿が変更されました。対象を選び直してください" }
        require(!remove || target.existing != null)
        val next = applyFormat(Format.Ruby, start, end, reading.takeUnless { remove }, remove, target.position)
        rubyTarget = null
        return next
    }

    fun cancelRuby(): EditorPosition? {
        val target = rubyTarget
        rubyTarget = null
        return target?.takeIf { it.documentId == visible.document.id && it.revision == revision }?.position
    }

    private fun applyFormat(
        format: Format, start: Int, end: Int, reading: String?, remove: Boolean, position: EditorPosition,
    ): Revision {
        require(start < end) { "書式を適用する文字を選択してください" }
        val document = visible.document
        val matching = document.annotations.filter { it.format == format && it.start < end && it.end > start }
        val new = document.annotations - matching.toSet()
        val tails = if (format == Format.Ruby) emptyList() else matching.flatMap {
            buildList {
                if (it.start < start) add(it.copy(end = start))
                if (end < it.end) add(it.copy(start = end))
            }
        }
        val added = if (remove) emptyList() else listOf(Annotation(format, start, end, reading))
        return update(document.copy(annotations = new + tails + added), position)
    }

    fun pageBreak(): Revision {
        val (start, end) = selection()
        require(start == end) { "改ページ位置へカーソルを移動してください" }
        val document = visible.document
        val offsets = if (start in document.pageBreaks) document.pageBreaks - start
        else (document.pageBreaks + start).sorted()
        return update(document.copy(pageBreaks = offsets))
    }

    private fun selection(): Pair<Int, Int> {
        check(composition == null) { "日本語変換を確定してから操作してください" }
        val (anchor, focus) = visible.position
        val body = visible.document.body
        require(isGraphemeBoundary(body, anchor) && isGraphemeBoundary(body, focus)) {
            "文字のまとまりを壊さない範囲を選択してください"
        }
        return minOf(anchor, focus) to maxOf(anchor, focus)
    }

    private fun update(document: Document, position: EditorPosition = visible.position): Revision {
        document.validated()
        return publish(Revision(document, position)).also(::commit)
    }

    private fun commit(next: Revision) {
        val changed = next.document != history.current.document
        history.update(next)
        if (changed) onCommitted(next.document)
    }

    private fun publish(next: Revision): Revision {
        if (next.document != visible.document) revision++
        visible = next
        return next
    }
}

internal data class WritingEdit(val changes: List<TextChange>, val position: EditorPosition)
