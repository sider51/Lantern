package com.sider.lantern

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class LibraryArchive(
    val version: Int = 1,
    val folders: List<FolderRow> = emptyList(),
    val manuscripts: List<Manuscript> = emptyList(),
    val trash: List<TrashRow> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
) {
    fun validated(includeTrash: Boolean = true): LibraryArchive {
        require(version == 1 && folders.size <= 10_000 && manuscripts.size <= 10_000)
        val byId = folders.associateBy(FolderRow::id)
        require(byId.size == folders.size && ROOT_ID !in byId && INBOX_ID !in byId)
        require(folders.map { it.parentId to it.name }.distinct().size == folders.size)
        for (folder in folders) {
            require(folder.name.isNotBlank() && folder.name.isWellFormed() && folder.name.none(Char::isISOControl))
            var parent = folder.parentId
            val seen = mutableSetOf(folder.id)
            while (parent != ROOT_ID && parent in byId) {
                val id = checkNotNull(parent)
                require(seen.add(id)) { "フォルダ階層に循環があります" }
                parent = byId.getValue(id).parentId
            }
            require(parent != null && parent != INBOX_ID)
        }
        require(manuscripts.map { it.document.id }.distinct().size == manuscripts.size)
        manuscripts.forEach {
            require(it.folderId != ROOT_ID && it.revision > 0 && it.updatedAt >= it.createdAt)
            it.document.validated()
        }
        require(includeTrash || trash.isEmpty())
        require(trash.map(TrashRow::id).distinct().size == trash.size)
        trash.forEach { Json.decodeFromString<LibraryArchive>(it.payload).validated(includeTrash = false) }
        return this
    }
}
