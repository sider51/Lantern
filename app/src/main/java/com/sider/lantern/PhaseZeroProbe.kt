package com.sider.lantern

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PhaseZeroActivity : ComponentActivity() {
    private val model by viewModels<ProbeModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LanternTheme { LanternProbe(model) } }
    }
}

internal class ProbeModel : ViewModel() {
    val composeEditor = EditorSession()
    val editTextEditor = EditorSession()
    val typesetting = TypesettingProbe()
    private var storage: StorageProbe? = null

    fun storage(context: Context): StorageProbe =
        storage ?: StorageProbe(context.applicationContext).also { storage = it }
}

private enum class Screen(val label: String) {
    Home("Lantern Phase 0"), ComposeEditor("Compose Editor"), EditTextEditor("EditText Editor"),
    Typesetting("Typesetting"), Storage("Storage"), Diagnostics("Diagnostics"),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LanternTheme(dark: Boolean = isSystemInDarkTheme(), lantern: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (lantern) lanternColors(dark) else if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        androidx.compose.material3.darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFFACC9C0),
            surface = androidx.compose.ui.graphics.Color(0xFF171E1C),
            background = androidx.compose.ui.graphics.Color(0xFF171E1C),
        )
    } else {
        androidx.compose.material3.lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF365D54),
            surface = androidx.compose.ui.graphics.Color(0xFFF8FAF7),
            background = androidx.compose.ui.graphics.Color(0xFFF8FAF7),
        )
    }
    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialExpressiveTheme(colorScheme = colors, content = content)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LanternProbe(model: ProbeModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val context = LocalContext.current
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(screen.label) },
                navigationIcon = {
                    if (screen != Screen.Home) TextButton(onClick = { screen = Screen.Home }) { Text("戻る") }
                },
            )
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        Column(content) {
            key(screen) {
                when (screen) {
                    Screen.Home -> Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (destination in Screen.entries.drop(1)) {
                            Button(onClick = { screen = destination }, modifier = Modifier.fillMaxWidth()) {
                                Text(destination.label)
                            }
                        }
                    }
                    Screen.ComposeEditor -> EditorScreen(model.composeEditor, useCompose = true)
                    Screen.EditTextEditor -> EditorScreen(model.editTextEditor, useCompose = false)
                    Screen.Typesetting -> TypesettingScreen(model.typesetting)
                    Screen.Storage -> StorageScreen(model.storage(context))
                    Screen.Diagnostics -> Diagnostics()
                }
            }
        }
    }
}

@Composable
private fun Diagnostics() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var memory by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        Text("アプリ ID ${context.packageName}")
        Text("システム言語 ${LocalLocale.current.platformLocale.toLanguageTag()}")
        Text("組版ロケール ${japaneseStrict.toLanguageTag()}")
        Button(onClick = { scope.launch { memory = withContext(Dispatchers.IO) { "プロセス PSS ${Debug.getPss()} KiB" } } }) {
            Text("現在のメモリを測定")
        }
        memory?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
