package com.example.guidelensapp.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.guidelensapp.Config
import com.example.guidelensapp.viewmodel.NavigationUiState

@Composable
fun MainOverlay(uiState: NavigationUiState) {
    // The live preview is handled by CameraView, drawing it here is redundant and inefficient.

    // 1. Display the semi-transparent floor mask on top
    uiState.floorMaskBitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Floor Mask",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            alpha = 0.4f // Make it semi-transparent
        )
    }

    // 2. Draw detections, path, and other visuals on the topmost canvas
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Required for scaling drawings to the screen size
        val (originalWidth, originalHeight) = uiState.cameraBitmap?.width to uiState.cameraBitmap?.height
        if (originalWidth == null || originalHeight == null) return@Canvas
        val scaleX = size.width / originalWidth
        val scaleY = size.height / originalHeight

        // Draw navigation path
        uiState.navigationPath?.let { path ->
            if (path.size > 1) {
                val points = path.map { Offset(it.x * scaleX, it.y * scaleY) } // Scale path
                drawPoints(
                    points = points,
                    pointMode = PointMode.Polygon,
                    color = Color.Yellow,
                    strokeWidth = 5.dp.toPx()
                )
            }
        }

        // Draw detection bounding boxes
        uiState.detections.forEach { detection ->
            val color = if (detection.label.equals(Config.TARGET_OBJECT_LABEL, true)) Color.Cyan else Color.Red
            drawRect(
                color = color,
                // Scale bounding box coordinates
                topLeft = Offset(detection.boundingBox.left * scaleX, detection.boundingBox.top * scaleY),
                size = Size(detection.boundingBox.width() * scaleX, detection.boundingBox.height() * scaleY),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
