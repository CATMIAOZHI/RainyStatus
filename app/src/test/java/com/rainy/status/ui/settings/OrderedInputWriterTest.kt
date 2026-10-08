package com.rainy.status.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedInputWriterTest {
    private fun writer(
        scope: CoroutineScope,
        queue: OrderedWriteQueue = OrderedWriteQueue(),
        errors: MutableMap<String, String?> = mutableMapOf(),
        persist: suspend (String, String) -> Unit,
    ) = OrderedInputWriter(
        scope = scope,
        debounceMillis = 600,
        validate = { key: String, value: String ->
            if (key != "name" && (value.isBlank() || value.startsWith("http:"))) "invalid" else null
        },
        onValidation = { key, error -> errors[key] = error },
        persist = persist,
        queue = queue,
    )

    @Test fun `suspended old focus write cannot overtake newer flush for any field`() = runTest {
        for (field in listOf("endpoint", "token", "name")) {
            val gate = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val saved = mutableListOf<String>()
            val inputs = writer(this) { _, value ->
                started += value
                if (value == "A") gate.await()
                saved += value
            }
            inputs.edit(field, "A")
            inputs.commitNow(field, "A")
            runCurrent() // old write has actually entered persistence, then suspended
            inputs.edit(field, "B")
            val action = async { inputs.flush() }
            runCurrent()
            assertFalse(action.isCompleted)
            assertEquals(listOf("A"), started)
            gate.complete(Unit)
            runCurrent()
            assertTrue(action.await())
            assertEquals(listOf("A", "B"), saved)
        }
    }

    @Test fun `queued but unstarted old write is skipped`() = runTest {
        val saved = mutableListOf<String>()
        val inputs = writer(this) { _, value -> saved += value }
        inputs.edit("token", "A")
        inputs.commitNow("token", "A")
        inputs.edit("token", "B")
        assertTrue(inputs.flush())
        assertEquals(listOf("B"), saved)
    }

    @Test fun `valid correction clears focus error immediately before debounce`() = runTest {
        val errors = mutableMapOf<String, String?>()
        val inputs = writer(this, errors = errors) { _, _ -> }
        for (field in listOf("endpoint", "token")) {
            inputs.edit(field, "")
            inputs.commitNow(field, "")
            assertEquals("invalid", errors[field])
            inputs.edit(field, "valid")
            inputs.commitNow(field, "valid")
            assertNull(errors[field])
        }
        assertTrue(inputs.flush())
    }

    @Test fun `invalid focus draft keeps blocking actions using old credentials`() = runTest {
        val saved = mutableListOf<String>()
        val inputs = writer(this) { _, value -> saved += value }
        inputs.edit("endpoint", "https://old.example")
        assertTrue(inputs.flush())
        inputs.edit("endpoint", "http://invalid.example")
        inputs.commitNow("endpoint", "http://invalid.example")
        assertFalse(inputs.flush())
        assertEquals(listOf("https://old.example"), saved)
    }

    @Test fun `edit during suspended flush is awaited before action proceeds`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var persisted = ""
        val inputs = writer(this) { _, value ->
            if (value == "A") gate.await()
            persisted = value
        }
        inputs.edit("token", "A")
        val action = async { inputs.flush() }
        runCurrent()
        inputs.edit("token", "B")
        gate.complete(Unit)
        runCurrent()
        assertTrue(action.await())
        assertEquals("B", persisted)
    }

    @Test fun `invalid edit while flush suspends blocks the action`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val inputs = writer(this) { _, _ -> gate.await() }
        inputs.edit("token", "A")
        val action = async { inputs.flush() }
        runCurrent()
        inputs.edit("token", "")
        gate.complete(Unit)
        runCurrent()
        assertFalse(action.await())
    }

    @Test fun `cancelling UI waiter does not cancel application save`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var saved = ""
        val inputs = writer(this) { _, value -> gate.await(); saved = value }
        inputs.edit("token", "latest")
        val uiJob = launch { inputs.flush() }
        runCurrent()
        uiJob.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("latest", saved)
        assertTrue(inputs.flush())
    }

    @Test fun `application debounce saves even with no UI waiter or focus callback`() = runTest {
        var saved = ""
        val inputs = writer(this) { _, value -> saved = value }
        inputs.edit("token", "latest")
        advanceTimeBy(601)
        runCurrent()
        assertEquals("latest", saved)
    }

    @Test fun `replacement writer wins over old screen late debounce and focus`() = runTest {
        val queue = OrderedWriteQueue()
        val saved = mutableListOf<String>()
        val old = writer(this, queue) { _, value -> saved += value }
        val replacement = writer(this, queue) { _, value -> saved += value }
        old.edit("token", "A")
        replacement.edit("token", "B")
        assertTrue(replacement.flush())
        advanceTimeBy(601)
        old.commitNow("token", "A")
        old.commitAll()
        runCurrent()
        assertEquals(listOf("B"), saved)
    }

    @Test fun `replacement action waits for old screen navigation save`() = runTest {
        val queue = OrderedWriteQueue()
        val gate = CompletableDeferred<Unit>()
        var saved = ""
        val old = writer(this, queue) { _, value -> gate.await(); saved = value }
        old.edit("token", "latest")
        old.commitAll()
        val replacement = writer(this, queue) { _, value -> saved = value }
        val action = async { replacement.flush() }
        runCurrent()
        assertFalse(action.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue(action.await())
        assertEquals("latest", saved)
    }

    @Test fun `storage failure blocks action but subsequent save can recover`() = runTest {
        var fail = true
        var saved = ""
        val inputs = writer(this) { _, value ->
            if (fail) throw IllegalStateException("storage unavailable")
            saved = value
        }
        inputs.edit("token", "A")
        assertFalse(inputs.flush())
        fail = false
        inputs.edit("token", "B")
        assertTrue(inputs.flush())
        assertEquals("B", saved)
    }

    @Test fun `focus without editing cannot overwrite pending previous screen save`() = runTest {
        val queue = OrderedWriteQueue()
        val gate = CompletableDeferred<Unit>()
        var saved = "A"
        val old = writer(this, queue) { _, value -> gate.await(); saved = value }
        old.edit("endpoint", "B")
        old.commitAll()
        runCurrent()
        val replacement = writer(this, queue) { _, value -> saved = value }
        replacement.commitNow("endpoint", "A")
        assertNull(replacement.draftValue("endpoint"))
        gate.complete(Unit)
        assertTrue(replacement.flush())
        assertEquals("B", saved)
    }

    @Test fun `another field success cannot hide failure from replacement screen`() = runTest {
        val queue = OrderedWriteQueue()
        val old = writer(this, queue) { key, _ ->
            if (key == "endpoint") throw IllegalStateException("disk full")
        }
        old.edit("endpoint", "B")
        old.edit("token", "T")
        old.commitAll()
        runCurrent()
        val replacement = writer(this, queue) { _, _ -> }
        assertFalse(replacement.flush())
        replacement.edit("token", "new T")
        assertFalse(replacement.flush())
        replacement.edit("endpoint", "new B")
        assertTrue(replacement.flush())
    }

    @Test fun `superseded write is neutral and cannot clear another failure`() = runTest {
        val queue = OrderedWriteQueue()
        val first = queue.nextRevision("endpoint")
        queue.enqueue(this, "endpoint", first) { throw IllegalStateException("disk full") }
        runCurrent()
        queue.nextRevision("endpoint")
        queue.enqueue(this, "endpoint", first) { fail("stale write should not run") }
        assertFalse(queue.awaitIdle())
        val latest = queue.nextRevision("endpoint")
        queue.enqueue(this, "endpoint", latest) { }
        assertTrue(queue.awaitIdle())
    }

    @Test fun `raw invalid and empty drafts remain available for recomposed screen`() = runTest {
        val inputs = writer(this) { _, _ -> }
        assertNull(inputs.draftValue("endpoint"))
        inputs.edit("endpoint", "  http://invalid.example  ")
        inputs.edit("token", "")
        assertFalse(inputs.flush())
        assertEquals("  http://invalid.example  ", inputs.draftValue("endpoint"))
        assertEquals("", inputs.draftValue("token"))
    }

    @Test fun `late stale focus callback uses latest edit`() = runTest {
        var saved = ""
        val inputs = writer(this) { _, value -> saved = value }
        inputs.edit("token", "A")
        inputs.edit("token", "B")
        inputs.commitNow("token", "A")
        runCurrent()
        assertEquals("B", saved)
    }
}
