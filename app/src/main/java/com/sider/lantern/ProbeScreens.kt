package com.sider.lantern

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class TypesettingProbe {
    private var font = Typeface.DEFAULT
    var result by mutableStateOf<Result?>(null)
        private set
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)

    data class Result(
        val renderer: PageRenderer,
        val pages: List<PageSlice>,
        val index: Int,
        val bitmap: Bitmap,
    )

    suspend fun prepare(vertical: Boolean, size: Int) {
        val requestedFont = font
        result = withContext(Dispatchers.Default) {
            val renderer = PageRenderer(sampleDocument(size), vertical, requestedFont)
            val pages = renderer.pages()
            Result(renderer, pages, 0, renderer.render(pages.first()))
        }
        status = ""
    }

    suspend fun show(index: Int) {
        val old = result ?: return
        result = withContext(Dispatchers.Default) {
            old.copy(index = index, bitmap = old.renderer.render(old.pages[index]))
        }
    }

    suspend fun font(context: Context, uri: Uri) {
        font = withContext(Dispatchers.IO) {
            checkNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use {
                checkNotNull(Typeface.Builder(it.fileDescriptor).build()) { "フォントを読み込めませんでした" }
            }
        }
        result = null
        status = "フォントを読み込みました。横書きまたは縦書きを選択してください"
    }

    fun resetFont() {
        font = Typeface.DEFAULT
        result = null
        status = "システムフォントに戻しました"
    }

    suspend fun saveSamples(context: Context) {
        val requestedFont = font
        withContext(Dispatchers.IO) {
            val document = sampleDocument(700)
            for (vertical in listOf(false, true)) {
                val renderer = PageRenderer(document, vertical, requestedFont)
                val name = if (vertical) "vertical.png" else "horizontal.png"
                val bitmap = renderer.render(renderer.pages().first())
                try {
                    savePng(bitmap, File(context.filesDir, "typesetting/$name"))
                } finally {
                    bitmap.recycle()
                }
            }
        }
        status = "横書きと縦書きの1ページ目を files/typesetting に保存しました"
    }
}

@Composable
internal fun TypesettingScreen(probe: TypesettingProbe) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    fun work(action: suspend () -> Unit) {
        if (probe.busy) return
        probe.busy = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                probe.status = "組版エラー（${failure.javaClass.simpleName}） ${failure.message.orEmpty()}"
            } finally {
                probe.busy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri != null) work { probe.font(context, uri) }
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { work { probe.prepare(false, 700) } }, enabled = !probe.busy) { Text("横書き") }
            Button(onClick = { work { probe.prepare(true, 700) } }, enabled = !probe.busy) { Text("縦書き") }
            TextButton(onClick = { work { probe.prepare(true, 50_000) } }, enabled = !probe.busy) { Text("縦書き5万") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { picker.launch(arrayOf("font/*", "application/octet-stream")) }, enabled = !probe.busy) {
                Text("フォントを選ぶ")
            }
            TextButton(onClick = probe::resetFont, enabled = !probe.busy) { Text("標準フォント") }
            TextButton(onClick = { work { probe.saveSamples(context) } }, enabled = !probe.busy) { Text("PNG 2枚を保存") }
        }
        if (probe.busy) Text("組版を処理中")
        if (probe.status.isNotEmpty()) Text(probe.status)
        probe.result?.let { result ->
            val page = result.pages[result.index]
            Row {
                TextButton(onClick = { work { probe.show(result.index - 1) } }, enabled = !probe.busy && result.index > 0) {
                    Text("前へ")
                }
                TextButton(
                    onClick = { work { probe.show(result.index + 1) } },
                    enabled = !probe.busy && result.index < result.pages.lastIndex,
                ) { Text("次へ") }
            }
            Text(
                "${result.index + 1}/${result.pages.size} ページ · UTF-16 ${page.start}–${page.end}" +
                    if (page.forcedWrap) " · 強制折返し" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Image(
                bitmap = result.bitmap.asImageBitmap(),
                contentDescription = "検証原稿の組版プレビュー ${result.index + 1}ページ",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal class StorageProbe(context: Context) {
    private val internal = AtomicDocumentStore(File(context.filesDir, "phase0/internal.json"))
    val external = SafProbe(context)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")

    fun save(): String {
        internal.save(sampleDocument())
        return "合成原稿を内部に保存し、再読込を確認しました"
    }

    fun load(): String {
        val loaded = internal.load()
        return "${if (loaded.recovered) "復旧版" else "正本"}を読込（${loaded.document.title}）"
    }
}

@Composable
internal fun StorageScreen(probe: StorageProbe) {
    val scope = rememberCoroutineScope()
    fun work(action: () -> String) {
        if (probe.busy) return
        probe.busy = true
        scope.launch {
            try {
                probe.status = withContext(Dispatchers.IO) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                probe.status = "保存検証エラー（${failure.javaClass.simpleName}） ${failure.message.orEmpty()}"
            } finally {
                probe.busy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        if (uri != null) work { probe.external.create(uri) }
    }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("アプリ内保存", style = MaterialTheme.typography.titleMedium)
        Row {
            TextButton(onClick = { work(probe::save) }, enabled = !probe.busy) { Text("合成原稿を保存") }
            TextButton(onClick = { work(probe::load) }, enabled = !probe.busy) { Text("再読込") }
        }
        Text("SAF", style = MaterialTheme.typography.titleMedium)
        Text("新しく作成した専用テストフォルダを選択してください。")
        Button(onClick = { picker.launch(null) }, enabled = !probe.busy) { Text("専用フォルダを選ぶ") }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { work(probe.external::inspect) }, enabled = !probe.busy) { Text("再読込・差分確認") }
            TextButton(onClick = { work(probe.external::update) }, enabled = !probe.busy) { Text("更新") }
            TextButton(onClick = { work(probe.external::rename) }, enabled = !probe.busy) { Text("改名") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { work(probe.external::recovery) }, enabled = !probe.busy) { Text("内部復旧版を確認") }
            TextButton(onClick = { work(probe.external::release) }, enabled = !probe.busy) { Text("アクセス権を解放") }
        }
        if (probe.busy) Text("保存を検証中")
        if (probe.status.isNotEmpty()) Text(probe.status)
    }
}
