package com.sider.lantern

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.provider.MediaStore
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefinementDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun firstSaveUsesTheRequestedFolderAndSortKeepsItsDirection() = runBlocking {
        val filename = "refinement-${UUID.randomUUID()}.db"
        val database = LibraryDatabase.open(context, filename)
        try {
            var time = 1L
            val library = LibraryRepository(database, clock = { time++ })
            library.initialize()
            val parent = library.createFolder("Stories", ROOT_ID)
            val child = library.createFolder("Winter", parent.id)
            withContext(Dispatchers.Main) {
                for ((name, folder) in listOf("Home" to INBOX_ID, "Zoe" to child.id, "Amy" to child.id, "Folder" to parent.id)) {
                    val draft = ProductDraft(Document.create("", ""), null, this, library, initialFolder = folder)
                    assertTrue(draft.autosave.flush())
                    assertNull(library.load(draft.id))
                    draft.updateTitle(TextFieldValue(name))
                    assertTrue(draft.autosave.flush())
                    assertEquals(folder, library.load(draft.id)?.folderId)
                    draft.updateTitle(TextFieldValue("$name 2"))
                    assertTrue(draft.autosave.flush())
                    assertEquals(folder, library.load(draft.id)?.folderId)
                }
            }
            suspend fun titles(byTitle: Boolean, descending: Boolean) =
                library.summaries(child.id, "", byTitle, descending = descending).first().map { it.title }
            assertEquals(listOf("Amy 2", "Zoe 2"), titles(true, false))
            assertEquals(listOf("Zoe 2", "Amy 2"), titles(true, true))
            assertEquals(listOf("Zoe 2", "Amy 2"), titles(false, false))
            assertEquals(listOf("Amy 2", "Zoe 2"), titles(false, true))
        } finally {
            database.close()
            context.deleteDatabase(filename)
        }
    }

    @Test fun resetRejectionKeepsWritesAvailableAndAcceptedResetFencesOldWriters() = runBlocking {
        val filename = "refinement-${UUID.randomUUID()}.db"
        val database = LibraryDatabase.open(context, filename)
        try {
            val library = LibraryRepository(database)
            library.initialize()
            assertFalse(library.requestLocalReset { false })
            assertTrue(runCatching { library.requestLocalReset { error("interrupted") } }.isFailure)
            val document = Document.create("Retained", "原稿")
            library.save(document, null, 1)
            assertTrue(library.requestLocalReset { true })
            assertTrue(runCatching { library.save(document.copy(body = "追記"), 1, 2) }.isFailure)
            assertEquals(document, library.load(document.id)?.document)
        } finally {
            database.close()
            context.deleteDatabase(filename)
        }
    }

    @Test fun bothZenFacesRenderStyledJapaneseInTheEditorAndImageOutput() = runBlocking {
        val base = visualReviewDraft()
        val document = base.copy(annotations = base.annotations + listOf(
            Annotation(Format.Bold, 0, 2), Annotation(Format.Italic, 2, 4),
        ))
        val directory = File(context.cacheDir, "refinement-images").apply { mkdirs() }
        for (font in listOf(EditorFont.ZenGothic, EditorFont.ZenMincho)) {
            val face = checkNotNull(ResourcesCompat.getFont(context, checkNotNull(font.file)))
            assertTrue(Paint().apply { typeface = face }.hasGlyph("凪"))
            withContext(Dispatchers.Main) {
                val session = EditorSession(document)
                val editor = ProbeEditText(context, session).apply {
                    typeface = face
                    show(session.visible, special = true)
                }
                val text = checkNotNull(editor.text)
                assertEquals(document.body, text.toString())
                assertEquals(setOf(Typeface.BOLD, Typeface.ITALIC),
                    text.getSpans(0, text.length, StyleSpan::class.java).map { it.style }.toSet())
            }
            for (vertical in listOf(false, true)) {
                val renderer = imageRenderer(context, document, ImageOptions.recommended(vertical).copy(font = font.imageKey))
                val pages = renderer.pages()
                assertEquals(document.body, pages.joinToString("") { document.body.substring(it.start, it.end) })
                val bitmap = renderer.render(pages.first())
                try {
                    assertEquals(1080, bitmap.width)
                    File(directory, "${font.imageKey}-${if (vertical) "vertical" else "horizontal"}.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun seedIsolatedResetQa() {
        assumeTrue(context.packageName == "com.sider.lantern.resetqa")
        assumeTrue(InstrumentationRegistry.getArguments().getString("lanternResetFixture") == "true")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: LanternModel
            scenario.onActivity { model = ViewModelProvider(it)[LanternModel::class.java] }
            runBlocking {
                model.library.initialize()
                val draft = visualReviewDraft()
                val folder = model.library.createFolder("Reset QA", ROOT_ID)
                model.library.save(draft, null, 1, folder.id)
                model.recovery.save(draft)
                val discarded = Document.create("Reset QA Trash", "合成原稿")
                model.library.save(discarded, null, 1)
                model.library.discardManuscript(discarded.id)
                model.writing.setExportOption("palette", "lantern")
                model.writing.setExportOption("image_presets", "[]")
                model.writing.savePosition(draft.id, SavedEditorPosition(2, 3))
                File(context.filesDir, "export-assets").mkdirs()
                File(context.filesDir, "export-assets/qa-only").writeText("Local reset fixture")
                val image = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "Lantern-Reset-QA.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lantern-Reset-QA")
                    }))
                val png = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
                try {
                    png.eraseColor(0xFFDAA25A.toInt())
                    checkNotNull(context.contentResolver.openOutputStream(image)).use {
                        check(png.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { png.recycle() }
            }
        }
    }
}
