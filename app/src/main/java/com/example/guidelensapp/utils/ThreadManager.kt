// app/src/main/java/com/example/guidelensapp/utils/ThreadManager.kt
package com.example.guidelensapp.utils

import kotlinx.coroutines.*
import java.util.concurrent.*
import android.util.Log
import kotlin.coroutines.CoroutineContext

class ThreadManager private constructor() {

    companion object {
        private const val TAG = "ThreadManager"

        @Volatile
        private var INSTANCE: ThreadManager? = null

        fun getInstance(): ThreadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThreadManager().also { INSTANCE = it }
            }
        }
    }

    // CPU core count detection
    private val coreCount = Runtime.getRuntime().availableProcessors()

    // Specialized thread pools
    private val mlInferenceExecutor = ThreadPoolExecutor(
        1, // Single thread for ML inference to avoid context switching
        1,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(3), // Small queue to prevent backlog
        { r -> Thread(r, "ML-Inference").apply { priority = Thread.MAX_PRIORITY } },
        ThreadPoolExecutor.DiscardOldestPolicy() // Drop old tasks if queue full
    )

    private val imageProcessingExecutor = ThreadPoolExecutor(
        minOf(2, coreCount - 1), // Use available cores but leave one for UI
        minOf(4, coreCount),
        30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(5),
        { r -> Thread(r, "Image-Processing-${Thread.currentThread().id}").apply {
            priority = Thread.NORM_PRIORITY + 1
        } },
        ThreadPoolExecutor.CallerRunsPolicy() // Backpressure handling
    )

    private val ioExecutor = ThreadPoolExecutor(
        2,
        4,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(10),
        { r -> Thread(r, "IO-${Thread.currentThread().id}").apply {
            priority = Thread.NORM_PRIORITY - 1
        } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    // Coroutine dispatchers
    val mlDispatcher = mlInferenceExecutor.asCoroutineDispatcher()
    val imageProcessingDispatcher = imageProcessingExecutor.asCoroutineDispatcher()
    val ioDispatcher = ioExecutor.asCoroutineDispatcher()

    // Background scope for app-wide background tasks
    val backgroundScope = CoroutineScope(
        SupervisorJob() +
                CoroutineName("AppBackground") +
                ioDispatcher
    )

    init {
        Log.d(TAG, "ThreadManager initialized. CPU cores: $coreCount")
        Log.d(TAG, "ML Inference: 1 thread (high priority)")
        Log.d(TAG, "Image Processing: ${minOf(2, coreCount - 1)}-${minOf(4, coreCount)} threads")
        Log.d(TAG, "IO Operations: 2-4 threads")
    }

    /**
     * Execute ML inference with proper priority and resource management
     */
    fun <T> executeMLInference(block: suspend CoroutineScope.() -> T): Deferred<T> {
        return backgroundScope.async(mlDispatcher) {
            try {
                // Set thread priority for ML inference
                Thread.currentThread().priority = Thread.MAX_PRIORITY
                block()
            } finally {
                // Reset priority
                Thread.currentThread().priority = Thread.NORM_PRIORITY
            }
        }
    }

    /**
     * Execute image processing with balanced resource usage
     */
    fun <T> executeImageProcessing(block: suspend CoroutineScope.() -> T): Deferred<T> {
        return backgroundScope.async(imageProcessingDispatcher) {
            block()
        }
    }

    /**
     * Execute IO operations with lower priority
     */
    fun <T> executeIO(block: suspend CoroutineScope.() -> T): Deferred<T> {
        return backgroundScope.async(ioDispatcher) {
            block()
        }
    }

    /**
     * Get current thread pool statistics
     */
    fun getThreadPoolStats(): ThreadPoolStats {
        return ThreadPoolStats(
            mlPoolActive = mlInferenceExecutor.activeCount,
            mlPoolQueue = mlInferenceExecutor.queue.size,
            imagePoolActive = imageProcessingExecutor.activeCount,
            imagePoolQueue = imageProcessingExecutor.queue.size,
            ioPoolActive = ioExecutor.activeCount,
            ioPoolQueue = ioExecutor.queue.size
        )
    }

    /**
     * Shutdown all thread pools gracefully
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down thread pools...")

        backgroundScope.cancel()

        mlInferenceExecutor.shutdown()
        imageProcessingExecutor.shutdown()
        ioExecutor.shutdown()

        try {
            if (!mlInferenceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                mlInferenceExecutor.shutdownNow()
            }
            if (!imageProcessingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                imageProcessingExecutor.shutdownNow()
            }
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Thread pool shutdown interrupted", e)
            mlInferenceExecutor.shutdownNow()
            imageProcessingExecutor.shutdownNow()
            ioExecutor.shutdownNow()
        }
    }
}

data class ThreadPoolStats(
    val mlPoolActive: Int,
    val mlPoolQueue: Int,
    val imagePoolActive: Int,
    val imagePoolQueue: Int,
    val ioPoolActive: Int,
    val ioPoolQueue: Int
) {
    override fun toString(): String {
        return "Threads - ML: $mlPoolActive active, $mlPoolQueue queued | " +
                "Image: $imagePoolActive active, $imagePoolQueue queued | " +
                "IO: $ioPoolActive active, $ioPoolQueue queued"
    }
}