package com.example.guidelensapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.guidelensapp.ui.composables.*
import com.example.guidelensapp.ui.theme.GuideLensAppTheme
import com.example.guidelensapp.viewmodel.NavigationUiState
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
                    permissions = listOf(Manifest.permission.CAMERA)
                )

                LaunchedEffect(Unit) {
                    permissionsState.launchMultiplePermissionRequest()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (permissionsState.allPermissionsGranted) {
                        NavigationScreen(viewModel = viewModel)
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

        // Main camera feed and overlays
        OverlayCanvas(uiState = uiState)

        // Spatial compass (only during navigation)
        AnimatedVisibility(
            visible = uiState.isNavigating,
            enter = fadeIn() + slideInVertically(
                animationSpec = spring(stiffness = Spring.StiffnessLow)
            ),
            exit = fadeOut() + slideOutVertically()
        ) {
            SpatialCompassOverlay(uiState = uiState)
        }

        // Top status bar
        TopStatusBar(uiState = uiState, viewModel = viewModel)

        // Bottom control panel
        BottomControlPanel(uiState = uiState, viewModel = viewModel)

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

        // Off-screen target indicator
        uiState.offScreenGuidance?.let { guidance ->
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically() + fadeIn(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                OffScreenGuidanceBanner(guidance)
            }
        }
    }
}

@Composable
fun TopStatusBar(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = Color.Black.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App title and status
            Column {
                Text(
                    text = "GuideLens",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                AnimatedContent(
                    targetState = uiState.isNavigating,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    }
                ) { isNavigating ->
                    if (isNavigating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = null,
                                tint = Color.Green,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Navigating to ${uiState.targetObject}",
                                color = Color.Green,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Text(
                            text = "Ready",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Settings button
            IconButton(
                onClick = { viewModel.toggleObjectSelector() },
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
fun BoxScope.BottomControlPanel(
    uiState: NavigationUiState,
    viewModel: NavigationViewModel
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
    ) {
        // Navigation command banner
        AnimatedVisibility(
            visible = uiState.navigationCommand.isNotEmpty(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.large,
                color = Color.Black.copy(alpha = 0.8f),
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = uiState.navigationCommand,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Main control buttons
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            color = Color.Black.copy(alpha = 0.7f),
            shadowElevation = 16.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scene description button
                NavigationFAB(
                    icon = Icons.Default.Description,
                    label = "Describe",
                    onClick = { viewModel.describeScene() },
                    containerColor = MaterialTheme.colorScheme.primary
                )

                // Stop/Mute button
                AnimatedContent(
                    targetState = uiState.isNavigating to uiState.isSpeaking,
                    transitionSpec = {
                        scaleIn() togetherWith scaleOut()
                    }
                ) { (isNavigating, isSpeaking) ->
                    when {
                        isNavigating -> NavigationFAB(
                            icon = Icons.Default.Stop,
                            label = "Stop Nav",
                            onClick = { viewModel.stopNavigation() },
                            containerColor = Color(0xFFE53935),
                            size = 70
                        )
                        isSpeaking -> NavigationFAB(
                            icon = Icons.Default.VolumeOff,
                            label = "Mute",
                            onClick = { viewModel.stopSpeaking() },
                            containerColor = Color(0xFFFF9800)
                        )
                        else -> Spacer(modifier = Modifier.size(56.dp))
                    }
                }

                // Start navigation placeholder (when not navigating)
                if (!uiState.isNavigating) {
                    NavigationFAB(
                        icon = Icons.Default.Explore,
                        label = "Navigate",
                        onClick = { viewModel.toggleObjectSelector() },
                        containerColor = Color(0xFF4CAF50)
                    )
                } else {
                    Spacer(modifier = Modifier.size(56.dp))
                }
            }
        }
    }
}

@Composable
fun NavigationFAB(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    size: Int = 56
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = Color.White,
            modifier = Modifier
                .size(size.dp)
                .shadow(8.dp, CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size((size * 0.4).dp)
            )
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OffScreenGuidanceBanner(guidance: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .statusBarsPadding(),
        shape = MaterialTheme.shapes.medium,
        color = Color(0xFFFFA000),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = guidance,
                color = Color.White,
                fontSize = 14.sp,
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp)
            )

            Text(
                text = "Camera Permission Required",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Text(
                text = "GuideLens needs camera access to provide navigation assistance. Please enable it in your device settings.",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        }
    }
}
