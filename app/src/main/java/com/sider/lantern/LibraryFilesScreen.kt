package com.sider.lantern

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal suspend fun readSelected(context: Context, uri: Uri, limit: Int = 64 * 1024 * 1024) = withContext(Dispatchers.IO) {
    checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readDocumentBytes(limit) }
}

internal suspend fun writeSelected(context: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
    check(readSelected(context, uri).contentEquals(bytes)) { context.getString(R.string.verify_write_failed) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryFilesScreen(model: LanternModel, trashOnly: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val library = model.library
    val trash by library.trash.collectAsStateWithLifecycle(emptyList())
    var recoveries by remember { mutableStateOf<List<Document>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<TrashRow?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var recoveryExport by remember { mutableStateOf<Document?>(null) }
    var recoverInternal by remember { mutableStateOf(false) }
    var pendingLocal by rememberSaveable { mutableStateOf(false) }
    var confirmInternal by rememberSaveable { mutableStateOf(false) }
    var resetStep by rememberSaveable { mutableStateOf(0) }
    val customization by model.customization.collectAsStateWithLifecycle()
    val external = library.externalSelected
    fun run(success: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try {
                operation()
                message = success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                message = resources.getString(when (problem) {
                    is LibraryConflict -> problem.messageId
                    is ExternalChanged -> R.string.external_changed
                    else -> R.string.storage_failed
                })
            } finally { busy = false }
        }
    }
    LaunchedEffect(trashOnly) {
        if (trashOnly) run("") { library.purgeExpired(model.writing.customization.first().retention) } else recoveries = model.recovery.list()
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pendingLocal = false
        if (uri != null) run(resources.getString(R.string.storage_moved)) { library.selectExternal(uri) }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run(resources.getString(R.string.saved_backup)) {
            val archive = model.backup()
            val bytes = withContext(Dispatchers.Default) { Json.encodeToString(archive).encodeToByteArray() }
            writeSelected(context, uri, bytes)
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreUri = it }
    val importDraft = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run(resources.getString(R.string.imported_draft)) {
            val data = readSelected(context, uri)
            val draft = runCatching { DocumentJson.decode(data) }.getOrElse {
                runCatching { CheckedJson.decode(data) }.getOrElse {
                    Json.decodeFromString<Manuscript>(data.decodeToString(throwOnInvalidSequence = true)).document.validated()
                }
            }
            library.importDocument(draft)
        }
    }
    val exportRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val document = recoveryExport
        recoveryExport = null
        if (uri != null && document != null) run(resources.getString(R.string.exported_recovery)) {
            writeSelected(context, uri, DocumentJson.encode(document))
        }
    }
    BackHandler(enabled = busy) {}
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (trashOnly) stringResource(R.string.trash) else stringResource(R.string.storage_backup)) },
            navigationIcon = { LanternBack(!busy, onBack) })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (message.isNotEmpty()) item { FileNote(message) }
            if (busy) item { FileNote(stringResource(R.string.working)) }
            if (trashOnly) {
                item {
                    val period = customization.retention.days
                    FileNote(stringResource(R.string.retention_value,
                        period?.let { pluralStringResource(R.plurals.days, it, it) } ?: stringResource(R.string.keep_forever)))
                }
                val active = trash
                if (active.isEmpty()) item { FileNote(stringResource(R.string.trash_empty)) }
                items(active, key = { it.id }) { entry ->
                    val entryName = entry.name.ifBlank { stringResource(R.string.untitled) }
                    FileItem(entryName, R.drawable.ui_delete) {
                        LanternIconButton(R.drawable.ui_restore, stringResource(R.string.restore_named, entryName), !busy) {
                            run(resources.getString(R.string.restored)) { library.restoreTrash(entry.id) }
                        }
                        LanternIconButton(R.drawable.ui_delete, stringResource(R.string.delete_named, entryName), !busy) { confirm = entry }
                    }
                }
            } else {
                item {
                    Column {
                        SettingChoice(stringResource(R.string.storage_location), external || pendingLocal, listOf(
                            false to stringResource(R.string.internal_storage),
                            true to stringResource(R.string.local_folder),
                        ), enabled = !busy) { local ->
                            if (local) pendingLocal = !external
                            else if (external) confirmInternal = true
                            else pendingLocal = false
                        }
                        if (pendingLocal) FileNote(stringResource(R.string.root_required))
                        LanternActionRow(R.drawable.ui_folder, stringResource(R.string.choose_root),
                            enabled = !busy && (external || pendingLocal)) { tree.launch(null) }
                        if (external) {
                            LanternActionRow(R.drawable.ui_check, stringResource(R.string.revalidate_folder), enabled = !busy) {
                                run("") { library.reloadExternal() }
                            }
                        }
                        if (library.externalUnavailable) {
                            FileNote(stringResource(R.string.storage_recheck))
                            LanternActionRow(R.drawable.ui_restore, stringResource(R.string.recover_verified), enabled = !busy) {
                                recoverInternal = true
                            }
                        }
                        LanternSection(stringResource(R.string.backup_and_import), Modifier.padding(start = 16.dp))
                        LanternActionRow(R.drawable.ui_download, stringResource(R.string.save_backup), enabled = !busy) {
                            exportBackup.launch("Lantern-backup.json")
                        }
                        LanternActionRow(R.drawable.ui_restore, stringResource(R.string.restore_backup), enabled = !busy) {
                            openBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                        }
                        LanternActionRow(R.drawable.ui_document, stringResource(R.string.import_json), enabled = !busy) {
                            importDraft.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                        }
                    }
                }
                if (recoveries.isNotEmpty()) item { LanternSection(stringResource(R.string.recovery_copies), Modifier.padding(start = 16.dp)) }
                items(recoveries, key = { it.id }) { draft ->
                    val name = draft.title.ifBlank { stringResource(R.string.untitled) }
                    FileItem(name, R.drawable.ui_document) {
                        LanternIconButton(R.drawable.ui_restore, stringResource(R.string.restore_named, name), !busy) {
                            run(resources.getString(R.string.imported_recovery)) {
                                library.importDocument(draft)
                                model.recovery.clear(draft.id)
                                recoveries = model.recovery.list()
                            }
                        }
                        LanternIconButton(R.drawable.ui_download, stringResource(R.string.save_json_named, name), !busy) {
                            recoveryExport = draft
                            exportRecovery.launch("Lantern-recovered.json")
                        }
                    }
                }
                item("local-reset") {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    TextButton(
                        onClick = { resetStep = 1 },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        LanternIcon(R.drawable.ui_warning, size = 20.dp)
                        Text(stringResource(R.string.reset_local), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
    }
    if (resetStep > 0) {
        val review = resetStep == 1
        val dismiss: () -> Unit = { resetStep = 0 }
        AlertDialog(
            onDismissRequest = dismiss,
            icon = { LanternIcon(R.drawable.ui_warning, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(if (review) R.string.reset_local else R.string.reset_final_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (review) {
                        Text(stringResource(R.string.reset_removed), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.reset_scope))
                        Text(stringResource(R.string.reset_kept), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.reset_external))
                        TextButton(onClick = { dismiss(); exportBackup.launch("Lantern-backup.json") }) {
                            Text(stringResource(R.string.save_backup))
                        }
                    } else Text(stringResource(R.string.reset_final_text))
                }
            },
            confirmButton = {
                if (review) TextButton(onClick = { resetStep = 2 }) { Text(stringResource(R.string.continue_action)) }
                else Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError),
                    onClick = {
                        dismiss()
                        busy = true
                        message = ""
                        scope.launch {
                            val accepted = try { model.requestLocalReset() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { false }
                            if (!accepted) {
                                busy = false
                                message = resources.getString(R.string.reset_failed)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.erase_local)) }
            },
            dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmInternal) AlertDialog(onDismissRequest = { confirmInternal = false },
        title = { Text(stringResource(R.string.switch_internal_prompt)) },
        text = { Text(stringResource(R.string.switch_internal_detail)) },
        confirmButton = { Button(onClick = {
            confirmInternal = false
            run(resources.getString(R.string.storage_moved)) { library.selectInternal() }
        }) { Text(stringResource(R.string.switch_action)) } },
        dismissButton = { TextButton({ confirmInternal = false }) { Text(stringResource(R.string.cancel)) } })
    if (recoverInternal) AlertDialog(onDismissRequest = { recoverInternal = false },
        title = { Text(stringResource(R.string.recover_internal_title)) },
        text = { Text(stringResource(R.string.recover_internal_body)) },
        confirmButton = { Button(onClick = {
            recoverInternal = false
            run(resources.getString(R.string.recovered_internal)) { library.recoverInternal() }
        }) { Text(stringResource(R.string.recover)) } },
        dismissButton = { TextButton({ recoverInternal = false }) { Text(stringResource(R.string.cancel)) } })
    confirm?.let { entry -> AlertDialog(onDismissRequest = { confirm = null },
        title = { Text(stringResource(R.string.permanent_delete)) }, text = { Text(entry.name.ifBlank { stringResource(R.string.untitled) }) },
        confirmButton = { Button(onClick = { confirm = null; run(resources.getString(R.string.deleted)) { library.deleteTrash(entry.id) } }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } }) }
    restoreUri?.let { uri -> AlertDialog(onDismissRequest = { restoreUri = null },
        title = { Text(stringResource(R.string.restore_backup)) }, text = { Text(stringResource(R.string.restore_backup_detail)) },
        confirmButton = { Button(onClick = {
            restoreUri = null
            run(resources.getString(R.string.backup_imported)) {
                val bytes = readSelected(context, uri)
                val archive = withContext(Dispatchers.Default) {
                    Json.decodeFromString<LibraryArchive>(bytes.decodeToString(throwOnInvalidSequence = true)).validated()
                }
                model.restore(archive)
            }
        }) { Text(stringResource(R.string.restore)) } },
        dismissButton = { TextButton({ restoreUri = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun FileNote(message: String) {
    Text(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FileItem(name: String, icon: Int, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        LanternIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        actions()
    }
}
