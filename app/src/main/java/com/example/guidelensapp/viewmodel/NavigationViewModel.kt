package com.example.guidelensapp.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guidelensapp.ml.ObjectDetector
import com.example.guidelensapp.ml.FloorSegmenter
import com.example.guidelensapp.navigation.NavigationOutput
import com.example.guidelensapp.navigation.PathPlanner
import com.example.guidelensapp.utils.MemoryManager
import com.example.guidelensapp.utils.ThreadManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

class NavigationViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState = _uiState.asStateFlow()

    private var objectDetector: ObjectDetector? = null
    private var floorSegmenter: FloorSegmenter? = null
    private var pathPlanner: PathPlanner? = null

    private var applicationContext: Context? = null

    private val threadManager = ThreadManager.getInstance()
    private val memoryManager = MemoryManager.getInstance()

    private val isEmulator = android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.FINGERPRINT.contains("unknown") ||
            android.os.Build.MODEL.contains("Emulator")

    private val isProcessingFrame = AtomicBoolean(false)

    companion object {
        private const val TAG = "NavigationViewModel"
    }

    fun initializeModels(context: Context) {
        if (objectDetector != null) return

        applicationContext = context.applicationContext

        viewModelScope.launch(threadManager.ioDispatcher) {
            try {
                Log.d(TAG, "Initializing models... (isEmulator=$isEmulator)")
                _uiState.update { it.copy(navigationCommand = "Loading models...") }

                objectDetector = ObjectDetector(context)
                floorSegmenter = FloorSegmenter(context)
                pathPlanner = PathPlanner()

                Log.d(TAG, "Models initialized successfully")
                _uiState.update { it.copy(navigationCommand = "Models loaded - Warming up...") }

                var waitTime = 0
                while (floorSegmenter?.isReady() == false && waitTime < 5000) {
                    delay(100)
                    waitTime += 100
                }

                _uiState.update { it.copy(navigationCommand = "Ready") }
                Log.d(TAG, "Models ready for inference")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize models", e)
                _uiState.update { it.copy(navigationCommand = "Initialization failed: ${e.message}") }
            }
        }
    }

    fun processFrame(bitmap: Bitmap) {
        if (!isProcessingFrame.compareAndSet(false, true)) {
            return
        }

        viewModelScope.launch(threadManager.mlDispatcher) {
            try {
                val targetObject = _uiState.value.targetObject

                // OPTIMIZED: Only detect the selected target object
                val detectionResults = objectDetector?.detectTargetObject(bitmap, targetObject) ?: emptyList()

                // Find the best detection (highest confidence)
                val targetDetection = detectionResults.maxByOrNull { it.confidence }

                val targetPos = targetDetection?.let {
                    PointF(
                        it.boundingBox.centerX(),
                        it.boundingBox.centerY()
                    )
                }

                // Floor Segmentation (only if target is found)
                val floorMask = if (targetPos != null) {
                    floorSegmenter?.segmentFloor(bitmap)
                } else {
                    null
                }

                // Path Planning
                val navOutput = if (floorMask != null && targetPos != null) {
                    pathPlanner?.getNavigationCommand(
                        floorMask,
                        targetPos,
                        bitmap.width,
                        bitmap.height
                    ) ?: NavigationOutput("Path planning error")
                } else {
                    if (detectionResults.isEmpty()) {
                        NavigationOutput("Searching for $targetObject...")
                    } else {
                        NavigationOutput("$targetObject detected, calculating path...")
                    }
                }

                // Update UI - only show target object detections
                _uiState.update { currentState ->
                    currentState.copy(
                        cameraImage = bitmap.asImageBitmap(),
                        floorMaskOverlay = floorMask?.asImageBitmap(),
                        detectedObjects = detectionResults, // Only contains target object
                        targetPosition = targetPos,
                        path = navOutput.path,
                        navigationCommand = navOutput.command
                    )
                }

                // Log target detection status
                if (targetDetection != null) {
                    Log.d(TAG, "✓ $targetObject detected: ${(targetDetection.confidence * 100).toInt()}%")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
            } finally {
                isProcessingFrame.set(false)
            }
        }
    }

    fun setTargetObject(objectLabel: String) {
        _uiState.update { it.copy(targetObject = objectLabel.lowercase().trim()) }
        Log.d(TAG, "Target object changed to: ${objectLabel.lowercase().trim()}")
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
        floorSegmenter?.forceCleanup()
        memoryManager.forceGarbageCollection()
        _uiState.update { it.copy(navigationCommand = "Reducing quality due to memory pressure") }
    }

    fun setQuality(quality: Float) {
        floorSegmenter?.setQuality(quality)
        Log.d(TAG, "Quality manually set to ${(quality * 100).toInt()}%")
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "Cleaning up NavigationViewModel...")
        isProcessingFrame.set(true)

        try {
            viewModelScope.cancel()
            objectDetector?.close()
            floorSegmenter?.close()

            objectDetector = null
            floorSegmenter = null
            pathPlanner = null
            applicationContext = null

            Log.d(TAG, "NavigationViewModel cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
