// app/src/main/java/com/example/guidelensapp/viewmodel/NavigationUiState.kt
package com.example.guidelensapp.viewmodel

import android.graphics.PointF
import androidx.compose.ui.graphics.ImageBitmap
import com.example.guidelensapp.ml.DetectionResult
import com.example.guidelensapp.sensors.SpatialTracker

data class NavigationUiState(
    val cameraImage: ImageBitmap? = null,
    val floorMaskOverlay: ImageBitmap? = null,
    val detectedObjects: List<DetectionResult> = emptyList(),
    val targetObject: String = "chair", // Default target
    val targetPosition: PointF? = null,
    val path: List<PointF>? = null,
    val navigationCommand: String = "Initializing...",
    val isNavigating: Boolean = false,
    val showObjectSelector: Boolean = true, // Show selector at start
    val pathPoints: List<PointF>? = null,
    // NEW: TTS and UI state
    val isSpeaking: Boolean = false,
    val lastSpokenCommand: String? = null,
    // NEW: Spatial tracking state
    val spatialObjects: List<SpatialTracker.SpatialObject> = emptyList(),
    val currentOrientation: Triple<Float, Float, Float> = Triple(0f, 0f, 0f), // azimuth, pitch, roll
    val offScreenGuidance: String? = null // Guidance for off-screen target
)
