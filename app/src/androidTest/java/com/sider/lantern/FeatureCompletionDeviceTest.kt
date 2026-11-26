package com.sider.lantern

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureCompletionDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun verticalSpacingSeparatesGlyphsWithoutChangingTheirBounds() {
        val document = Document.create("", "甲乙丙\n天地人")
        fun ink(letter: Int, line: Int, horizontal: Boolean): List<IntRange> {
            val renderer = PageRenderer(document, true, Typeface.DEFAULT,
                ImageOptions(letterSpacing = letter, lineSpacing = line))
            val bitmap = renderer.render(renderer.pages().single())
            try {
                val length = if (horizontal) bitmap.width else bitmap.height
                val scan = if (horizontal) bitmap.height else bitmap.width
                val filled = BooleanArray(length) { at ->
                    (0 until scan).any { offset ->
                        val pixel = if (horizontal) bitmap.getPixel(at, offset) else bitmap.getPixel(offset, at)
                        (pixel and 0xff) < 160
                    }
                }
                return buildList {
                    var start = -1
                    for (i in filled.indices) {
                        if (filled[i] && start == -1) start = i
                        else if (!filled[i] && start >= 0) { add(start until i); start = -1 }
                    }
                }
            } finally { bitmap.recycle() }
        }
        for (horizontal in listOf(true, false)) {
            val original = ink(0, 0, horizontal)
            val spaced = ink(25, 50, horizontal)
            assertTrue(original.size >= 2)
            assertEquals(original.map { it.last - it.first }, spaced.map { it.last - it.first })
            assertTrue(spaced.last().last - spaced.first().first > original.last().last - original.first().first)
        }
        val styled = sampleDocument(1_200)
        val renderer = PageRenderer(styled, true, Typeface.DEFAULT, ImageOptions(letterSpacing = 30, lineSpacing = 35))
        val pages = renderer.pages()
        assertTrue(pages.size > 1)
        assertEquals(styled.body, pages.joinToString("") { styled.body.substring(it.start, it.end) })
        assertTrue(styled.pageBreaks.all { offset -> pages.any { it.end == offset } })
        pages.forEach { page ->
            assertTrue(styled.annotations.none { it.format == Format.Ruby && page.end in (it.start + 1) until it.end })
            renderer.render(page).recycle()
        }
    }

    @Test fun folderTrashAndBackupRestoreKeepStylesAndAvoidNameCollisions() = runBlocking {
        val name = "phase3-${UUID.randomUUID()}.db"
        val db = LibraryDatabase.open(context, name)
        try {
            var time = 10L
            val library = LibraryRepository(db, clock = { time })
            library.initialize()
            val parent = library.createFolder("作品", ROOT_ID)
            val child = library.createFolder("短編", parent.id)
            val draft = sampleDocument()
            library.save(draft, null, 1)
            library.moveManuscript(draft.id, child.id)
            val backup = Json.decodeFromString<LibraryArchive>(Json.encodeToString(library.backup()))
            library.discardFolder(parent.id)
            assertNull(library.load(draft.id))
            library.createFolder("作品", ROOT_ID)
            val deleted = library.trash.first().single()
            library.restoreTrash(deleted.id)
            assertEquals(draft, library.load(draft.id)?.document)
            assertTrue(library.folders.first().any { it.name == "作品 (2)" })
            library.importArchive(backup)
            val manuscripts = library.backup().manuscripts
            assertEquals(2, manuscripts.size)
            assertEquals(2, manuscripts.map { it.document.id }.toSet().size)
            assertTrue(manuscripts.all { it.document.annotations == draft.annotations })
            library.discardManuscript(draft.id)
            time += TRASH_RETENTION
            library.purgeExpired()
            assertTrue(library.trash.first().isEmpty())
            assertEquals(1, library.backup().manuscripts.size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun imagePagesRoundTripThroughActualPngAndJpegShareFiles() = runBlocking {
        val document = sampleDocument(1_200)
        for (vertical in listOf(true, false)) {
            val options = ImageOptions(vertical = vertical, width = 520, height = 640, size = 28,
                author = "合成原稿", title = true, pageNumber = true, jpeg = !vertical, watermark = "見本")
            val cjk = File("/system/fonts").listFiles().orEmpty().firstOrNull {
                "CJK" in it.name && "Regular" in it.name && it.extension == "ttc"
            }
            val font = if (vertical && cjk != null) Typeface.createFromFile(cjk) else Typeface.DEFAULT
            val renderer = PageRenderer(document, vertical, font, options)
            val pages = renderer.pages()
            assertTrue(pages.size > 1)
            assertEquals(document.body, pages.joinToString("") { document.body.substring(it.start, it.end) })
            assertTrue(document.pageBreaks.all { offset -> pages.any { it.end == offset } })
            val files = outputImages(context, ImagePlan(renderer, pages, options.jpeg), true)
            assertEquals(pages.size, files.size)
            for (file in files) {
                val bitmap = context.contentResolver.openInputStream(file).use { BitmapFactory.decodeStream(it) }
                try {
                    assertEquals(520, bitmap.width)
                    assertEquals(640, bitmap.height)
                } finally { bitmap.recycle(); context.contentResolver.delete(file, null, null) }
            }
        }
    }

    @Test fun dedicatedSafTreePreservesChangesAcrossReopenAndRejectsExternalOverwrite() = runBlocking {
        val folder = InstrumentationRegistry.getArguments().getString("lanternSafTree")
        assumeTrue(folder != null)
        val folderId = "primary:Documents/$folder"
        val initial = Uri.parse("content://com.android.externalstorage.documents/document").buildUpon()
            .appendPath(folderId).build()
        val picked = CompletableDeferred<Uri?>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked.complete(it) }
                    LaunchedEffect(Unit) { launcher.launch(initial) }
                    Text("Lantern SAF テスト")
                }
            }
            val tree = checkNotNull(withTimeout(110_000) { picked.await() })
            require(tree.authority == "com.android.externalstorage.documents" && DocumentsContract.getTreeDocumentId(tree) == folderId)
            val settings = "saf-test-${UUID.randomUUID()}"
            val name = "$settings.db"
            var db = LibraryDatabase.open(context, name)
            val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                var external = ExternalLibrary(context, settings)
                var library = LibraryRepository(db, external = external)
                library.initialize()
                val original = sampleDocument()
                library.save(original, null, 1)
                val destination = library.createFolder("LAN-原稿", ROOT_ID)
                library.moveManuscript(original.id, destination.id)
                library.selectExternal(tree)
                val edited = original.copy(title = "SAF 合成原稿")
                library.save(edited, 2, 3)
                db.close()
                db = LibraryDatabase.open(context, name)
                external = ExternalLibrary(context, settings)
                library = LibraryRepository(db, external = external)
                library.initialize()
                assertEquals(edited, library.load(original.id)?.document)
                val resolver = context.contentResolver
                val manifestUri = Uri.parse(checkNotNull(external.generation).uri)
                val manifest = resolver.openInputStream(manifestUri)!!.use { it.readDocumentBytes().decodeToString() }
                val files = Json.parseToJsonElement(manifest).jsonObject.getValue("files").jsonObject
                val fileUri = Uri.parse(files.getValue(original.id).jsonObject.getValue("uri").jsonPrimitive.content)
                resolver.openOutputStream(fileUri, "rwt")!!.use { it.write("external change".encodeToByteArray()) }
                try {
                    library.save(edited.copy(title = "競合"), 3, 4)
                    fail("An external change was overwritten")
                } catch (_: ExternalChanged) {
                    assertEquals("external change", resolver.openInputStream(fileUri)!!.use { it.readDocumentBytes().decodeToString() })
                }
                resolver.releasePersistableUriPermission(tree, grant)
                library.reloadExternal()
                assertTrue(library.externalStatus.orEmpty().contains("読み込めません"))
                library.recoverInternal()
                assertEquals(edited, library.load(original.id)?.document)
            } finally {
                db.close()
                context.deleteDatabase(name)
                context.deleteSharedPreferences(settings)
                runCatching { context.contentResolver.releasePersistableUriPermission(tree, grant) }
            }
        }
    }

    @Test fun compactWritingToolbarFitsInLandscapeActivity() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            withTimeout(8_000) {
                var isLandscape = false
                while (!isLandscape) {
                    scenario.onActivity { isLandscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    if (!isLandscape) delay(100)
                }
            }
            val measured = CompletableDeferred<IntSize>()
            scenario.onActivity { activity ->
                val editor = EditorSession(Document.create("", "合成原稿"))
                activity.setContent {
                    LanternTheme {
                        Box(Modifier.width(280.dp).height(65.dp).onGloballyPositioned { measured.complete(it.size) }) {
                            WritingToolbar(true, WritingCategory.Symbols, {}, editor,
                                { _, _ -> }, {}, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
            val size = withTimeout(6_000) { measured.await() }
            assertTrue(size.width > size.height * 4)
            val capture = CompletableDeferred<Bitmap>()
            scenario.onActivity { activity ->
                val display = activity.window.decorView
                val bitmap = Bitmap.createBitmap(display.width, display.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) capture.complete(bitmap)
                    else capture.completeExceptionally(IllegalStateException("PixelCopy: $result"))
                }, Handler(Looper.getMainLooper()))
            }
            val bitmap = withTimeout(6_000) { capture.await() }
            try {
                assertTrue(bitmap.width > bitmap.height)
                File(context.cacheDir, "phase3-compact.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
