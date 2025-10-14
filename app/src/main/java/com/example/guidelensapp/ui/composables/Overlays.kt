package com.example.guidelensapp.ui.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.viewmodel.NavigationUiState

@Composable
fun OverlayCanvas(uiState: NavigationUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Camera Image (base layer)
        uiState.cameraImage?.let { cameraImage ->
            Image(
                bitmap = cameraImage,
                contentDescription = "Camera feed",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // 2. Floor Mask Overlay (semi-transparent green)
        uiState.floorMaskOverlay?.let { floorMask ->
            Image(
                bitmap = floorMask,
                contentDescription = "Floor segmentation",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.tint(
                    color = Color(0x8000FF00), // 50% transparent green
                    blendMode = BlendMode.Modulate
                )
            )
        }

        // 3. Detections and Path (Canvas overlay)
        NavigationOverlay(uiState = uiState)

        // 4. Navigation command display
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
        // Get canvas dimensions
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Calculate scale factors based on camera image dimensions
        val scaleX = if (uiState.cameraImage != null) {
            canvasWidth / uiState.cameraImage.width.toFloat()
        } else 1f

        val scaleY = if (uiState.cameraImage != null) {
            canvasHeight / uiState.cameraImage.height.toFloat()
        } else 1f

        // Draw only target object detections with proper scaling
        uiState.detectedObjects.forEach { detection ->
            val rect = detection.boundingBox

            // Scale bounding box coordinates to canvas size
            val scaledLeft = rect.left * scaleX
            val scaledTop = rect.top * scaleY
            val scaledWidth = rect.width() * scaleX
            val scaledHeight = rect.height() * scaleY

            val targetColor = Color(0xFF4CAF50) // Bright green

            // Draw bounding box with scaled coordinates
            drawRect(
                color = targetColor,
                topLeft = Offset(scaledLeft, scaledTop),
                size = Size(scaledWidth, scaledHeight),
                style = Stroke(width = 8f)
            )

            // Draw label with scaled position
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

                // Label background with scaled position
                drawRect(
                    scaledLeft,
                    scaledTop - textBounds.height() - 20f,
                    scaledLeft + textBounds.width() + 20f,
                    scaledTop,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#4CAF50")
                        style = android.graphics.Paint.Style.FILL
                    }
                )

                // Label text with scaled position
                drawText(
                    labelText,
                    scaledLeft + 10f,
                    scaledTop - 10f,
                    textPaint
                )
            }
        }

        // Draw path if available with scaling
        uiState.path?.let { pathPoints ->
            if (pathPoints.size > 1) {
                for (i in 0 until pathPoints.size - 1) {
                    drawLine(
                        color = Color.Cyan,
                        start = Offset(pathPoints[i].x * scaleX, pathPoints[i].y * scaleY),
                        end = Offset(pathPoints[i + 1].x * scaleX, pathPoints[i + 1].y * scaleY),
                        strokeWidth = 6f
                    )
                }
            }
        }

        // Draw target position marker with scaling
        uiState.targetPosition?.let { target ->
            val scaledTargetX = target.x * scaleX
            val scaledTargetY = target.y * scaleY

            // Draw crosshair at target
            drawLine(
                color = Color.Red,
                start = Offset(scaledTargetX - 30f, scaledTargetY),
                end = Offset(scaledTargetX + 30f, scaledTargetY),
                strokeWidth = 5f
            )
            drawLine(
                color = Color.Red,
                start = Offset(scaledTargetX, scaledTargetY - 30f),
                end = Offset(scaledTargetX, scaledTargetY + 30f),
                strokeWidth = 5f
            )
        }
    }
}
