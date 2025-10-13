package com.example.guidelensapp.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.Config
import com.example.guidelensapp.viewmodel.NavigationUiState

@Composable
fun Overlays(uiState: NavigationUiState) {
    // Floor mask overlay
    uiState.floorMaskBitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Floor Mask",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            alpha = 0.4f // Semi-transparent
        )
    }

    // Detections and navigation path
    Canvas(modifier = Modifier.fillMaxSize()) {
        val (originalWidth, originalHeight) = uiState.cameraBitmap?.width to uiState.cameraBitmap?.height
        if (originalWidth == null || originalHeight == null) return@Canvas
        val scaleX = size.width / originalWidth
        val scaleY = size.height / originalHeight

        // Draw navigation path
        uiState.navigationPath?.let { path ->
            if (path.size > 1) {
                val points = path.map { Offset(it.x * scaleX, it.y * scaleY) }
                drawPoints(
                    points = points,
                    pointMode = PointMode.Polygon,
                    color = Color.Yellow,
                    strokeWidth = 5.dp.toPx()
                )

                // Draw path points as circles for better visibility
                points.forEach { point ->
                    drawCircle(
                        color = Color.Yellow,
                        radius = 4.dp.toPx(),
                        center = point
                    )
                }
            }
        }

        // Draw detection bounding boxes with labels
        uiState.detections.forEach { detection ->
            val isTarget = detection.label.equals(Config.TARGET_OBJECT_LABEL, true)
            val color = if (isTarget) Color.Cyan else Color.Red
            val boxLeft = detection.boundingBox.left * scaleX
            val boxTop = detection.boundingBox.top * scaleY
            val boxWidth = detection.boundingBox.width() * scaleX
            val boxHeight = detection.boundingBox.height() * scaleY

            // Draw bounding box
            drawRect(
                color = color,
                topLeft = Offset(boxLeft, boxTop),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = if (isTarget) 3.dp.toPx() else 2.dp.toPx())
            )

            // Draw confidence indicator
            val confidence = detection.confidence
            if (confidence > 0) {
                // Draw confidence bar at top of box
                val barHeight = 4.dp.toPx()
                drawRect(
                    color = color.copy(alpha = 0.8f),
                    topLeft = Offset(boxLeft, boxTop - barHeight - 2.dp.toPx()),
                    size = Size(boxWidth * confidence, barHeight)
                )
            }
        }
    }

    // UI overlays
    Box(modifier = Modifier.fillMaxSize()) {
        // Performance info overlay (top-right)
        if (uiState.performanceInfo.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = uiState.performanceInfo,
                    modifier = Modifier.padding(8.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 14.sp
                )
            }
        }

        // Detection count indicator (top-left)
        if (uiState.detections.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "Objects: ${uiState.detections.size}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Command and state overlay (bottom)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth(0.9f),
            color = when (uiState.systemState) {
                "ERROR" -> Color.Red.copy(alpha = 0.85f)
                "DEGRADED" -> Color(0xFFFF9800).copy(alpha = 0.85f) // Orange
                "GUIDING" -> Color(0xFF4CAF50).copy(alpha = 0.85f) // Green
                "SEARCHING" -> Color(0xFF2196F3).copy(alpha = 0.85f) // Blue
                "WARMING_UP", "LOADING" -> Color(0xFF9C27B0).copy(alpha = 0.85f) // Purple
                else -> Color.Black.copy(alpha = 0.85f)
            },
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Main command text
                Text(
                    text = uiState.command,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )

                // System state indicator
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (uiState.systemState) {
                        "ERROR" -> "⚠️ Error"
                        "DEGRADED" -> "⚡ Performance Reduced"
                        "GUIDING" -> "🎯 Target Found"
                        "SEARCHING" -> "🔍 Searching"
                        "WARMING_UP" -> "🔥 Warming Up"
                        "LOADING" -> "⏳ Loading"
                        else -> uiState.systemState
                    },
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Optional: Debug mode indicator
        if (uiState.performanceInfo.contains("Health")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(8.dp),
                color = Color.Red.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "DEBUG",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}