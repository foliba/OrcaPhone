package com.orcaphone.core.slice

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/** Cooperative cancellation flag shared between UI and the slicing threads. */
class CancelToken {
    @Volatile
    var isCancelled = false
        private set

    fun cancel() { isCancelled = true }

    fun check() { if (isCancelled) throw CancellationException("Slicing cancelled") }
}

/** Small parallel-for helper over a fixed thread pool. */
class ParallelRunner(
    threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    private val cancel: CancelToken = CancelToken(),
) : AutoCloseable {
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "slicer").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val threadCount = threads

    fun forEach(n: Int, onProgress: ((Int) -> Unit)? = null, body: (Int) -> Unit) {
        if (n == 0) return
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val futures = ArrayList<Future<*>>()
        repeat(minOf(threadCount, n)) {
            futures.add(pool.submit {
                while (true) {
                    if (cancel.isCancelled) return@submit
                    val i = next.getAndIncrement()
                    if (i >= n) return@submit
                    body(i)
                    val d = done.incrementAndGet()
                    onProgress?.invoke(d)
                }
            })
        }
        try {
            for (f in futures) f.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        cancel.check()
    }

    override fun close() {
        pool.shutdownNow()
    }
}
