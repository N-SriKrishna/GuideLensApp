package com.example.guidelensapp.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.guidelensapp.viewmodel.NavigationUiState

@Composable
fun OverlayCanvas(uiState: NavigationUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Floor mask overlay
        uiState.floorMaskOverlay?.let {
            Image(
                bitmap = it,
                contentDescription = "Floor Mask",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                alpha = 0.4f
            )
        }

        // Detections and navigation path
        Canvas(modifier = Modifier.fillMaxSize()) {
            val originalWidth = uiState.cameraImage?.width ?: return@Canvas
            val originalHeight = uiState.cameraImage?.height ?: return@Canvas
            val scaleX = size.width / originalWidth
            val scaleY = size.height / originalHeight

            // Draw navigation path
            uiState.path?.let { path ->
                if (path.size > 1) {
                    val points = path.map { Offset(it.x * scaleX, it.y * scaleY) }
                    drawPoints(
                        points = points,
                        pointMode = PointMode.Polygon,
                        color = Color.Yellow,
                        strokeWidth = 5.dp.toPx()
                    )

                    // Draw path points as circles
                    points.forEach { point ->
                        drawCircle(
                            color = Color.Yellow,
                            radius = 4.dp.toPx(),
                            center = point
                        )
                    }
                }
            }

            // Draw detection bounding boxes
            uiState.detectedObjects.forEach { detection ->
                val isTarget = detection.label.equals(uiState.targetObject, ignoreCase = true)
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
                    val barHeight = 4.dp.toPx()
                    drawRect(
                        color = color.copy(alpha = 0.8f),
                        topLeft = Offset(boxLeft, boxTop - barHeight - 2.dp.toPx()),
                        size = Size(boxWidth * confidence, barHeight)
                    )
                }
            }
        }

        // Display current target object at the top
        Text(
            text = "Looking for: ${uiState.targetObject.replaceFirstChar { it.uppercase() }}",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        // Detection count indicator (top-left)
        if (uiState.detectedObjects.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "Objects: ${uiState.detectedObjects.size}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Command overlay (bottom)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth(0.9f),
            color = Color(0xFF2196F3).copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = uiState.navigationCommand,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )
            }
        }
    }
}
