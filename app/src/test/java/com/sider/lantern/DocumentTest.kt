package com.sider.lantern

import org.junit.Assert.*
import org.junit.Test

class DocumentTest {
    @Test fun strictUtf8JsonRoundTripAndInvalidData() {
        val document = sampleDocument()
        assertEquals(document, DocumentJson.decode(DocumentJson.encode(document)))
        assertThrows(IllegalArgumentException::class.java) { document.copy(schemaVersion = 2).validated() }
        assertThrows(IllegalArgumentException::class.java) { document.copy(id = "invalid").validated() }
        assertThrows(IllegalArgumentException::class.java) { document.copy(pageBreaks = listOf(0)).validated() }
        assertThrows(Exception::class.java) { DocumentJson.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { document.copy(body = "\uD800").validated() }
    }

    @Test fun annotationBoundariesAreGraphemeAligned() {
        val body = "か\u3099👩‍🚀Z"
        val document = Document.create("", body)
        val edges = boundaries(body)
        assertEquals(listOf(0, 2, 7, 8), edges.indices.filter { edges[it] })
        for (invalidOffset in listOf(1, 3, 4, 5, 6)) {
            assertThrows(IllegalArgumentException::class.java) {
                document.copy(annotations = listOf(Annotation(Format.Bold, 0, invalidOffset))).validated()
            }
        }
        val bold = document.copy(annotations = listOf(Annotation(Format.Bold, 7, 8)))
        assertTrue(bold.edit(TextChange(8, 8, "\u0301")).annotations.isEmpty())
    }

    @Test fun paragraphBoundaryChecksMatchWholeTextSegmentation() {
        val text = "か\u3099👩‍🚀\r\n🇯🇵🇨🇦A\u0301\n\n\u0600B👨🏾‍🚀\r最後"
        val expected = boundaries(text)
        for (offset in expected.indices) {
            assertEquals("UTF-16 offset $offset", expected[offset], isGraphemeBoundary(text, offset))
        }
        assertFalse(isGraphemeBoundary(text, -1))
        assertFalse(isGraphemeBoundary(text, text.length + 1))
    }

    @Test fun insertionAndRemovalKeepRangesAligned() {
        val source = Document.create("", "前銀河夜後").copy(
            annotations = listOf(Annotation(Format.Ruby, 1, 3, "ぎんが"), Annotation(Format.Bold, 1, 4)),
            pageBreaks = listOf(4),
        ).validated()
        val edge = source.edit(TextChange(1, 1, "星")).validated()
        assertEquals(listOf(Annotation(Format.Ruby, 2, 4, "ぎんが"), Annotation(Format.Bold, 2, 5)), edge.annotations)
        assertEquals(listOf(5), edge.pageBreaks)
        val interior = source.edit(TextChange(2, 2, "星")).validated()
        assertEquals(listOf(Annotation(Format.Bold, 1, 5)), interior.annotations)
        val partial = source.edit(TextChange(2, 4, "")).validated()
        assertEquals(listOf(Annotation(Format.Bold, 1, 2)), partial.annotations)
        assertEquals(listOf(2), partial.pageBreaks)
        val all = source.edit(TextChange(1, 4, "他")).validated()
        assertTrue(all.annotations.isEmpty())
    }

    @Test fun repeatedTextDoesNotMoveAnUncertainRubyToAnotherCharacter() {
        val source = Document.create("", "あああ").copy(
            annotations = listOf(Annotation(Format.Ruby, 1, 2, "ア")),
        ).validated()
        assertTrue(source.reconcile("ああ").validated().annotations.isEmpty())
        val exact = source.edit(TextChange(0, 1, ""))
        assertEquals(listOf(Annotation(Format.Ruby, 0, 1, "ア")), exact.annotations)
    }

    @Test fun undoAndRedoRestoreBodyFormatsBreaksAndSelectionTogether() {
        val original = Revision(sampleDocument(), EditorPosition(2, 4))
        val edit = original.document.edit(TextChange(2, 3, "星"))
        val next = Revision(edit, EditorPosition(3, 3))
        val history = EditorHistory(original)
        history.update(next)
        assertEquals(original, history.undo())
        assertEquals(next, history.redo())
        history.undo()
        val branch = original.copy(document = original.document.copy(title = "変更"))
        history.update(branch)
        assertEquals(branch, history.redo())
    }
}
