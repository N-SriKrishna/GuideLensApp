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

    // Adaptive thread pool sizing
    private val mlThreads = 1  // Always single thread for ML to avoid context switching

    private val imageThreadsMin = when {
        coreCount >= 8 -> 2
        coreCount >= 4 -> 1
        else -> 1
    }

    private val imageThreadsMax = when {
        coreCount >= 8 -> 4
        coreCount >= 6 -> 3
        coreCount >= 4 -> 2
        else -> 1
    }

    // Specialized thread pools
    private val mlInferenceExecutor = ThreadPoolExecutor(
        mlThreads,
        mlThreads,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(3),
        { r -> Thread(r, "ML-Inference").apply { priority = Thread.MAX_PRIORITY } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val imageProcessingExecutor = ThreadPoolExecutor(
        imageThreadsMin,
        imageThreadsMax,
        30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(5),
        { r -> Thread(r, "Image-Processing-${Thread.currentThread().id}").apply {
            priority = Thread.NORM_PRIORITY + 1
        } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val ioExecutor = ThreadPoolExecutor(
        2,
        minOf(4, coreCount),
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

    val backgroundScope = CoroutineScope(
        SupervisorJob() +
                CoroutineName("AppBackground") +
                ioDispatcher
    )

    init {
        Log.d(TAG, "ThreadManager initialized. CPU cores: $coreCount")
        Log.d(TAG, "ML Inference: $mlThreads thread (high priority)")
        Log.d(TAG, "Image Processing: $imageThreadsMin-$imageThreadsMax threads")
        Log.d(TAG, "IO Operations: 2-${minOf(4, coreCount)} threads")
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