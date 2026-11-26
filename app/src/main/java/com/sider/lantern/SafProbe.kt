package com.sider.lantern

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.util.UUID

internal class SafProbe(context: Context) {
    private val resolver = context.contentResolver
    private val state = context.getSharedPreferences("phase0-saf", Context.MODE_PRIVATE)
    private val previous = AtomicDocumentStore(File(context.filesDir, "saf/previous.json"))
    private val pending = AtomicDocumentStore(File(context.filesDir, "saf/proposed.json"))
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun create(tree: Uri): String {
        resolver.takePersistableUriPermission(tree, flags)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val probe = checkNotNull(DocumentsContract.createDocument(
            resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, "Lantern Phase 0 ${UUID.randomUUID()}",
        ))
        val child = checkNotNull(DocumentsContract.createDocument(
            resolver, probe, DocumentsContract.Document.MIME_TYPE_DIR, "Inbox",
        ))
        val file = checkNotNull(DocumentsContract.createDocument(resolver, child, "application/json", "sample.json"))
        val sample = sampleDocument()
        val bytes = CheckedJson.encode(sample)
        pending.save(sample)
        persist(tree, file, sha256(bytes))
        check(read(file).isEmpty()) { "作成した文書に既存の内容があります" }
        write(file, bytes)
        check(read(file).contentEquals(bytes)) { "新規原稿の再読込で不一致を検出しました" }
        return "専用サブフォルダ / Inbox / sample.json を作成し、再読込を確認しました"
    }

    fun inspect(): String {
        val (_, file, expected) = target()
        val bytes = read(file)
        val changed = sha256(bytes) != expected
        val document = runCatching { CheckedJson.decode(bytes) }.getOrElse {
            throw IOException("破損または異なる形式の外部原稿です", it)
        }
        return if (changed) "外部変更を検出しました（${document.title}）"
        else "読込と照合に成功しました（${document.title}）"
    }

    fun update(): String {
        val (tree, file, expected) = target()
        val old = read(file)
        if (sha256(old) != expected) throw ExternalChanged()
        val current = CheckedJson.decode(old)
        val next = current.copy(title = current.title + "・更新")
        val hash = verifiedExternalWrite(
            expected, next, { read(file) },
            { existing, proposed -> previous.save(existing); pending.save(proposed) },
            { bytes -> write(file, bytes) },
        )
        persist(tree, file, hash)
        return "更新後の再読込と SHA-256 照合に成功しました"
    }

    fun rename(): String {
        val (tree, file, expected) = target()
        val bytes = read(file)
        if (sha256(bytes) != expected) throw ExternalChanged()
        previous.save(CheckedJson.decode(bytes))
        if (!read(file).contentEquals(bytes)) throw ExternalChanged()
        val renamed = checkNotNull(DocumentsContract.renameDocument(resolver, file, "sample-${UUID.randomUUID()}.json")) {
            "プロバイダで改名できませんでした"
        }
        persist(tree, renamed, expected)
        check(read(renamed).contentEquals(bytes)) { "改名後の原稿が一致しません" }
        return "改名後の URI で再読込に成功しました"
    }

    fun release(): String {
        val (tree, _, _) = target()
        resolver.releasePersistableUriPermission(tree, flags)
        return "アクセス権を解放しました。再読込で権限喪失を確認できます"
    }

    fun recovery(): String {
        val candidates = listOf("保存前" to previous, "保存予定" to pending).mapNotNull { (label, store) ->
            runCatching { "$label＝${store.load().document.title}" }.getOrNull()
        }
        check(candidates.isNotEmpty()) { "読込可能な内部復旧版がありません" }
        return candidates.joinToString(" / ")
    }

    private data class Target(val tree: Uri, val file: Uri, val expected: String)

    private fun target(): Target {
        val tree = checkNotNull(state.getString("tree", null)) { "専用テストフォルダを選択してください" }.toUri()
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            "テストフォルダへの永続アクセス権がありません"
        }
        return Target(tree, checkNotNull(state.getString("file", null)).toUri(), checkNotNull(state.getString("hash", null)))
    }

    // The direct commit exposes whether the new URI and hash were persisted.
    @SuppressLint("UseKtx")
    private fun persist(tree: Uri, file: Uri, hash: String) {
        check(state.edit().putString("tree", tree.toString()).putString("file", file.toString())
            .putString("hash", hash).commit()) { "検証対象の記録を保存できませんでした" }
    }

    private fun read(uri: Uri): ByteArray =
        checkNotNull(resolver.openInputStream(uri)).use { it.readDocumentBytes() }

    private fun write(uri: Uri, bytes: ByteArray) {
        checkNotNull(resolver.openOutputStream(uri, "rwt")).use { it.write(bytes) }
    }
}
