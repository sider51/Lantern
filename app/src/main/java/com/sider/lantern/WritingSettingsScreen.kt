package com.sider.lantern

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.launch

private enum class SettingPage(val route: String, val label: Int, val icon: Int) {
    Appearance("settings/appearance", R.string.appearance, R.drawable.ui_palette),
    Editor("settings/editor", R.string.editor_settings, R.drawable.ui_edit),
    Writing("settings/writing", R.string.writing_aid, R.drawable.ui_keyboard),
    Library("settings/library", R.string.library, R.drawable.ui_inbox),
    Gestures("settings/gestures", R.string.gestures, R.drawable.ui_arrow_right),
    Export("settings/export", R.string.export, R.drawable.ui_export),
    Storage("files", R.string.storage_backup, R.drawable.ui_storage),
    Trash("settings/trash", R.string.trash, R.drawable.ui_delete),
    Language("settings/language", R.string.language, R.drawable.ui_language),
    About("about", R.string.about, R.drawable.ui_info),
}

@Composable
internal fun SettingsHome(onBack: () -> Unit, open: (String) -> Unit) {
    SettingsLayout(stringResource(R.string.settings), onBack) {
        for (page in SettingPage.entries) LanternActionRow(page.icon, stringResource(page.label)) { open(page.route) }
    }
}

@Composable
internal fun SettingsSection(section: String, model: LanternModel, writing: WritingOptions, onBack: () -> Unit,
    openTrash: () -> Unit = {}) {
    val page = SettingPage.entries.first { it.route == "settings/$section" }
    val prefs = model.writing
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val config by model.customization.collectAsStateWithLifecycle()
    val appearance by prefs.appearance.collectAsStateWithLifecycle("system")
    val category by model.writingCategory.collectAsStateWithLifecycle()
    val exportTab by prefs.defaultExportTab.collectAsStateWithLifecycle(0)
    var failed by remember { mutableStateOf(false) }
    var confirmRetention by rememberSaveable { mutableStateOf<Int?>(null) }
    fun write(action: suspend () -> Unit) {
        scope.launch {
            failed = false
            try { action() } catch (_: IOException) { failed = true }
        }
    }
    fun set(key: String, value: String) = write { prefs.setExportOption(key, value) }
    SettingsLayout(stringResource(page.label), onBack) {
        when (page) {
            SettingPage.Appearance -> {
                SettingChoice(stringResource(R.string.display_mode), appearance, listOf(
                    "system" to stringResource(R.string.system_mode), "light" to stringResource(R.string.light_mode),
                    "dark" to stringResource(R.string.dark_mode),
                )) { set("appearance", it) }
                SettingChoice(stringResource(R.string.color_theme), config.lantern, listOf(
                    false to stringResource(R.string.dynamic_color), true to stringResource(R.string.lantern_palette),
                )) { set("palette", if (it) "lantern" else "dynamic") }
            }
            SettingPage.Editor -> {
                var size by remember(config.textSize) { mutableIntStateOf(config.textSize) }
                var gap by remember(config.lineGap) { mutableIntStateOf(config.lineGap) }
                var margin by remember(config.margin) { mutableIntStateOf(config.margin) }
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.font_specimen), Modifier.padding(horizontal = margin.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = fontFamily(config.font), fontSize = size.sp, lineHeight = (size + gap + 4).sp,
                        ))
                }
                SettingChoice(stringResource(R.string.body_font), config.font,
                    EditorFont.entries.map { it to stringResource(it.label) },
                    specimen = { FontSpecimen(fontFamily(it)) }) { set("editor_font", it.name) }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    IntSlider(stringResource(R.string.body_size), config.textSize, 14..26, R.string.unit_sp,
                        onPreview = { size = it }) { set("editor_size", it.toString()) }
                    IntSlider(stringResource(R.string.body_spacing), config.lineGap, 0..12, R.string.unit_dp,
                        onPreview = { gap = it }) { set("editor_gap", it.toString()) }
                    IntSlider(stringResource(R.string.body_margin), config.margin, 12..40, R.string.unit_dp,
                        onPreview = { margin = it }) { set("editor_margin", it.toString()) }
                }
            }
            SettingPage.Writing -> {
                for (setting in WritingSetting.entries) {
                    SettingSwitch(stringResource(setting.label), writing[setting]) { enabled ->
                        write { prefs.change(setting, enabled) }
                    }
                }
                SettingSwitch(stringResource(R.string.toolbar_show), config.toolbarExpanded) { set("toolbar_expanded", it.toString()) }
                SettingChoice(stringResource(R.string.toolbar_default_category), category ?: WritingCategory.Symbols,
                    WritingCategory.entries.map { it to stringResource(it.label) }) { item -> write { prefs.select(item) } }
            }
            SettingPage.Library -> SettingSwitch(stringResource(R.string.show_recent), config.showRecent) { set("show_recent", it.toString()) }
            SettingPage.Gestures -> {
                val choices = SwipeAction.entries.map { it to stringResource(it.label) }
                SettingChoice(stringResource(R.string.swipe_left), config.left, choices) { set("swipe_left", it.name) }
                SettingChoice(stringResource(R.string.swipe_right), config.right, choices) { set("swipe_right", it.name) }
            }
            SettingPage.Export -> SettingChoice(stringResource(R.string.export_default), exportTab,
                listOf(0 to stringResource(R.string.text_tab), 1 to stringResource(R.string.image_tab), 2 to stringResource(R.string.json_tab))) {
                set("export_tab", it.toString())
            }
            SettingPage.Trash -> {
                val choices = listOf(7, 30, 90).map { it as Int? to pluralStringResource(R.plurals.days, it, it) } +
                    (null to stringResource(R.string.keep_forever))
                SettingChoice(stringResource(R.string.trash_retention), config.retention.days, choices) { days ->
                    val previousDays = config.retention.days
                    if (days != null && (previousDays == null || days < previousDays)) confirmRetention = days
                    else write { prefs.setRetention(days) }
                }
                LanternActionRow(R.drawable.ui_delete, stringResource(R.string.trash), onClick = openTrash)
            }
            SettingPage.Language -> {
                val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                    .filterIsInstance<MainActivity>().first()
                SettingChoice(stringResource(R.string.language), activity.appLanguage().substringBefore(',').substringBefore('-'),
                    listOf("" to stringResource(R.string.system_mode), "ja" to stringResource(R.string.japanese),
                        "en" to stringResource(R.string.english)), change = activity::selectLanguage)
            }
            else -> Unit
        }
        if (failed) Text(stringResource(R.string.setting_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    }
    confirmRetention?.let { days ->
        AlertDialog(
            onDismissRequest = { confirmRetention = null },
            title = { Text(stringResource(R.string.confirm_retention_title)) },
            text = { Text(stringResource(R.string.confirm_retention_body)) },
            confirmButton = {
                TextButton({ confirmRetention = null; write { prefs.setRetention(days) } }) { Text(stringResource(R.string.continue_action)) }
            },
            dismissButton = { TextButton({ confirmRetention = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, change: (Boolean) -> Unit) {
    ListItem(trailingContent = { Switch(checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = change)) { Text(title) }
}

@Composable
internal fun <T> SettingChoice(label: String, current: T, options: List<Pair<T, String>>, enabled: Boolean = true,
    specimen: (@Composable (T) -> Unit)? = null, change: (T) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val selected = options.firstOrNull { it.first == current }?.second.orEmpty()
    ListItem(
        supportingContent = { Text(selected) },
        trailingContent = { LanternIcon(R.drawable.ui_next) },
        modifier = Modifier.clickable(enabled = enabled) { choosing = true },
    ) { Text(label) }
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(label) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).selectableGroup().verticalScroll(rememberScrollState())) {
                for ((value, title) in options) ListItem(
                    supportingContent = if (specimen == null) null else ({ specimen(value) }),
                    trailingContent = { RadioButton(value == current, onClick = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.selectable(value == current, role = Role.RadioButton) { choosing = false; change(value) },
                ) { Text(title) }
            }
        },
        confirmButton = { TextButton({ choosing = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsLayout(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { LanternBack(onClick = onBack) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                content()
            }
        }
    }
}

@Composable
internal fun LanternAboutScreen(onBack: () -> Unit, onLicenses: () -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    SettingsLayout(stringResource(R.string.about), onBack) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(R.drawable.lantern_brand), null, Modifier.size(64.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.version, version), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
        }
        LanternActionRow(R.drawable.ui_document, stringResource(R.string.licenses), onClick = onLicenses)
    }
}

@Composable
internal fun LanternLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SettingsLayout(stringResource(R.string.licenses), onBack) {
        for ((name, file) in listOf(
            "Lantern — MIT License" to "lantern-mit.txt",
            "AndroidX, Kotlin, kotlinx" to "apache-2.0.txt",
            "Noto Sans JP" to "noto-sans-jp-ofl.txt",
            "Noto Serif JP" to "noto-serif-jp-ofl.txt",
            "Zen Kaku Gothic New" to "zen_kaku_gothic_new-ofl.txt",
            "Zen Old Mincho" to "zen_old_mincho-ofl.txt",
        )) {
            var expanded by rememberSaveable(file) { mutableStateOf(false) }
            LanternActionRow(R.drawable.ui_document, name) { expanded = !expanded }
            if (expanded) {
                val license = remember(file) { context.assets.open(file).bufferedReader().use { it.readText() } }
                SelectionContainer { Text(license, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
