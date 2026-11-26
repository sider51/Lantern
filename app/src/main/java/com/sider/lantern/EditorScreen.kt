package com.sider.lantern

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.style.SuggestionSpan
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import android.widget.EditText
import androidx.core.view.doOnLayout
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first

private enum class Group(val label: String) { Samples("原稿"), Formats("書式"), Editing("編集") }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EditorScreen(session: EditorSession, useCompose: Boolean) {
    val initial = remember(session) { session.committed }
    val state = remember(session) {
        TextFieldState(initial.document.body, TextRange(initial.position.start, initial.position.end))
    }
    val scroll = rememberScrollState(session.scroll)
    var native by remember(session) { mutableStateOf<ProbeEditText?>(null) }
    var group by rememberSaveable { mutableStateOf(Group.Samples) }
    var reading by rememberSaveable { mutableStateOf("はくめい") }
    var format by rememberSaveable { mutableStateOf(Format.Ruby) }
    var specialSpans by rememberSaveable { mutableStateOf(true) }
    var status by rememberSaveable { mutableStateOf("") }
    var restoreRubyFocus by rememberSaveable { mutableStateOf(false) }
    val revision = session.visible
    val composing = session.composition
    val colorScheme = MaterialTheme.colorScheme

    fun observeCompose() = session.observe(
        state.text.toString(),
        EditorPosition(state.selection.start, state.selection.end),
        state.composition?.let { EditorPosition(it.start, it.end) },
    )

    fun perform(operation: String? = null, action: () -> Revision) {
        if (useCompose) observeCompose() else native?.observe()
        if (session.composition != null) {
            status = "変換確定後に操作できます"
            return
        }
        status = runCatching {
            val started = SystemClock.elapsedRealtimeNanos()
            val next = action()
            if (useCompose) {
                if (state.text.toString() != next.document.body ||
                    state.selection != TextRange(next.position.start, next.position.end)
                ) {
                    state.edit {
                        if (asCharSequence().toString() != next.document.body) replace(0, length, next.document.body)
                        selection = TextRange(next.position.start, next.position.end)
                    }
                }
            } else {
                native?.show(next, specialSpans)
                if (operation != null && next.document.body.length >= 10_000) {
                    logEditorDuration(operation, next, started)
                }
            }
            ""
        }.getOrElse { it.message ?: "書式または編集操作を適用できませんでした" }
    }

    fun beginRuby() {
        native?.observe()
        status = runCatching {
            restoreRubyFocus = native?.hasFocus() == true
            session.beginRuby()
            native?.suspendForRuby()
            ""
        }.getOrElse { it.message ?: "ルビの対象を選択してください" }
    }

    if (useCompose) {
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.text, state.selection, state.composition) }.collect { observeCompose() }
        }
        LaunchedEffect(scroll) { snapshotFlow { scroll.value }.collect { session.scroll = it } }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(revision.document.title, style = MaterialTheme.typography.titleMedium)
        val editor = Modifier.weight(1f).fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .padding(8.dp)
            .semantics { contentDescription = "本文" }
        if (useCompose) {
            val track = remember(session) {
                InputTransformation {
                    val before = originalText.toString()
                    val after = asCharSequence().toString()
                    if (!after.isWellFormed()) {
                        revertAllChanges()
                        return@InputTransformation
                    }
                    val list = List(changes.changeCount) {
                        val from = changes.getOriginalRange(it)
                        val to = changes.getRange(it)
                        TextChange(from.min, from.max, after.substring(to.min, to.max))
                    }
                    session.record(before, after, list)
                }
            }
            val styles = remember(session) {
                OutputTransformation {
                    val doc = session.visible.document
                    if (doc.body == asCharSequence().toString()) for (annotation in doc.annotations) {
                        val style = when (annotation.format) {
                            Format.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                            Format.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                            else -> continue
                        }
                        addStyle(style, annotation.start, annotation.end)
                    }
                }
            }
            BasicTextField(
                state = state,
                modifier = editor.onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown || !it.isCtrlPressed) return@onPreviewKeyEvent false
                    when (it.key) {
                        Key.Z -> perform { if (it.isShiftPressed) session.redo() else session.undo() }
                        Key.Y -> perform(action = session::redo)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
                scrollState = scroll,
                lineLimits = TextFieldLineLimits.MultiLine(),
                inputTransformation = track,
                outputTransformation = styles,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = true),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colorScheme.onSurface),
                cursorBrush = SolidColor(colorScheme.primary),
            )
        } else {
            AndroidView(
                modifier = editor,
                factory = { context ->
                    ProbeEditText(context, session).also { view ->
                        view.setTextColor(colorScheme.onSurface.toArgb())
                        view.show(initial, specialSpans)
                        view.observe()
                        if (session.rubyTarget != null) view.suspendForRuby()
                        native = view
                    }
                },
                update = { it.setSpecialSpans(specialSpans) },
            )
        }
        val position = revision.position
        Text(
            "${revision.document.body.length} UTF-16 · 選択 ${position.start}–${position.end}" +
                if (composing == null) "" else " · IME ${composing.start}–${composing.end}",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Group.entries.forEach {
                TextButton(onClick = { group = it }, enabled = group != it) { Text(it.label) }
            }
            if (!useCompose) TextButton(onClick = {
                native?.observe()
                if (session.composition == null) specialSpans = !specialSpans else status = "変換確定後に切り替えてください"
            }) { Text(if (specialSpans) "ルビ・傍点 ON" else "ルビ・傍点 OFF") }
        }
        if (group == Group.Formats) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Format.entries.forEach {
                    TextButton(onClick = { format = it }, enabled = format != it) {
                        Text(when (it) {
                            Format.Ruby -> "ルビ"
                            Format.Emphasis -> "傍点"
                            Format.Bold -> "太字"
                            Format.Italic -> "斜体"
                        })
                    }
                }
            }
            if (useCompose && format == Format.Ruby) OutlinedTextField(
                value = reading, onValueChange = { reading = it }, singleLine = true, label = { Text(stringResource(R.string.ruby_reading)) },
            )
        }
        if (group == Group.Formats && useCompose && format in listOf(Format.Ruby, Format.Emphasis)) {
            Text("選択範囲と読みを原稿に記録します。本文上の描画は EditText で比較できます。", style = MaterialTheme.typography.bodySmall)
        }
        HorizontalFloatingToolbar(expanded = true, modifier = Modifier.heightIn(min = 48.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                when (group) {
                    Group.Samples -> {
                        TextButton(onClick = { perform { session.load(0) } }) { Text("短文") }
                        TextButton(onClick = { perform("load") { session.load(10_000) } }) { Text("1万") }
                        TextButton(onClick = { perform("load") { session.load(50_000) } }) { Text("5万") }
                    }
                    Group.Formats -> {
                        if (!useCompose && format == Format.Ruby) {
                            TextButton(onClick = ::beginRuby) { Text("ルビを編集") }
                        } else {
                            TextButton(onClick = {
                                perform { session.format(format, reading.trim().takeIf { format == Format.Ruby }) }
                            }) { Text("適用・変更") }
                            TextButton(onClick = { perform { session.format(format, remove = true) } }) { Text(stringResource(R.string.remove)) }
                        }
                        TextButton(onClick = { perform(action = session::pageBreak) }) { Text("改ページ") }
                    }
                    Group.Editing -> {
                        TextButton(onClick = { perform(action = session::surround) }) { Text("「」") }
                        TextButton(onClick = { perform("undo", session::undo) }) { Text("Undo") }
                        TextButton(onClick = { perform("redo", session::redo) }) { Text("Redo") }
                    }
                }
            }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (group == Group.Editing && useCompose) {
            Text("Compose UndoState · Undo ${state.undoState.canUndo} / Redo ${state.undoState.canRedo}",
                style = MaterialTheme.typography.bodySmall)
        }
        if (group != Group.Samples) {
            val metadata = revision.document.spanSummary()
            if (metadata.isNotEmpty()) Text(metadata, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }

    if (!useCompose) session.rubyTarget?.let { target ->
        RubyDialog(
            target = target,
            onApply = { reading, remove ->
                native?.observe()
                val started = SystemClock.elapsedRealtimeNanos()
                val next = session.applyRuby(reading, remove)
                native?.show(next, specialSpans)
                native?.restoreSelectionAfterDialog(next.position, restoreRubyFocus)
                if (next.document.body.length >= 10_000) {
                    logEditorDuration(if (remove) "ruby-remove" else "ruby-apply", next, started)
                }
            },
            onCancel = {
                val position = session.cancelRuby()
                native?.restoreSelectionAfterDialog(position, restoreRubyFocus)
            },
        )
    }
}

@Composable
internal fun RubyDialog(target: RubyTarget, onApply: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    val resources = LocalResources.current
    val reading = rememberTextFieldState(target.existing?.reading.orEmpty())
    val focus = remember { FocusRequester() }
    var error by rememberSaveable { mutableStateOf("") }
    val composing = reading.composition != null

    fun apply(remove: Boolean) {
        if (reading.composition != null) return
        error = runCatching {
            onApply(reading.text.toString().trim(), remove)
            ""
        }.getOrElse { resources.getString(R.string.ruby_apply_failed) }
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.edit_ruby)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.text, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(
                    state = reading,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text("読み仮名") },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    isError = error.isNotEmpty(),
                    supportingText = when {
                        composing -> ({ Text(stringResource(R.string.confirm_reading)) })
                        error.isNotEmpty() -> ({ Text(error) })
                        else -> null
                    },
                )
                val window = LocalWindowInfo.current
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(window) {
                    snapshotFlow { window.isWindowFocused }.first { it }
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { apply(false) }, enabled = !composing && reading.text.isNotBlank()) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            val focusManager = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            Row {
                if (target.existing != null) {
                    TextButton(onClick = { apply(true) }, enabled = !composing) { Text("解除") }
                }
                TextButton(onClick = {
                    keyboard?.hide()
                    focusManager.clearFocus(force = true)
                    onCancel()
                }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private fun logEditorDuration(operation: String, revision: Revision, started: Long) {
    val micros = (SystemClock.elapsedRealtimeNanos() - started) / 1_000
    Log.i("LanternEditor", "$operation length=${revision.document.body.length} selection=${revision.position.start}–${revision.position.end} syncUs=$micros")
}

private fun Document.spanSummary(): String =
    annotations.take(8).joinToString(" · ") {
        "${it.format} ${it.start}–${it.end}" + (it.reading?.let { reading -> " ($reading)" } ?: "")
    } + if (pageBreaks.isEmpty()) "" else " · 改ページ ${pageBreaks.take(8).joinToString()}"

// This view is created by the AndroidView factory, which supplies the editor session.
@SuppressLint("ViewConstructor")
internal class ProbeEditText(
    context: Context,
    private val session: EditorSession,
    private val measureEdits: Boolean = true,
    initialSpellCheck: Boolean = true,
) : EditText(context) {
    // Nullable so the framework's superclass constructor sees its default behavior.
    private var spellCheck: Boolean? = initialSpellCheck
    var completeBrackets = false
    private var projecting = false
    private var showSpecial = true
    private var displayed = emptyList<Annotation>()
    private val spans = mutableListOf<Any>()
    private val dispatch = Runnable { observe() }
    private var rubyEditing = false
    private var dialogSelection: Pair<EditorPosition?, Boolean>? = null

    init {
        background = null
        gravity = Gravity.START or Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = true
        setPadding(0, 0, 0, 0)
        textSize = 16f
        textLocale = japaneseStrict
        filters = arrayOf(InputFilter { source, start, end, destination, from, until ->
            if ((projecting || !rubyEditing) && source.isWellFormed(start, end) && !destination.splitsSurrogatePair(from) &&
                !destination.splitsSurrogatePair(until)
            ) null else destination.subSequence(from, until)
        })
        val savedScroll = session.scroll
        doOnLayout {
            val viewport = (height - totalPaddingTop - totalPaddingBottom).coerceAtLeast(0)
            val limit = ((layout?.height ?: 0) - viewport).coerceAtLeast(0)
            scrollTo(0, savedScroll.coerceIn(0, limit))
            session.scroll = scrollY
        }
        addTextChangedListener(object : TextWatcher {
            private var before = ""
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (!projecting) before = s.toString()
            }
            override fun onTextChanged(s: CharSequence, start: Int, beforeCount: Int, count: Int) {
                if (!projecting) {
                    session.record(before, s.toString(), listOf(TextChange(start, start + beforeCount, s.subSequence(start, start + count).toString())))
                    scheduleObserve()
                }
            }
            override fun afterTextChanged(s: Editable) = Unit
        })
    }

    fun observe() {
        if (projecting) return
        val editable = text
        if (selectionStart !in 0..editable.length || selectionEnd !in 0..editable.length) return
        val start = BaseInputConnection.getComposingSpanStart(editable)
        val end = BaseInputConnection.getComposingSpanEnd(editable)
        val previousComposition = session.composition
        val started = SystemClock.elapsedRealtimeNanos()
        val body = editable.toString()
        val changed = body != session.visible.document.body
        session.observe(body, EditorPosition(selectionStart, selectionEnd),
            if (start >= 0 && end >= 0) EditorPosition(start, end) else null)
        if (session.composition == null) refresh(session.visible.document, force = previousComposition != null)
        if (measureEdits && changed && body.length >= 10_000) logEditorDuration("observe-edit", session.visible, started)
    }

    override fun isSuggestionsEnabled(): Boolean = spellCheck != false && super.isSuggestionsEnabled()

    fun setSpellCheck(enabled: Boolean) {
        if (spellCheck == enabled) return
        spellCheck = enabled
        if (!enabled) text.getSpans(0, text.length, SuggestionSpan::class.java).forEach(text::removeSpan)
    }

    fun show(revision: Revision, special: Boolean, changes: List<TextChange>? = null, revealCaret: Boolean = false) {
        projecting = true
        try {
            val textChanged = changes != null || text.toString() != revision.document.body
            when {
                changes != null -> changes.forEach { text.replace(it.start, it.end, it.inserted) }
                textChanged -> text.replace(0, text.length, revision.document.body)
            }
            setSelection(revision.position.start, revision.position.end)
            val displayChanged = showSpecial != special
            showSpecial = special
            refresh(revision.document, force = textChanged || displayChanged)
            if (revealCaret && hasFocus()) bringPointIntoView(revision.position.end)
        } finally {
            projecting = false
        }
    }

    fun setSpecialSpans(special: Boolean) {
        if (showSpecial == special) return
        showSpecial = special
        if (session.composition == null) refresh(session.visible.document, force = true)
    }

    fun suspendForRuby() {
        rubyEditing = true
        clearFocus()
    }

    fun restoreSelectionAfterDialog(position: EditorPosition?, restoreFocus: Boolean) {
        dialogSelection = position to restoreFocus
        postOnAnimation { restoreDialogSelection() }
    }

    private fun restoreDialogSelection() {
        if (!hasWindowFocus()) return
        val (position, restoreFocus) = dialogSelection ?: return
        dialogSelection = null
        rubyEditing = false
        if (restoreFocus) requestFocus()
        if (position != null && position.start in 0..text.length && position.end in 0..text.length) {
            setSelection(position.start, position.end)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && dialogSelection != null) postOnAnimation { restoreDialogSelection() }
    }

    private fun refresh(document: Document, force: Boolean) {
        val effective = if (showSpecial) document.annotations
        else document.annotations.filter { it.format == Format.Bold || it.format == Format.Italic }
        if (!force && effective == displayed) return
        spans.forEach(text::removeSpan)
        spans.clear()
        document.copy(annotations = effective).applySpans(text, installed = spans)
        displayed = effective
    }

    private fun scheduleObserve() {
        removeCallbacks(dispatch)
        post(dispatch)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // Selection can change during the superclass constructor.
        if (isAttachedToWindow && !projecting) scheduleObserve()
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (isAttachedToWindow) session.scroll = vert
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(dispatch)
        super.onDetachedFromWindow()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (rubyEditing) null else super.onCreateInputConnection(outAttrs)?.let { delegate ->
            object : InputConnectionWrapper(delegate, false) {
                override fun finishComposingText(): Boolean = super.finishComposingText().also { scheduleObserve() }
                override fun endBatchEdit(): Boolean = super.endBatchEdit().also { scheduleObserve() }
                override fun setComposingRegion(start: Int, end: Int): Boolean =
                    super.setComposingRegion(start, end).also { scheduleObserve() }

                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
                    commitOpeningBracket(text, newCursorPosition, delegate) { value ->
                        super.commitText(value, newCursorPosition)
                    }

                override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
                    commitOpeningBracket(text, newCursorPosition, delegate) { value ->
                        if (Build.VERSION.SDK_INT >= 33) super.commitText(value, newCursorPosition, textAttribute)
                        else super.commitText(value, newCursorPosition)
                    }
            }
        }

    private fun bracketCompletion(input: CharSequence): String? {
        if (!completeBrackets || rubyEditing || projecting || BaseInputConnection.getComposingSpanStart(text) >= 0) return null
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        return completedBracket(input, text, start, end)
    }

    private inline fun commitOpeningBracket(
        input: CharSequence, cursor: Int, connection: InputConnection, commit: (CharSequence) -> Boolean,
    ): Boolean {
        val replacement = if (cursor == 1) bracketCompletion(input) else null
        if (replacement == null) return commit(input).also { if (completeBrackets) scheduleObserve() }
        observe()
        connection.beginBatchEdit()
        try {
            val committed = commit(replacement)
            if (committed && replacement.length == 2 && selectionStart > 0) {
                val caret = selectionStart - 1
                connection.setSelection(caret, caret)
            }
            return committed
        } finally {
            connection.endBatchEdit()
            observe()
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.paste -> super.onTextContextMenuItem(android.R.id.pasteAsPlainText)
        android.R.id.undo, android.R.id.redo -> {
            observe()
            if (session.composition == null) show(
                if (id == android.R.id.undo) session.undo() else session.redo(), showSpecial,
            )
            true
        }
        else -> super.onTextContextMenuItem(id)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.isCtrlPressed) when (keyCode) {
            android.view.KeyEvent.KEYCODE_Z -> return onTextContextMenuItem(
                if (event.isShiftPressed) android.R.id.redo else android.R.id.undo,
            )
            android.view.KeyEvent.KEYCODE_Y -> return onTextContextMenuItem(android.R.id.redo)
        }
        if (!event.isCtrlPressed && !event.isMetaPressed && event.unicodeChar in 1..Char.MAX_VALUE.code) {
            val replacement = bracketCompletion(event.unicodeChar.toChar().toString())
            if (replacement != null) {
                observe()
                val start = minOf(selectionStart, selectionEnd)
                val end = maxOf(selectionStart, selectionEnd)
                beginBatchEdit()
                try {
                    text.replace(start, end, replacement)
                    setSelection(start + 1)
                } finally {
                    endBatchEdit()
                    observe()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
