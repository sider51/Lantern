package com.sider.lantern

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DraftRecovery(private val folder: File) {
    private fun file(id: String) = File(folder, "$id.json")
    suspend fun save(document: Document) = withContext(Dispatchers.IO) {
        AtomicDocumentStore(file(document.id)).save(document)
    }
    suspend fun clear(id: String) = withContext(Dispatchers.IO) {
        // Keep the primary recoverable if removal of the older copy fails.
        val previous = File(folder, "$id.json.previous")
        if (!previous.exists() || previous.delete()) file(id).delete()
        Unit
    }
    suspend fun list() = withContext(Dispatchers.IO) {
        folder.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.previous") }
            .map { it.name.substringBefore(".json") }.distinct().mapNotNull {
                runCatching { AtomicDocumentStore(file(it)).load().document }.getOrNull()
            }
    }
}
