package com.sider.lantern

import org.junit.Assert.*
import org.junit.Test

class WritingAssistanceTest {
    @Test fun explicitPairKeepsTheCaretInsideAndUndoRedoIsOneStep() {
        val session = EditorSession(Document.create("", "朝夜"), EditorPosition(1, 1))
        val original = session.committed
        val pair = session.apply(session.symbolEdit("『", "』"))
        assertEquals("朝『』夜", pair.document.body)
        assertEquals(EditorPosition(2, 2), pair.position)
        assertEquals(original, session.undo())
        assertFalse(session.canUndo)
        assertEquals(pair, session.redo())
        assertEquals("朝『……』夜", session.apply(session.symbolEdit("……")).document.body)
    }

    @Test fun wrappingMultilineSelectionKeepsItsTextStylesAndManualBreaks() {
        val original = Document.create("", "前星\n👩‍🚀話後").copy(
            annotations = listOf(
                Annotation(Format.Ruby, 1, 2, "ほし"), Annotation(Format.Emphasis, 1, 2),
                Annotation(Format.Bold, 1, 3), Annotation(Format.Italic, 3, 9),
            ),
            pageBreaks = listOf(3),
        ).validated()
        val session = EditorSession(original, EditorPosition(9, 1))
        val wrapped = session.apply(session.symbolEdit("【", "】"))
        assertEquals("前【星\n👩‍🚀話】後", wrapped.document.body)
        assertEquals(EditorPosition(10, 2), wrapped.position)
        assertEquals(original.annotations.map { it.copy(start = it.start + 1, end = it.end + 1) }, wrapped.document.annotations)
        assertEquals(listOf(4), wrapped.document.pageBreaks)
        assertEquals(original, session.undo().document)
        assertEquals(wrapped, session.redo())
    }

    @Test fun partialRubyAndInvalidUnicodeSelectionCannotBeWrapped() {
        val original = Document.create("", "星空👩‍🚀").copy(annotations = listOf(Annotation(Format.Ruby, 0, 2, "ほしぞら")))
        val session = EditorSession(original, EditorPosition(1, 2))
        assertThrows(IllegalArgumentException::class.java) { session.symbolEdit("「", "」") }
        session.observe(original.body, EditorPosition(2, 3), null)
        assertThrows(IllegalArgumentException::class.java) { session.symbolEdit("「", "」") }
        assertEquals(original, session.committed.document)
        assertFalse(session.canUndo)
    }

    @Test fun cursorAndRestorationRespectCombiningEmojiAndNewlines() {
        val body = "ルか\u3099👩‍🚀\r\n甲"
        val original = Document.create("", body).copy(annotations = listOf(Annotation(Format.Ruby, 0, 3, "るか")))
        val session = EditorSession(original, EditorPosition(1, 1)) { fail("Navigation committed text") }
        for (offset in listOf(3, 8, 10, 11)) assertEquals(EditorPosition(offset, offset), session.moveCursor(true).position)
        for (offset in listOf(10, 8, 3, 1, 0)) assertEquals(EditorPosition(offset, offset), session.moveCursor(false).position)
        session.observe(body, EditorPosition(8, 1), null)
        assertEquals(EditorPosition(1, 1), session.moveCursor(false).position)
        assertFalse(session.canUndo)
        assertEquals(EditorPosition(3, 3), SavedEditorPosition(4, -10).selectionIn(body))
        assertEquals(EditorPosition(1, 1), SavedEditorPosition(2, 0).selectionIn(body))
        assertEquals(EditorPosition(0, 0), SavedEditorPosition(-3, 0).selectionIn(body))
        assertEquals(EditorPosition(11, 11), SavedEditorPosition(99, 0).selectionIn(body))
    }

    @Test fun completionOnlyAcceptsASingleOpenerAndReusesTheImmediateClose() {
        assertEquals("（", completedBracket("（", "文字）", 0, 2))
        assertEquals("『』", completedBracket("『", "文字」", 2, 2))
        assertEquals("【】", completedBracket("【", "前後", 1, 1))
        assertNull(completedBracket("「語", "", 0, 0))
        assertNull(completedBracket("」", "", 0, 0))
        assertNull(completedBracket("「", "👩‍🚀", 1, 1))
    }

    @Test fun explicitToolbarEditingIsRejectedWhileConversionIsPending() {
        val session = EditorSession(Document.create("", "かな"))
        session.observe("仮名", EditorPosition(2, 2), EditorPosition(0, 2))
        assertThrows(IllegalStateException::class.java) { session.symbolEdit("「", "」") }
        assertThrows(IllegalStateException::class.java) { session.moveCursor(false) }
        assertEquals("かな", session.committed.document.body)
        assertEquals("仮名", session.visible.document.body)
    }
}
