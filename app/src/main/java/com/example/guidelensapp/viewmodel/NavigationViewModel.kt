// D:/WorkSpace/androidapps/GuideLensApp/app/src/main/java/com/example/guidelensapp/NavigationViewModel.kt
package com.example.guidelensapp.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
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
import kotlinx.coroutines.launch

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

    fun initializeModels(context: Context) {
        if (objectDetector != null) return
        viewModelScope.launch(Dispatchers.IO) {
            objectDetector = ObjectDetector(context)
            floorSegmenter = FloorSegmenter(context)
            pathPlanner = PathPlanner()
            _uiState.update { it.copy(command = "Ready", systemState = "SEARCHING") }
        }
    }

    fun onFrame(bitmap: Bitmap) {
        val detector = objectDetector ?: return
        val segmenter = floorSegmenter ?: return
        val planner = pathPlanner ?: return

        viewModelScope.launch(Dispatchers.Default) {
            val rgbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val detectionResults = detector.detect(rgbBitmap)
            val floorMask = segmenter.segmentFloor(rgbBitmap)
            val target = detectionResults.find { it.label.equals(Config.TARGET_OBJECT_LABEL, ignoreCase = true) }
            val targetPosition = target?.let { PointF(it.boundingBox.centerX(), it.boundingBox.centerY()) }
            var navOutput = NavigationOutput("Searching for ${Config.TARGET_OBJECT_LABEL}...")
            if (floorMask != null) {
                navOutput = planner.getNavigationCommand(floorMask, targetPosition, rgbBitmap.width, rgbBitmap.height)
            }

            _uiState.update { currentState ->
                val currentTime = System.currentTimeMillis()
                val newCommand = if (currentTime - lastInstructionTime > Config.INSTRUCTION_LOCK_DURATION_MS) {
                    lastInstructionTime = currentTime
                    navOutput.command
                } else {
                    currentState.command
                }
                currentState.copy(
                    cameraBitmap = rgbBitmap.asImageBitmap(), // Still needed for size info in the overlay
                    detections = detectionResults,
                    floorMaskBitmap = floorMask?.asImageBitmap(),
                    navigationPath = navOutput.path,
                    command = newCommand,
                    systemState = if (target != null) "GUIDING" else "SEARCHING"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        objectDetector?.close()
        floorSegmenter?.close()
    }
}
