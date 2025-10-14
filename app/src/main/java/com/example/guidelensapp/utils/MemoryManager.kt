// app/src/main/java/com/example/guidelensapp/utils/MemoryManager.kt

package com.example.guidelensapp.utils

import android.graphics.Bitmap
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue

class MemoryManager private constructor() {
    companion object {
        private const val TAG = "MemoryManager"
        @Volatile
        private var INSTANCE: MemoryManager? = null

        fun getInstance(): MemoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryManager().also { INSTANCE = it }
            }
        }
    }

    // Bitmap pool for reuse
    private val bitmapPool = ConcurrentLinkedQueue<WeakReference<Bitmap>>()

    // Memory monitoring
    private var lastGcTime = 0L

    /**
     * Force garbage collection immediately (NEW METHOD)
     */
    fun forceGarbageCollection() {
        Log.d(TAG, "Forcing garbage collection")
        System.gc()
        cleanupBitmapPool()
        lastGcTime = System.currentTimeMillis()
    }

    private fun cleanupBitmapPool() {
        val iterator = bitmapPool.iterator()
        var cleaned = 0
        while (iterator.hasNext()) {
            val ref = iterator.next()
            val bitmap = ref.get()
            if (bitmap == null || bitmap.isRecycled) {
                iterator.remove()
                cleaned++
            }
        }
        Log.d(TAG, "Cleaned up $cleaned dead bitmap references. Pool size: ${bitmapPool.size}")
    }

}
