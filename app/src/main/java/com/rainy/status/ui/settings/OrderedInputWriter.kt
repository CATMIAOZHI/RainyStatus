package com.rainy.status.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main-thread-confined input state. All calls and [scope] must use the same single-thread
 * dispatcher (Main.immediate in production). The scope belongs to the application, not the UI.
 *
 * Debouncing only cancels timers, never persistence. Each write captures its predecessor before
 * scheduling, so even a suspended old write must finish before a newer write can start.
 * Drafts remain available after submission: cancellation of a UI waiter cannot consume them.
 */
internal class OrderedInputWriter<K, E>(
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val validate: (K, String) -> E?,
    private val onValidation: (K, E?) -> Unit,
    private val persist: suspend (K, String) -> Unit,
    private val queue: OrderedWriteQueue = OrderedWriteQueue(),
) {
    private data class Draft(val value: String, val revision: Long)
    private val drafts = mutableMapOf<K, Draft>()
    private val timers = mutableMapOf<K, Job>()
    private var revision = 0L

    fun edit(key: K, value: String) {
        revision++
        val draft = Draft(value, queue.nextRevision(key))
        drafts[key] = draft
        timers.remove(key)?.cancel()
        timers[key] = scope.launch {
            delay(debounceMillis)
            if (drafts[key] == draft) submit(key, draft)
        }
    }

    fun draftValue(key: K): String? = drafts[key]?.value

    fun commitNow(key: K, @Suppress("UNUSED_PARAMETER") value: String) {
        // Focus alone is not an edit. Never promote a potentially stale UI/storage snapshot
        // to a newer revision; only onValueChange may create a draft.
        val draft = drafts[key] ?: return
        timers.remove(key)?.cancel()
        submit(key, draft)
    }

    private fun submit(key: K, draft: Draft): Deferred<Boolean>? {
        val value = draft.value.trim()
        val error = validate(key, value)
        onValidation(key, error) // includes clearing a previous error on every valid path
        if (error != null) return null
        return queue.enqueue(scope, key, draft.revision) { persist(key, value) }
    }

    /** Navigation flush: enqueue before the old ViewModel is discarded, without a UI-owned job. */
    fun commitAll() {
        timers.values.forEach { it.cancel() }
        timers.clear()
        drafts.forEach { (key, draft) -> submit(key, draft) }
    }

    /** Await a stable latest revision; edits arriving while storage suspends are included. */
    suspend fun flush(): Boolean {
        while (true) {
            val targetRevision = revision
            timers.values.forEach { it.cancel() }
            timers.clear()
            var valid = true
            val writes = drafts.mapNotNull { (key, draft) ->
                submit(key, draft).also { if (it == null) valid = false }
            }
            // Writes are application-owned before the first suspension. Cancelling the caller
            // only cancels these awaits, not the writes or their predecessors.
            writes.forEach { if (!it.await()) valid = false }
            if (!queue.awaitIdle()) valid = false
            if (targetRevision == revision) return valid
        }
    }
}

/** Shared across Settings ViewModels. Access only from the same Main dispatcher. */
internal class OrderedWriteQueue {
    private val revisions = mutableMapOf<Any?, Long>()
    private var tail: Deferred<Boolean>? = null
    private val failedKeys = mutableSetOf<Any?>()

    fun hasFailures(): Boolean = failedKeys.isNotEmpty()

    suspend fun awaitIdle(): Boolean {
        while (true) {
            val current = tail
            current?.await()
            if (tail === current) return !hasFailures()
        }
    }

    fun nextRevision(key: Any?): Long = ((revisions[key] ?: 0L) + 1L).also {
        revisions[key] = it
    }

    fun enqueue(
        scope: CoroutineScope,
        key: Any?,
        revision: Long,
        persist: suspend () -> Unit,
    ): Deferred<Boolean> {
        val previous = tail
        return scope.async {
            previous?.join()
            // An old screen's debounce may fire AFTER the new screen has edited this field.
            if (revisions[key] != revision) return@async true // superseded, not a storage failure
            try {
                persist()
                failedKeys.remove(key)
                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A later success for another key must not hide this failed save.
                failedKeys.add(key)
                false
            }
        }.also { tail = it }
    }
}
