package com.sider.lantern

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportDestination(model: LanternModel, id: String, onBack: () -> Unit, initial: Document? = null) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var draft by remember(id) { mutableStateOf(initial) }
    var message by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(-1) }
    val defaultTab by model.writing.defaultExportTab.collectAsStateWithLifecycle(0)
    val tab = if (mode == -1) defaultTab else mode
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (initial != null) return@LaunchedEffect
        try { draft = model.library.load(id)?.document ?: error(resources.getString(R.string.draft_missing)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = resources.getString(R.string.draft_error) }
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val document = draft
        if (uri != null && document != null) scope.launch {
            busy = true
            try {
                writeSelected(context, uri, DocumentJson.encode(document))
                message = resources.getString(R.string.json_saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = resources.getString(R.string.json_failed) }
            finally { busy = false }
        }
    }
    Scaffold(modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.export)) }, navigationIcon = { LanternBack(!busy, onBack) })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val document = draft
            if (document == null) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    if (message.isEmpty()) CircularProgressIndicator() else Text(message)
                }
            } else {
                ExportTabs(tab, listOf(stringResource(R.string.text_tab), stringResource(R.string.image_tab), stringResource(R.string.json_tab)), !busy) { mode = it }
                when (tab) {
                    0 -> TextExportPanel(model.writing, document)
                    1 -> ImageExportPanel(model.writing, document, onBusy = { busy = it })
                    else -> {
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LanternIcon(R.drawable.ui_document, size = 32.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(document.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.json_description), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ExportActions(
                            primary = stringResource(R.string.save_draft_json), icon = R.drawable.ui_download, enabled = !busy,
                            status = if (busy) stringResource(R.string.saving_progress) else message,
                            onPrimary = { json.launch("Lantern-${document.id.take(8)}.json") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextExportPanel(preferences: WritingPreferences, document: Document) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<TextExportOptions?>(null) }
    var preview by remember { mutableStateOf<Pair<TextExportOptions, ExportedText>?>(null) }
    var message by remember { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(preferences) {
        options = preferences.exportOption("text_export")?.let { runCatching { Json.decodeFromString<TextExportOptions>(it) }.getOrNull() }
            ?: TextExportOptions()
    }
    val current = options ?: return
    fun change(next: TextExportOptions) {
        options = next
        scope.launch {
            try { preferences.setExportOption("text_export", Json.encodeToString(next)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = resources.getString(R.string.export_setting_failed) }
        }
    }
    LaunchedEffect(document, current) {
        preview = current to withContext(Dispatchers.Default) { exportText(document, current) }
    }
    val ready = preview?.takeIf { it.first == current }?.second
    Column(Modifier.fillMaxSize()) {
        Choice(textTargetLabel(current.target), TextTarget.entries.map { target -> textTargetLabel(target) to { change(current.copy(target = target)) } },
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), prompt = stringResource(R.string.text_format))
        if (ready != null && ready.omitted.isNotEmpty()) {
            val omitted = ready.omitted.map { stringResource(it.label) }.joinToString(", ")
            Text(stringResource(R.string.omitted_styles, omitted), Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Surface(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp)) {
            Box(contentAlignment = Alignment.TopStart) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val formatted = preview?.second
                    if (formatted != null) {
                        SelectionContainer { Text(formatted.text.take(12_000), style = MaterialTheme.typography.bodyLarge) }
                        if (formatted.text.length > 12_000) Text(stringResource(R.string.preview_excerpt),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (preview == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        ExportActions(
            primary = stringResource(R.string.copy), icon = R.drawable.ui_copy, enabled = ready != null, status = message,
            onPrimary = {
                if (ready != null) context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Lantern", ready.text))
                message = resources.getString(R.string.copied)
            },
            onShare = {
                if (ready != null) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, ready.text)
                }, null))
            },
            settingsLabel = stringResource(R.string.format_text), onSettings = { editing = true },
        )
    }
    if (editing) ExportSettingsSheet(stringResource(R.string.text_layout), { editing = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            ExportToggle(stringResource(R.string.include_title), current.title) { change(current.copy(title = it)) }
            ExportToggle(stringResource(R.string.indent), current.indent) { change(current.copy(indent = it)) }
            ExportToggle(stringResource(R.string.compact_blanks), current.compactBlanks) { change(current.copy(compactBlanks = it)) }
        }
    }
}

@Composable
private fun ImageExportPanel(preferences: WritingPreferences, document: Document, onBusy: (Boolean) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(ImageOptions.recommended()) }
    var presets by remember { mutableStateOf<List<ImagePreset>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var layoutError by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<Pair<ImageOptions, ImagePlan>?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    var fullPreview by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    fun run(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        onBusy(true)
        message = ""
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { message = (problem as? ImageExportFailure)?.let { resources.getString(it.label) }
                ?: resources.getString(R.string.operation_failed) }
            finally { busy = false; onBusy(false) }
        }
    }
    LaunchedEffect(Unit) {
        presets = preferences.exportOption("image_presets")?.let { runCatching { Json.decodeFromString<List<ImagePreset>>(it) }.getOrNull() }.orEmpty()
        options = preferences.exportOption("image_export")?.let {
            runCatching { Json.decodeFromString<ImageOptions>(it).validated() }.getOrNull()
        } ?: ImageOptions.recommended()
        loaded = true
    }
    LaunchedEffect(document, options, loaded) {
        if (!loaded) return@LaunchedEffect
        layoutError = ""
        page = 0
        delay(180)
        plan = try {
            options to withContext(Dispatchers.Default) {
                val renderer = imageRenderer(context, document, options)
                ImagePlan(renderer, renderer.pages(), options.jpeg)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            layoutError = resources.getString((problem as? ImageExportFailure)?.label ?: R.string.image_layout_error)
            null
        }
    }
    val ready = plan?.takeIf { it.first == options }?.second
    LaunchedEffect(ready) {
        if (ready != null) {
            try { preferences.setExportOption("image_export", Json.encodeToString(options)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { message = resources.getString(R.string.export_setting_failed) }
        }
    }
    var rendered by remember { mutableStateOf<Pair<ImagePlan, Int>?>(null) }
    LaunchedEffect(ready, page) {
        if (ready != null) {
            val index = page.coerceIn(0, ready.pages.lastIndex)
            val slice = ready.pages.get(index)
            bitmap = withContext(Dispatchers.Default) { ready.renderer.render(slice, index + 1) }
            rendered = ready to index
        }
    }
    val font = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { options = options.copy(font = "custom", customFont = importImageAsset(context, uri, true)) }
    }
    val mark = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { options = options.copy(watermarkImage = importImageAsset(context, uri, false)) }
    }
    fun output(share: Boolean) {
        val selected = ready ?: return
        run {
            val uris = outputImages(context, selected, share)
            if (share) {
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = if (selected.jpeg) "image/jpeg" else "image/png"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("Lantern", uris.first()).also { data ->
                        uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
                    }
                }
                context.startActivity(Intent.createChooser(intent, null))
            } else {
                val total = uris.size
                message = resources.getQuantityString(R.plurals.pages_saved, total, total)
            }
        }
    }
    val pageNavigation: @Composable () -> Unit = {
        if (ready != null && ready.pages.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanternIconButton(R.drawable.ui_previous, stringResource(R.string.previous_page), enabled = !busy && page > 0) { page-- }
                Text("${page + 1} / ${ready.pages.size}", style = MaterialTheme.typography.labelLarge)
                LanternIconButton(R.drawable.ui_next, stringResource(R.string.next_page), enabled = !busy && page + 1 < ready.pages.size) { page++ }
            }
        }
    }
    val imageActions: @Composable (Boolean) -> Unit = { compact ->
        Column {
            if (!compact) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { pageNavigation() }
            ExportActions(
                primary = stringResource(R.string.save_images), icon = R.drawable.ui_download,
                enabled = !busy && ready != null && rendered == (ready to page),
                status = if (busy) stringResource(R.string.working) else message,
                onPrimary = { output(false) }, onShare = { output(true) },
                settingsLabel = stringResource(R.string.image_settings), settingsEnabled = !busy, onSettings = { editing = !editing },
                compact = compact, leading = if (compact) pageNavigation else null,
            )
        }
    }
    val fields: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            ExportTabs(section, listOf(stringResource(R.string.paper), stringResource(R.string.lettering),
                stringResource(R.string.watermark), stringResource(R.string.presets)), enabled = !busy) { section = it }
            androidx.compose.runtime.key(section) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageSettings(section, options, { options = it }, onFont = { font.launch(arrayOf("*/*")) },
                        onMark = { mark.launch(arrayOf("image/*")) },
                        presets = presets, name = name, onName = { name = it },
                        canSavePreset = !busy && name.isNotBlank() && ready != null, onSavePreset = {
                            run {
                                val next = presets.filterNot { it.name == name.trim() } + ImagePreset(name.trim(), options.validated())
                                preferences.setExportOption("image_presets", Json.encodeToString(next))
                                presets = next
                                message = resources.getString(R.string.preset_saved)
                            }
                        }, onReset = { resetting = true })
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideBySide = maxWidth >= 680.dp && maxHeight >= 160.dp
        val compact = maxHeight < 280.dp && maxWidth >= 520.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxSize()) {
                PagePreview(bitmap, layoutError, Modifier.fillMaxWidth().weight(1f), expand = { fullPreview = true })
                imageActions(compact)
            }
            if (editing && sideBySide) Surface(Modifier.width(320.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                fields()
            }
        }
        if (editing && !sideBySide) ExportSettingsSheet(stringResource(R.string.image_settings), { editing = false }) {
            BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(.91f)) {
                val showPreview = maxHeight >= 400.dp
                Column(Modifier.fillMaxSize()) {
                    if (showPreview) PagePreview(bitmap, layoutError, Modifier.fillMaxWidth().height(192.dp),
                        expand = { fullPreview = true })
                    else if (layoutError.isNotEmpty()) Text(layoutError, Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Box(Modifier.weight(1f)) { fields() }
                }
            }
        }
    }
    if (fullPreview && bitmap != null) Dialog(
        onDismissRequest = { fullPreview = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.page_preview), Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium)
                    LanternIconButton(R.drawable.ui_close, stringResource(R.string.close)) { fullPreview = false }
                }
                PagePreview(bitmap, layoutError, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if (resetting) AlertDialog(
        onDismissRequest = { resetting = false },
        title = { Text(stringResource(R.string.reset_image)) },
        text = { Text(stringResource(R.string.reset_image_prompt)) },
        confirmButton = { TextButton({ options = ImageOptions.recommended(options.vertical); resetting = false }) {
            Text(stringResource(R.string.reset_image))
        } },
        dismissButton = { TextButton({ resetting = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ImageSettings(
    section: Int, options: ImageOptions, onOptions: (ImageOptions) -> Unit, onFont: () -> Unit, onMark: () -> Unit,
    presets: List<ImagePreset>, name: String, onName: (String) -> Unit, canSavePreset: Boolean, onSavePreset: () -> Unit, onReset: () -> Unit,
) {
    if (section == 0) {
        SegmentSetting(stringResource(R.string.orientation), options.vertical, listOf(
            false to stringResource(R.string.horizontal), true to stringResource(R.string.vertical),
        )) { onOptions(options.reoriented(it)) }
        SegmentSetting(stringResource(R.string.image_format), options.jpeg, listOf(false to "PNG", true to "JPEG")) {
            onOptions(options.copy(jpeg = it))
        }
        Choice(aspectLabel(options.width, options.height), listOf(
            stringResource(R.string.aspect_portrait) to { onOptions(options.copy(width = 1080, height = 1440)) },
            stringResource(R.string.aspect_square) to { onOptions(options.copy(width = 1080, height = 1080)) },
            stringResource(R.string.aspect_landscape) to { onOptions(options.copy(width = 1440, height = 1080)) },
        ), prompt = stringResource(R.string.aspect_ratio))
        val resolutions = if (options.width == options.height) listOf(720 to 720, 1080 to 1080, 1440 to 1440)
            else if (options.width > options.height) listOf(960 to 720, 1440 to 1080, 1920 to 1440)
            else listOf(720 to 960, 1080 to 1440, 1440 to 1920)
        Choice("${options.width} × ${options.height}", resolutions.map { (width, height) ->
            "$width × $height" to { onOptions(options.copy(width = width, height = height)) }
        }, prompt = stringResource(R.string.resolution))
        Text(stringResource(R.string.custom_resolution), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FieldPair(
            first = { IntField(stringResource(R.string.width_px), options.width, it) { value -> onOptions(options.copy(width = value)) } },
            second = { IntField(stringResource(R.string.height_px), options.height, it) { value -> onOptions(options.copy(height = value)) } },
        )
        LanternSection(stringResource(R.string.margins))
        val line = options.size * 2
        IntSlider(stringResource(R.string.margin_top), options.top, 0..(options.height - options.bottom - line).coerceIn(0, 1400),
            R.string.unit_px, exact = true) { onOptions(options.copy(top = it)) }
        IntSlider(stringResource(R.string.margin_bottom), options.bottom, 0..(options.height - options.top - line).coerceIn(0, 1400),
            R.string.unit_px, exact = true) { onOptions(options.copy(bottom = it)) }
        IntSlider(stringResource(R.string.margin_left), options.left, 0..(options.width - options.right - line).coerceIn(0, 1400),
            R.string.unit_px, exact = true) { onOptions(options.copy(left = it)) }
        IntSlider(stringResource(R.string.margin_right), options.right, 0..(options.width - options.left - line).coerceIn(0, 1400),
            R.string.unit_px, exact = true) { onOptions(options.copy(right = it)) }
    }
    if (section == 1) {
        val availableFonts = buildList {
            addAll(EditorFont.entries.map { it.imageKey to stringResource(it.label) })
            add("serif" to stringResource(R.string.system_serif))
            if (options.customFont != null) add("custom" to stringResource(R.string.custom_font))
            add("pick_file" to stringResource(R.string.pick_file))
        }
        SettingChoice(stringResource(R.string.font), options.font, availableFonts, specimen = { key ->
            if (key == "serif") FontSpecimen(FontFamily.Serif)
            else EditorFont.entries.firstOrNull { it.imageKey == key }?.let { FontSpecimen(fontFamily(it)) }
        }) { selected ->
            if (selected == "pick_file") onFont() else onOptions(options.copy(font = selected))
        }
        val available = minOf(options.width - options.left - options.right, options.height - options.top - options.bottom)
        IntSlider(stringResource(R.string.body_size), options.size, 12..(available / 2).coerceIn(12, 160), R.string.unit_px,
            exact = true) { onOptions(options.copy(size = it)) }
        if (options.vertical || android.os.Build.VERSION.SDK_INT >= 35) {
            IntSlider(stringResource(R.string.letter_spacing), options.letterSpacing, 0..40, R.string.unit_percent,
                exact = true) { onOptions(options.copy(letterSpacing = it)) }
        }
        IntSlider(stringResource(R.string.body_spacing), options.lineSpacing, 0..80, R.string.unit_percent,
            exact = true) { onOptions(options.copy(lineSpacing = it)) }
        ColorPickerSetting(stringResource(R.string.background_color), options.background) { onOptions(options.copy(background = it)) }
        ColorPickerSetting(stringResource(R.string.text_color), options.foreground) { onOptions(options.copy(foreground = it)) }
        ExportToggle(stringResource(R.string.title), options.title) { onOptions(options.copy(title = it)) }
        ExportToggle(stringResource(R.string.page_number), options.pageNumber) { onOptions(options.copy(pageNumber = it)) }
        OutlinedTextField(options.author, { onOptions(options.copy(author = it.take(120))) },
            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.author)) }, singleLine = true)
    }
    if (section == 2) {
        OutlinedTextField(options.watermark, { onOptions(options.copy(watermark = it.take(120))) },
            modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.watermark_text)) }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onMark, modifier = Modifier.weight(1f, fill = false)) { Text(stringResource(R.string.choose_watermark)) }
            if (options.watermarkImage != null) {
                LanternIconButton(R.drawable.ui_close, stringResource(R.string.remove_image)) { onOptions(options.copy(watermarkImage = null)) }
            }
        }
        val positions = listOf(stringResource(R.string.top_left), stringResource(R.string.top_right), stringResource(R.string.bottom_left), stringResource(R.string.bottom_right))
        Choice(positions.getOrElse(options.watermarkPosition) { stringResource(R.string.bottom_right) },
            positions.mapIndexed { index, label -> label to { onOptions(options.copy(watermarkPosition = index)) } },
            prompt = stringResource(R.string.watermark_position))
        IntSlider(stringResource(R.string.mark_size), options.watermarkSize, 5..50, R.string.unit_percent,
            exact = true) { onOptions(options.copy(watermarkSize = it)) }
        IntSlider(stringResource(R.string.mark_opacity), options.watermarkOpacity, 0..100, R.string.unit_percent,
            exact = true) { onOptions(options.copy(watermarkOpacity = it)) }
    }
    if (section == 3) {
        if (presets.isNotEmpty()) Choice(stringResource(R.string.load_preset),
            presets.map { preset -> preset.name to { onOptions(preset.options); onName(preset.name) } })
        OutlinedTextField(name, { onName(it.take(60)) }, modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.preset_name)) }, singleLine = true)
        Button(onClick = onSavePreset, enabled = canSavePreset) { Text(stringResource(R.string.save_preset)) }
        TextButton(onReset) { Text(stringResource(R.string.reset_image)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportTabs(selected: Int, labels: List<String>, enabled: Boolean, select: (Int) -> Unit) {
    SecondaryTabRow(selectedTabIndex = selected, indicator = {
        TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(selected, matchContentSize = true), height = 2.dp)
    }) {
        labels.forEachIndexed { index, label ->
            Tab(selected = selected == index, onClick = { select(index) }, enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                Text(label, Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSettingsSheet(title: String, dismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(dismiss, sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, dragHandle = null) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            LanternIconButton(R.drawable.ui_close, stringResource(R.string.close_settings)) {
                scope.launch { sheet.hide(); if (!sheet.isVisible) dismiss() }
            }
        }
        content()
    }
}

@Composable
private fun PagePreview(bitmap: Bitmap?, error: String, modifier: Modifier, expand: (() -> Unit)? = null) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            when {
                error.isNotEmpty() -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                bitmap == null -> CircularProgressIndicator()
                else -> Image(bitmap.asImageBitmap(), stringResource(R.string.page_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (bitmap != null && error.isEmpty() && expand != null) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    LanternIconButton(R.drawable.ui_fullscreen, stringResource(R.string.expand_preview), onClick = expand)
                }
            }
        }
    }
}

@Composable
private fun ExportActions(
    primary: String, icon: Int, enabled: Boolean, status: String, onPrimary: () -> Unit,
    onShare: (() -> Unit)? = null, settingsLabel: String? = null, settingsEnabled: Boolean = true,
    onSettings: (() -> Unit)? = null, compact: Boolean = false, leading: (@Composable () -> Unit)? = null,
) {
    Column {
        if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) leading()
            if (compact) {
                Spacer(Modifier.weight(1f))
                LanternIconButton(icon, primary, enabled, onPrimary)
            } else Button(onPrimary, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) {
                LanternIcon(icon, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(primary)
            }
            if (onShare != null) LanternIconButton(R.drawable.ui_share, stringResource(R.string.share), enabled, onShare)
            if (onSettings != null) LanternIconButton(R.drawable.ui_tune, settingsLabel ?: stringResource(R.string.settings), settingsEnabled, onSettings)
        }
    }
}

@Composable
private fun Choice(label: String, entries: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier, prompt: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton({ expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            if (prompt != null) {
                Text(prompt, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
            }
            Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = if (prompt == null) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End,
                color = if (prompt == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            LanternIcon(R.drawable.ui_expand, size = 20.dp)
        }
        DropdownMenu(expanded, { expanded = false }) {
            entries.forEach { (name, action) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; action() },
                    trailingIcon = if (name == label) ({ LanternIcon(R.drawable.ui_check, stringResource(R.string.selected_label)) }) else null)
            }
        }
    }
}

@Composable
private fun ExportToggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onCheckedChange = null)
    }
}

@Composable
private fun FieldPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 240.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.weight(1f))
                second(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                second(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun IntField(label: String, value: Int, modifier: Modifier = Modifier, change: (Int) -> Unit) {
    var text by remember(label) { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    OutlinedTextField(text, { next ->
        if (next.length <= 4 && next.all { it in '0'..'9' }) {
            text = next
            next.toIntOrNull()?.let(change)
        }
    }, modifier = modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

internal fun aspectLabel(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "$width:$height"
    var a = width
    var b = height
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return "${width / a}:${height / a}"
}
@Composable
private fun textTargetLabel(target: TextTarget) =
    if (target == TextTarget.Plain) stringResource(R.string.plain_text) else target.label
