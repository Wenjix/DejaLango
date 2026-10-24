package dev.dejalango.engine.pipeline

import kotlinx.coroutines.*

/** Owns the entire profile operation, including post-generation storage/embedding work. */
class OwnerOperations {
    private val gate = Any()
    private val jobs = HashSet<Job>()
    private var stopped = false

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        synchronized(gate) {
            if (stopped) throw CancellationException("Runtime stopped")
            jobs += job
        }
        try { block() } finally { synchronized(gate) { jobs -= job } }
    }

    fun stop(): List<Job> = synchronized(gate) {
        stopped = true
        jobs.toList().also { snapshot -> snapshot.forEach { it.cancel() } }
    }
}
