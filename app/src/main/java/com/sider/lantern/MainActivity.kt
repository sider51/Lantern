package com.sider.lantern

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    private val model by viewModels<LanternModel>()

    override fun attachBaseContext(base: Context) {
        val localePrefs = base.getSharedPreferences("locale", MODE_PRIVATE)
        if (!localePrefs.contains("initialized")) {
            val firstInstall = !base.getDatabasePath("lantern.db").exists() && !localePrefs.contains("language")
            if (firstInstall) {
                localePrefs.edit { putBoolean("new_default_lantern", true) }
                if (Build.VERSION.SDK_INT >= 33) {
                    val manager = base.getSystemService(LocaleManager::class.java)
                    if (manager.applicationLocales.isEmpty) manager.applicationLocales = LocaleList.forLanguageTags("ja")
                } else localePrefs.edit { putString("language", "ja") }
            }
            localePrefs.edit { putBoolean("initialized", true) }
        }
        val tag = if (Build.VERSION.SDK_INT < 33) base.getSharedPreferences("locale", MODE_PRIVATE)
            .getString("language", "").orEmpty() else ""
        super.attachBaseContext(if (tag.isEmpty()) base else base.createConfigurationContext(
            Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) },
        ))
    }

    internal fun appLanguage(): String =
        if (Build.VERSION.SDK_INT >= 33) getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else getSharedPreferences("locale", MODE_PRIVATE).getString("language", "").orEmpty()

    internal fun selectLanguage(tag: String) {
        if (tag == appLanguage()) return
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            getSharedPreferences("locale", MODE_PRIVATE).edit { putString("language", tag) }
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by model.writing.appearance.collectAsStateWithLifecycle("system")
            val customization by model.customization.collectAsStateWithLifecycle()
            val dark = when (appearance) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            LanternTheme(dark = dark, lantern = customization.lantern) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LanternApp(model)
                }
            }
        }
    }
}

@Composable
private fun LanternApp(model: LanternModel) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val options by model.writingOptions.collectAsStateWithLifecycle()
    val category by model.writingCategory.collectAsStateWithLifecycle()
    var opening by remember { mutableStateOf(false) }
    val settings = { nav.navigate("settings") { launchSingleTop = true } }
    if (!model.ready || options == null || category == null) {
        CenteredStatus(
            if (model.startupFailed) stringResource(R.string.library_error) else null,
            if (model.startupFailed) stringResource(R.string.retry) else null,
            model::initialize,
        )
        return
    }
    val open: (String) -> Unit = { id ->
        if (!opening) {
            opening = true
            scope.launch {
                try {
                    if (model.open(id)) nav.navigate("editor/$id") { launchSingleTop = true }
                    else android.widget.Toast.makeText(context, R.string.draft_missing, android.widget.Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, R.string.draft_error, android.widget.Toast.LENGTH_SHORT).show()
                } finally { opening = false }
            }
        }
    }
    val folder: (String) -> Unit = nav::openFolder
    val search: () -> Unit = { nav.navigate("search") { launchSingleTop = true } }
    val create: (String) -> Unit = { folderId ->
        if (!opening) nav.navigate("editor/${model.newDraft(folderId)}") { launchSingleTop = true }
    }
    val back: () -> Unit = { nav.popBackStack() }
    val export: (String) -> Unit = { id -> nav.navigate("export/$id") { launchSingleTop = true } }
    NavHost(
        navController = nav,
        startDestination = "library",
        enterTransition = { fadeIn(tween(190)) },
        exitTransition = { fadeOut(tween(150)) },
    ) {
        composable("library") {
            LibraryScreen(model, onFolder = folder, onOpen = open, onSearch = search, onNew = { create(INBOX_ID) }, onBack = back,
                onSettings = settings,
                onFiles = { nav.navigate("files") { launchSingleTop = true } },
                onTrash = { nav.navigate("trash") { launchSingleTop = true } }, onExport = export)
        }
        composable("files") { LibraryFilesScreen(model, false, back) }
        composable("trash") { LibraryFilesScreen(model, true, back) }
        composable("export/{id}") { entry ->
            val id = checkNotNull(entry.arguments?.getString("id"))
            ExportDestination(model, id, back, model.editor?.takeIf { it.id == id }?.let {
                it.editor.committed.document.copy(title = it.title.text)
            })
        }
        composable("settings") {
            SettingsHome(back) { route -> nav.navigate(route) }
        }
        composable("settings/{section}") { entry ->
            SettingsSection(checkNotNull(entry.arguments?.getString("section")), model, options ?: WritingOptions(), back) { nav.navigate("trash") }
        }
        composable("about") { LanternAboutScreen(back) { nav.navigate("licenses") } }
        composable("licenses") { LanternLicensesScreen(back) }
        composable("folder/{id}") { entry ->
            val id = checkNotNull(entry.arguments?.getString("id"))
            LibraryScreen(model, folderId = id,
                onFolder = folder, onOpen = open, onSearch = search, onNew = { create(id) }, onBack = back, onExport = export)
        }
        composable("search") {
            LibraryScreen(model, searching = true, onFolder = folder, onOpen = open,
                onSearch = search, onNew = { create(INBOX_ID) }, onBack = back, onExport = export)
        }
        composable("editor/{id}") { entry ->
            val id = checkNotNull(entry.arguments?.getString("id"))
            EditorDestination(model, id, checkNotNull(options), checkNotNull(category), onExport = { export(id) },
                onSettings = { nav.navigate("settings/editor") }, onExit = {
                model.close(id)
                nav.popBackStack()
            })
        }
    }
}

internal fun NavController.openFolder(id: String) {
    val current = currentBackStackEntry
    if (current?.destination?.route == "folder/{id}" && current.arguments?.getString("id") == id) return
    navigate("folder/$id")
}

@Composable
private fun EditorDestination(
    model: LanternModel, id: String, options: WritingOptions, category: WritingCategory,
    onExport: () -> Unit, onSettings: () -> Unit, onExit: () -> Unit,
) {
    val resources = LocalResources.current
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        try {
            if (!model.open(id)) error = resources.getString(R.string.draft_missing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = resources.getString(R.string.draft_error)
        }
    }
    val draft = model.editor?.takeIf { it.id == id }
    if (draft == null) CenteredStatus(error, stringResource(R.string.back), onExit)
    else key(draft.id) { ProductEditor(draft, model, options, category, onExit, onExport, onSettings) }
}

@Composable
private fun CenteredStatus(message: String?, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (message == null) CircularProgressIndicator() else Text(message)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
