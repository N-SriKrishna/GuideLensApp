// app/src/main/java/com/example/guidelensapp/utils/PerformanceMetrics.kt

package com.example.guidelensapp.utils

data class PerformanceMetrics(
    val currentFPS: Float = 0f,
    val averageInferenceTimeMs: Long = 0L,
    val memoryUsedMB: Long = 0L,
    val memoryAvailableMB: Long = 0L,
    val frameCount: Int = 0,
    val lastFrameTimeMs: Long = 0L
)
