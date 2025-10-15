package com.example.guidelensapp.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.viewmodel.NavigationUiState
import androidx.core.graphics.toColorInt
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun OverlayCanvas(uiState: NavigationUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Camera Image
        uiState.cameraImage?.let { cameraImage ->
            Image(
                bitmap = cameraImage,
                contentDescription = "Camera feed",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center
            )
        }

        // 2. Floor Mask Overlay
        uiState.floorMaskOverlay?.let { floorMask ->
            Image(
                bitmap = floorMask,
                contentDescription = "Floor segmentation",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                alpha = 0.6f
            )
        }

        // 3. Detections and Path
        NavigationOverlay(uiState = uiState)

        // 4. Navigation command
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.7f)
        ) {
            Text(
                text = uiState.navigationCommand,
                modifier = Modifier.padding(16.dp),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun NavigationOverlay(uiState: NavigationUiState) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Calculate proper scaling for ContentScale.Crop behavior
        val imageWidth = uiState.cameraImage?.width?.toFloat() ?: canvasWidth
        val imageHeight = uiState.cameraImage?.height?.toFloat() ?: canvasHeight

        val scaleX = canvasWidth / imageWidth
        val scaleY = canvasHeight / imageHeight

        // Use max scale for Crop behavior (fills entire space)
        val scale = maxOf(scaleX, scaleY)

        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val offsetX = (canvasWidth - scaledWidth) / 2f
        val offsetY = (canvasHeight - scaledHeight) / 2f

        // Draw detections
        uiState.detectedObjects.forEach { detection ->
            val rect = detection.boundingBox
            val targetColor = Color(0xFF4CAF50)
            val left = rect.left * scale + offsetX
            val top = rect.top * scale + offsetY
            val width = rect.width() * scale
            val height = rect.height() * scale

            // Draw bounding box
            drawRect(
                color = targetColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = 8f)
            )

            // Draw label
            val labelText = "${detection.label} ${(detection.confidence * 100).toInt()}%"
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 48f
                isAntiAlias = true
                isFakeBoldText = true
            }

            drawContext.canvas.nativeCanvas.apply {
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
                drawRect(
                    left,
                    top - textBounds.height() - 20f,
                    left + textBounds.width() + 20f,
                    top,
                    android.graphics.Paint().apply {
                        color = "#4CAF50".toColorInt()
                        style = android.graphics.Paint.Style.FILL
                    }
                )
                drawText(labelText, left + 10f, top - 10f, textPaint)
            }
        }

        // Draw path
        uiState.pathPoints?.let { pathPoints ->
            if (pathPoints.size > 1) {
                for (i in 0 until pathPoints.size - 1) {
                    drawLine(
                        color = Color.Cyan,
                        start = Offset(
                            pathPoints[i].x * scale + offsetX,
                            pathPoints[i].y * scale + offsetY
                        ),
                        end = Offset(
                            pathPoints[i + 1].x * scale + offsetX,
                            pathPoints[i + 1].y * scale + offsetY
                        ),
                        strokeWidth = 6f
                    )
                }
            }
        }

        // Draw target marker
        uiState.targetPosition?.let { target ->
            val targetX = target.x * scale + offsetX
            val targetY = target.y * scale + offsetY

            // Crosshair
            drawLine(
                color = Color.Red,
                start = Offset(targetX - 30f, targetY),
                end = Offset(targetX + 30f, targetY),
                strokeWidth = 5f
            )

            drawLine(
                color = Color.Red,
                start = Offset(targetX, targetY - 30f),
                end = Offset(targetX, targetY + 30f),
                strokeWidth = 5f
            )
        }
    }
}
@Composable
fun SpatialCompassOverlay(uiState: NavigationUiState) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Compact compass in top-right corner
        Canvas(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 100.dp, end = 16.dp)
                .size(120.dp)
                .shadow(8.dp, CircleShape)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                .padding(8.dp)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.width / 2f - 10f
            val (azimuth, _, _) = uiState.currentOrientation

            // Draw outer ring
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                radius = radius,
                center = center,
                style = Stroke(width = 2f)
            )

            // Draw cardinal directions
            listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (label, angle) ->
                val rad = Math.toRadians((angle - azimuth).toDouble()).toFloat()
                val x = center.x + radius * sin(rad) * 0.8f
                val y = center.y - radius * cos(rad) * 0.8f

                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    x,
                    y,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 24f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                        isFakeBoldText = true
                    }
                )
            }

            // Draw tracked objects
            uiState.spatialObjects.take(5).forEach { obj ->
                val angleDiff = obj.azimuth - azimuth
                val rad = Math.toRadians(angleDiff.toDouble()).toFloat()
                val distance = radius * 0.6f
                val x = center.x + distance * sin(rad)
                val y = center.y - distance * cos(rad)

                val markerColor = if (obj.isVisible) Color.Green else Color.Yellow.copy(alpha = obj.confidence)

                // Draw marker
                drawCircle(
                    color = markerColor,
                    radius = 6f,
                    center = Offset(x, y)
                )
            }

            // Draw heading indicator (red arrow)
            val arrowPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(center.x, center.y - radius * 0.5f)
                lineTo(center.x - 8f, center.y - radius * 0.3f)
                lineTo(center.x + 8f, center.y - radius * 0.3f)
                close()
            }
            drawPath(
                path = arrowPath,
                color = Color.Red
            )
        }
    }
}

