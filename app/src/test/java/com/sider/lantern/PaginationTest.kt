package com.sider.lantern

import org.junit.Assert.*
import org.junit.Test

class PaginationTest {
    @Test fun verticalPunctuationPreservesOffsetsAndKeepsUnsupportedOriginals() {
        val original = "「薄明、空。」Café12……——👩‍🚀"
        val formatted = verticalPunctuation(original) { it == "︑" }
        assertEquals("「薄明︑空。」Café12……——👩‍🚀", formatted)
        assertArrayEquals(boundaries(original), boundaries(formatted))
        val both = verticalPunctuation(original) { true }
        assertEquals(listOf(original.indexOf('、'), original.indexOf('。')),
            original.indices.filter { original[it] != both[it] })
        val document = Document.create("", original).copy(pageBreaks = listOf(original.indexOf('C')))
        val pages = paginate(document) { start, end -> end - start < 11 }
        assertEquals(original, pages.joinToString("") { original.substring(it.start, it.end) })
        assertEquals(both, pages.joinToString("") { both.substring(it.start, it.end) })
    }

    @Test fun pagesCoverEveryOffsetWithoutSplittingAnnotationsOrGraphemes() {
        val document = sampleDocument(50_000)
        val pages = paginate(document) { start, end -> end - start <= 36 }
        val edges = boundaries(document.body)
        assertEquals(document.body, pages.joinToString("") { document.body.substring(it.start, it.end) })
        assertTrue(pages.zipWithNext().all { (a, b) -> a.end == b.start })
        assertTrue(pages.all { edges[it.start] && edges[it.end] && it.end > it.start })
        assertTrue(document.pageBreaks.all { offset -> pages.any { it.end == offset } })
        assertTrue(document.annotations.filter { it.format == Format.Ruby }
            .none { span -> pages.any { it.end > span.start && it.end < span.end } })
    }

    @Test fun respectsManualBreakAndDoesNotSplitTwoDigitUnitAutomatically() {
        val document = Document.create("", "星が12個、見える。").copy(pageBreaks = listOf(5))
        val pages = paginate(document) { start, end -> end - start <= 3 }
        assertFalse(pages.any { it.end == 3 })
        assertTrue(pages.any { it.end == 5 })
    }

    @Test fun rejectsAnIndivisibleRangeThatCannotFit() {
        val document = Document.create("", "薄明").copy(annotations = listOf(Annotation(Format.Ruby, 0, 2, "はくめい")))
        assertThrows(IllegalStateException::class.java) {
            paginate(document) { start, end -> end - start <= 1 }
        }
        assertEquals(listOf(PageSlice(0, 0)), paginate(Document.create("", "")) { _, _ -> false })
    }
}
