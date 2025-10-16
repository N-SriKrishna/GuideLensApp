// app/src/main/java/com/example/guidelensapp/MainActivity.kt

package com.example.guidelensapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.ui.composables.*
import com.example.guidelensapp.ui.theme.GuideLensAppTheme
import com.example.guidelensapp.viewmodel.AppMode
import com.example.guidelensapp.viewmodel.NavigationUiState
import com.example.guidelensapp.viewmodel.NavigationViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.example.guidelensapp.ui.composables.EnhancedObjectSelector
import com.example.guidelensapp.ui.composables.CompactFPSCounter

class MainActivity : ComponentActivity() {
    private val viewModel: NavigationViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GuideLensAppTheme {
                // Request both CAMERA and RECORD_AUDIO permissions
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO  // Added microphone permission
                    )
                )

                LaunchedEffect(Unit) {
                    // Request permissions on app start
                    permissionsState.launchMultiplePermissionRequest()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (permissionsState.allPermissionsGranted) {
                        val uiState by viewModel.uiState.collectAsState()

                        // Show mode selector if not selected
                        if (uiState.showModeSelector) {
                            StartScreen(
                                onModeSelected = { mode ->
                                    viewModel.setAppMode(mode)
                                },
                                onSpeak = { text, priority ->
                                    viewModel.speak(text, priority)
                                }
                            )
                        } else {
                            NavigationScreen(viewModel = viewModel)
                        }
                    } else {
                        PermissionDeniedScreen()
                    }
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
        // Hidden camera view for processing
        Box(modifier = Modifier.size(0.dp)) {
            CameraView(onFrame = { bitmap ->
                viewModel.processFrame(bitmap)
            })
        }

        // Conditional rendering based on mode
        when (uiState.appMode) {
            AppMode.SIMPLE_NAVIGATION -> {
                SimpleNavigationUI(uiState = uiState, viewModel = viewModel)
            }
            AppMode.DEBUG_MODE -> {
                DebugNavigationUI(uiState = uiState, viewModel = viewModel)
            }
        }
    }
}
@Composable
fun SimpleNavigationUI(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel
) {
    // Black screen with minimal visual elements
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Large touch zones for accessibility
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top half: Object selection and scene description
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                SimpleNavigationTopZone(uiState = uiState, viewModel = viewModel)
            }

            // Bottom half: Start/Stop navigation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0D0D0D)),
                contentAlignment = Alignment.Center
            ) {
                SimpleNavigationBottomZone(uiState = uiState, viewModel = viewModel)
            }
        }

        // Status banner at top
        SimpleStatusBanner(uiState = uiState, viewModel = viewModel)
    }
}


@Composable
fun DebugNavigationUI(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Main camera feed and overlays
        OverlayCanvas(uiState = uiState)

        // Spatial compass (only during navigation)
        AnimatedVisibility(
            visible = uiState.isNavigating,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            SpatialCompassOverlay(uiState = uiState)
        }

        // Enhanced Top Bar with gradient
        EnhancedTopBar(uiState = uiState, viewModel = viewModel)

        // Floating Action Buttons on the right
        FloatingControlPanel(
            uiState = uiState,
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.CenterEnd)
        )

        // Bottom navigation command
        AnimatedVisibility(
            visible = uiState.navigationCommand.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            NavigationCommandBanner(uiState = uiState)
        }

        // Object selector dialog
        if (uiState.showObjectSelector) {
            EnhancedObjectSelector(
                currentTarget = uiState.targetObject,
                onTargetSelected = { target ->
                    viewModel.setTargetObject(target)
                },
                onDismiss = { viewModel.toggleObjectSelector() },
                onStartNavigation = {
                    viewModel.startNavigation()
                }
            )
        }

        // Off-screen target indicator
        uiState.offScreenGuidance?.let { guidance ->
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            ) {
                OffScreenGuidanceBanner(guidance)
            }
        }

        // Compact FPS counter (top-left)
        if (uiState.showPerformanceOverlay) {
            CompactFPSCounter(
                fps = uiState.performanceMetrics.currentFPS,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .statusBarsPadding()
            )

            // Full performance overlay (top-right)
            PerformanceOverlay(
                metrics = uiState.performanceMetrics,
                isVisible = true,
                onDismiss = { viewModel.togglePerformanceOverlay() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .statusBarsPadding()
            )
        }
    }

}

@Composable
fun EnhancedTopBar(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side - App branding with icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Explore,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "GuideLens",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        AnimatedContent(
                            targetState = uiState.isNavigating,
                            transitionSpec = {
                                fadeIn() togetherWith fadeOut()
                            }
                        ) { isNavigating ->
                            if (isNavigating) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Navigation,
                                        contentDescription = null,
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "→ ${uiState.targetObject.replaceFirstChar { it.uppercase() }}",
                                        color = Color(0xFF4CAF50),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            } else {
                                Text(
                                    text = "Debug Mode",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // Right side - Quick actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Voice commands indicator
                    if (uiState.voiceCommandsEnabled) {
                        IconButton(
                            onClick = { viewModel.toggleVoiceCommands() },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = Color(0xFF4CAF50).copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (uiState.isListeningForCommands)
                                    Icons.Filled.Mic
                                else
                                    Icons.Outlined.MicOff,
                                contentDescription = "Voice Commands",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Mode switcher
                    IconButton(
                        onClick = { viewModel.showModeSelector() },
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = "Change Mode",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingControlPanel(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(end = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.End
    ) {
        // Performance monitor toggle
        FloatingActionButton(
            onClick = { viewModel.togglePerformanceOverlay() },
            containerColor = if (uiState.showPerformanceOverlay)
                MaterialTheme.colorScheme.primary
            else
                Color(0xFF37474F),
            contentColor = Color.White,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Analytics,
                contentDescription = "Performance",
                modifier = Modifier.size(24.dp)
            )
        }

        // Scene description
        FloatingActionButton(
            onClick = { viewModel.describeScene() },
            containerColor = Color(0xFF2196F3),
            contentColor = Color.White,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = "Describe Scene",
                modifier = Modifier.size(24.dp)
            )
        }

        // Main navigation control (larger)
        FloatingActionButton(
            onClick = {
                if (uiState.isNavigating) {
                    viewModel.stopNavigation()
                } else {
                    viewModel.toggleObjectSelector()
                }
            },
            containerColor = if (uiState.isNavigating)
                Color(0xFFE53935)
            else
                Color(0xFF4CAF50),
            contentColor = Color.White,
            modifier = Modifier
                .size(72.dp)
                .shadow(12.dp, CircleShape)
        ) {
            Icon(
                imageVector = if (uiState.isNavigating)
                    Icons.Filled.Stop
                else
                    Icons.Filled.Navigation,
                contentDescription = if (uiState.isNavigating) "Stop" else "Navigate",
                modifier = Modifier.size(32.dp)
            )
        }

        // Object selector
        if (!uiState.isNavigating) {
            FloatingActionButton(
                onClick = { viewModel.toggleObjectSelector() },
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = Color.White,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Category,
                    contentDescription = "Select Object",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun NavigationCommandBanner(uiState: NavigationUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1E1E1E),
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Animated sound wave icon
            val infiniteTransition = rememberInfiniteTransition(label = "sound")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Surface(
                shape = CircleShape,
                color = Color(0xFF2196F3).copy(alpha = 0.2f),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = null,
                    tint = Color(0xFF2196F3),
                    modifier = Modifier
                        .size(48.dp)
                        .padding(12.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                )
            }

            Text(
                text = uiState.navigationCommand,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun OffScreenGuidanceBanner(guidance: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFFF9800),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Explore,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = guidance,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PermissionDeniedScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(120.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .size(120.dp)
                        .padding(32.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Permissions Required",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "GuideLens needs camera and microphone access to provide navigation assistance.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )
            }

            Button(
                onClick = { /* Navigate to settings */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Open Settings",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
