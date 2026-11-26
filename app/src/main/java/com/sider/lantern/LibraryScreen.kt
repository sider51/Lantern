package com.sider.lantern

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private sealed interface LibraryAction {
    data class NewFolder(val parent: String) : LibraryAction
    data class RenameFolder(val folder: FolderRow) : LibraryAction
    data class RenameDraft(val draft: ManuscriptSummary) : LibraryAction
    data class Move(val selection: LibrarySelection) : LibraryAction
    data class Trash(val selection: LibrarySelection) : LibraryAction
}

internal data class LibraryRead<T>(val value: T? = null, val failure: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LibraryScreen(
    model: LanternModel, folderId: String? = null, searching: Boolean = false,
    onFolder: (String) -> Unit, onOpen: (String) -> Unit, onSearch: () -> Unit, onNew: () -> Unit,
    onBack: () -> Unit, onSettings: (() -> Unit)? = null, onFiles: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null, onExport: (String) -> Unit = {},
) {
    val repository = model.library
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var action by remember { mutableStateOf<LibraryAction?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val config by model.customization.collectAsStateWithLifecycle()
    val order by model.order.collectAsStateWithLifecycle()
    val folderResult by model.folders.collectAsStateWithLifecycle()
    val folders = folderResult.value.orEmpty()
    val byId = remember(folders) { folders.associateBy(FolderRow::id) }
    val home = folderId == null && !searching
    val children = remember(folders, folderId, searching, order) {
        if (searching) emptyList() else folders.filter { it.parentId == (folderId ?: ROOT_ID) && it.id != INBOX_ID }
            .let { if (order.byTitle && order.descending) it.asReversed() else it }
    }
    val showDrafts = (!home || config.showRecent) && (!searching || query.isNotBlank())
    val rowsResult = if (!showDrafts) LibraryRead(emptyList()) else if (!searching) {
        val key = Triple(folderId, order, home)
        val flow = remember(model, key) { model.libraryRows(key) }
        androidx.compose.runtime.key(key) { flow.collectAsStateWithLifecycle(model.cachedRows(key)).value }
    } else {
        val flow = remember(repository, query, order) {
            repository.summaries(null, query, order.byTitle, descending = order.descending).map { LibraryRead(it) }
                .catch { if (it is CancellationException) throw it else emit(LibraryRead(failure = true)) }
        }
        androidx.compose.runtime.key(query, order) { flow.collectAsStateWithLifecycle(LibraryRead()).value }
    }
    val rows = rowsResult.value.orEmpty()
    val folderKeys = children.map { "folder-${it.id}" }
    val draftKeys = rows.map { "draft-${it.id}" }
    val visibleKeys = folderKeys + draftKeys
    val activeKeys = chosen.toSet().intersect(visibleKeys.toSet())
    val selection = LibrarySelection(
        children.filter { "folder-${it.id}" in activeKeys }.mapTo(mutableSetOf(), FolderRow::id),
        rows.filter { "draft-${it.id}" in activeKeys }.mapTo(mutableSetOf(), ManuscriptSummary::id),
    )
    val selecting = selection.size > 0
    BackHandler(selecting || busy) { if (!busy) chosen = emptyList() }

    fun toggle(key: String) {
        if (busy) return
        chosen = (if (key in activeKeys) activeKeys - key else activeKeys + key).toList()
    }

    fun runOperation(done: Int? = null, run: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                run()
                action = null
                chosen = emptyList()
                if (done != null) launch { snackbar.showSnackbar(resources.getString(done)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                val label = when (problem) {
                    is LibraryConflict -> problem.messageId
                    is ExternalChanged -> R.string.external_changed
                    else -> R.string.operation_failed
                }
                if (action == null) launch { snackbar.showSnackbar(resources.getString(label)) }
                else error = resources.getString(label)
            } finally { busy = false }
        }
    }

    fun choose(next: LibraryAction) {
        if (!busy) {
            error = ""
            action = next
        }
    }

    val newFolder = { choose(LibraryAction.NewFolder(folderId ?: ROOT_ID)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Scaffold(
            modifier = Modifier.widthIn(max = 860.dp).fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = {
                        AnimatedContent(selecting, transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(150)) },
                            label = "library selection") { inSelection ->
                            when {
                                inSelection -> Text(pluralStringResource(R.plurals.selected_items, selection.size, selection.size))
                                searching -> LibrarySearch(query) { query = it; chosen = emptyList() }
                                else -> Text(when {
                                    home -> stringResource(R.string.app_name)
                                    folderId == INBOX_ID -> stringResource(R.string.inbox)
                                    else -> byId[folderId]?.name ?: stringResource(R.string.folder_label)
                                }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    navigationIcon = {
                        if (selecting) LanternIconButton(R.drawable.ui_close, stringResource(R.string.selection_end), !busy) {
                            chosen = emptyList()
                        } else if (!home) LanternBack(!busy, onBack)
                    },
                    actions = {
                        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) {
                            val items = buildList {
                                add(MenuAction(R.string.select_all, R.drawable.ui_check) { chosen = visibleKeys })
                                add(MenuAction(R.string.move, R.drawable.ui_folder) { choose(LibraryAction.Move(selection)) })
                                if (selection.folders.isEmpty()) add(MenuAction(R.string.duplicate, R.drawable.ui_copy) {
                                    runOperation(R.string.duplicate_done) { repository.duplicateSelection(selection.drafts) }
                                })
                                add(MenuAction(R.string.move_to_trash, R.drawable.ui_delete) { choose(LibraryAction.Trash(selection)) })
                            }
                            LibraryMenu(R.string.more, items, !busy)
                        } else {
                            if (!searching) LanternIconButton(R.drawable.ui_search, stringResource(R.string.search), onClick = onSearch)
                            LibrarySortMenu(order, !busy) { next -> runOperation { model.sortDrafts(next) } }
                            LibraryMenu(R.string.more, buildList {
                                if (!searching && folderId != INBOX_ID) add(MenuAction(R.string.new_folder, R.drawable.ui_add, newFolder))
                                if (onSettings != null) add(MenuAction(R.string.settings, R.drawable.ui_settings, onSettings))
                                if (onFiles != null) add(MenuAction(R.string.storage_backup, R.drawable.ui_storage, onFiles))
                                if (onTrash != null) add(MenuAction(R.string.trash, R.drawable.ui_delete, onTrash))
                            }, !busy)
                        }
                        }
                    },
                )
            },
            floatingActionButton = {
                if (!searching && !selecting) {
                    val label = stringResource(R.string.new_draft)
                    val longLabel = stringResource(R.string.new_folder)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 2.dp) {
                        Box(Modifier.size(56.dp).combinedClickable(enabled = !busy, role = Role.Button,
                            onClickLabel = label, onClick = onNew,
                            onLongClickLabel = longLabel.takeIf { folderId != INBOX_ID },
                            onLongClick = newFolder.takeIf { folderId != INBOX_ID }),
                            contentAlignment = Alignment.Center) {
                            LanternIcon(R.drawable.ui_add, label)
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = if (searching || selecting) 16.dp else 100.dp)) {
                if (folderResult.failure || rowsResult.failure) {
                    item { LibraryMessage(stringResource(R.string.library_error)) }
                    return@LazyColumn
                }
                if (home) item("inbox") {
                    Surface(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        enabled = !selecting && !busy,
                        onClick = { onFolder(INBOX_ID) },
                    ) {
                        LibraryItem(R.drawable.ui_inbox, Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                            iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            trailing = {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { LanternIcon(R.drawable.ui_next) }
                            }) { Text(stringResource(R.string.inbox), style = MaterialTheme.typography.titleMedium) }
                    }
                }
                if (children.isNotEmpty()) {
                    item("folders") { LanternSection(stringResource(R.string.folder_label), Modifier.padding(start = 16.dp)) }
                    items(children, key = { "folder-${it.id}" }) { child ->
                        val key = "folder-${child.id}"
                        val one = LibrarySelection(folders = setOf(child.id))
                        fun swipe(chosenAction: SwipeAction) {
                            when (chosenAction) {
                                SwipeAction.Move -> choose(LibraryAction.Move(one))
                                SwipeAction.Rename -> choose(LibraryAction.RenameFolder(child))
                                SwipeAction.Trash -> choose(LibraryAction.Trash(one))
                                else -> Unit
                            }
                        }
                        SwipeLibraryRow(Modifier.animateItem(), config.left.forFolder(true), config.right.forFolder(true),
                            !selecting && !busy, ::swipe) {
                            LibraryRow(R.drawable.ui_folder, key in activeKeys, onOpen = {
                                if (selecting) toggle(key) else if (!busy) onFolder(child.id)
                            }, onSelect = { toggle(key) }, trailing = {
                                if (!selecting) LibraryMenu(R.string.folder_actions, listOf(
                                    MenuAction(R.string.rename, R.drawable.ui_italic) { choose(LibraryAction.RenameFolder(child)) },
                                    MenuAction(R.string.move, R.drawable.ui_folder) { choose(LibraryAction.Move(one)) },
                                    MenuAction(R.string.move_to_trash, R.drawable.ui_delete) { choose(LibraryAction.Trash(one)) },
                                ), !busy)
                            }) { Text(child.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                if (showDrafts && !searching) item("drafts-heading") {
                    LanternSection(stringResource(if (home) R.string.recent_drafts else R.string.drafts), Modifier.padding(start = 16.dp))
                }
                when {
                    searching && query.isBlank() -> item { LibraryMessage(stringResource(R.string.search_empty)) }
                    folderResult.value == null || rowsResult.value == null -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                    showDrafts && rows.isEmpty() -> item {
                        LibraryMessage(stringResource(if (searching) R.string.search_no_results else if (home) R.string.first_draft_hint else R.string.no_drafts))
                    }
                    showDrafts -> items(rows, key = { "draft-${it.id}" }) { draft ->
                        val key = "draft-${draft.id}"
                        val one = LibrarySelection(drafts = setOf(draft.id))
                        val duplicate = { runOperation(R.string.duplicate_done) { repository.duplicate(draft.id) } }
                        fun swipe(chosenAction: SwipeAction) {
                            when (chosenAction) {
                                SwipeAction.Move -> choose(LibraryAction.Move(one))
                                SwipeAction.Rename -> choose(LibraryAction.RenameDraft(draft))
                                SwipeAction.Duplicate -> duplicate()
                                SwipeAction.Trash -> choose(LibraryAction.Trash(one))
                                SwipeAction.None -> Unit
                            }
                        }
                        SwipeLibraryRow(Modifier.animateItem(), config.left, config.right, !selecting && !busy, ::swipe) {
                            LibraryRow(R.drawable.ui_document, key in activeKeys, draft = true, onOpen = {
                                if (selecting) toggle(key) else if (!busy) onOpen(draft.id)
                            }, onSelect = { toggle(key) }, trailing = {
                                if (!selecting) LibraryMenu(R.string.document_actions, listOf(
                                    MenuAction(R.string.rename, R.drawable.ui_italic) { choose(LibraryAction.RenameDraft(draft)) },
                                    MenuAction(R.string.move, R.drawable.ui_folder) { choose(LibraryAction.Move(one)) },
                                    MenuAction(R.string.export, R.drawable.ui_export) { onExport(draft.id) },
                                    MenuAction(R.string.duplicate, R.drawable.ui_copy, duplicate),
                                    MenuAction(R.string.move_to_trash, R.drawable.ui_delete) { choose(LibraryAction.Trash(one)) },
                                ), !busy)
                            }) { DraftDetails(draft, home || searching) }
                        }
                    }
                }
            }
        }
    }

    val dismiss: () -> Unit = { if (!busy) action = null }
    when (val pending = action) {
        is LibraryAction.NewFolder -> RowNameDialog(R.string.new_folder, "", false, busy, error, dismiss) {
            runOperation { repository.createFolder(it, pending.parent) }
        }
        is LibraryAction.RenameFolder -> RowNameDialog(R.string.rename_folder, pending.folder.name, false, busy, error, dismiss) {
            runOperation { repository.renameFolder(pending.folder.id, it) }
        }
        is LibraryAction.RenameDraft -> RowNameDialog(R.string.rename_draft, pending.draft.title, true, busy, error, dismiss) {
            runOperation { repository.renameManuscript(pending.draft.id, it) }
        }
        is LibraryAction.Move -> {
            val group = pending.selection
            val choices = folders.filter { row ->
                (row.id != ROOT_ID || group.drafts.isEmpty()) &&
                    (row.id != INBOX_ID || group.folders.isEmpty()) &&
                    folderChain(row, byId).none { it.id in group.folders }
            }
            MoveDialog(choices, byId, busy, error, dismiss) { id -> runOperation { repository.moveSelection(group, id) } }
        }
        is LibraryAction.Trash -> AlertDialog(
            dismiss, title = { Text(stringResource(R.string.trash_confirm_title)) },
            text = { Text(error.ifEmpty { stringResource(R.string.trash_confirm_message) }) },
            confirmButton = {
                TextButton({ runOperation { repository.discardSelection(pending.selection) } }, enabled = !busy) {
                    Text(stringResource(R.string.move_to_trash), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(dismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeLibraryRow(modifier: Modifier, left: SwipeAction, right: SwipeAction,
    enabled: Boolean, action: (SwipeAction) -> Unit, content: @Composable () -> Unit) {
    if (!enabled || (left == SwipeAction.None && right == SwipeAction.None)) {
        Box(modifier) { content() }
        return
    }
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * .34f })
    SwipeToDismissBox(state, modifier = modifier, enableDismissFromStartToEnd = right != SwipeAction.None,
        enableDismissFromEndToStart = left != SwipeAction.None,
        backgroundContent = {
            val direction = state.dismissDirection
            val operation = if (direction == SwipeToDismissBoxValue.StartToEnd) right else left
            if (direction != SwipeToDismissBoxValue.Settled && operation != SwipeAction.None) {
                val colors = MaterialTheme.colorScheme
                val destructive = operation == SwipeAction.Trash
                Box(Modifier.fillMaxSize().background(if (destructive) colors.errorContainer else colors.secondaryContainer)
                    .padding(horizontal = 22.dp),
                    contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                    LanternIcon(operation.icon, stringResource(operation.label),
                        tint = if (destructive) colors.onErrorContainer else colors.onSecondaryContainer)
                }
            }
        },
        onDismiss = { direction ->
            val operation = if (direction == SwipeToDismissBoxValue.StartToEnd) right else left
            if (direction != SwipeToDismissBoxValue.Settled && operation != SwipeAction.None) action(operation)
            scope.launch { state.reset() }
        },
    ) { content() }
}

@Composable
private fun LibrarySearch(query: String, update: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    Row(Modifier.fillMaxWidth().padding(end = 4.dp)
        .background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp)).heightIn(min = 48.dp).padding(start = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LanternIcon(R.drawable.ui_search, size = 20.dp, tint = colors.onSurfaceVariant)
        BasicTextField(query, update, modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
            decorationBox = { input ->
                Box {
                    if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
            })
        if (query.isNotEmpty()) LanternIconButton(R.drawable.ui_close, stringResource(R.string.clear_search)) { update("") }
    }
}

@Composable
private fun LibraryMessage(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun compactDate(timestamp: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(zone)
    val formatter = if (date.toLocalDate() == today) {
        if (locale.language == "ja") DateTimeFormatter.ofPattern("HH:mm", locale)
        else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    } else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    return formatter.format(date)
}

@Composable
private fun DraftDetails(draft: ManuscriptSummary, showFolder: Boolean) {
    val locale = LocalLocale.current.platformLocale
    val updated = compactDate(draft.updatedAt, locale)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text(draft.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleMedium,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    val preview = remember(draft.preview) { draft.preview.replace(Regex("\\s+"), " ").trim() }
    if (preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodyMedium, color = muted,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    val count = pluralStringResource(R.plurals.character_count, draft.characterCount, draft.characterCount)
    val folder = if (draft.folderId == INBOX_ID) stringResource(R.string.inbox) else draft.folderName
    Text(listOfNotNull(folder.takeIf { showFolder }, count, updated).joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(icon: Int, isSelected: Boolean, onOpen: () -> Unit, onSelect: () -> Unit,
    draft: Boolean = false, trailing: @Composable () -> Unit, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    LibraryItem(
        if (isSelected) R.drawable.ui_check else icon,
        Modifier.background(if (isSelected) colors.secondaryContainer else colors.surface)
            .semantics { selected = isSelected }
            .combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onSelect)
            .heightIn(min = if (draft) 80.dp else 56.dp).padding(horizontal = 16.dp),
        iconColor = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        trailing = trailing, content = content,
    )
}

@Composable
private fun LibraryItem(icon: Int, modifier: Modifier, iconColor: androidx.compose.ui.graphics.Color,
    trailing: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().then(modifier), verticalAlignment = Alignment.CenterVertically) {
        LanternIcon(icon, tint = iconColor)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        trailing()
    }
}

@Composable
private fun LibrarySortMenu(order: DraftOrder, enabled: Boolean, select: (DraftOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LanternIconButton(R.drawable.ui_sort, stringResource(R.string.sort_options), enabled) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            Text(stringResource(R.string.sort_criterion), Modifier.padding(start = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((title, label) in listOf(false to R.string.updated_at, true to R.string.sort_title)) {
                DropdownMenuItem(text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(order.byTitle == title, onClick = null) },
                    onClick = { if (order.byTitle != title) select(DraftOrder(title)) })
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.sort_direction), Modifier.padding(start = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((descending, label) in listOf(false to R.string.ascending, true to R.string.descending)) {
                DropdownMenuItem(text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(order.descending == descending, onClick = null) },
                    onClick = { select(order.copy(descending = descending)); expanded = false })
            }
        }
    }
}

private data class MenuAction(val label: Int, val icon: Int? = null, val onClick: () -> Unit)

@Composable
private fun LibraryMenu(label: Int, options: List<MenuAction>, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LanternIconButton(R.drawable.ui_more, stringResource(label), enabled) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            for ((name, icon, click) in options) DropdownMenuItem(
                text = { Text(stringResource(name)) }, onClick = { expanded = false; click() },
                leadingIcon = icon?.let { { LanternIcon(it) } },
            )
        }
    }
}

@Composable
private fun RowNameDialog(title: Int, original: String, allowEmpty: Boolean, busy: Boolean, error: String,
    dismiss: () -> Unit, submit: (String) -> Unit) {
    var name by rememberSaveable(original, title) { mutableStateOf(original) }
    AlertDialog(dismiss, title = { Text(stringResource(title)) }, text = {
        OutlinedTextField(name, { name = it }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(if (allowEmpty) R.string.title else R.string.name)) },
            isError = error.isNotEmpty(), supportingText = if (error.isNotEmpty()) ({ Text(error) }) else null)
    }, confirmButton = {
        TextButton({ submit(name) }, enabled = !busy && (allowEmpty || name.isNotBlank())) { Text(stringResource(R.string.confirm)) }
    }, dismissButton = { TextButton(dismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun MoveDialog(choices: List<FolderRow>, byId: Map<String, FolderRow>,
    busy: Boolean, error: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(dismiss, title = { Text(stringResource(R.string.move_destination)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (choices.isEmpty()) Text(stringResource(R.string.no_destination))
            else LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(choices, key = FolderRow::id) { folder ->
                    val path = when (folder.id) {
                        ROOT_ID -> stringResource(R.string.library)
                        INBOX_ID -> stringResource(R.string.inbox)
                        else -> folderChain(folder, byId).filter { it.id != ROOT_ID }.asReversed().joinToString(" / ") { it.name }
                    }
                    DropdownMenuItem(text = { Text(path) }, onClick = { chosen = folder.id }, enabled = !busy,
                        leadingIcon = { LanternIcon(if (folder.id == INBOX_ID) R.drawable.ui_inbox else R.drawable.ui_folder) },
                        trailingIcon = if (chosen == folder.id) ({ LanternIcon(R.drawable.ui_check, stringResource(R.string.selected_label)) }) else null)
                }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton({ chosen?.let(submit) }, enabled = !busy && chosen != null) { Text(stringResource(R.string.move)) }
    }, dismissButton = { TextButton(dismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

private fun folderChain(folder: FolderRow, all: Map<String, FolderRow>): List<FolderRow> = buildList {
    val seen = mutableSetOf<String>()
    var current: FolderRow? = folder
    while (current != null && seen.add(current.id)) {
        add(current)
        current = current.parentId?.let(all::get)
    }
}
