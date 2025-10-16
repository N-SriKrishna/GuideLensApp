// app/src/main/java/com/example/guidelensapp/ui/composables/SimpleNavigationComposables.kt

package com.example.guidelensapp.ui.composables

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.viewmodel.NavigationUiState
import com.example.guidelensapp.viewmodel.NavigationViewModel

@Composable
fun SimpleNavigationTopZone(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel
) {
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.speak(
                            "Top zone. Single tap for scene description. " +
                                    "Double tap to select navigation target.",
                            1
                        )
                    },
                    onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleObjectSelector()
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.describeScene()
                    }
                )
            }
            .semantics {
                contentDescription = "Scene control zone. Tap for instructions, " +
                        "double tap to select target object, long press for scene description."
                role = Role.Button
            }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Scene & Target",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (uiState.targetObject.isNotEmpty())
                "Target: ${uiState.targetObject}"
            else
                "No target selected",
            fontSize = 18.sp,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SimpleNavigationBottomZone(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel
) {
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(uiState.isNavigating) {
                detectTapGestures(
                    onTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val message = if (uiState.isNavigating) {
                            "Bottom zone. Navigation active. Double tap to stop navigation."
                        } else {
                            "Bottom zone. Double tap to start navigation to ${uiState.targetObject}."
                        }
                        viewModel.speak(message, 1)
                    },
                    onDoubleTap = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (uiState.isNavigating) {
                            viewModel.stopNavigation()
                            viewModel.speak("Navigation stopped.", 0)
                        } else {
                            if (uiState.targetObject.isNotEmpty()) {
                                viewModel.startNavigation()
                                viewModel.speak("Navigation started to ${uiState.targetObject}.", 0)
                            } else {
                                viewModel.speak("Please select a target object first by double tapping the top zone.", 0)
                            }
                        }
                    }
                )
            }
            .semantics {
                contentDescription = if (uiState.isNavigating) {
                    "Navigation control zone. Currently navigating. Double tap to stop."
                } else {
                    "Navigation control zone. Double tap to start navigation."
                }
                role = Role.Button
            }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (uiState.isNavigating) Icons.Default.Stop else Icons.Default.Navigation,
            contentDescription = null,
            tint = if (uiState.isNavigating) Color.Red.copy(alpha = 0.5f) else Color.Green.copy(alpha = 0.5f),
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (uiState.isNavigating) "Stop Navigation" else "Start Navigation",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        if (uiState.isNavigating) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Navigating to ${uiState.targetObject}",
                fontSize = 18.sp,
                color = Color.Green.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SimpleStatusBanner(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = Color.Black.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GuideLens - Simple Mode",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.navigationCommand.isNotEmpty() && uiState.isNavigating) {
                    Text(
                        text = uiState.navigationCommand,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
            }

            // Mode switcher button
            IconButton(
                onClick = { viewModel.showModeSelector() },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Change mode",
                    tint = Color.White
                )
            }
        }
    }
}
