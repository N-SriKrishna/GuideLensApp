package com.example.guidelensapp.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.guidelensapp.Config
import com.example.guidelensapp.accessibility.TextToSpeechManager
import com.example.guidelensapp.ml.DetectionResult
import com.example.guidelensapp.ml.ObjectDetector
import com.example.guidelensapp.ml.FloorSegmenter
import com.example.guidelensapp.navigation.NavigationOutput
import com.example.guidelensapp.navigation.PathPlanner
import com.example.guidelensapp.sensors.SpatialTracker
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

    private var ttsManager: TextToSpeechManager? = null
    private var lastTTSAnnouncementTime = AtomicLong(0L)
    private var lastAnnouncedCommand: String? = null
    private var targetDetectedCount = 0
    private var targetLostCount = 0

    private var spatialTracker: SpatialTracker? = null
    private var lastSpatialUpdate = AtomicLong(0L)
    private val SPATIAL_UPDATE_INTERVAL = 500L // Update every 500ms

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

                ttsManager = TextToSpeechManager(context)
                ttsManager?.speechRate = Config.TTS_SPEECH_RATE
                ttsManager?.pitch = Config.TTS_PITCH

                // NEW: Initialize spatial tracker
                spatialTracker = SpatialTracker(context)
                spatialTracker?.startTracking()

                // Start orientation update loop
                startOrientationUpdates()

                Log.d(TAG, "✅ Models initialized with spatial tracking")

                Log.d(TAG, "NavigationViewModel initialized with TTS")

                Log.d(TAG, "✅ Models initialized successfully")
                _uiState.update { it.copy(navigationCommand = "Models loaded - Warming up...") }

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

    /**
     * Continuously update device orientation in UI state
     */
    private fun startOrientationUpdates() {
        viewModelScope.launch {
            while (true) {
                delay(100) // Update at 10Hz
                spatialTracker?.let { tracker ->
                    val orientation = tracker.getCurrentOrientation()
                    _uiState.update { it.copy(currentOrientation = orientation) }
                }
            }
        }
    }

    /**
     * Update spatial tracker with detections
     */
    private fun updateSpatialTracking(
        detections: List<DetectionResult>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSpatialUpdate.get() < SPATIAL_UPDATE_INTERVAL) return

        spatialTracker?.let { tracker ->
            tracker.updateWithDetections(detections, imageWidth, imageHeight)

            val allTrackedObjects = tracker.getAllTrackedObjects()
            _uiState.update { it.copy(spatialObjects = allTrackedObjects) }

            // If navigating, provide off-screen guidance
            if (_uiState.value.isNavigating) {
                val guidance = tracker.getDirectionToObject(_uiState.value.targetObject)
                if (guidance != null && !guidance.isVisible) {
                    val offScreenMsg = "${_uiState.value.targetObject} is ${guidance.direction}"
                    _uiState.update { it.copy(offScreenGuidance = offScreenMsg) }
                    Log.d(TAG, "🧭 Off-screen guidance: $offScreenMsg")
                } else {
                    _uiState.update { it.copy(offScreenGuidance = null) }
                }
            }

            lastSpatialUpdate.set(currentTime)
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

                // Make a COPY of the bitmap to prevent recycling issues
                val bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

                // Object Detection
                val detections = try {
                    if (_uiState.value.isNavigating && targetObjectName.isNotEmpty()) {
                        objectDetector?.detectObjects(bitmapCopy, listOf(targetObjectName))
                            ?: emptyList()
                    } else {
                        objectDetector?.detectObjects(bitmapCopy, Config.NAVIGABLE_OBJECTS)
                            ?: emptyList()
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
                updateSpatialTracking(detections, bitmap.width, bitmap.height)

                // Floor Segmentation
                val floorMask = if (_uiState.value.isNavigating) {
                    try {
                        Log.d(TAG, "🟢 Running floor segmentation...")
                        floorSegmenter?.segmentFloor(bitmapCopy)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Floor segmentation crashed", e)
                        null
                    }
                } else {
                    null
                }

                // Path Planning
                val navigationOutput = when {
                    _uiState.value.isNavigating && floorMask != null && targetPos != null -> {
                        try {
                            Log.d(TAG, "🧭 Running sensor-integrated path planning...")

                            // Get spatial guidance for off-screen targets
                            val spatialGuidance = if (targetObject == null) {
                                spatialTracker?.getDirectionToObject(targetObjectName)
                            } else {
                                null
                            }

                            // Get current device orientation
                            val (azimuth, _, _) = spatialTracker?.getCurrentOrientation() ?: Triple(0f, 0f, 0f)

                            val navResult = PathPlanner.getNavigationCommand(
                                floorMask = floorMask,
                                targetPosition = targetPos,
                                imageWidth = bitmapCopy.width,
                                imageHeight = bitmapCopy.height,
                                spatialGuidance = spatialGuidance,
                                currentAzimuth = azimuth
                            )

                            Log.d(TAG, "📍 Nav result: ${navResult.command}, centered: ${navResult.targetCentered}")
                            announceNavigationCommand(navResult.command)
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

                        // Use spatial guidance even when target not visible
                        val spatialGuidance = spatialTracker?.getDirectionToObject(targetObjectName)
                        if (spatialGuidance != null) {
                            val (azimuth, _, _) = spatialTracker?.getCurrentOrientation() ?: Triple(0f, 0f, 0f)
                            PathPlanner.getNavigationCommand(
                                floorMask = floorMask ?: bitmapCopy,
                                targetPosition = null,
                                imageWidth = bitmapCopy.width,
                                imageHeight = bitmapCopy.height,
                                spatialGuidance = spatialGuidance,
                                currentAzimuth = azimuth
                            )
                        } else {
                            NavigationOutput("Searching for $targetObjectName...", null)
                        }
                    }
                    else -> null
                }

                val totalTime = System.currentTimeMillis() - startTime
                val navCmd = navigationOutput?.command ?: "none"
                Log.d(
                    TAG,
                    "✅ Frame processed in ${totalTime}ms - Target: ${targetObject?.label ?: "none"} ${
                        targetObject?.confidence?.times(100)?.toInt() ?: 0
                    }% - Nav: $navCmd"
                )

                // Store old bitmaps to recycle AFTER UI update completes
                val oldCameraImage = previousCameraImage
                val oldFloorMask = previousFloorMask

                // Update UI State with converted ImageBitmaps
                _uiState.update { current ->
                    current.copy(
                        cameraImage = bitmapCopy.asImageBitmap(),
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

                // Store new bitmaps for next iteration
                previousCameraImage = bitmapCopy
                previousFloorMask = floorMask

                // Delay recycling old bitmaps to ensure Compose has finished rendering
                delay(100) // Give Compose time to render

                // Now safe to recycle old bitmaps
                try {
                    if (oldCameraImage?.isRecycled == false) {
                        oldCameraImage.recycle()
                    }
                    if (oldFloorMask?.isRecycled == false) {
                        oldFloorMask.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error recycling old bitmaps", e)
                }

                lastProcessedTime.set(currentTime)
            } finally {
                isProcessingFrame.set(false)
            }
        }
    }


    // UI Button Functions

    fun setTargetObject(objectLabel: String) {
        val normalizedLabel = objectLabel.lowercase().trim()
        _uiState.update {
            it.copy(
                targetObject = normalizedLabel,
                targetPosition = null,
                path = null,
                pathPoints = null,
                floorMaskOverlay = null
            )
        }
        Log.d(TAG, "🎯 Target object changed to: $normalizedLabel")
    }

    fun toggleObjectSelector() {
        _uiState.update { it.copy(showObjectSelector = !it.showObjectSelector) }
        Log.d(TAG, "⚙️ Object selector toggled: ${_uiState.value.showObjectSelector}")
    }

    fun startNavigation() {
        val targetObject = _uiState.value.targetObject
        if (targetObject.isEmpty()) {
            Log.w(TAG, "⚠️ Cannot start navigation: no target object selected")
            ttsManager?.speak("Please select a target object first")
            return
        }

        _uiState.update {
            it.copy(
                isNavigating = true,
                showObjectSelector = false
            )
        }
        targetDetectedCount = 0
        targetLostCount = 0
        lastAnnouncedCommand = null

        ttsManager?.speakImmediate("Navigating to $targetObject")
        Log.d(TAG, "🚀 Navigation started for: $targetObject")
    }

    fun stopNavigation() {
        viewModelScope.launch {
            // Clear spatial memory
            spatialTracker?.clearMemory()

            // Reset all navigation state
            _uiState.update {
                it.copy(
                    isNavigating = false,
                    navigationCommand = "Ready",
                    path = null,
                    pathPoints = null,
                    targetPosition = null,
                    offScreenGuidance = null,
                    spatialObjects = emptyList(), // Clear UI spatial objects
                    floorMaskOverlay = null,
                    detectedObjects = emptyList() // Clear current detections
                )
            }

            // Reset tracking counters
            lastAnnouncedCommand = null
            targetDetectedCount = 0
            targetLostCount = 0

            // Announce stop
            ttsManager?.speakImmediate("Navigation stopped")
            Log.d(TAG, "🛑 Navigation stopped and memory cleared")
        }
    }

    fun describeScene() {
        viewModelScope.launch {
            val detections = _uiState.value.detectedObjects
            if (detections.isEmpty()) {
                ttsManager?.speak("No objects detected in view")
                _uiState.update { it.copy(isSpeaking = true) }
                return@launch
            }

            val objectCounts = detections.groupingBy { it.label }.eachCount()
            val description = buildString {
                append("I can see ")
                objectCounts.entries.forEachIndexed { index, (obj, count) ->
                    if (index > 0 && index == objectCounts.size - 1) {
                        append(" and ")
                    } else if (index > 0) {
                        append(", ")
                    }
                    append("$count $obj")
                    if (count > 1) append("s")
                }
            }

            _uiState.update { it.copy(isSpeaking = true) }
            ttsManager?.speak(description)
            Log.d(TAG, "🔊 Scene description: $description")

            // Reset speaking state after delay
            delay(3000)
            _uiState.update { it.copy(isSpeaking = false) }
        }
    }

    fun stopSpeaking() {
        ttsManager?.stop()
        _uiState.update { it.copy(isSpeaking = false) }
        Log.d(TAG, "🔇 TTS stopped")
    }

    private fun announceNavigationCommand(command: String?) {
        if (command.isNullOrBlank()) return

        if (command.contains("Searching", ignoreCase = true) ||
            command.contains("Analyzing", ignoreCase = true)
        ) {
            return
        }

        if (command.contains("Arrived", ignoreCase = true) ||
            command.contains("destination", ignoreCase = true)
        ) {
            _uiState.update { it.copy(isSpeaking = true) }
            ttsManager?.speakImmediate(command)
            lastAnnouncedCommand = command
            viewModelScope.launch {
                delay(2000)
                _uiState.update { it.copy(isSpeaking = false) }
            }
            return
        }

        val currentTime = System.currentTimeMillis()
        val timeSinceLastAnnouncement = currentTime - lastTTSAnnouncementTime.get()

        val shouldAnnounce = (command != lastAnnouncedCommand) ||
                (timeSinceLastAnnouncement >= Config.TTS_ANNOUNCEMENT_INTERVAL_MS)

        if (shouldAnnounce) {
            _uiState.update { it.copy(isSpeaking = true) }
            ttsManager?.speak(command)
            lastAnnouncedCommand = command
            lastTTSAnnouncementTime.set(currentTime)
            Log.d(TAG, "🔊 TTS: $command")

            viewModelScope.launch {
                delay(2000)
                _uiState.update { it.copy(isSpeaking = false) }
            }
        }
    }

    fun cleanup() {
        isShuttingDown.set(true)
        viewModelScope.launch {
            delay(200) // Wait for any pending UI operations

            try {
                if (previousCameraImage?.isRecycled == false) {
                    previousCameraImage?.recycle()
                }
                if (previousFloorMask?.isRecycled == false) {
                    previousFloorMask?.recycle()
                }
                previousCameraImage = null
                previousFloorMask = null
                ttsManager?.shutdown()
                Log.d(TAG, "🧹 Cleanup completed with TTS shutdown")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        spatialTracker?.stopTracking()
        spatialTracker = null
        Log.d(TAG, "🧹 Cleaning up NavigationViewModel...")
        isShuttingDown.set(true)

        var waitCount = 0
        while (isProcessingFrame.get() && waitCount < 20) {
            Thread.sleep(100)
            waitCount++
        }

        try {
            viewModelScope.cancel()
            Thread.sleep(200)

            if (previousCameraImage?.isRecycled == false) {
                previousCameraImage?.recycle()
            }
            if (previousFloorMask?.isRecycled == false) {
                previousFloorMask?.recycle()
            }
            previousCameraImage = null
            previousFloorMask = null
            objectDetector?.close()
            objectDetector = null
            floorSegmenter = null
            ttsManager?.shutdown()
            applicationContext = null
            Log.d(TAG, "✅ NavigationViewModel cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup", e)
        }
    }
    // Add these methods to your existing NavigationViewModel.kt

    fun setAppMode(mode: AppMode) {
        _uiState.update { it.copy(appMode = mode, showModeSelector = false) }

        // Announce mode
        val announcement = when (mode) {
            AppMode.SIMPLE_NAVIGATION -> "Simple navigation mode activated. " +
                    "Interface is audio-only. Tap the top half of screen for scene controls, " +
                    "bottom half for navigation controls."
            AppMode.DEBUG_MODE -> "Debug mode activated. Visual overlays enabled."
        }
        speak(announcement, Config.TTSPriority.EMERGENCY)
    }

    fun showModeSelector() {
        _uiState.update { it.copy(showModeSelector = true) }
        speak("Returning to mode selection screen.", Config.TTSPriority.NAVIGATION)
    }

    fun toggleContinuousGuidance() {
        _uiState.update { it.copy(continuousAudioGuidance = !it.continuousAudioGuidance) }
    }

    fun toggleHapticFeedback() {
        _uiState.update { it.copy(hapticFeedbackEnabled = !it.hapticFeedbackEnabled) }
    }
    fun speak(text: String, priority: Int = Config.TTSPriority.NAVIGATION) {
        if (text.isBlank()) {
            Log.w(TAG, "⚠️ Empty text, skipping speech")
            return
        }

        when (priority) {
            Config.TTSPriority.EMERGENCY -> {
                // Interrupt current speech for emergency messages
                ttsManager?.speakImmediate(text)
                _uiState.update { it.copy(isSpeaking = true, lastSpokenCommand = text) }
                Log.d(TAG, "🔊 EMERGENCY TTS: $text")
            }
            else -> {
                // Queue for non-emergency messages
                ttsManager?.speak(text)
                _uiState.update { it.copy(lastSpokenCommand = text) }
                Log.d(TAG, "🔊 TTS: $text (Priority: $priority)")
            }
        }

        // Auto-reset speaking state
        viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(isSpeaking = false) }
        }
    }

    /**
     * Speak text immediately, interrupting current speech (convenience method)
     */
    fun speakImmediate(text: String) {
        speak(text, Config.TTSPriority.EMERGENCY)
    }
    fun togglePerformanceOverlay() {
        _uiState.update {
            it.copy(showPerformanceOverlay = !it.showPerformanceOverlay)
        }
        Log.d(TAG, "📊 Performance overlay toggled: ${_uiState.value.showPerformanceOverlay}")
    }

    /**
     * Toggle voice commands on/off
     */
    fun toggleVoiceCommands() {
        val newValue = !_uiState.value.voiceCommandsEnabled
        _uiState.update {
            it.copy(
                voiceCommandsEnabled = newValue,
                isListeningForCommands = newValue
            )
        }

        if (newValue) {
            startVoiceCommands()
        } else {
            stopVoiceCommands()
        }

        Log.d(TAG, "🎤 Voice commands: $newValue")
    }
    fun startVoiceCommands() {
        Log.d(TAG, "Voice commands - Not yet implemented")
        ttsManager?.speak("Voice commands will be available soon")
    }

    fun stopVoiceCommands() {
        Log.d(TAG, "Voice commands stopped")
    }


}

