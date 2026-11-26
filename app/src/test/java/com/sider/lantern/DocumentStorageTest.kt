package com.sider.lantern

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentStorageTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun internalSaveRecoversPriorVersionWhenPrimaryIsCorrupt() {
        val file = File(directory.root, "document.json")
        val store = AtomicDocumentStore(file)
        val first = sampleDocument()
        val second = first.copy(title = "更新")
        store.save(first)
        store.save(second)
        assertEquals(LoadedDocument(second, false), store.load())
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(LoadedDocument(first, true), store.load())
        store.save(second)
        file.writeText("壊れた原稿")
        assertEquals(LoadedDocument(first, true), store.load())
    }

    @Test fun failedReplacementRetainsPublishedDocument() {
        val file = File(directory.root, "document.json")
        val original = sampleDocument()
        AtomicDocumentStore(file).save(original)
        val interrupted = AtomicDocumentStore(file) { staged, destination ->
            if (destination == file) throw IOException("interrupted")
            Files.move(staged.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        assertThrows(IOException::class.java) { interrupted.save(original.copy(title = "更新")) }
        assertEquals(LoadedDocument(original, false), AtomicDocumentStore(file).load())
        assertFalse(directory.root.listFiles()!!.any { it.name.endsWith(".pending") })
    }

    @Test fun validJsonWithAlteredContentsFailsChecksum() {
        val bytes = CheckedJson.encode(sampleDocument()).decodeToString().replace("灯りの向こう", "書き換えた表題")
        assertThrows(IllegalArgumentException::class.java) { CheckedJson.decode(bytes.encodeToByteArray()) }
    }

    @Test fun externalConflictBeforeAndDuringStagingRefusesOverwrite() {
        val original = sampleDocument()
        val bytes = CheckedJson.encode(original)
        val replacement = original.copy(title = "更新")
        val changed = CheckedJson.encode(original.copy(title = "外部変更"))
        assertThrows(ExternalChanged::class.java) {
            verifiedExternalWrite(sha256(bytes), replacement, { changed }, { _, _ -> fail() }, { fail() })
        }
        var remote = bytes
        assertThrows(ExternalChanged::class.java) {
            verifiedExternalWrite(sha256(bytes), replacement, { remote }, { _, _ -> remote = changed }, { fail() })
        }
    }

    @Test fun partialProviderWriteFailsWithBothRecoveryVersionsAvailable() {
        val original = sampleDocument()
        val next = original.copy(title = "更新")
        var remote = CheckedJson.encode(original)
        var recovery: Pair<Document, Document>? = null
        assertThrows(IOException::class.java) {
            verifiedExternalWrite(sha256(remote), next, { remote },
                { previous, proposed -> recovery = previous to proposed },
                { remote = it.copyOf(10) },
            )
        }
        assertEquals(original to next, recovery)
    }

    @Test fun externalSuccessReturnsTheHashOfTheVerifiedBytes() {
        val original = sampleDocument()
        val next = original.copy(title = "更新")
        var remote = CheckedJson.encode(original)
        val hash = verifiedExternalWrite(sha256(remote), next, { remote }, { _, _ -> }, { remote = it })
        assertEquals(sha256(remote), hash)
        assertEquals(next, CheckedJson.decode(remote))
    }
}
