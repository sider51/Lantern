package com.sider.lantern

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as Saf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class ExternalLibrary(context: Context, settingsName: String = "library_location") {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @Serializable
    internal data class FileRef(val uri: String, val hash: String)
    @Serializable
    private data class Location(val tree: String, val root: String, val manifest: FileRef)
    @Serializable
    private data class Manifest(
        val version: Int = 1,
        val folders: List<FolderRow>,
        val directories: Map<String, String>,
        val files: Map<String, FileRef>,
        val trash: List<TrashRow>,
    )
    internal data class Cleanup(val files: List<FileRef>)
    private var location = prefs.getString("location", null)?.let { Json.decodeFromString<Location>(it) }
    val active get() = location != null
    val generation get() = location?.manifest
    var status by mutableStateOf(if (active) "指定フォルダ" else "アプリ内")
        private set

    suspend fun switch(tree: Uri, archive: LibraryArchive) = withContext(Dispatchers.IO) {
        val previous = location
        if (previous != null) verified(previous)
        resolver.takePersistableUriPermission(tree, flags)
        val treeRoot = Saf.buildDocumentUriUsingTree(tree, Saf.getTreeDocumentId(tree))
        val root = create(treeRoot, Saf.Document.MIME_TYPE_DIR, "Lantern-${UUID.randomUUID()}")
        val manifest = writeSnapshot(root, archive, null)
        require(readArchive(manifest) == archive.copy(settings = emptyMap()))
        val next = Location(tree.toString(), root.toString(), newFile(root, "library", manifest))
        setLocation(next)
    }

    suspend fun disable() = withContext(Dispatchers.IO) { setLocation(null) }

    suspend fun refresh(use: suspend (LibraryArchive) -> Unit): Boolean {
        val source = location ?: return true
        return try {
            val archive = withContext(Dispatchers.IO) { readArchive(verified(source, checkFiles = false)) }
            use(archive)
            status = "指定フォルダ"
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "指定フォルダを読み込めません。権限と外部変更を確認してください。"
            false
        }
    }

    suspend fun verify() = withContext(Dispatchers.IO) { location?.let { verified(it) }; Unit }

    suspend fun verifyManuscript(id: String) = withContext(Dispatchers.IO) {
        location?.let { verified(it, checkFiles = false).files[id]?.let(::read) }
        Unit
    }

    suspend fun publish(archive: LibraryArchive): Cleanup? = withContext(Dispatchers.IO) {
        val previous = location ?: return@withContext null
        val old = verified(previous, checkFiles = false)
        val root = previous.root.toUri()
        val next = writeSnapshot(root, archive, old)
        if (next == old) return@withContext null
        val manifest = newFile(root, "library", next)
        // Recheck immediately before switching. Files are immutable within Lantern.
        verified(previous)
        setLocation(previous.copy(manifest = manifest))
        Cleanup(listOf(previous.manifest) + old.files.values.filter { it !in next.files.values })
    }

    suspend fun clean(cleanup: Cleanup) = withContext(Dispatchers.IO) {
        cleanup.files.forEach { ref ->
            runCatching {
                read(ref)
                Saf.deleteDocument(resolver, ref.uri.toUri())
            }
        }
    }

    private fun verified(source: Location, checkFiles: Boolean = true): Manifest {
        check(resolver.persistedUriPermissions.any { it.uri.toString() == source.tree && it.isReadPermission && it.isWritePermission }) {
            "指定フォルダの永続アクセス権がありません"
        }
        val manifest = Json.decodeFromString<Manifest>(read(source.manifest).decodeToString(throwOnInvalidSequence = true))
        require(manifest.version == 1)
        if (checkFiles) manifest.files.values.forEach { read(it) }
        return manifest
    }

    private fun readArchive(manifest: Manifest) = LibraryArchive(
        folders = manifest.folders,
        manuscripts = manifest.files.values.map { Json.decodeFromString<Manuscript>(read(it).decodeToString(throwOnInvalidSequence = true)) },
        trash = manifest.trash,
    ).validated()

    private fun writeSnapshot(root: Uri, archive: LibraryArchive, previous: Manifest?): Manifest {
        val folders = archive.folders.associateBy(FolderRow::id)
        val oldFolders = previous?.folders?.associateBy(FolderRow::id).orEmpty()
        val directories = mutableMapOf(ROOT_ID to root.toString())
        fun folder(id: String): Uri {
            directories[id]?.let { return it.toUri() }
            val row = if (id == INBOX_ID) FolderRow(id, ROOT_ID, "Inbox") else folders.getValue(id)
            val parentId = checkNotNull(row.parentId)
            val parent = folder(parentId)
            val oldParent = if (parentId == ROOT_ID) root.toString() else previous?.directories?.get(parentId)
            val canReuse = parent.toString() == oldParent && (id == INBOX_ID || oldFolders[id] == row)
            val directory = if (canReuse) previous?.directories?.get(id)?.toUri() else null
            val safeName = row.name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80).trimEnd('.', ' ')
            return (directory ?: create(parent, Saf.Document.MIME_TYPE_DIR, safeName.ifBlank { "Folder" }))
                .also { directories[id] = it.toString() }
        }
        folder(INBOX_ID)
        for (id in folders.keys) folder(id)
        val files = archive.manuscripts.associate { manuscript ->
            val id = manuscript.document.id
            val parent = folder(manuscript.folderId)
            val bytes = Json.encodeToString(manuscript).encodeToByteArray()
            val old = previous?.files?.get(id)
            id to if (old != null && previous.directories[manuscript.folderId] == parent.toString() && old.hash == sha256(bytes)) old
            else newFile(parent, id, bytes)
        }
        return Manifest(folders = archive.folders, directories = directories, files = files, trash = archive.trash)
    }

    private inline fun <reified T> newFile(parent: Uri, label: String, value: T): FileRef =
        newFile(parent, label, Json.encodeToString(value).encodeToByteArray())

    private fun newFile(parent: Uri, label: String, bytes: ByteArray): FileRef {
        val file = create(parent, "application/json", "$label-${UUID.randomUUID()}.json")
        check(bytesAt(file).isEmpty()) { "新規ファイル名が既存ファイルと衝突しました" }
        checkNotNull(resolver.openOutputStream(file, "rwt")).use { it.write(bytes) }
        check(bytesAt(file).contentEquals(bytes)) { "指定フォルダへの書き込みを確認できませんでした" }
        return FileRef(file.toString(), sha256(bytes))
    }

    private fun create(parent: Uri, type: String, name: String): Uri =
        Saf.createDocument(resolver, parent, type, name) ?: throw IOException("指定フォルダにファイルを作成できません")

    private fun read(ref: FileRef): ByteArray = bytesAt(ref.uri.toUri()).also {
        if (sha256(it) != ref.hash) throw ExternalChanged()
    }

    private fun bytesAt(uri: Uri) = checkNotNull(resolver.openInputStream(uri)).use { it.readDocumentBytes(64 * 1024 * 1024) }

    @SuppressLint("UseKtx")
    private fun setLocation(next: Location?) {
        val edit = prefs.edit()
        if (next == null) edit.remove("location") else edit.putString("location", Json.encodeToString(next))
        check(edit.commit()) { "保存先の変更を確認できませんでした" }
        location = next
        status = if (next == null) "アプリ内" else "指定フォルダ"
    }
}
