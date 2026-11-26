package com.sider.lantern

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class SaveStatus { Blank, Saving, Saved, Failed }

/** Confined to the editor's UI coroutine scope. Timer cancellation never cancels a write. */
internal class DraftAutosave(
    initial: Document,
    savedRevision: Long?,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 350,
    private val checkpoint: suspend (Document) -> Unit = {},
    private val clearCheckpoint: suspend (String) -> Unit = {},
    private val write: suspend (Document, Long?, Long) -> Unit,
) {
    private data class Snapshot(val document: Document, val revision: Long)

    private val id = initial.id
    private var lastSubmitted = initial
    private var persisted = savedRevision
    private var sequence = savedRevision ?: 0
    private var pending: Snapshot? = null
    private var timer: Job? = null
    private var writer: Job? = null
    val hasPending: Boolean get() = pending != null
    var status by mutableStateOf(if (savedRevision == null) SaveStatus.Blank else SaveStatus.Saved)
        private set
    var reason by mutableStateOf<Int?>(null)
        private set

    fun submit(document: Document) {
        require(document.id == id)
        if (document == lastSubmitted) return
        if (sequence == 0L && document.title.isEmpty() && document.body.isEmpty()) return
        lastSubmitted = document
        pending = Snapshot(document, ++sequence)
        status = SaveStatus.Saving
        reason = null
        if (writer == null) {
            timer?.cancel()
            timer = scope.launch {
                delay(debounceMillis)
                timer = null
                requestFlush()
            }
        }
    }

    fun requestFlush() {
        timer?.cancel()
        timer = null
        if (pending == null || writer != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val snapshot = pending ?: break
                    status = SaveStatus.Saving
                    checkpoint(snapshot.document)
                    write(snapshot.document, persisted, snapshot.revision)
                    persisted = snapshot.revision
                    if (pending?.revision == snapshot.revision) {
                        clearCheckpoint(snapshot.document.id)
                    }
                    if (pending?.revision == snapshot.revision) {
                        pending = null
                        status = SaveStatus.Saved
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = SaveStatus.Failed
                reason = when (failure) {
                    is LibraryConflict -> failure.messageId
                    is ExternalChanged -> R.string.external_changed
                    else -> null
                }
            } finally {
                writer = null
            }
        }
        writer = job
        job.start()
    }

    suspend fun flush(): Boolean {
        requestFlush()
        writer?.join()
        return !hasPending
    }

    suspend fun retainForRecovery(): Document? {
        val snapshot = pending ?: return null
        if (writer != null) return null
        checkpoint(snapshot.document)
        return snapshot.document.takeIf(::hasPendingDocument)
    }

    fun hasPendingDocument(document: Document) = pending?.document == document && writer == null
}
