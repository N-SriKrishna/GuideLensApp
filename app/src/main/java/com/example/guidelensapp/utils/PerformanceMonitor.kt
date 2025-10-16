// app/src/main/java/com/example/guidelensapp/utils/PerformanceMonitor.kt

package com.example.guidelensapp.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class PerformanceMonitor(private val context: Context) {

    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val FPS_WINDOW_SIZE = 60
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val frameTimestamps = mutableListOf<Long>()
    private val lastFrameTime = AtomicLong(0L)

    private val inferenceTimestamps = mutableListOf<Long>()
    private var lastInferenceStartTime = 0L

    private val memoryInfo = ActivityManager.MemoryInfo()

    private val _performanceMetrics = MutableStateFlow(PerformanceMetrics())
    val performanceMetrics = _performanceMetrics.asStateFlow()

    init {
        Log.d(TAG, "Performance monitor initialized")
    }

    fun onFrameStart() {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastFrameTime.getAndSet(currentTime)

        if (lastTime > 0) {
            val frameTime = currentTime - lastTime
            synchronized(frameTimestamps) {
                frameTimestamps.add(frameTime)
                if (frameTimestamps.size > FPS_WINDOW_SIZE) {
                    frameTimestamps.removeAt(0)
                }
            }
        }
    }

    fun onInferenceStart() {
        lastInferenceStartTime = System.currentTimeMillis()
    }

    fun onInferenceEnd() {
        if (lastInferenceStartTime > 0) {
            val inferenceTime = System.currentTimeMillis() - lastInferenceStartTime
            synchronized(inferenceTimestamps) {
                inferenceTimestamps.add(inferenceTime)
                if (inferenceTimestamps.size > FPS_WINDOW_SIZE) {
                    inferenceTimestamps.removeAt(0)
                }
            }
            lastInferenceStartTime = 0L
        }
    }

    fun updateMetrics() {
        val fps = calculateFPS()
        val avgInferenceTime = calculateAverageInferenceTime()
        val memoryUsage = getMemoryUsage()
        val availableMemory = getAvailableMemory()

        _performanceMetrics.value = PerformanceMetrics(
            currentFPS = fps,
            averageInferenceTimeMs = avgInferenceTime,
            memoryUsedMB = memoryUsage,
            memoryAvailableMB = availableMemory,
            frameCount = frameTimestamps.size,
            lastFrameTimeMs = frameTimestamps.lastOrNull() ?: 0L
        )
    }

    private fun calculateFPS(): Float {
        synchronized(frameTimestamps) {
            if (frameTimestamps.isEmpty()) return 0f

            val avgFrameTime = frameTimestamps.average()
            return if (avgFrameTime > 0) {
                1000f / avgFrameTime.toFloat()
            } else {
                0f
            }
        }
    }

    private fun calculateAverageInferenceTime(): Long {
        synchronized(inferenceTimestamps) {
            return if (inferenceTimestamps.isEmpty()) {
                0L
            } else {
                inferenceTimestamps.average().toLong()
            }
        }
    }

    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        return usedMemory / (1024 * 1024)
    }

    private fun getAvailableMemory(): Long {
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }

    fun reset() {
        synchronized(frameTimestamps) {
            frameTimestamps.clear()
        }
        synchronized(inferenceTimestamps) {
            inferenceTimestamps.clear()
        }
        lastFrameTime.set(0L)
        _performanceMetrics.value = PerformanceMetrics()
        Log.d(TAG, "Performance metrics reset")
    }

    fun getSummary(): String {
        val metrics = _performanceMetrics.value
        return """
            Performance Summary:
            FPS: ${String.format("%.1f", metrics.currentFPS)}
            Avg Inference: ${metrics.averageInferenceTimeMs}ms
            Memory: ${metrics.memoryUsedMB}MB / ${metrics.memoryAvailableMB}MB available
            Frames: ${metrics.frameCount}
        """.trimIndent()
    }
}
