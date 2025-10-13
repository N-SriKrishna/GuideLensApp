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
import java.util.concurrent.atomic.AtomicLong

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
    private var targetPosition: PointF? = null

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

    // Target object tracking
    private val _targetObject = MutableStateFlow("chair")
    val targetObject = _targetObject.asStateFlow()

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
                _uiState.update { it.copy(navigationCommand = "Loading models...") }

                // Initialize models
                objectDetector = ObjectDetector(context)
                floorSegmenter = FloorSegmenter(context)
                pathPlanner = PathPlanner()

                Log.d(TAG, "Models initialized successfully")
                _uiState.update { it.copy(navigationCommand = "Models loaded - Warming up...") }

                // Wait for floor segmenter to warm up
                var waitTime = 0
                while (floorSegmenter?.isReady() == false && waitTime < 5000) {
                    delay(100)
                    waitTime += 100
                }

                modelsWarmedUp = true
                _uiState.update { it.copy(navigationCommand = "Ready") }
                Log.d(TAG, "Models ready for inference")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize models", e)
                _uiState.update { it.copy(navigationCommand = "Initialization failed: ${e.message}") }
            }
        }
    }

    fun processFrame(bitmap: Bitmap) {
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

                // Find target using dynamic object
                targetPosition = findTarget(detectionResults)

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

                // Update UI state
                _uiState.update { currentState ->
                    val instructionTime = System.currentTimeMillis()
                    val newCommand = if (instructionTime - lastInstructionTime > Config.INSTRUCTION_LOCK_DURATION_MS) {
                        lastInstructionTime = instructionTime
                        when {
                            consecutiveErrors >= MAX_CONSECUTIVE_ERRORS -> "System experiencing issues..."
                            else -> navOutput.command
                        }
                    } else {
                        currentState.navigationCommand
                    }

                    currentState.copy(
                        cameraImage = bitmap.asImageBitmap(),
                        detectedObjects = detectionResults,
                        floorMaskOverlay = floorMask?.asImageBitmap(),
                        path = navOutput.path,
                        navigationCommand = newCommand,
                        targetPosition = targetPosition
                    )
                }

                // Log performance periodically
                if (frameCount % 30 == 0L) {
                    Log.d(TAG, "Frame processing took ${totalTime}ms (detection: ${detectionTime}ms)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in frame processing", e)
                consecutiveErrors++
                _uiState.update { currentState ->
                    currentState.copy(navigationCommand = "Processing error")
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    private fun findTarget(detections: List<DetectionResult>): PointF? {
        val currentTarget = _targetObject.value
        val targetDetection = detections
            .filter { it.label.equals(currentTarget, ignoreCase = true) }
            .maxByOrNull { it.confidence }

        return targetDetection?.let {
            PointF(
                it.boundingBox.centerX(),
                it.boundingBox.centerY()
            )
        }
    }

    fun setTargetObject(objectLabel: String) {
        _targetObject.value = objectLabel.lowercase().trim()
        _uiState.update { it.copy(targetObject = objectLabel.lowercase().trim()) }
        Log.d(TAG, "Target object changed to: ${_targetObject.value}")

        // Reset target position when changing objects
        targetPosition = null
        _uiState.update { it.copy(targetPosition = null, path = null) }
    }

    fun toggleObjectSelector() {
        _uiState.update { it.copy(showObjectSelector = !it.showObjectSelector) }
    }

    fun startNavigation() {
        _uiState.update { it.copy(isNavigating = true, showObjectSelector = false) }
    }

    fun stopNavigation() {
        _uiState.update { it.copy(isNavigating = false, showObjectSelector = true) }
    }

    fun onLowMemory() {
        Log.w(TAG, "Low memory warning received")
        // Force cleanup
        floorSegmenter?.forceCleanup()

        // Clear cached floor mask
        cachedFloorMask?.let {
            memoryManager.recycleBitmap(it)
            cachedFloorMask = null
        }

        // Force GC
        System.gc()

        _uiState.update { it.copy(navigationCommand = "Reducing quality due to memory pressure") }
    }

    fun setQuality(quality: Float) {
        floorSegmenter?.setQuality(quality)
        Log.d(TAG, "Quality manually set to ${(quality * 100).toInt()}%")
    }

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
