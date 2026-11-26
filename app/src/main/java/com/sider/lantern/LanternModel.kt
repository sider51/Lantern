package com.sider.lantern

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private val Context.libraryPreferences by preferencesDataStore("library_preferences")
private val titleOrder = booleanPreferencesKey("sort_by_title")
private val descendingOrder = booleanPreferencesKey("sort_descending")

internal data class DraftOrder(val byTitle: Boolean = false, val descending: Boolean = !byTitle)

internal class LanternModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.libraryPreferences
    private val database = LibraryDatabase.open(application)
    val library = LibraryRepository(database, external = ExternalLibrary(application))
    val recovery = DraftRecovery(File(application.filesDir, "draft-checkpoints"))
    val writing = WritingPreferences(application.writingDataStore)
    val writingOptions = writing.options.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val writingCategory = writing.category.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val customization = writing.customization.stateIn(viewModelScope, SharingStarted.Eagerly, Customization())
    val folders = library.folders.map { LibraryRead(it) }
        .catch { if (it is CancellationException) throw it else emit(LibraryRead(failure = true)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryRead())
    private val lists = LinkedHashMap<Triple<String?, DraftOrder, Boolean>, LibraryRead<List<ManuscriptSummary>>>()

    fun cachedRows(key: Triple<String?, DraftOrder, Boolean>) = lists[key] ?: LibraryRead()
    fun libraryRows(key: Triple<String?, DraftOrder, Boolean>) =
        library.summaries(key.first, "", key.second.byTitle, recent = key.third, descending = key.second.descending).map { LibraryRead(it) }
            .catch { if (it is CancellationException) throw it else emit(LibraryRead(failure = true)) }
            .onEach {
                if (key !in lists && lists.size >= 16) lists.remove(lists.keys.first())
                lists[key] = it
            }
    val order = preferences.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { it.draftOrder() }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DraftOrder())

    var ready by mutableStateOf(false)
        private set
    var startupFailed by mutableStateOf(false)
        private set
    var editor by mutableStateOf<ProductDraft?>(null)
        private set

    init { initialize() }

    fun initialize() {
        startupFailed = false
        viewModelScope.launch {
            try {
                library.initialize()
                ready = true
                try { library.purgeExpired(writing.customization.first().retention) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retry when Trash is opened. */ }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                startupFailed = true
            }
        }
    }

    suspend fun sortDrafts(order: DraftOrder) {
        preferences.edit {
            it[titleOrder] = order.byTitle
            it[descendingOrder] = order.descending
        }
    }

    suspend fun requestLocalReset(): Boolean {
        // Navigation leaves the editor only after its writer has finished or a checkpoint
        // has been retained. Refuse to proceed if a live editor is still attached.
        if (editor != null) return false
        val manager = getApplication<Application>().getSystemService(ActivityManager::class.java)
        return library.requestLocalReset { manager.clearApplicationUserData() }
    }

    fun newDraft(folderId: String = INBOX_ID): String {
        val draft = editor ?: ProductDraft(Document.create("", ""), null, viewModelScope, library, recovery = recovery,
            initialFolder = folderId)
        editor = draft
        return draft.id
    }

    suspend fun open(id: String): Boolean {
        if (editor?.id == id) return true
        check(editor == null) { "開いている原稿があります。" }
        val stored = library.load(id) ?: return false
        editor = ProductDraft(stored.document, stored.revision, viewModelScope, library, writing.loadPosition(id), recovery)
        return true
    }

    fun close(id: String) {
        if (editor?.id == id) editor = null
    }

    suspend fun backup(): LibraryArchive {
        val order = preferences.data.first().draftOrder()
        return library.backup().copy(settings = writing.backup() + mapOf(
            "sort_by_title" to order.byTitle.toString(), "sort_descending" to order.descending.toString(),
        ))
    }

    suspend fun restore(archive: LibraryArchive) {
        library.importArchive(archive)
        try {
            writing.restore(archive.settings)
            archive.settings["sort_by_title"]?.toBooleanStrictOrNull()?.let { byTitle ->
                sortDrafts(DraftOrder(byTitle, archive.settings["sort_descending"]?.toBooleanStrictOrNull() ?: !byTitle))
            }
        } catch (_: IOException) {
            throw LibraryConflict(R.string.partial_restore)
        }
    }

    fun rememberCategory(category: WritingCategory) {
        viewModelScope.launch {
            try { writing.select(category) } catch (_: IOException) { /* The selected tab remains usable. */ }
        }
    }

    fun checkpointPosition(draft: ProductDraft) {
        viewModelScope.launch {
            if (draft.autosave.flush()) rememberPosition(draft)
        }
    }

    suspend fun rememberPosition(draft: ProductDraft) {
        val position = draft.positionForStorage() ?: return
        try { writing.savePosition(draft.id, position) } catch (_: IOException) { /* Draft storage is independent. */ }
    }
}

private fun androidx.datastore.preferences.core.Preferences.draftOrder(): DraftOrder {
    val byTitle = this[titleOrder] ?: false
    return DraftOrder(byTitle, this[descendingOrder] ?: !byTitle)
}

internal class ProductDraft(
    initial: Document,
    revision: Long?,
    scope: CoroutineScope,
    repository: LibraryRepository,
    position: SavedEditorPosition? = null,
    recovery: DraftRecovery? = null,
    val initialFolder: String = INBOX_ID,
) {
    val id = initial.id
    val autosave = DraftAutosave(initial, revision, scope,
        checkpoint = { recovery?.save(it) }, clearCheckpoint = { recovery?.clear(it) },
        write = { document, expected, revision -> repository.save(document, expected, revision, initialFolder) })
    private var committedTitle = initial.title
    var title by mutableStateOf(TextFieldValue(initial.title))
        private set
    val editor = EditorSession(initial, position?.selectionIn(initial.body) ?: EditorPosition()) { body ->
        autosave.submit(body.copy(title = committedTitle))
    }.apply { scroll = position?.scroll?.coerceAtLeast(0) ?: 0 }
    val hasComposition: Boolean get() = title.composition != null || editor.composition != null

    fun positionForStorage(): SavedEditorPosition? = if (hasComposition || autosave.status != SaveStatus.Saved) null
    else SavedEditorPosition(editor.committed.position.end, editor.scroll)

    fun updateTitle(value: TextFieldValue) {
        if (!value.text.isWellFormed()) return
        title = value
        if (value.composition == null && value.text != committedTitle) {
            committedTitle = value.text
            autosave.submit(editor.committed.document.copy(title = committedTitle))
        }
    }
}
