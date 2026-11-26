package com.sider.lantern

import android.view.View
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductEditor(
    draft: ProductDraft, model: LanternModel, options: WritingOptions, startingCategory: WritingCategory,
    onExit: () -> Unit, onExport: () -> Unit, onSettings: () -> Unit = {},
) {
    val session = draft.editor
    val save = draft.autosave
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val config by model.customization.collectAsStateWithLifecycle()
    val nativeTypeface = remember(context, config.font) {
        config.font.file?.let { ResourcesCompat.getFont(context, it) } ?: Typeface.DEFAULT
    }
    var menu by remember { mutableStateOf(false) }
    var properties by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hostView = LocalView.current
    val editorColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val editorHint = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    var native by remember(draft) { mutableStateOf<ProbeEditText?>(null) }
    var bodyFocused by remember(draft) { mutableStateOf(false) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var category by rememberSaveable(draft.id) { mutableStateOf(startingCategory) }
    var count by remember(draft) { mutableIntStateOf(0) }
    var leaving by remember(draft) { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf("") }
    var restoreRubyFocus by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(session, options[WritingSetting.Count], properties) {
        if (!options[WritingSetting.Count] && !properties) return@LaunchedEffect
        snapshotFlow { session.visible.document.body }.collectLatest { body ->
            count = withContext(Dispatchers.Default) { countCharacters(body) }
        }
    }

    LaunchedEffect(draft, options[WritingSetting.Position]) {
        if (!options[WritingSetting.Position]) return@LaunchedEffect
        snapshotFlow { draft.positionForStorage() }.collectLatest { position ->
            if (position == null) return@collectLatest
            delay(300)
            model.rememberPosition(draft)
        }
    }

    DisposableEffect(lifecycle, draft, native) {
        val view = native
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                view?.observe()
                model.checkpointPosition(draft)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun leave(destination: () -> Unit) {
        if (leaving) return
        leaving = true
        scope.launch {
            try {
                while (true) {
                    native?.observe()
                    if (draft.hasComposition) {
                        feedback = resources.getString(R.string.confirm_input_before_leaving)
                        return@launch
                    }
                    if (!save.flush()) return@launch
                    native?.observe()
                    if (!save.hasPending && !draft.hasComposition) {
                        val position = draft.positionForStorage()
                        model.rememberPosition(draft)
                        native?.observe()
                        if (save.hasPending || draft.hasComposition || draft.positionForStorage() != position) continue
                        ViewCompat.getWindowInsetsController(hostView)?.hide(WindowInsetsCompat.Type.ime())
                        destination()
                        return@launch
                    }
                }
            } finally {
                leaving = false
            }
        }
    }

    fun change(revealCaret: Boolean = false, action: () -> Revision) {
        native?.observe()
        feedback = runCatching {
            check(session.composition == null) { resources.getString(R.string.confirm_body_input) }
            val before = session.visible
            val next = action()
            if (next != before) native?.show(next, special = true, revealCaret = revealCaret)
            ""
        }.getOrElse { resources.getString(R.string.format_failed) }
    }

    fun insertSymbol(symbol: String, close: String?) {
        val view = native ?: return
        view.observe()
        feedback = runCatching {
            val operation = session.symbolEdit(symbol, close)
            val next = session.apply(operation)
            view.show(next, special = true, changes = operation.changes, revealCaret = true)
            ""
        }.getOrElse { resources.getString(R.string.symbol_failed) }
    }

    fun openRuby() {
        native?.observe()
        feedback = runCatching {
            restoreRubyFocus = native?.hasFocus() == true
            session.beginRuby()
            native?.suspendForRuby()
            ""
        }.getOrElse { resources.getString(R.string.select_ruby_text) }
    }

    BackHandler { leave(onExit) }
    val current = session.visible
    val colors = MaterialTheme.colorScheme
    val selectionColor = colors.primary.copy(alpha = .30f).toArgb()
    val cursorColor = colors.primary.toArgb()
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp)
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { LanternBack(enabled = !leaving) { leave(onExit) } },
                actions = {
                    Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SaveIndicator(save, draft.hasComposition)
                    Box {
                        LanternIconButton(R.drawable.ui_more, stringResource(R.string.more),
                            enabled = !leaving && !draft.hasComposition) { menu = true }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.export)) },
                                onClick = { menu = false; leave(onExport) },
                                enabled = save.status == SaveStatus.Saved && !draft.hasComposition,
                                leadingIcon = { LanternIcon(R.drawable.ui_export) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.properties)) },
                                onClick = { menu = false; properties = true },
                                enabled = !draft.hasComposition, leadingIcon = { LanternIcon(R.drawable.ui_info) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.writing_options_menu)) },
                                onClick = { menu = false; leave(onSettings) }, leadingIcon = { LanternIcon(R.drawable.ui_settings) })
                        }
                    }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter) {
            val showTitle = !bodyFocused || !imeVisible || maxHeight >= 220.dp
            val compactToolbar = maxHeight < 175.dp
            Column(Modifier.widthIn(max = 700.dp).fillMaxSize()) {
                if (showTitle) BasicTextField(
                    value = draft.title,
                    onValueChange = { feedback = ""; draft.updateTitle(it) },
                    singleLine = true,
                    textStyle = titleStyle.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(start = config.margin.dp, end = config.margin.dp, top = 8.dp, bottom = 12.dp),
                    decorationBox = { input ->
                        androidx.compose.foundation.layout.Box {
                            if (draft.title.text.isEmpty()) Text(stringResource(R.string.title), style = titleStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            input()
                        }
                    },
                )
                val bodyHint = stringResource(R.string.body)
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = config.margin.dp),
                    factory = { context ->
                        ProbeEditText(context, session, measureEdits = false, initialSpellCheck = options[WritingSetting.SpellCheck]).also { view ->
                            view.hint = bodyHint
                            view.setTextColor(editorColor)
                            view.setHintTextColor(editorHint)
                            view.typeface = nativeTypeface
                            view.textSize = config.textSize.toFloat()
                            view.setLineSpacing(view.resources.displayMetrics.density * config.lineGap, 1f)
                            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                            view.imeOptions = view.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            view.onFocusChangeListener = View.OnFocusChangeListener { _, focused -> bodyFocused = focused }
                            view.show(session.visible, special = true)
                            view.observe()
                            if (session.rubyTarget != null) view.suspendForRuby()
                            native = view
                        }
                    },
                    update = { view ->
                        if (view.currentTextColor != editorColor) view.setTextColor(editorColor)
                        if (view.currentHintTextColor != editorHint) view.setHintTextColor(editorHint)
                        if (view.hint?.toString() != bodyHint) view.hint = bodyHint
                        if (view.typeface != nativeTypeface) view.typeface = nativeTypeface
                        val sizePx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                            config.textSize.toFloat(), view.resources.displayMetrics)
                        if (kotlin.math.abs(view.textSize - sizePx) > .1f) view.textSize = config.textSize.toFloat()
                        val gapPx = view.resources.displayMetrics.density * config.lineGap
                        if (view.lineSpacingExtra != gapPx) view.setLineSpacing(gapPx, 1f)
                        if (view.highlightColor != selectionColor) {
                            view.highlightColor = selectionColor
                            view.textCursorDrawable = view.textCursorDrawable?.mutate()?.apply { setTint(cursorColor) }
                            view.textSelectHandle?.mutate()?.let { it.setTint(cursorColor); view.setTextSelectHandle(it) }
                            view.textSelectHandleLeft?.mutate()?.let { it.setTint(cursorColor); view.setTextSelectHandleLeft(it) }
                            view.textSelectHandleRight?.mutate()?.let { it.setTint(cursorColor); view.setTextSelectHandleRight(it) }
                        }
                        view.completeBrackets = options[WritingSetting.Brackets]
                        view.setSpellCheck(options[WritingSetting.SpellCheck])
                    },
                )
                if (save.status == SaveStatus.Failed) {
                    Text(stringResource(save.reason ?: R.string.save_failed),
                        Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        leaving = true
                        scope.launch {
                            try {
                                native?.observe()
                                val retained = if (draft.hasComposition) null else save.retainForRecovery()
                                if (retained != null) {
                                    native?.observe()
                                    if (!draft.hasComposition && save.hasPendingDocument(retained)) {
                                        ViewCompat.getWindowInsetsController(hostView)?.hide(WindowInsetsCompat.Type.ime())
                                        onExit()
                                    }
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                feedback = resources.getString(R.string.recovery_failed)
                            } finally { leaving = false }
                        }
                    }, enabled = !leaving && !draft.hasComposition) { Text(stringResource(R.string.keep_recovery_close)) }
                    if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.bodySmall)
                } else if (feedback.isNotEmpty()) {
                    Text(feedback, style = MaterialTheme.typography.bodySmall)
                }
                val breaks = current.document.pageBreaks.size
                val metadata = listOfNotNull(
                    pluralStringResource(R.plurals.character_count, count, count).takeIf { options[WritingSetting.Count] },
                    pluralStringResource(R.plurals.break_count, breaks, breaks).takeIf { breaks != 0 },
                ).joinToString("　")
                if (metadata.isNotEmpty() && !compactToolbar) Text(metadata,
                    Modifier.align(Alignment.End).padding(end = 20.dp, top = 2.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (session.rubyTarget == null && !properties && (!imeVisible || bodyFocused)) {
                    fun toggleToolbar() {
                        scope.launch {
                            try { model.writing.setExportOption("toolbar_expanded", (!config.toolbarExpanded).toString()) }
                            catch (_: IOException) { feedback = resources.getString(R.string.setting_failed) }
                        }
                    }
                    AnimatedContent(config.toolbarExpanded, transitionSpec = {
                        (fadeIn(tween(160)) togetherWith fadeOut(tween(150)))
                            .using(SizeTransform { _, _ -> tween(200) })
                    }, label = "writing toolbar") { expanded ->
                        if (expanded) WritingToolbar(
                    compact = compactToolbar,
                    category = category,
                    onCategory = { category = it; model.rememberCategory(it) },
                    session = session,
                    onSymbol = ::insertSymbol,
                    onRuby = ::openRuby,
                    onFormat = { format -> change { session.toggleFormat(format) } },
                    onBreak = { change(action = session::pageBreak) },
                    onUndo = { change(revealCaret = true, action = session::undo) },
                    onRedo = { change(revealCaret = true, action = session::redo) },
                    onCursor = { forward -> change(revealCaret = true) { session.moveCursor(forward) } },
                            onCollapse = ::toggleToolbar,
                        ) else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            LanternIconButton(R.drawable.ui_collapse, stringResource(R.string.toolbar_expand), onClick = ::toggleToolbar)
                        }
                    }
                }
            }
        }
    }

    if (properties) DraftProperties(model, draft, count) { properties = false }

    session.rubyTarget?.let { target ->
        RubyDialog(
            target = target,
            onApply = { reading, remove ->
                native?.observe()
                val next = session.applyRuby(reading, remove)
                native?.show(next, special = true)
                native?.restoreSelectionAfterDialog(next.position, restoreRubyFocus)
            },
            onCancel = {
                val position = session.cancelRuby()
                native?.restoreSelectionAfterDialog(position, restoreRubyFocus)
            },
        )
    }
}
@Composable
private fun SaveIndicator(save: DraftAutosave, composing: Boolean) {
    val colors = MaterialTheme.colorScheme
    if (save.status == SaveStatus.Failed) {
        TextButton(onClick = save::requestFlush) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LanternIcon(R.drawable.ui_warning, size = 18.dp, tint = colors.error)
                Text(stringResource(R.string.retry_save), color = colors.error, style = MaterialTheme.typography.labelMedium)
            }
        }
        return
    }
    val (icon, label) = when {
        composing -> R.drawable.ui_italic to R.string.composing
        save.status == SaveStatus.Saving -> R.drawable.ui_clock to R.string.saving
        save.status == SaveStatus.Saved -> R.drawable.ui_check to R.string.saved
        else -> R.drawable.ui_document to R.string.unsaved
    }
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LanternIcon(icon, size = 16.dp, tint = colors.onSurfaceVariant)
        Text(stringResource(label), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}
