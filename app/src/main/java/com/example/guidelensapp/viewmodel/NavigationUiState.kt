// app/src/main/java/com/example/guidelensapp/viewmodel/NavigationUiState.kt
package com.example.guidelensapp.viewmodel

import android.graphics.PointF
import androidx.compose.ui.graphics.ImageBitmap
import com.example.guidelensapp.ml.DetectionResult

data class NavigationUiState(
    val cameraImage: ImageBitmap? = null,
    val floorMaskOverlay: ImageBitmap? = null,
    val detectedObjects: List<DetectionResult> = emptyList(),
    val targetObject: String = "chair", // Default target
    val targetPosition: PointF? = null,
    val path: List<PointF>? = null,
    val navigationCommand: String = "Initializing...",
    val isNavigating: Boolean = false,
    val showObjectSelector: Boolean = true // Show selector at start
)
