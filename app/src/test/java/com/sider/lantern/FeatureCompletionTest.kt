package com.sider.lantern

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeatureCompletionTest {
    @Test fun narouKeepsLiteralParenthesizedKanaSeparateFromRuby() {
        val doc = Document.create("", "山（やま）と川(かわ)")
        assertEquals("山｜（やま）と川｜(かわ)", exportText(doc, TextExportOptions(TextTarget.Narou)).text)
        assertEquals(doc.body, exportText(doc, TextExportOptions()).text)
    }

    @Test fun documentedMarkersKeepTheOriginalBodyAndReportOmissions() {
        val document = Document.create("題", "星空\n赤\n青").copy(annotations = listOf(
            Annotation(Format.Ruby, 0, 2, "ほしぞら"), Annotation(Format.Emphasis, 0, 2),
            Annotation(Format.Bold, 3, 4), Annotation(Format.Italic, 5, 6),
        ), pageBreaks = listOf(5)).validated()
        fun forTarget(target: TextTarget) = exportText(document, TextExportOptions(target, title = false))
        assertEquals(document.body, forTarget(TextTarget.X).text)
        assertEquals(5, forTarget(TextTarget.Plain).omitted.size)
        assertEquals("[[rb:星空 > ほしぞら]]\n[b:赤]\n\n[newpage]\n[i:青]", forTarget(TextTarget.Pixiv).text)
        for (target in listOf(TextTarget.Kakuyomu, TextTarget.Narou, TextTarget.Everystar)) {
            assertEquals("｜星空《ほしぞら》\n赤\n青", forTarget(target).text)
        }
        assertEquals("《ruby》星空《rt》ほしぞら《/rt》《/ruby》\n《b》赤《/b》\n《i》青《/i》",
            forTarget(TextTarget.Hameln).text)
        assertEquals(setOf(OmittedStyle.Emphasis), forTarget(TextTarget.Pixiv).omitted)
        assertEquals("星空\n赤\n青", document.body)
    }

    @Test fun overlappingAndOverlongFormattingNeverDropsText() {
        val document = Document.create("", "星空\n\n\n長い文字列ABCDEFGHIJK").copy(annotations = listOf(
            Annotation(Format.Emphasis, 0, 2),
            Annotation(Format.Ruby, 5, 20, "とてもながいよみがな"),
        ))
        val output = exportText(document, TextExportOptions(TextTarget.Narou, indent = true, compactBlanks = true))
        assertEquals("　星空\n\n　長い文字列ABCDEFGHIJK", output.text)
        assertEquals(setOf(OmittedStyle.Emphasis, OmittedStyle.Ruby), output.omitted)
    }

    @Test fun backupRejectsCyclesAndDuplicateDraftIds() {
        val first = FolderRow("a", "b", "A")
        val second = FolderRow("b", "a", "B")
        assertThrows(IllegalArgumentException::class.java) { LibraryArchive(folders = listOf(first, second)).validated() }
        val draft = Manuscript(Document.create("", "本文"), INBOX_ID, 0, 0, 1)
        assertThrows(IllegalArgumentException::class.java) { LibraryArchive(manuscripts = listOf(draft, draft)).validated() }
    }

    @Test fun writeFailureKeepsTheRecoveryCheckpointAndSuccessfulRetryClearsIt() = runBlocking {
        val original = Document.create("", "")
        val draft = original.copy(body = "作業中")
        var retained: Document? = null
        var fail = true
        val save = DraftAutosave(original, null, this,
            checkpoint = { retained = it }, clearCheckpoint = { retained = null }) { _, expected, _ ->
            assertNull(expected)
            if (fail) error("Write interrupted")
        }
        save.submit(draft)
        assertFalse(save.flush())
        assertEquals(draft, retained)
        assertEquals(draft, save.retainForRecovery())
        fail = false
        assertTrue(save.flush())
        assertNull(retained)
    }
}
