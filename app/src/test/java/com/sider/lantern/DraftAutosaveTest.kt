package com.sider.lantern

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DraftAutosaveTest {
    @Test fun pristineDraftIsNotCreatedButPreviouslyTypedBlankDraftIsKept() = runBlocking {
        val blank = Document.create("", "")
        val writes = mutableListOf<Document>()
        val save = DraftAutosave(blank, null, this) { doc, _, _ -> writes += doc }
        save.submit(blank)
        assertTrue(save.flush())
        assertTrue(writes.isEmpty())
        save.submit(blank.copy(body = "書"))
        save.submit(blank)
        assertTrue(save.flush())
        assertEquals(listOf(blank), writes)
        assertEquals(SaveStatus.Saved, save.status)
    }

    @Test fun editDuringWriteKeepsLatestBodyAndFormattingAndAdvancesExpectedRevision() = runBlocking {
        val original = Document.create("", "海")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Triple<Document, Long?, Long>>()
        val save = DraftAutosave(original, 4, this) { doc, expected, revision ->
            writes += Triple(doc, expected, revision)
            if (revision == 5L) {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = original.copy(body = "夜の海")
        val last = first.copy(title = "潮", annotations = listOf(Annotation(Format.Ruby, 2, 3, "うみ")))
        save.submit(first)
        save.requestFlush()
        entered.await()
        save.submit(first.copy(title = "途中"))
        save.submit(last)
        assertEquals(SaveStatus.Saving, save.status)
        release.complete(Unit)
        assertTrue(save.flush())
        assertEquals(listOf(Triple(first, 4L, 5L), Triple(last, 5L, 7L)), writes)
    }

    @Test fun writerFailureRetainsDraftAndRetryUsesSameRevision() = runBlocking {
        val initial = Document.create("", "初稿")
        val updated = initial.copy(body = "再稿")
        var attempts = 0
        val save = DraftAutosave(initial, 2, this) { document, expected, revision ->
            assertEquals(updated, document)
            assertEquals(2L, expected)
            assertEquals(3L, revision)
            if (++attempts == 1) throw IOException("test")
        }
        save.submit(updated)
        assertFalse(save.flush())
        assertEquals(SaveStatus.Failed, save.status)
        assertTrue(save.hasPending)
        assertTrue(save.flush())
        assertEquals(SaveStatus.Saved, save.status)
        assertEquals(2, attempts)
    }

    @Test fun draftWritersStayBoundToTheirOwnIdsAcrossNavigation() = runBlocking {
        val a = Document.create("", "")
        val b = Document.create("", "")
        val writes = mutableMapOf<String, Document>()
        val writer: suspend (Document, Long?, Long) -> Unit = { document, _, _ -> writes[document.id] = document }
        val first = DraftAutosave(a, null, this, write = writer)
        val second = DraftAutosave(b, null, this, write = writer)
        val updateA = a.copy(body = "甲")
        val updateB = b.copy(title = "乙")
        first.submit(updateA)
        second.submit(updateB)
        assertTrue(second.flush())
        assertTrue(first.flush())
        assertEquals(mapOf(a.id to updateA, b.id to updateB), writes)
        assertThrows(IllegalArgumentException::class.java) { first.submit(updateB) }
        Unit
    }

    @Test fun editorPublishesOnlyCommittedTextAndRestoresFormattingThroughUndo() {
        val original = Document.create("", "")
        val committed = mutableListOf<Document>()
        val editor = EditorSession(original) { committed += it }
        editor.observe("ほし", EditorPosition(2, 2), EditorPosition(0, 2))
        assertTrue(committed.isEmpty())
        editor.observe("星", EditorPosition(0, 1), null)
        editor.beginRuby()
        editor.applyRuby("ほし", remove = false)
        assertEquals(listOf(Annotation(Format.Ruby, 0, 1, "ほし")), committed.last().annotations)
        editor.undo()
        assertEquals("星", committed.last().body)
        assertTrue(committed.last().annotations.isEmpty())
        editor.redo()
        assertEquals("ほし", committed.last().annotations.single().reading)
    }
}
