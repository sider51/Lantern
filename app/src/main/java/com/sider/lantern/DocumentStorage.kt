package com.sider.lantern

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val MAX_BYTES = 4 * 1024 * 1024

internal fun InputStream.readDocumentBytes(limit: Int = MAX_BYTES): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) return result.toByteArray()
        if (result.size() + count > limit) throw IOException("ファイルの読み込み上限を超えました")
        result.write(buffer, 0, count)
    }
}

@Serializable
private data class CheckedDocument(val sha256: String, val document: Document)

internal object CheckedJson {
    private val json = Json { encodeDefaults = true }

    fun encode(document: Document): ByteArray =
        json.encodeToString(CheckedDocument(sha256(DocumentJson.encode(document)), document))
            .encodeToByteArray().also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): Document {
        val envelope = json.decodeFromString<CheckedDocument>(bytes.decodeToString(throwOnInvalidSequence = true))
        require(sha256(DocumentJson.encode(envelope.document)) == envelope.sha256) { "原稿のハッシュが一致しません" }
        return envelope.document
    }
}

internal data class LoadedDocument(val document: Document, val recovered: Boolean)

internal class AtomicDocumentStore(
    private val file: File,
    private val replace: (File, File) -> Unit = { source, destination ->
        Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    },
) {
    private val backup = File(file.parentFile, "${file.name}.previous")

    @Synchronized
    fun load(): LoadedDocument {
        val primary = runCatching { read(file) }
        primary.getOrNull()?.let { return LoadedDocument(it, false) }
        if (backup.exists()) return LoadedDocument(read(backup), true)
        throw IOException("有効な原稿または復旧版がありません", primary.exceptionOrNull())
    }

    @Synchronized
    fun save(document: Document) {
        val bytes = CheckedJson.encode(document)
        val previous = runCatching { read(file) }.getOrNull()
        if (previous != null) writeAtomically(backup, CheckedJson.encode(previous))
        writeAtomically(file, bytes)
        check(read(file) == document) { "保存後の原稿が一致しません" }
    }

    private fun read(source: File): Document =
        CheckedJson.decode(source.inputStream().use { it.readDocumentBytes() })

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val directory = checkNotNull(destination.parentFile)
        check(directory.isDirectory || directory.mkdirs())
        val staged = File.createTempFile(".lantern-", ".pending", directory)
        try {
            FileOutputStream(staged).use {
                it.write(bytes)
                it.fd.sync()
            }
            check(staged.inputStream().use { it.readDocumentBytes() }.contentEquals(bytes))
            replace(staged, destination)
        } finally {
            staged.delete()
        }
    }
}

internal class ExternalChanged : IOException("外部原稿に変更があります。上書きは中止されました")

internal fun verifiedExternalWrite(
    expected: String,
    next: Document,
    read: () -> ByteArray,
    stageRecovery: (previous: Document, proposed: Document) -> Unit,
    write: (ByteArray) -> Unit,
): String {
    val previous = read()
    if (sha256(previous) != expected) throw ExternalChanged()
    val bytes = CheckedJson.encode(next)
    stageRecovery(CheckedJson.decode(previous), next)
    // The provider may still receive a concurrent write after this comparison.
    if (!read().contentEquals(previous)) throw ExternalChanged()
    write(bytes)
    val verified = read()
    if (!verified.contentEquals(bytes)) throw IOException("書き込み後の再読込で不一致を検出しました")
    check(CheckedJson.decode(verified) == next)
    return sha256(verified)
}
