package dev.dejalango.engine.pipeline

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerOperationsTest {
    @Test fun `shutdown joins embedding cleanup and prevents new owner operations`() = runTest {
        val owner = OwnerOperations()
        var embedding = false; var cleaned = false; var reachedStorage = false
        val job = launch {
            owner.run {
                // Generation has already returned; the operation still owns embedding and its unwind.
                embedding = true
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { delay(500); cleaned = true } }
                @Suppress("UNREACHABLE_CODE")
                run { reachedStorage = true }
            }
        }
        runCurrent(); assertTrue(embedding)
        val stopped = owner.stop()
        val closing = launch { stopped.joinAll() }
        runCurrent(); assertFalse(closing.isCompleted); assertFalse(cleaned)
        advanceUntilIdle()
        assertTrue(job.isCompleted); assertTrue(closing.isCompleted); assertTrue(cleaned); assertFalse(reachedStorage)
        var admitted = false
        try { owner.run { admitted = true } } catch (_: CancellationException) {}
        assertFalse(admitted)
    }
}
