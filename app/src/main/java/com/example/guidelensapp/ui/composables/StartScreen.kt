// app/src/main/java/com/example/guidelensapp/ui/composables/StartScreen.kt

package com.example.guidelensapp.ui.composables

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.viewmodel.AppMode

@Composable
fun StartScreen(
    onModeSelected: (AppMode) -> Unit,
    onSpeak: (String, Int) -> Unit
) {
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        // Announce screen on load
        onSpeak(
            "Welcome to GuideLens. Double tap the top half of the screen for simple navigation mode, " +
                    "or double tap the bottom half for debug mode with visual feedback.",
            0 // Emergency priority for initial announcement
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "GuideLens",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Indoor Navigation Assistant",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            // Mode Selection Cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Simple Navigation Mode (Blind-Friendly)
                ModeSelectionCard(
                    title = "Simple Navigation",
                    description = "Audio-guided navigation for blind users. Tap anywhere on this card to select.",
                    icon = Icons.Default.VolumeUp,
                    backgroundColor = Color(0xFF4CAF50),
                    onSelected = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSpeak("Simple navigation mode selected. Starting navigation.", 0)
                        onModeSelected(AppMode.SIMPLE_NAVIGATION)
                    },
                    onFocus = {
                        onSpeak(
                            "Simple navigation mode. Audio-only interface for blind users. " +
                                    "Double tap to select.",
                            1
                        )
                    }
                )

                // Debug Mode
                ModeSelectionCard(
                    title = "Debug Mode",
                    description = "Visual mode with floor masks, object detection overlays, and path visualization.",
                    icon = Icons.Default.BugReport,
                    backgroundColor = Color(0xFFFF9800),
                    onSelected = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSpeak("Debug mode selected. Visual overlays enabled.", 0)
                        onModeSelected(AppMode.DEBUG_MODE)
                    },
                    onFocus = {
                        onSpeak(
                            "Debug mode. Includes visual overlays for testing. " +
                                    "Double tap to select.",
                            1
                        )
                    }
                )
            }

            // Instructions
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = "Swipe between cards to hear descriptions. Double tap to select a mode.",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ModeSelectionCard(
    title: String,
    description: String,
    icon: ImageVector,
    backgroundColor: Color,
    onSelected: () -> Unit,
    onFocus: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .scale(scale)
            .semantics {
                contentDescription = "$title. $description"
                role = Role.Button
                onClick {
                    onSelected()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onFocus()
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onDoubleTap = {
                        onSelected()
                    }
                )
            },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(60.dp)
            )

            Spacer(modifier = Modifier.width(20.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    lineHeight = 20.sp
                )
            }
        }
    }
}
