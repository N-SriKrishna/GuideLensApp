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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

data class NavigationUiState(
    val cameraBitmap: ImageBitmap? = null,
    val floorMaskBitmap: ImageBitmap? = null,
    val detections: List<DetectionResult> = emptyList(),
    val navigationPath: List<PointF>? = null,
    val command: String = "Initializing...",
    val systemState: String = "INIT"
)

class NavigationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState = _uiState.asStateFlow()

    private var objectDetector: ObjectDetector? = null
    private var floorSegmenter: FloorSegmenter? = null
    private var pathPlanner: PathPlanner? = null
    private var lastInstructionTime = 0L

    // Emulator detection - disable heavy processing on emulator
    private val isEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.FINGERPRINT.contains("unknown") ||
            android.os.Build.MODEL.contains("Emulator")

    // Frame throttling - adjusted based on device type
    private var lastProcessTime = 0L
    private val MIN_FRAME_INTERVAL_MS = if (isEmulator) 1000L else 300L  // More conservative timing

    // Use atomic boolean instead of checking job status
    private val isProcessing = AtomicBoolean(false)

    // Segmentation caching - run floor segmentation less frequently
    private var segmentationFrameCounter = 0
    private val SEGMENT_EVERY_N_FRAMES = if (isEmulator) 100 else 10  // Less frequent segmentation
    private var cachedFloorMask: Bitmap? = null
    private var lastSegmentationTime = 0L

    // Performance tracking
    private val recentFrameTimes = mutableListOf<Long>()
    private val MAX_TRACKED_FRAMES = 30

    // First-time processing flags
    private var isFirstDetection = true
    private var isFirstSegmentation = true
    private var modelsWarmedUp = false

    // Error handling
    private var consecutiveErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 5

    companion object {
        private const val TAG = "NavigationViewModel"
        private const val SEGMENTATION_TIMEOUT_MS = 8000L // 8 second timeout for segmentation
        private const val DETECTION_TIMEOUT_MS = 3000L    // 3 second timeout for detection
    }

    fun initializeModels(context: Context) {
        if (objectDetector != null) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing models... (isEmulator=$isEmulator)")
                _uiState.update { it.copy(command = "Loading models...", systemState = "LOADING") }

                // Initialize models
                objectDetector = ObjectDetector(context)
                floorSegmenter = FloorSegmenter(context)
                pathPlanner = PathPlanner()

                Log.d(TAG, "Models initialized successfully")
                _uiState.update { it.copy(command = "Models loaded - Warming up...", systemState = "WARMING_UP") }

                // Give models a moment to warm up
                kotlinx.coroutines.delay(1000)
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
        // Don't process frames until models are warmed up
        if (!modelsWarmedUp) {
            return
        }

        // Throttle frame processing
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < MIN_FRAME_INTERVAL_MS) {
            return  // Skip this frame
        }

        // Skip if still processing previous frame using atomic boolean
        if (!isProcessing.compareAndSet(false, true)) {
            // Log less frequently to avoid spam
            if (currentTime % 2000 < 100) { // Log once every 2 seconds roughly
                Log.d(TAG, "Skipping frame - still processing previous frame")
            }
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

        viewModelScope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "=== FRAME PROCESSING START ===")
                val frameStartTime = System.currentTimeMillis()

                // Convert bitmap to ARGB_8888 if needed
                Log.d(TAG, "Step 1: Converting bitmap format...")
                val rgbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
                } else {
                    bitmap
                }
                Log.d(TAG, "Step 1 complete: ${System.currentTimeMillis() - frameStartTime}ms")

                // Run detection with timeout (faster operation - always run)
                Log.d(TAG, "Step 2: Running object detection...")
                val detectionStartTime = System.currentTimeMillis()
                val detectionResults = withTimeoutOrNull(DETECTION_TIMEOUT_MS) {
                    detector.detect(rgbBitmap)
                } ?: run {
                    Log.w(TAG, "Object detection timed out")
                    emptyList<DetectionResult>()
                }
                val detectionTime = System.currentTimeMillis() - detectionStartTime
                Log.d(TAG, "Step 2 complete: ${detectionTime}ms, found ${detectionResults.size} objects")

                if (isFirstDetection) {
                    Log.i(TAG, "✅ First detection completed in ${detectionTime}ms")
                    isFirstDetection = false
                }

                if (!isActive) {
                    Log.d(TAG, "Coroutine cancelled after detection")
                    return@launch
                }

                // Run floor segmentation conditionally with timeout
                segmentationFrameCounter++
                val shouldSegment = segmentationFrameCounter >= SEGMENT_EVERY_N_FRAMES || cachedFloorMask == null

                Log.d(TAG, "Step 3: Floor segmentation (shouldSegment=$shouldSegment, isEmulator=$isEmulator)...")
                val segmentationStartTime = System.currentTimeMillis()
                val floorMask = if (shouldSegment) {
                    if (isEmulator) {
                        // Use mock mask on emulator to avoid performance issues
                        Log.d(TAG, "Creating mock floor mask for emulator testing")
                        createMockFloorMask(rgbBitmap.width, rgbBitmap.height)
                    } else {
                        // Real segmentation on physical device with timeout handling
                        Log.d(TAG, "Calling segmentFloor()...")

                        val timeoutDuration = if (isFirstSegmentation) SEGMENTATION_TIMEOUT_MS * 2 else SEGMENTATION_TIMEOUT_MS

                        val mask = withTimeoutOrNull(timeoutDuration) {
                            segmenter.segmentFloor(rgbBitmap)
                        }

                        if (mask == null) {
                            Log.w(TAG, "Floor segmentation timed out or failed")
                            consecutiveErrors++

                            // Use cached mask or create a simple mock
                            cachedFloorMask ?: createMockFloorMask(rgbBitmap.width, rgbBitmap.height)
                        } else {
                            Log.d(TAG, "segmentFloor() completed successfully")
                            consecutiveErrors = 0 // Reset error counter

                            if (isFirstSegmentation) {
                                Log.i(TAG, "✅ First floor segmentation completed")
                                isFirstSegmentation = false
                            }

                            mask
                        }
                    }?.also { mask ->
                        // Update cache - handle nullable config
                        cachedFloorMask?.recycle()
                        cachedFloorMask = mask.config?.let {
                            mask.copy(it, false)
                        } ?: mask
                        segmentationFrameCounter = 0
                        lastSegmentationTime = System.currentTimeMillis()
                    }
                } else {
                    Log.d(TAG, "Using cached floor mask")
                    cachedFloorMask
                }
                val segmentationTime = System.currentTimeMillis() - segmentationStartTime
                Log.d(TAG, "Step 3 complete: ${segmentationTime}ms")

                if (!isActive) {
                    Log.d(TAG, "Coroutine cancelled after segmentation")
                    return@launch
                }

                // Find target object
                Log.d(TAG, "Step 4: Finding target object...")
                val target = detectionResults.find {
                    it.label.equals(Config.TARGET_OBJECT_LABEL, ignoreCase = true)
                }
                val targetPosition = target?.let {
                    PointF(it.boundingBox.centerX(), it.boundingBox.centerY())
                }
                Log.d(TAG, "Step 4 complete: target ${if (target != null) "found" else "not found"}")

                // Plan navigation
                Log.d(TAG, "Step 5: Planning navigation...")
                val planningStartTime = System.currentTimeMillis()
                val navOutput = if (floorMask != null) {
                    planner.getNavigationCommand(
                        floorMask,
                        targetPosition,
                        rgbBitmap.width,
                        rgbBitmap.height
                    )
                } else {
                    NavigationOutput("Floor detection unavailable")
                }
                val planningTime = System.currentTimeMillis() - planningStartTime
                Log.d(TAG, "Step 5 complete: ${planningTime}ms")

                val totalTime = System.currentTimeMillis() - frameStartTime

                // Track performance
                trackFrameTime(totalTime)

                // Check for too many consecutive errors
                val errorStatus = if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    " (⚠️ ${consecutiveErrors} errors)"
                } else {
                    ""
                }

                Log.d(TAG, "=== FRAME COMPLETE: detection=${detectionTime}ms, " +
                        "segmentation=${segmentationTime}ms${if (!shouldSegment) " (cached)" else ""}${if (isEmulator) " (mock)" else ""}, " +
                        "planning=${planningTime}ms, total=${totalTime}ms, " +
                        "detections=${detectionResults.size}, avgFPS=${getAverageFPS()}$errorStatus ===")

                // Update UI state
                Log.d(TAG, "Step 6: Updating UI state...")
                _uiState.update { currentState ->
                    val instructionTime = System.currentTimeMillis()
                    val newCommand = if (instructionTime - lastInstructionTime > Config.INSTRUCTION_LOCK_DURATION_MS) {
                        lastInstructionTime = instructionTime
                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            "System experiencing issues..."
                        } else {
                            navOutput.command
                        }
                    } else {
                        currentState.command
                    }

                    val systemState = when {
                        consecutiveErrors >= MAX_CONSECUTIVE_ERRORS -> "ERROR"
                        target != null -> "GUIDING"
                        else -> "SEARCHING"
                    }

                    currentState.copy(
                        cameraBitmap = rgbBitmap.asImageBitmap(),
                        detections = detectionResults,
                        floorMaskBitmap = floorMask?.asImageBitmap(),
                        navigationPath = navOutput.path,
                        command = newCommand,
                        systemState = systemState
                    )
                }
                Log.d(TAG, "Step 6 complete: UI updated")

            } catch (e: Exception) {
                Log.e(TAG, "ERROR in frame processing", e)
                e.printStackTrace()
                consecutiveErrors++

                // Update UI to show error state
                _uiState.update { currentState ->
                    currentState.copy(
                        command = "Processing error: ${e.message?.take(50) ?: "Unknown error"}",
                        systemState = "ERROR"
                    )
                }
            } finally {
                // CRITICAL: Always release the processing lock
                Log.d(TAG, "=== RELEASING PROCESSING LOCK ===")
                isProcessing.set(false)
            }
        }
    }

    /**
     * Create a mock floor mask for emulator testing or fallback
     */
    private fun createMockFloorMask(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            // Fill bottom 60% of image with semi-transparent green to simulate floor
            val floorHeight = (height * 0.6f).toInt()
            val paint = android.graphics.Paint().apply {
                color = Color.argb(128, 0, 255, 0)
            }
            canvas.drawRect(
                0f,
                (height - floorHeight).toFloat(),
                width.toFloat(),
                height.toFloat(),
                paint
            )
        }
    }

    private fun trackFrameTime(timeMs: Long) {
        synchronized(recentFrameTimes) {
            recentFrameTimes.add(timeMs)
            if (recentFrameTimes.size > MAX_TRACKED_FRAMES) {
                recentFrameTimes.removeAt(0)
            }
        }
    }

    private fun getAverageFPS(): String {
        synchronized(recentFrameTimes) {
            if (recentFrameTimes.isEmpty()) return "N/A"
            val avgTime = recentFrameTimes.average()
            val fps = 1000.0 / avgTime
            return String.format("%.1f", fps)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "Cleaning up models...")

        // Stop processing
        isProcessing.set(true) // Prevent new processing

        try {
            objectDetector?.close()
            floorSegmenter?.close()
            cachedFloorMask?.recycle()

            objectDetector = null
            floorSegmenter = null
            pathPlanner = null
            cachedFloorMask = null

            Log.d(TAG, "Models cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}