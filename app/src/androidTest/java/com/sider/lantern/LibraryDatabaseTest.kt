package com.sider.lantern

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "lantern-test-${UUID.randomUUID()}.db"
    private lateinit var database: LibraryDatabase
    private lateinit var repository: LibraryRepository

    @Before fun open() {
        database = LibraryDatabase.open(context, name)
        repository = LibraryRepository(database)
    }

    @After fun clean() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun inboxAndUntitledFormattedManuscriptSurviveReopen() = runBlocking {
        repository.initialize()
        repository.initialize()
        val original = sampleDocument().copy(title = "")
        repository.save(original, null, 1)
        val folder = repository.createFolder("作品", ROOT_ID)
        val child = repository.createFolder("短編", folder.id)
        repository.moveManuscript(original.id, child.id)
        val duplicateId = repository.duplicate(original.id)
        database.close()
        open()
        repository.initialize()
        assertEquals(1, repository.folders.first().count { it.id == INBOX_ID && it.parentId == ROOT_ID })
        assertEquals(2, repository.summaries(child.id, "", false).first().size)
        val stored = checkNotNull(repository.load(original.id))
        assertEquals(original, stored.document)
        assertEquals(child.id, stored.folderId)
        assertEquals(2L, stored.revision)
        assertEquals(original.copy(id = duplicateId), repository.load(duplicateId)?.document)
        assertEquals(setOf(original.id, duplicateId), repository.summaries(null, "銀河", false).first().map { it.id }.toSet())
    }

    @Test fun systemFoldersCyclesAndNameCollisionsAreRejected() = runBlocking {
        repository.initialize()
        val a = repository.createFolder("作品", ROOT_ID)
        val b = repository.createFolder("短編", a.id)
        val c = repository.createFolder("控え", ROOT_ID)
        repository.createFolder("短編", c.id)
        reject { repository.moveFolder(a.id, b.id) }
        reject { repository.moveFolder(a.id, a.id) }
        reject { repository.moveFolder(b.id, c.id) }
        reject { repository.createFolder(" 短編 ", a.id) }
        reject { repository.renameFolder(INBOX_ID, "別名") }
        reject { repository.moveFolder(INBOX_ID, a.id) }
        reject { repository.createFolder("サブ", INBOX_ID) }
        repository.renameFolder(b.id, "掌編")
        repository.moveFolder(b.id, c.id)
        assertEquals(c.id, repository.folders.first().single { it.id == b.id }.parentId)
        assertEquals("掌編", repository.folders.first().single { it.id == b.id }.name)
    }

    @Test fun batchOperationsRollBackAndKeepNestedSelectionsTogether() = runBlocking {
        repository.initialize()
        val projects = repository.createFolder("Projects", ROOT_ID)
        val first = repository.createFolder("First", projects.id)
        val second = repository.createFolder("Second", projects.id)
        val nested = repository.createFolder("Nested", first.id)
        val target = repository.createFolder("Target", ROOT_ID)
        repository.createFolder("Second", target.id)
        val draft = sampleDocument()
        repository.save(draft, null, 1)
        repository.moveManuscript(draft.id, nested.id)
        val folders = setOf(first.id, second.id)
        reject { repository.moveSelection(LibrarySelection(folders), target.id) }
        assertEquals(projects.id, repository.folders.first().single { it.id == first.id }.parentId)
        reject { repository.discardSelection(LibrarySelection(setOf(first.id, INBOX_ID))) }
        assertEquals(draft, repository.load(draft.id)?.document)

        repository.discardSelection(LibrarySelection(setOf(first.id, nested.id), setOf(draft.id)))
        val entry = repository.trash.first().single()
        assertNull(repository.load(draft.id))
        repository.restoreTrash(entry.id)
        assertEquals(draft, repository.load(draft.id)?.document)
        assertEquals(nested.id, repository.load(draft.id)?.folderId)

        repository.renameManuscript(draft.id, "New title")
        val changed = checkNotNull(repository.load(draft.id)).document
        assertEquals(draft.copy(title = "New title"), changed)
        repository.duplicateSelection(setOf(draft.id))
        assertEquals(2, repository.summaries(nested.id, "", false).first().size)
    }

    @Test fun optimisticWritesKeepNewestBodyStylesAndFolder() = runBlocking {
        repository.initialize()
        val original = Document.create("", "初稿")
        repository.save(original, null, 1)
        assertEquals(INBOX_ID, repository.load(original.id)?.folderId)
        val styled = original.copy(title = "タイトル", body = "星の夜", annotations = listOf(Annotation(Format.Bold, 0, 1)),
            pageBreaks = listOf(2))
        repository.save(styled, 1, 2)
        reject { repository.save(original, 1, 3) }
        reject { repository.moveManuscript(original.id, "missing") }
        val destination = repository.createFolder("短編", ROOT_ID)
        repository.moveManuscript(original.id, destination.id)
        reject { repository.save(original, 2, 4) }
        assertEquals(styled, repository.load(original.id)?.document)
        assertEquals(destination.id, repository.load(original.id)?.folderId)
        assertEquals(original.id, repository.summaries(null, "タイトル", true).first().single().id)
    }

    @Test fun longDraftAndGraphemeCountPersistAsOneSnapshot() = runBlocking {
        repository.initialize()
        val sample = sampleDocument(50_000)
        repository.save(sample, null, 1)
        val saved = checkNotNull(repository.load(sample.id)).document
        assertEquals(sample, saved)
        val row = repository.summaries(INBOX_ID, "", false).first().single()
        assertTrue(row.preview.length <= 200)
        assertEquals(countCharacters(saved.body), row.characterCount)
        assertEquals(5, countCharacters("か\u3099👩‍🚀 \r\n字"))
        try {
            repository.save(sample.copy(annotations = listOf(Annotation(Format.Bold, 0, -1))), 1, 2)
            fail("Invalid annotation was written")
        } catch (_: IllegalArgumentException) {
            assertEquals(sample, repository.load(sample.id)?.document)
        }
    }

    @Test fun nativeLongEditAutosavesWithoutReplacingTheEditable() = runBlocking {
        repository.initialize()
        val sample = sampleDocument(50_000)
        repository.save(sample, null, 1)
        val main = CoroutineScope(Dispatchers.Main + SupervisorJob())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val draft = withContext(Dispatchers.Main) { ProductDraft(sample, 1, main, repository) }
                val position = sample.body.indexOf("銀河", 25_000)
                lateinit var view: ProbeEditText
                scenario.onActivity { activity ->
                    view = ProbeEditText(activity, draft.editor, measureEdits = false)
                    view.show(draft.editor.visible, special = true)
                    val buffer = view.text
                    buffer.insert(position, "星")
                    view.setSelection(position + 1)
                    view.observe()
                    assertSame(buffer, view.text)
                }
                assertTrue(withContext(Dispatchers.Main) { draft.autosave.flush() })
                val saved = checkNotNull(repository.load(sample.id))
                assertEquals(sample.edit(TextChange(position, position, "星")), saved.document)
                assertEquals(2L, saved.revision)

                scenario.onActivity {
                    val buffer = view.text
                    view.setSelection(position, position + 3)
                    view.observe()
                    val edit = draft.editor.symbolEdit("『", "』")
                    val wrapped = draft.editor.apply(edit)
                    view.show(wrapped, special = true, changes = edit.changes)
                    assertEquals(wrapped.document.body, view.text.toString())
                    assertEquals("『星銀河』", view.text.substring(position, position + 5))
                    assertSame(buffer, view.text)
                    assertEquals(saved.document, draft.editor.undo().document)
                    view.show(draft.editor.redo(), special = true)
                }
                assertTrue(withContext(Dispatchers.Main) { draft.autosave.flush() })
                assertEquals(draft.editor.committed.document, repository.load(sample.id)?.document)
            }
        } finally {
            main.cancel()
        }
    }

    @Test fun activityRecreationRetainsTheOpenDraftAndItsUncommittedText() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var id: String
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[LanternModel::class.java]
                id = model.newDraft()
                val editor = checkNotNull(model.editor).editor
                editor.observe("かな", EditorPosition(2, 2), EditorPosition(0, 2))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[LanternModel::class.java]
                val draft = checkNotNull(model.editor)
                assertEquals(id, draft.id)
                assertEquals("かな", draft.editor.visible.document.body)
                assertEquals("", draft.editor.committed.document.body)
                model.close(id)
            }
        }
    }

    private suspend fun reject(operation: suspend () -> Unit) {
        try {
            operation()
            fail("Unsafe operation succeeded")
        } catch (_: LibraryConflict) {
            // The transaction rejected it without changing the stored manuscript.
        }
    }
}
