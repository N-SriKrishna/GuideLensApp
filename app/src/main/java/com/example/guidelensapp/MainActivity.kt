// File: app/src/main/java/com/example/guidelensapp/MainActivity.kt
package com.example.guidelensapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.guidelensapp.ui.composables.CameraView
import com.example.guidelensapp.ui.composables.Overlays
import com.example.guidelensapp.ui.composables.StartScreen
import com.example.guidelensapp.ui.theme.GuideLensAppTheme
import com.example.guidelensapp.utils.ThreadManager
import com.example.guidelensapp.viewmodel.NavigationViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

class MainActivity : ComponentActivity() {
    private val viewModel: NavigationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GuideLensAppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "start") {
                        composable("start") {
                            StartScreen(onStartClick = { navController.navigate("navigation") })
                        }
                        composable("navigation") {
                            AppContent(viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        viewModel.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        when (level) {
            TRIM_MEMORY_UI_HIDDEN -> {
                // App is in background
                viewModel.setQuality(0.5f)
            }
            TRIM_MEMORY_RUNNING_MODERATE,
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL -> {
                // Memory pressure while running
                viewModel.onLowMemory()
            }
        }
    }

    override fun onDestroy() {
        // Ensure thread manager is shutdown
        ThreadManager.getInstance().shutdown()
        super.onDestroy()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppContent(viewModel: NavigationViewModel) {
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        cameraPermissionState.launchPermissionRequest()
    }

    if (cameraPermissionState.status.isGranted) {
        LaunchedEffect(Unit) {
            viewModel.initializeModels(context)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            CameraView(onFrame = { viewModel.onFrame(it) })
            Overlays(uiState = uiState)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Camera permission is required to use this app.",
                modifier = Modifier.padding(16.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}