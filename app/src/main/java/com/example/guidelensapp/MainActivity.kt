package com.example.guidelensapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        setContent {
            GuideLensAppTheme {
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA
                    )
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
}

@Composable
fun NavigationScreen(viewModel: NavigationViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initializeModels(context)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera feed (hidden, just processes frames)
        CameraView(onFrame = { bitmap ->
            viewModel.processFrame(bitmap)
        })

        // Overlay canvas (shows camera, floor mask, detections, path)
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

        // Settings button (top-right)
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
    }
}

@Composable
fun PermissionDeniedScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Camera permission is required for navigation",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
