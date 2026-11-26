package com.sider.lantern

import org.junit.Assert.*
import org.junit.Test

class EditorSessionTest {
    @Test fun compositionIsTransactionalAndCancellationRestoresRuby() {
        val session = EditorSession()
        val initial = session.committed
        val ruby = initial.document.annotations.single { it.format == Format.Ruby }
        val change = TextChange(ruby.start, ruby.end, "はく")
        val draft = initial.document.body.replaceRange(change.start, change.end, change.inserted)
        session.record(initial.document.body, draft, listOf(change))
        session.observe(draft, EditorPosition(ruby.start + 2, ruby.start + 2), EditorPosition(ruby.start, ruby.start + 2))
        assertEquals(initial, session.committed)
        assertFalse(session.visible.document.annotations.any { it.format == Format.Ruby })
        session.observe(initial.document.body, initial.position, null)
        assertEquals(initial, session.visible)
        assertEquals(initial, session.undo())
    }

    @Test fun committedConversionUndoesBodyAndRubyTogether() {
        val session = EditorSession()
        val initial = session.committed
        val ruby = initial.document.annotations.single { it.format == Format.Ruby }
        val change = TextChange(ruby.start, ruby.end, "星")
        val after = initial.document.body.replaceRange(change.start, change.end, change.inserted)
        val position = EditorPosition(ruby.start + 1, ruby.start + 1)
        session.record(initial.document.body, after, listOf(change))
        session.observe(after, position, EditorPosition(ruby.start, ruby.start + 1))
        session.observe(after, position, null)
        assertEquals(after, session.committed.document.body)
        assertFalse(session.committed.document.annotations.any { it.format == Format.Ruby })
        val committed = session.committed
        assertEquals(initial, session.undo())
        assertEquals(committed, session.redo())
    }

    @Test fun rubyKeepsOriginalRangeAfterSelectionMovesAndRoundTripsThroughHistory() {
        val session = EditorSession()
        val initial = session.committed.document
        val offset = initial.body.indexOf("手紙")
        val targetPosition = EditorPosition(offset, offset + 2)
        session.observe(initial.body, targetPosition, null)
        session.beginRuby()
        val target = checkNotNull(session.rubyTarget)
        assertEquals(initial.id, target.documentId)
        assertEquals("手紙", target.text)
        assertEquals(targetPosition, target.position)
        session.observe(initial.body, EditorPosition(0, 0), null)
        val added = session.applyRuby("てがみ", remove = false)
        assertEquals(Annotation(Format.Ruby, offset, offset + 2, "てがみ"), added.document.annotations.last())
        assertEquals(targetPosition, added.position)
        assertEquals(initial, session.undo().document)
        assertEquals(added, session.redo())
    }

    @Test fun existingRubyCanBeModifiedAndRemovedFromTheCaret() {
        val session = EditorSession()
        val initial = session.committed.document
        val existing = initial.annotations.single { it.format == Format.Ruby }
        session.observe(initial.body, EditorPosition(existing.end, existing.end), null)
        session.beginRuby()
        assertEquals(existing, session.rubyTarget?.existing)
        val changed = session.applyRuby("うすあかり", remove = false)
        assertEquals(existing.copy(reading = "うすあかり"), changed.document.annotations.last())
        session.beginRuby()
        val removed = session.applyRuby("", remove = true)
        assertEquals(initial.body, removed.document.body)
        assertFalse(removed.document.annotations.any { it.format == Format.Ruby })
        assertEquals(changed.document, session.undo().document)
        assertEquals(removed, session.redo())
    }

    @Test fun cancelsWithoutCreatingAHistoryEntry() {
        val session = EditorSession()
        val initial = session.committed.document
        val position = EditorPosition(1, 3)
        session.observe(initial.body, position, null)
        val selected = session.committed
        session.beginRuby()
        assertEquals(position, session.cancelRuby())
        assertNull(session.rubyTarget)
        assertEquals(selected, session.undo())
    }

    @Test fun staleRubyCannotChangeAnEditedOrReplacedDocument() {
        val session = EditorSession()
        val body = session.committed.document.body
        session.observe(body, EditorPosition(1, 3), null)
        session.beginRuby()
        session.observe(body + "。", EditorPosition(), null)
        assertThrows(IllegalStateException::class.java) { session.applyRuby("ほし", remove = false) }
        val replacement = session.load(0)
        assertThrows(IllegalStateException::class.java) { session.applyRuby("ほし", remove = false) }
        assertNull(session.cancelRuby())
        assertEquals(replacement, session.committed)
    }

    @Test fun rubyRejectsUnicodeBoundarySplitsAndActiveComposition() {
        val session = EditorSession()
        val document = session.committed.document
        val start = document.body.indexOf("👩")
        session.observe(document.body, EditorPosition(start, start + 1), null)
        assertThrows(IllegalArgumentException::class.java) { session.beginRuby() }
        val wholeEmoji = EditorPosition(start, start + "👩‍🚀".length)
        session.observe(document.body, wholeEmoji, wholeEmoji)
        assertThrows(IllegalStateException::class.java) { session.beginRuby() }
        session.observe(document.body, wholeEmoji, null)
        session.beginRuby()
        assertEquals("👩‍🚀", session.rubyTarget?.text)
    }
}
