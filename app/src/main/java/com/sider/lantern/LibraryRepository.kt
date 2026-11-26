package com.sider.lantern

import android.icu.text.BreakIterator
import android.net.Uri
import androidx.room.withTransaction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class Manuscript(
    val document: Document,
    val folderId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
)

@Serializable
private data class Formatting(val annotations: List<Annotation>, val pageBreaks: List<Int>)

internal class LibraryConflict(val messageId: Int) : IllegalStateException()

internal class LibraryRepository(
    private val database: LibraryDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val external: ExternalLibrary? = null,
) {
    private val dao = database.library()
    private val writes = Mutex()
    private var projectionFailed by androidx.compose.runtime.mutableStateOf(false)
    val folders = dao.observeFolders()
    val trash = dao.observeTrash()

    private var resetRequested = false

    suspend fun initialize() = writes.withLock {
        database.withTransaction {
            dao.seedFolder(FolderRow(ROOT_ID, null, "Lantern"))
            dao.seedFolder(FolderRow(INBOX_ID, ROOT_ID, "Inbox"))
        }
        reloadExternalLocked()
    }

    val externalStatus get() = external?.status
    val externalSelected get() = external?.status?.let { external.active } == true
    val externalUnavailable get() = externalSelected && projectionFailed

    private suspend fun reloadExternalLocked() {
        if (external?.active != true) return
        projectionFailed = true
        if (external.refresh { archive ->
                database.withTransaction {
                    requireFullArchive(archive)
                    dao.allManuscripts().forEach { dao.deleteManuscript(it.id) }
                    removeFolders(dao.allFolders().filter { it.id != ROOT_ID && it.id != INBOX_ID })
                    dao.allTrash().forEach { dao.deleteTrash(it.id) }
                    orderedFolders(archive.folders).forEach { dao.insertFolder(it) }
                    archive.manuscripts.forEach { dao.insertManuscript(encode(it)) }
                    archive.trash.forEach { dao.insertTrash(it) }
                }
            }) projectionFailed = false
    }

    suspend fun reloadExternal() = writes.withLock { reloadExternalLocked() }

    suspend fun selectExternal(tree: Uri) = writes.withLock {
        check(!projectionFailed) { "先に外部保存を再検証してください" }
        val store = checkNotNull(external)
        store.switch(tree, snapshot())
    }

    suspend fun selectInternal() = writes.withLock {
        reloadExternalLocked()
        check(!projectionFailed) { "外部データを確認できません。権限とファイルを確認してください" }
        external?.disable()
    }

    suspend fun recoverInternal() = writes.withLock {
        check(external?.active == true && projectionFailed)
        external.disable()
        projectionFailed = false
    }

    suspend fun requestLocalReset(request: () -> Boolean): Boolean = writes.withLock {
        if (resetRequested) return@withLock false
        request().also { resetRequested = it }
    }

    private suspend fun <T> mutate(action: suspend () -> T): T = writes.withLock {
        check(!resetRequested)
        if (projectionFailed) throw LibraryConflict(R.string.storage_recheck)
        val generation = external?.generation
        var published: ExternalLibrary.Cleanup? = null
        try {
            val result = database.withTransaction {
                val result = action()
                if (external?.active == true) published = external.publish(snapshot())
                result
            }
            published?.let { external?.clean(it) }
            result
        } catch (failure: Exception) {
            if (external?.generation != generation) projectionFailed = true
            throw failure
        }
    }

    fun summaries(folderId: String?, query: String, byTitle: Boolean, recent: Boolean = false, descending: Boolean = !byTitle) =
        dao.observeManuscripts(folderId, query.trim(), byTitle, descending, if (recent) 30 else Int.MAX_VALUE)

    suspend fun load(id: String): Manuscript? = withContext(Dispatchers.Default) {
        external?.verifyManuscript(id)
        dao.manuscript(id)?.let(::decode)
    }

    suspend fun save(document: Document, expected: Long?, revision: Long, initialFolder: String = INBOX_ID) = mutate {
        require(revision > (expected ?: 0))
        val (formatting, count) = withContext(Dispatchers.Default) {
            document.validated()
            Json.encodeToString(Formatting(document.annotations, document.pageBreaks)) to countCharacters(document.body)
        }
        val now = clock()
        if (expected == null) {
            if (initialFolder == ROOT_ID) throw LibraryConflict(R.string.no_destination)
            requireFolder(initialFolder)
            dao.insertManuscript(ManuscriptRow(document.id, initialFolder, now, now, revision,
                document.schemaVersion, document.title, document.body, formatting, count))
        } else {
            val changed = dao.save(document.id, expected, revision, document.title, document.body,
                formatting, document.schemaVersion, count, now)
            if (changed != 1) throw LibraryConflict(R.string.conflict_revision)
        }
    }

    suspend fun createFolder(name: String, parentId: String): FolderRow = mutate {
        val clean = folderName(name)
        requireFolderParent(parentId)
        checkUnique(parentId, clean)
        FolderRow(UUID.randomUUID().toString(), parentId, clean).also { dao.insertFolder(it) }
    }

    suspend fun renameFolder(id: String, name: String) = mutate {
        val folder = userFolder(id)
        val parent = checkNotNull(folder.parentId)
        val clean = folderName(name)
        if (clean != folder.name) {
            checkUnique(parent, clean, id)
            check(dao.updateFolder(id, clean, parent) == 1)
        }
    }

    suspend fun moveFolder(id: String, destination: String) = mutate { moveFolderLocked(id, destination) }

    private suspend fun moveFolderLocked(id: String, destination: String) {
        val folder = userFolder(id)
        requireFolderParent(destination)
        var ancestor: String? = destination
        val seen = mutableSetOf<String>()
        while (ancestor != null) {
            if (ancestor == id || !seen.add(ancestor)) throw LibraryConflict(R.string.conflict_cycle)
            ancestor = requireFolder(ancestor).parentId
        }
        if (folder.parentId != destination) {
            checkUnique(destination, folder.name, id)
            check(dao.updateFolder(id, folder.name, destination) == 1)
        }
    }

    suspend fun moveManuscript(id: String, folderId: String) = mutate { moveManuscriptLocked(id, folderId) }

    private suspend fun moveManuscriptLocked(id: String, folderId: String) {
        if (folderId == ROOT_ID) throw LibraryConflict(R.string.no_destination)
        requireFolder(folderId)
        requireManuscript(id)
        dao.moveManuscript(id, folderId, clock())
    }

    suspend fun duplicate(id: String): String = mutate { duplicateLocked(id) }

    private suspend fun duplicateLocked(id: String): String {
        val row = requireManuscript(id)
        decode(row)
        val now = clock()
        val copy = row.copy(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now, revision = 1)
        dao.insertManuscript(copy)
        return copy.id
    }

    suspend fun backup(): LibraryArchive = writes.withLock {
        external?.verify()
        database.withTransaction { snapshot() }
    }

    suspend fun importDocument(document: Document) = mutate {
        val copy = document.validated().copy(id = UUID.randomUUID().toString())
        val now = clock()
        dao.insertManuscript(encode(Manuscript(copy, INBOX_ID, now, now, 1)))
    }

    suspend fun importArchive(archive: LibraryArchive) = mutate {
        requireFullArchive(archive)
        insertArchive(archive)
        for (item in archive.trash) dao.insertTrash(item.copy(id = UUID.randomUUID().toString()))
    }

    suspend fun discardManuscript(id: String) = mutate { discardManuscriptLocked(id) }

    private suspend fun discardManuscriptLocked(id: String) {
        val row = requireManuscript(id)
        putTrash(row.title, LibraryArchive(manuscripts = listOf(decode(row))))
        dao.deleteManuscript(id)
    }

    suspend fun discardFolder(id: String) = mutate { discardFolderLocked(id) }

    private suspend fun discardFolderLocked(id: String) {
        val root = userFolder(id)
        val children = dao.allFolders().groupBy(FolderRow::parentId)
        val folders = buildList {
            val pending = ArrayDeque(listOf(root))
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                add(next)
                pending.addAll(children[next.id].orEmpty())
            }
        }
        val ids = folders.mapTo(mutableSetOf(), FolderRow::id)
        val manuscripts = dao.allManuscripts().filter { it.folderId in ids }
        putTrash(root.name, LibraryArchive(folders = folders, manuscripts = manuscripts.map(::decode)))
        manuscripts.forEach { dao.deleteManuscript(it.id) }
        folders.asReversed().forEach { dao.deleteFolder(it.id) }
    }

    suspend fun restoreTrash(id: String) = mutate {
        val item = requireNotNull(dao.trash(id))
        insertArchive(Json.decodeFromString<LibraryArchive>(item.payload).validated(false), keepIds = true)
        dao.deleteTrash(id)
    }

    suspend fun deleteTrash(id: String) = mutate { dao.deleteTrash(id) }

    suspend fun purgeExpired(retention: TrashRetention = TrashRetention()) {
        val now = clock()
        if (dao.allTrash().none { retention.expires(it.removedAt, now) }) return
        mutate { dao.allTrash().filter { retention.expires(it.removedAt, now) }.forEach { dao.deleteTrash(it.id) } }
    }

    suspend fun renameManuscript(id: String, title: String) = mutate {
        require(title.isWellFormed() && title.none(Char::isISOControl))
        val row = requireManuscript(id)
        if (row.title != title) {
            check(dao.save(id, row.revision, row.revision + 1, title, row.body, row.formatting,
                row.schemaVersion, row.characterCount, clock()) == 1)
        }
    }

    suspend fun moveSelection(selection: LibrarySelection, destination: String) = mutate {
        val (folders, drafts) = normalizeSelection(selection)
        folders.forEach { moveFolderLocked(it, destination) }
        drafts.forEach { moveManuscriptLocked(it, destination) }
    }

    suspend fun discardSelection(selection: LibrarySelection) = mutate {
        val (folders, drafts) = normalizeSelection(selection)
        folders.forEach { discardFolderLocked(it) }
        drafts.forEach { discardManuscriptLocked(it) }
    }

    suspend fun duplicateSelection(drafts: Set<String>) = mutate { drafts.forEach { duplicateLocked(it) } }

    private suspend fun normalizeSelection(selection: LibrarySelection): LibrarySelection {
        val parents = dao.allFolders().associate { it.id to it.parentId }
        val folders = selection.folders
        folders.forEach { userFolder(it) }
        fun withinSelection(start: String?): Boolean {
            var id = start
            val visited = mutableSetOf<String>()
            while (id != null && visited.add(id)) {
                if (id in folders) return true
                id = parents[id]
            }
            return false
        }
        return LibrarySelection(
            folders.filterNot { withinSelection(parents[it]) }.toSet(),
            selection.drafts.filterNot { withinSelection(requireManuscript(it).folderId) }.toSet(),
        )
    }

    private suspend fun snapshot() = LibraryArchive(
        folders = dao.allFolders().filter { it.id != ROOT_ID && it.id != INBOX_ID },
        manuscripts = dao.allManuscripts().map(::decode),
        trash = dao.allTrash(),
    )

    private fun requireFullArchive(archive: LibraryArchive) {
        archive.validated()
        val ids = archive.folders.mapTo(mutableSetOf(), FolderRow::id)
        require(archive.folders.all { it.parentId == ROOT_ID || it.parentId in ids })
        require(archive.manuscripts.all { it.folderId == INBOX_ID || it.folderId in ids })
    }

    private fun orderedFolders(folders: List<FolderRow>): List<FolderRow> = buildList {
        val children = folders.groupBy(FolderRow::parentId)
        val ids = folders.mapTo(mutableSetOf(), FolderRow::id)
        val queue = ArrayDeque(folders.filter { it.parentId !in ids })
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            add(next)
            queue.addAll(children[next.id].orEmpty())
        }
        require(size == folders.size)
    }

    private suspend fun removeFolders(folders: List<FolderRow>) {
        orderedFolders(folders).asReversed().forEach { dao.deleteFolder(it.id) }
    }

    private suspend fun insertArchive(archive: LibraryArchive, keepIds: Boolean = false) {
        val mapping = mutableMapOf(ROOT_ID to ROOT_ID, INBOX_ID to INBOX_ID)
        for (folder in orderedFolders(archive.folders)) {
            val parent = mapping[folder.parentId] ?: folder.parentId?.let { dao.folder(it)?.id } ?: ROOT_ID
            val id = if (keepIds && dao.folder(folder.id) == null) folder.id else UUID.randomUUID().toString()
            var name = folder.name
            var suffix = 2
            while (dao.folderExists(parent, name, "")) name = "${folder.name} (${suffix++})"
            dao.insertFolder(folder.copy(id = id, parentId = parent, name = name))
            mapping[folder.id] = id
        }
        for (entry in archive.manuscripts) {
            val original = entry.document
            val id = if (keepIds && dao.manuscript(original.id) == null) original.id else UUID.randomUUID().toString()
            val parent = mapping[entry.folderId] ?: dao.folder(entry.folderId)?.id ?: INBOX_ID
            dao.insertManuscript(encode(entry.copy(document = original.copy(id = id), folderId = parent)))
        }
    }

    private suspend fun putTrash(name: String, archive: LibraryArchive) {
        dao.insertTrash(TrashRow(UUID.randomUUID().toString(), name, clock(), Json.encodeToString(archive)))
    }

    private fun encode(entry: Manuscript): ManuscriptRow = entry.document.let { document ->
        ManuscriptRow(document.id, entry.folderId, entry.createdAt, entry.updatedAt, entry.revision,
            document.schemaVersion, document.title, document.body,
            Json.encodeToString(Formatting(document.annotations, document.pageBreaks)), countCharacters(document.body))
    }

    private suspend fun requireFolder(id: String) =
        dao.folder(id) ?: throw LibraryConflict(R.string.folder_missing)

    private suspend fun requireManuscript(id: String) =
        dao.manuscript(id) ?: throw LibraryConflict(R.string.draft_missing)

    private fun decode(row: ManuscriptRow): Manuscript {
        require(row.revision > 0 && row.updatedAt >= row.createdAt && row.folderId != ROOT_ID)
        val formatting = Json.decodeFromString<Formatting>(row.formatting)
        val document = Document(row.schemaVersion, row.id, row.title, row.body,
            formatting.annotations, formatting.pageBreaks).validated()
        return Manuscript(document, row.folderId, row.createdAt, row.updatedAt, row.revision)
    }

    private suspend fun userFolder(id: String): FolderRow {
        if (id == INBOX_ID || id == ROOT_ID) throw LibraryConflict(R.string.conflict_system_folder)
        return requireFolder(id)
    }

    private suspend fun requireFolderParent(id: String) {
        if (id == INBOX_ID) throw LibraryConflict(R.string.conflict_inbox)
        requireFolder(id)
    }

    private fun folderName(input: String): String = input.trim().also {
        if (it.isEmpty() || !it.isWellFormed() || it.any(Char::isISOControl)) {
            throw LibraryConflict(R.string.folder_name_required)
        }
    }

    private suspend fun checkUnique(parentId: String, name: String, except: String = "") {
        if (dao.folderExists(parentId, name, except)) throw LibraryConflict(R.string.conflict_folder_name)
    }
}

internal const val TRASH_RETENTION = 30L * 24 * 60 * 60 * 1000

/** UAX #29 grapheme clusters. Spaces and newline clusters each count as one. */
internal fun countCharacters(body: String): Int {
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
    breaks.setText(body)
    var count = 0
    while (breaks.next() != BreakIterator.DONE) count++
    return count
}
