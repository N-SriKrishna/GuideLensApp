// app/src/main/java/com/example/guidelensapp/viewmodel/NavigationUiState.kt
package com.example.guidelensapp.viewmodel

import android.graphics.PointF
import androidx.compose.ui.graphics.ImageBitmap
import com.example.guidelensapp.ml.DetectionResult
import com.example.guidelensapp.sensors.SpatialTracker
import com.example.guidelensapp.utils.PerformanceMetrics

/**
 * Complete UI state for GuideLens navigation
 */
data class NavigationUiState(
    // Camera and visual state
    val cameraImage: ImageBitmap? = null,
    val floorMaskOverlay: ImageBitmap? = null,

    // Object detection state
    val detectedObjects: List<DetectionResult> = emptyList(),
    val targetObject: String = "chair", // Default target
    val targetPosition: PointF? = null,

    // Navigation state
    val path: List<PointF>? = null,
    val navigationCommand: String = "Initializing...",
    val isNavigating: Boolean = false,
    val pathPoints: List<PointF>? = null,

    // TTS and UI state
    val isSpeaking: Boolean = false,
    val lastSpokenCommand: String? = null,

    // Spatial tracking state
    val spatialObjects: List<SpatialTracker.SpatialObject> = emptyList(),
    val currentOrientation: Triple<Float, Float, Float> = Triple(0f, 0f, 0f), // azimuth, pitch, roll
    val offScreenGuidance: String? = null, // Guidance for off-screen target

    // UI control state
    val showObjectSelector: Boolean = true, // Show selector at start

    // Mode selection state
    val appMode: AppMode = AppMode.SIMPLE_NAVIGATION,
    val showModeSelector: Boolean = true,

    // Accessibility settings
    val hapticFeedbackEnabled: Boolean = true,
    val audioDescriptionsEnabled: Boolean = true,
    val continuousAudioGuidance: Boolean = false,

    // Voice commands state
    val voiceCommandsEnabled: Boolean = false,
    val isListeningForCommands: Boolean = false,
    val lastVoiceCommand: String? = null,

    // Performance monitoring state (for debug mode)
    val performanceMetrics: PerformanceMetrics = PerformanceMetrics(),
    val showPerformanceOverlay: Boolean = false
)


enum class AppMode {
    SIMPLE_NAVIGATION,
    DEBUG_MODE
}

