package com.example.guidelensapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.ui.composables.CameraView
import com.example.guidelensapp.ui.composables.ObjectSelectorView
import com.example.guidelensapp.ui.composables.OverlayCanvas
import com.example.guidelensapp.ui.theme.GuideLensAppTheme
import com.example.guidelensapp.viewmodel.NavigationViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : ComponentActivity() {
    private val viewModel: NavigationViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initializeModels(this)

        setContent {
            GuideLensAppTheme {
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(Manifest.permission.CAMERA)
                )

                LaunchedEffect(Unit) {
                    permissionsState.launchMultiplePermissionRequest()
                }

                if (permissionsState.allPermissionsGranted) {
                    NavigationScreen(viewModel = viewModel)
                } else {
                    PermissionDeniedScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        viewModel.cleanup()
        super.onDestroy()
    }
}

@Composable
fun NavigationScreen(viewModel: NavigationViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initializeModels(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Hidden camera - processes frames in background
        Box(modifier = Modifier.size(0.dp)) {
            CameraView(onFrame = { bitmap ->
                viewModel.processFrame(bitmap)
            })
        }

        // Display processed image with all overlays
        OverlayCanvas(uiState = uiState)

        // Object selector dialog
        if (uiState.showObjectSelector) {
            ObjectSelectorView(
                currentTarget = uiState.targetObject,
                onTargetSelected = { target ->
                    viewModel.setTargetObject(target)
                },
                onStartNavigation = {
                    viewModel.startNavigation()
                }
            )
        }

        // Top-right: Settings button
        IconButton(
            onClick = { viewModel.toggleObjectSelector() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Change target",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // Bottom controls - Dynamic based on navigation state
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Bottom-left: Scene Description button
            FloatingActionButton(
                onClick = { viewModel.describeScene() },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "Describe Scene",
                    modifier = Modifier.size(24.dp)
                )
            }

            // Bottom-center: Stop Navigation button (only when navigating)
            if (uiState.isNavigating) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.stopNavigation() },
                    containerColor = Color(0xFFFF6B6B),
                    contentColor = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop Navigation",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stop",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }

            // Bottom-right: Stop Speaking button (only when speaking)
            if (uiState.isSpeaking) {
                FloatingActionButton(
                    onClick = { viewModel.stopSpeaking() },
                    containerColor = Color(0xFFFF9800)
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeOff,
                        contentDescription = "Stop Speaking",
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                // Placeholder to maintain layout consistency
                Spacer(modifier = Modifier.size(56.dp))
            }
        }

        // Visual indicator when TTS is speaking (top center)
        if (uiState.isSpeaking) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp),
                color = Color.Blue.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Speaking",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Speaking...",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Visual indicator when navigation is active (top center)
        if (uiState.isNavigating && !uiState.isSpeaking) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp),
                color = Color(0xFF4CAF50).copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🧭",
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Navigating to ${uiState.targetObject}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionDeniedScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "📷",
                fontSize = 64.sp
            )
            Text(
                text = "Camera permission is required",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Please grant camera access in Settings",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp
            )
        }
    }
}
