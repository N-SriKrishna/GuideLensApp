package com.example.guidelensapp.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guidelensapp.Config
import com.example.guidelensapp.ml.ObjectDetector
import com.example.guidelensapp.ml.FloorSegmenter
import com.example.guidelensapp.navigation.NavigationOutput
import com.example.guidelensapp.navigation.PathPlanner
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

    private var objectDetector: ObjectDetector? = null
    private var floorSegmenter: FloorSegmenter? = null
    private var applicationContext: Context? = null

    private val threadManager = ThreadManager.getInstance()

    private val isProcessingFrame = AtomicBoolean(false)
    private val lastProcessedTime = AtomicLong(0L)
    private val isShuttingDown = AtomicBoolean(false)

    private var previousCameraImage: Bitmap? = null
    private var previousFloorMask: Bitmap? = null

    companion object {
        private const val TAG = "NavigationViewModel"
    }

    fun initializeModels(context: Context) {
        if (objectDetector != null) return

        applicationContext = context.applicationContext
        viewModelScope.launch(threadManager.ioDispatcher) {
            try {
                Log.d(TAG, "🔧 Initializing models...")
                _uiState.update { it.copy(navigationCommand = "Loading models...") }

                objectDetector = ObjectDetector(context)
                floorSegmenter = FloorSegmenter(context)

                Log.d(TAG, "✅ Models initialized successfully")
                _uiState.update { it.copy(navigationCommand = "Models loaded - Warming up...") }

                // Wait for floor segmenter to be ready
                var waitTime = 0
                while (floorSegmenter?.isReady() == false && waitTime < 5000) {
                    delay(100)
                    waitTime += 100
                }

                _uiState.update { it.copy(navigationCommand = "Ready - Select target and start navigation") }
                Log.d(TAG, "✅ Models ready for inference")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize models", e)
                _uiState.update { it.copy(navigationCommand = "Initialization failed: ${e.message}") }
            }
        }
    }

    fun processFrame(bitmap: Bitmap) {
        if (isShuttingDown.get()) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime.get() < Config.MIN_FRAME_INTERVAL_MS) {
            return
        }

        if (!isProcessingFrame.compareAndSet(false, true)) {
            return
        }

        viewModelScope.launch(threadManager.mlDispatcher) {
            try {
                val startTime = System.currentTimeMillis()
                val targetObjectName = _uiState.value.targetObject

                // STEP 1: Object Detection
                val detections = try {
                    if (_uiState.value.isNavigating && targetObjectName.isNotEmpty()) {
                        objectDetector?.detectObjects(bitmap, listOf(targetObjectName)) ?: emptyList()
                    } else {
                        objectDetector?.detectObjects(bitmap, Config.NAVIGABLE_OBJECTS) ?: emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Object detection crashed", e)
                    emptyList()
                }

                val targetObject = detections.find {
                    it.label.equals(targetObjectName, ignoreCase = true)
                }

                val targetPos = targetObject?.let {
                    PointF(it.boundingBox.centerX(), it.boundingBox.centerY())
                }

                // STEP 2: Floor Segmentation
                val floorMask = if (_uiState.value.isNavigating) {
                    try {
                        Log.d(TAG, "🟢 Running floor segmentation...")
                        floorSegmenter?.segmentFloor(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Floor segmentation crashed", e)
                        null
                    }
                } else {
                    null
                }

                // STEP 3: Path Planning - CRITICAL FIX!
                val navigationOutput = when {
                    _uiState.value.isNavigating && floorMask != null && targetPos != null -> {
                        try {
                            Log.d(TAG, "🧭 Running path planning to $targetObjectName at (${ targetPos.x},${targetPos.y})...")
                            val navResult = PathPlanner.getNavigationCommand(
                                floorMask = floorMask,
                                targetPosition = targetPos,
                                imageWidth = bitmap.width,
                                imageHeight = bitmap.height
                            )
                            Log.d(TAG, "📍 Path planning result: ${navResult.command}, path points: ${navResult.path?.size ?: 0}")
                            navResult
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Path planning crashed", e)
                            NavigationOutput("Navigation error", null)
                        }
                    }
                    _uiState.value.isNavigating && floorMask == null && targetPos != null -> {
                        Log.d(TAG, "⏳ Waiting for floor mask...")
                        NavigationOutput("Analyzing floor...", null)
                    }
                    _uiState.value.isNavigating -> {
                        Log.d(TAG, "🔍 Searching for $targetObjectName...")
                        NavigationOutput("Searching for $targetObjectName...", null)
                    }
                    else -> null
                }

                val totalTime = System.currentTimeMillis() - startTime
                val navCmd = navigationOutput?.command ?: "none"
                Log.d(TAG, "✅ Frame processed in ${totalTime}ms - Target: ${targetObject?.label ?: "none"} ${targetObject?.confidence?.times(100)?.toInt() ?: 0}% - Nav: $navCmd")

                // STEP 4: Update UI State
                _uiState.update { current ->
                    current.copy(
                        cameraImage = bitmap.asImageBitmap(),
                        detectedObjects = if (current.isNavigating) {
                            targetObject?.let { listOf(it) } ?: emptyList()
                        } else {
                            detections
                        },
                        targetPosition = targetPos,
                        floorMaskOverlay = floorMask?.asImageBitmap(),
                        navigationCommand = navigationOutput?.command ?: current.navigationCommand,
                        path = navigationOutput?.path,
                        pathPoints = navigationOutput?.path
                    )
                }

                lastProcessedTime.set(currentTime)

                previousCameraImage?.recycle()
                previousFloorMask?.recycle()
                previousCameraImage = bitmap
                previousFloorMask = floorMask

            } finally {
                isProcessingFrame.set(false)
            }
        }
    }





    fun setTargetObject(objectLabel: String) {
        val normalizedLabel = objectLabel.lowercase().trim()
        _uiState.update {
            it.copy(
                targetObject = normalizedLabel,
                targetPosition = null,
                path = null,
                pathPoints = null,
                floorMaskOverlay = null  // Also reset floor mask
            )
        }
        Log.d(TAG, "🎯 Target object changed to: $normalizedLabel")
    }


    fun toggleObjectSelector() {
        _uiState.update { it.copy(showObjectSelector = !it.showObjectSelector) }
    }

    fun startNavigation() {
        _uiState.update { it.copy(isNavigating = true, showObjectSelector = false) }
        Log.d(TAG, "▶️ Navigation STARTED")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "🧹 Cleaning up NavigationViewModel...")
        isShuttingDown.set(true)

        // Wait for processing to complete
        var waitCount = 0
        while (isProcessingFrame.get() && waitCount < 20) {
            Thread.sleep(100)
            waitCount++
        }

        try {
            viewModelScope.cancel()
            Thread.sleep(100)

            previousCameraImage?.recycle()
            previousFloorMask?.recycle()
            previousCameraImage = null
            previousFloorMask = null

            objectDetector?.close()
            objectDetector = null
            floorSegmenter = null
            applicationContext = null

            Log.d(TAG, "✅ NavigationViewModel cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup", e)
        }
    }
}
