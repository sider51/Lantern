package com.sider.lantern

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WritingToolbar(
    compact: Boolean,
    category: WritingCategory,
    onCategory: (WritingCategory) -> Unit,
    session: EditorSession,
    onSymbol: (String, String?) -> Unit,
    onRuby: () -> Unit,
    onFormat: (Format) -> Unit,
    onBreak: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCursor: (Boolean) -> Unit,
    onCollapse: (() -> Unit)? = null,
) {
    val categories = WritingCategory.entries
    val currentCategory by rememberUpdatedState(category)
    val select by rememberUpdatedState(onCategory)
    val current = session.visible
    val editable = session.composition == null
    val (anchor, caret) = current.position
    val range = anchor != caret
    val lower = minOf(anchor, caret)
    val upper = maxOf(anchor, caret)
    val body = current.document.body
    val swipe = Modifier.pointerInput(Unit) {
        val threshold = 36.dp.toPx()
        var distance = 0f
        // The category selector and the scrollable shortcuts handle separate gestures.
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onDragEnd = {
                val step = when {
                    distance <= -threshold -> 1
                    distance >= threshold -> -1
                    else -> 0
                }
                if (step != 0) categories.getOrNull(currentCategory.ordinal + step)?.let(select)
            },
        ) { change, delta ->
            change.consume()
            distance += delta
        }
    }
    val shortcuts: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 300.dp
            Crossfade(category, animationSpec = tween(150), label = "writing actions") { group ->
            val scroll = narrow || group == WritingCategory.Symbols
            Row(Modifier.fillMaxWidth().then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 4.dp),
                horizontalArrangement = if (scroll) Arrangement.Start else Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                when (group) {
                    WritingCategory.Symbols -> {
                        bracketPairs.forEach { (open, close) ->
                            Shortcut("$open$close", editable) { onSymbol(open.toString(), close.toString()) }
                        }
                        listOf("……", "――", "！？", "？！").forEach { symbol ->
                            Shortcut(symbol, editable) { onSymbol(symbol, null) }
                        }
                        Shortcut("␣", editable, stringResource(R.string.fullwidth_space)) { onSymbol("　", null) }
                    }
                    WritingCategory.Formatting -> {
                        val singleLine = range && body.indexOf('\n', lower).let { it == -1 || it >= upper } &&
                            body.indexOf('\r', lower).let { it == -1 || it >= upper }
                        Shortcut(stringResource(R.string.ruby), editable && session.canApplyRuby(), action = onRuby)
                        Shortcut(stringResource(R.string.emphasis), editable && singleLine) { onFormat(Format.Emphasis) }
                        for ((format, icon, label) in listOf(
                            Triple(Format.Bold, R.drawable.ui_bold, R.string.bold),
                            Triple(Format.Italic, R.drawable.ui_italic, R.string.italic),
                        )) {
                            IconToggleButton(checked = range && current.document.annotations.any {
                                it.format == format && it.start <= lower && it.end >= upper
                            }, onCheckedChange = { onFormat(format) }, enabled = editable && range,
                                modifier = Modifier.size(48.dp)) { LanternIcon(icon, stringResource(label)) }
                        }
                        val insideRuby = current.document.annotations.any { it.format == Format.Ruby && it.start < caret && caret < it.end }
                        LanternIconButton(R.drawable.ui_page_break, stringResource(R.string.page_break),
                            editable && !range && caret in 1 until body.length && !insideRuby, onBreak)
                    }
                    WritingCategory.Editing -> {
                        LanternIconButton(R.drawable.ui_undo, stringResource(R.string.undo), editable && session.canUndo, onUndo)
                        LanternIconButton(R.drawable.ui_redo, stringResource(R.string.redo), editable && session.canRedo, onRedo)
                        LanternIconButton(R.drawable.ui_arrow_left, stringResource(R.string.caret_left), editable && (range || caret > 0)) { onCursor(false) }
                        LanternIconButton(R.drawable.ui_arrow_right, stringResource(R.string.caret_right), editable && (range || caret < body.length)) { onCursor(true) }
                    }
                }
            }
            }
        }
    }
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    Surface(Modifier.fillMaxWidth(), color = surface) { if (compact) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            var expanded by remember { mutableStateOf(false) }
            Box(swipe.heightIn(min = 48.dp)) {
                Shortcut(stringResource(category.label), true, stringResource(R.string.category_named, stringResource(category.label))) { expanded = true }
                DropdownMenu(expanded, onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = false)) {
                    categories.forEach { item ->
                        DropdownMenuItem(text = { Text(stringResource(item.label)) }, onClick = { expanded = false; onCategory(item) })
                    }
                }
            }
            Box(Modifier.weight(1f)) { shortcuts() }
            if (onCollapse != null) LanternIconButton(R.drawable.ui_expand, stringResource(R.string.toolbar_collapse), onClick = onCollapse)
        }
    } else Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryTabRow(selectedTabIndex = category.ordinal, modifier = Modifier.weight(1f).then(swipe), containerColor = surface, divider = {},
            indicator = {
                TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(category.ordinal, matchContentSize = true),
                    height = 2.dp)
            }) {
            categories.forEach { item ->
                Tab(
                    selected = category == item,
                    onClick = { onCategory(item) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(item.label), Modifier.padding(horizontal = 3.dp),
                        style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        if (onCollapse != null) LanternIconButton(R.drawable.ui_expand, stringResource(R.string.toolbar_collapse), onClick = onCollapse)
        }
        shortcuts()
    } }
}

@Composable
private fun Shortcut(label: String, enabled: Boolean, description: String? = null, action: () -> Unit) {
    TextButton(
        onClick = action,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).then(
            if (description == null) Modifier else Modifier.semantics { contentDescription = description },
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
