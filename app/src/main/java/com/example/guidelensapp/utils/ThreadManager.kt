// app/src/main/java/com/example/guidelensapp/utils/ThreadManager.kt
package com.example.guidelensapp.utils

import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
    val ioDispatcher = ioExecutor.asCoroutineDispatcher()

    init {
        Log.d(TAG, "ThreadManager initialized. CPU cores: $coreCount")
        Log.d(TAG, "ML Inference: $mlThreads thread (high priority)")
        Log.d(TAG, "Image Processing: $imageThreadsMin-$imageThreadsMax threads")
        Log.d(TAG, "IO Operations: 2-${minOf(4, coreCount)} threads")
    }


}

