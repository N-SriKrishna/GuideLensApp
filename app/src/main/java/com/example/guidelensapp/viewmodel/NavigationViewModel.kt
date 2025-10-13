package com.example.guidelensapp.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guidelensapp.Config
import com.example.guidelensapp.ml.ObjectDetector
import com.example.guidelensapp.ml.FloorSegmenter
import com.example.guidelensapp.ml.DetectionResult
import com.example.guidelensapp.navigation.NavigationOutput
import com.example.guidelensapp.navigation.PathPlanner
import com.example.guidelensapp.utils.MemoryManager
import com.example.guidelensapp.utils.ThreadManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

data class NavigationUiState(
    val cameraBitmap: ImageBitmap? = null,
    val floorMaskBitmap: ImageBitmap? = null,
    val detections: List<DetectionResult> = emptyList(),
    val navigationPath: List<PointF>? = null,
    val command: String = "Initializing...",
    val systemState: String = "INIT",
    val performanceInfo: String = ""
)

class NavigationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState = _uiState.asStateFlow()

    // Core components
    private var objectDetector: ObjectDetector? = null
    private var floorSegmenter: FloorSegmenter? = null
    private var pathPlanner: PathPlanner? = null

    // Context reference for memory operations
    private var applicationContext: Context? = null

    // Managers
    private val threadManager = ThreadManager.getInstance()
    private val memoryManager = MemoryManager.getInstance()

    // Navigation timing
    private var lastInstructionTime = 0L

    // Device detection
    private val isEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.FINGERPRINT.contains("unknown") ||
            android.os.Build.MODEL.contains("Emulator")

    // Frame throttling
    private var lastProcessTime = 0L
    private val MIN_FRAME_INTERVAL_MS = if (isEmulator) 1000L else 300L

    // Processing state
    private val isProcessing = AtomicBoolean(false)

    // Segmentation caching
    private var segmentationFrameCounter = 0
    private val SEGMENT_EVERY_N_FRAMES = if (isEmulator) 100 else 10
    private var cachedFloorMask: Bitmap? = null
    private var lastSegmentationTime = 0L

    // Performance tracking
    private var frameCount = 0L
    private var modelsWarmedUp = false

    // Error handling
    private var consecutiveErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 5

    companion object {
        private const val TAG = "NavigationViewModel"
        private const val SEGMENTATION_TIMEOUT_MS = 8000L
        private const val DETECTION_TIMEOUT_MS = 3000L
    }

    fun initializeModels(context: Context) {
        if (objectDetector != null) return

        // Store application context
        applicationContext = context.applicationContext

        viewModelScope.launch(threadManager.ioDispatcher) {
            try {
                Log.d(TAG, "Initializing models... (isEmulator=$isEmulator)")
                _uiState.update { it.copy(command = "Loading models...", systemState = "LOADING") }

                // Initialize models
                objectDetector = ObjectDetector(context)
                floorSegmenter = FloorSegmenter(context)
                pathPlanner = PathPlanner()

                Log.d(TAG, "Models initialized successfully")
                _uiState.update { it.copy(command = "Models loaded - Warming up...", systemState = "WARMING_UP") }

                // Wait for floor segmenter to warm up
                var waitTime = 0
                while (!floorSegmenter?.isReady()!! && waitTime < 5000) {
                    delay(100)
                    waitTime += 100
                }

                modelsWarmedUp = true

                _uiState.update { it.copy(command = "Ready", systemState = "SEARCHING") }
                Log.d(TAG, "Models ready for inference")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize models", e)
                _uiState.update { it.copy(command = "Initialization failed: ${e.message}", systemState = "ERROR") }
            }
        }
    }

    fun onFrame(bitmap: Bitmap) {
        // Don't process until models are ready
        if (!modelsWarmedUp) {
            return
        }

        // Throttle frame processing
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < MIN_FRAME_INTERVAL_MS) {
            return
        }

        // Skip if still processing
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }

        lastProcessTime = currentTime

        val detector = objectDetector
        val segmenter = floorSegmenter
        val planner = pathPlanner

        if (detector == null || segmenter == null || planner == null) {
            Log.w(TAG, "Models not initialized yet")
            isProcessing.set(false)
            return
        }

        viewModelScope.launch(threadManager.imageProcessingDispatcher) {
            try {
                frameCount++
                val frameStartTime = System.currentTimeMillis()

                // Convert bitmap if needed
                val rgbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    val converted = memoryManager.getBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    Canvas(converted).drawBitmap(bitmap, 0f, 0f, null)
                    converted
                } else {
                    bitmap
                }

                // Run detection
                val detectionStartTime = System.currentTimeMillis()
                val detectionResults = withTimeoutOrNull(DETECTION_TIMEOUT_MS) {
                    detector.detect(rgbBitmap)
                } ?: emptyList()
                val detectionTime = System.currentTimeMillis() - detectionStartTime

                if (!isActive) {
                    if (rgbBitmap !== bitmap) memoryManager.recycleBitmap(rgbBitmap)
                    return@launch
                }

                // Run floor segmentation conditionally
                segmentationFrameCounter++
                val shouldSegment = segmentationFrameCounter >= SEGMENT_EVERY_N_FRAMES || cachedFloorMask == null

                val floorMask = if (shouldSegment) {
                    val segmentationStartTime = System.currentTimeMillis()

                    val mask = withTimeoutOrNull(SEGMENTATION_TIMEOUT_MS) {
                        segmenter.segmentFloor(rgbBitmap)
                    }

                    val segmentationTime = System.currentTimeMillis() - segmentationStartTime
                    Log.d(TAG, "Segmentation took ${segmentationTime}ms")

                    if (mask != null) {
                        consecutiveErrors = 0
                        // Update cache
                        cachedFloorMask?.let { memoryManager.recycleBitmap(it) }
                        cachedFloorMask = mask.copy(mask.config ?: Bitmap.Config.ARGB_8888, false)
                        segmentationFrameCounter = 0
                        lastSegmentationTime = System.currentTimeMillis()
                    } else {
                        consecutiveErrors++
                        Log.w(TAG, "Floor segmentation failed")
                    }

                    mask ?: cachedFloorMask
                } else {
                    cachedFloorMask
                }

                // Clean up converted bitmap
                if (rgbBitmap !== bitmap) {
                    memoryManager.recycleBitmap(rgbBitmap)
                }

                if (!isActive) return@launch

                // Find target and plan path
                val target = detectionResults.find {
                    it.label.equals(Config.TARGET_OBJECT_LABEL, ignoreCase = true)
                }

                val targetPosition = target?.let {
                    PointF(it.boundingBox.centerX(), it.boundingBox.centerY())
                }

                val navOutput = if (floorMask != null) {
                    planner.getNavigationCommand(
                        floorMask,
                        targetPosition,
                        bitmap.width,
                        bitmap.height
                    )
                } else {
                    NavigationOutput("Floor detection unavailable")
                }

                val totalTime = System.currentTimeMillis() - frameStartTime

                // Get performance info
                val performanceInfo = buildPerformanceInfo(
                    frameTime = totalTime,
                    detectionTime = detectionTime,
                    segmentationUsedCache = !shouldSegment
                )

                // Check system health
                val systemHealth = checkSystemHealth()

                // Update UI state
                _uiState.update { currentState ->
                    val instructionTime = System.currentTimeMillis()
                    val newCommand = if (instructionTime - lastInstructionTime > Config.INSTRUCTION_LOCK_DURATION_MS) {
                        lastInstructionTime = instructionTime
                        when {
                            consecutiveErrors >= MAX_CONSECUTIVE_ERRORS -> "System experiencing issues..."
                            !systemHealth -> "Performance degraded - reducing quality"
                            else -> navOutput.command
                        }
                    } else {
                        currentState.command
                    }

                    val systemState = when {
                        consecutiveErrors >= MAX_CONSECUTIVE_ERRORS -> "ERROR"
                        !systemHealth -> "DEGRADED"
                        target != null -> "GUIDING"
                        else -> "SEARCHING"
                    }

                    currentState.copy(
                        cameraBitmap = bitmap.asImageBitmap(),
                        detections = detectionResults,
                        floorMaskBitmap = floorMask?.asImageBitmap(),
                        navigationPath = navOutput.path,
                        command = newCommand,
                        systemState = systemState,
                        performanceInfo = performanceInfo
                    )
                }

                // Log performance periodically
                if (frameCount % 30 == 0L) {
                    logDetailedPerformance()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in frame processing", e)
                consecutiveErrors++

                _uiState.update { currentState ->
                    currentState.copy(
                        command = "Processing error",
                        systemState = "ERROR"
                    )
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    /**
     * Build performance info string for UI
     */
    private fun buildPerformanceInfo(
        frameTime: Long,
        detectionTime: Long,
        segmentationUsedCache: Boolean
    ): String {
        val fps = if (frameTime > 0) 1000f / frameTime else 0f
        val segmenterStats = floorSegmenter?.getPerformanceStats()
        val health = floorSegmenter?.getHealth()

        return buildString {
            append("FPS: ${String.format("%.1f", fps)}")
            append(" | Detection: ${detectionTime}ms")

            segmenterStats?.let { stats ->
                if (stats.totalFrames > 0) {
                    append("\nSegmentation: ${stats.avgInferenceTime}ms")
                    append(" (Q: ${(stats.currentQuality * 100).toInt()}%)")
                }
            }

            health?.let { h ->
                if (!h.isHealthy) {
                    append("\n⚠️ Health: ${h.errorRate.toInt()}% errors")
                }
            }

            if (segmentationUsedCache) {
                append(" [cached]")
            }

            // Memory info - use stored context
            applicationContext?.let { ctx ->
                val memInfo = memoryManager.getMemoryInfo(ctx)
                if (memInfo.getUsagePercentage() > 80) {
                    append("\n⚠️ Memory: ${memInfo.getUsagePercentage().toInt()}%")
                }
            }
        }
    }

    /**
     * Check system health and adjust quality if needed
     */
    private fun checkSystemHealth(): Boolean {
        val segmenterHealth = floorSegmenter?.getHealth() ?: return true

        // Adjust quality based on health
        if (!segmenterHealth.isHealthy) {
            when {
                segmenterHealth.avgResponseTime > 500 && segmenterHealth.quality > 0.5f -> {
                    Log.w(TAG, "Performance degraded, reducing quality")
                    floorSegmenter?.setQuality(0.5f)
                    return false
                }
                segmenterHealth.errorRate > 20f -> {
                    Log.w(TAG, "High error rate, forcing cleanup")
                    floorSegmenter?.forceCleanup()
                    return false
                }
                segmenterHealth.memoryPressure > 85f -> {
                    Log.w(TAG, "High memory pressure, forcing cleanup")
                    applicationContext?.let { ctx ->
                        memoryManager.performGcIfNeeded(ctx)
                    }
                    floorSegmenter?.forceCleanup()
                    return false
                }
            }
        } else {
            // Health is good, try to restore quality
            if (segmenterHealth.quality < 1.0f && segmenterHealth.avgResponseTime < 200) {
                floorSegmenter?.setQuality(1.0f)
            }
        }

        return segmenterHealth.isHealthy
    }

    /**
     * Log detailed performance metrics
     */
    private fun logDetailedPerformance() {
        Log.i(TAG, "=== PERFORMANCE REPORT (Frame $frameCount) ===")

        // Object detector stats
        Log.i(TAG, "Object Detection: Active")

        // Floor segmenter stats
        floorSegmenter?.let { segmenter ->
            val stats = segmenter.getPerformanceStats()
            val health = segmenter.getHealth()

            Log.i(TAG, "Floor Segmentation:")
            Log.i(TAG, "  - Frames: ${stats.totalFrames}")
            Log.i(TAG, "  - Avg Total: ${stats.avgTotalTime}ms")
            Log.i(TAG, "  - Avg FPS: ${String.format("%.1f", stats.avgFPS)}")
            Log.i(TAG, "  - Quality: ${(stats.currentQuality * 100).toInt()}%")
            Log.i(TAG, "  - Health: ${if (health.isHealthy) "✅ GOOD" else "⚠️ DEGRADED"}")
            Log.i(TAG, "  - Errors: ${String.format("%.1f", health.errorRate)}%")
        }

        // Memory stats - use stored context
        applicationContext?.let { ctx ->
            val memInfo = memoryManager.getMemoryInfo(ctx)
            Log.i(TAG, "Memory:")
            Log.i(TAG, "  - App Usage: ${memInfo.getUsagePercentage().toInt()}%")
            Log.i(TAG, "  - Bitmap Pool: ${memInfo.bitmapPoolSize}")
        }

        // Thread stats
        val threadStats = threadManager.getThreadPoolStats()
        Log.i(TAG, "Threads: $threadStats")

        Log.i(TAG, "=======================================")
    }

    /**
     * Handle low memory situations
     */
    fun onLowMemory() {
        Log.w(TAG, "Low memory warning received")

        // Force cleanup
        floorSegmenter?.forceCleanup()

        // Clear cached floor mask
        cachedFloorMask?.let {
            memoryManager.recycleBitmap(it)
            cachedFloorMask = null
        }

        // Reduce quality
        floorSegmenter?.setQuality(0.5f)

        // Force GC
        System.gc()

        _uiState.update { it.copy(
            command = "Reducing quality due to memory pressure",
            systemState = "DEGRADED"
        ) }
    }

    /**
     * Manually adjust quality
     */
    fun setQuality(quality: Float) {
        floorSegmenter?.setQuality(quality)
        Log.d(TAG, "Quality manually set to ${(quality * 100).toInt()}%")
    }

    /**
     * Get current performance statistics
     */
    fun getPerformanceStats() = floorSegmenter?.getPerformanceStats()

    /**
     * Get system health
     */
    fun getSystemHealth() = floorSegmenter?.getHealth()

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "Cleaning up NavigationViewModel...")

        // Stop processing
        isProcessing.set(true)

        try {
            // Cancel all coroutines
            viewModelScope.cancel()

            // Clean up models
            objectDetector?.close()
            floorSegmenter?.close()

            // Clean up cached resources
            cachedFloorMask?.let {
                memoryManager.recycleBitmap(it)
            }

            // Null out references
            objectDetector = null
            floorSegmenter = null
            pathPlanner = null
            cachedFloorMask = null
            applicationContext = null

            Log.d(TAG, "NavigationViewModel cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}