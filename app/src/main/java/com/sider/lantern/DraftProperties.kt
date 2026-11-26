package com.sider.lantern

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException

@Composable
internal fun DraftProperties(model: LanternModel, draft: ProductDraft, count: Int, close: () -> Unit) {
    val manuscript by produceState<Manuscript?>(null, model, draft.id, draft.autosave.status) {
        value = try { model.library.load(draft.id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    val folders by model.folders.collectAsStateWithLifecycle()
    val folder = manuscript?.folderId ?: draft.initialFolder
    val document = draft.editor.visible.document
    val (lines, paragraphs) = remember(document.body) { documentStructure(document.body) }
    val locale = LocalLocale.current.platformLocale
    val dates = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale) }
    val unsaved = stringResource(R.string.unsaved)
    fun date(time: Long?) = time?.let { dates.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: unsaved
    val numbers = java.text.NumberFormat.getIntegerInstance(locale)
    val details = listOf(
        R.string.title to draft.title.text.ifBlank { stringResource(R.string.untitled) },
        R.string.folder_label to (if (folder == INBOX_ID) stringResource(R.string.inbox)
            else folders.value?.firstOrNull { it.id == folder }?.name.orEmpty()),
        R.string.characters to numbers.format(count),
        R.string.lines to numbers.format(lines),
        R.string.paragraphs to numbers.format(paragraphs),
        R.string.page_breaks to numbers.format(document.pageBreaks.size),
        R.string.created_at to date(manuscript?.createdAt),
        R.string.updated_at to date(manuscript?.updatedAt),
    )
    AlertDialog(close, title = { Text(stringResource(R.string.properties)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((label, text) in details) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(label), Modifier.weight(.44f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, Modifier.weight(.56f))
            }
        }
    }, confirmButton = { TextButton(close) { Text(stringResource(R.string.close)) } })
}

internal fun documentStructure(body: String): Pair<Int, Int> {
    if (body.isEmpty()) return 0 to 0
    var lines = 0
    var paragraphs = 0
    var separated = true
    for (line in body.lineSequence()) {
        lines++
        if (line.isBlank()) separated = true
        else if (separated) {
            paragraphs++
            separated = false
        }
    }
    return lines to paragraphs
}
