// app/src/main/java/com/example/guidelensapp/utils/MemoryManager.kt
package com.example.guidelensapp.utils

import android.app.ActivityManager
import android.content.Context
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
    private val maxPoolSize = 10

    // Memory monitoring
    private var lastGcTime = 0L
    private val gcInterval = 30_000L // 30 seconds

    /**
     * Get a reusable bitmap from pool or create new one
     */
    fun getBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        // Try to reuse from pool first
        val iterator = bitmapPool.iterator()
        while (iterator.hasNext()) {
            val ref = iterator.next()
            val bitmap = ref.get()

            if (bitmap == null) {
                iterator.remove() // Clean up dead references
                continue
            }

            if (bitmap.width == width &&
                bitmap.height == height &&
                bitmap.config == config &&
                !bitmap.isRecycled) {
                iterator.remove()
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                Log.d(TAG, "Reused bitmap from pool: ${width}x${height}")
                return bitmap
            }
        }

        // Create new bitmap if none available
        Log.d(TAG, "Created new bitmap: ${width}x${height}")
        return Bitmap.createBitmap(width, height, config)
    }

    /**
     * Return bitmap to pool for reuse
     */
    fun recycleBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return

        if (bitmapPool.size < maxPoolSize) {
            bitmapPool.offer(WeakReference(bitmap))
            Log.d(TAG, "Bitmap returned to pool. Pool size: ${bitmapPool.size}")
        } else {
            bitmap.recycle()
            Log.d(TAG, "Bitmap recycled (pool full)")
        }
    }

    /**
     * Force garbage collection if needed
     */
    fun performGcIfNeeded(context: Context) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGcTime > gcInterval) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)

            // Trigger GC if available memory is low
            if (memInfo.availMem < memInfo.totalMem * 0.1) { // Less than 10% available
                Log.d(TAG, "Low memory detected. Triggering GC. Available: ${memInfo.availMem / 1024 / 1024}MB")
                System.gc()
                cleanupBitmapPool()
                lastGcTime = currentTime
            }
        }
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

    /**
     * Get current memory usage info
     */
    fun getMemoryInfo(context: Context): MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()

        return MemoryInfo(
            totalSystemMemory = memInfo.totalMem,
            availableSystemMemory = memInfo.availMem,
            usedAppMemory = runtime.totalMemory() - runtime.freeMemory(),
            maxAppMemory = runtime.maxMemory(),
            bitmapPoolSize = bitmapPool.size
        )
    }
}

data class MemoryInfo(
    val totalSystemMemory: Long,
    val availableSystemMemory: Long,
    val usedAppMemory: Long,
    val maxAppMemory: Long,
    val bitmapPoolSize: Int
) {
    fun getUsagePercentage(): Float = (usedAppMemory.toFloat() / maxAppMemory.toFloat()) * 100f

    override fun toString(): String {
        return "Memory Usage: ${getUsagePercentage().toInt()}% " +
                "(${usedAppMemory / 1024 / 1024}MB / ${maxAppMemory / 1024 / 1024}MB), " +
                "System: ${availableSystemMemory / 1024 / 1024}MB available, " +
                "Bitmap Pool: $bitmapPoolSize"
    }
}