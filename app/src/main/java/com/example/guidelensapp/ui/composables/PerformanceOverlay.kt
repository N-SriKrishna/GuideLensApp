// app/src/main/java/com/example/guidelensapp/ui/composables/PerformanceOverlay.kt

package com.example.guidelensapp.ui.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.utils.PerformanceMetrics

@Composable
fun PerformanceOverlay(
    metrics: PerformanceMetrics,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .width(220.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E),
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Analytics,
                            contentDescription = null,
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Performance",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.2f), thickness = 1.dp)

                // FPS
                PerformanceMetricRow(
                    label = "FPS",
                    value = String.format("%.1f", metrics.currentFPS),
                    icon = Icons.Filled.Speed,
                    color = getFPSColor(metrics.currentFPS)
                )

                // Inference Time
                PerformanceMetricRow(
                    label = "Inference",
                    value = "${metrics.averageInferenceTimeMs}ms",
                    icon = Icons.Filled.Timer,
                    color = getInferenceColor(metrics.averageInferenceTimeMs)
                )

                // Memory Usage
                PerformanceMetricRow(
                    label = "Memory",
                    value = "${metrics.memoryUsedMB}MB",
                    icon = Icons.Filled.Memory,
                    color = getMemoryColor(metrics.memoryUsedMB, metrics.memoryAvailableMB)
                )

                // Frame Time
                PerformanceMetricRow(
                    label = "Frame Time",
                    value = "${metrics.lastFrameTimeMs}ms",
                    icon = Icons.Filled.AccessTime,
                    color = Color(0xFF00BCD4)
                )

                Divider(color = Color.White.copy(alpha = 0.2f), thickness = 1.dp)

                // Additional info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Frames",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${metrics.frameCount}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Available",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${metrics.memoryAvailableMB}MB",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun PerformanceMetricRow(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp
            )
        }

        Text(
            text = value,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun CompactFPSCounter(
    fps: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E1E1E),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Speed,
                contentDescription = null,
                tint = getFPSColor(fps),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = String.format("%.1f", fps),
                color = getFPSColor(fps),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "FPS",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
        }
    }
}

private fun getFPSColor(fps: Float): Color {
    return when {
        fps >= 25f -> Color(0xFF4CAF50) // Green
        fps >= 15f -> Color(0xFFFFC107) // Yellow/Amber
        else -> Color(0xFFE53935) // Red
    }
}

private fun getInferenceColor(timeMs: Long): Color {
    return when {
        timeMs <= 50 -> Color(0xFF4CAF50) // Green
        timeMs <= 100 -> Color(0xFFFFC107) // Yellow/Amber
        else -> Color(0xFFE53935) // Red
    }
}

private fun getMemoryColor(used: Long, available: Long): Color {
    val percentUsed = if (available > 0) (used.toFloat() / available) * 100 else 0f
    return when {
        percentUsed < 60 -> Color(0xFF4CAF50) // Green
        percentUsed < 80 -> Color(0xFFFFC107) // Yellow/Amber
        else -> Color(0xFFE53935) // Red
    }
}
