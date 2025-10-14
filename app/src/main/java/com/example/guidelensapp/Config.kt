package com.example.guidelensapp

import android.os.Build
import android.util.Log

object Config {
    private const val TAG = "Config"

    // --- Device Capability Detection ---
    data class DeviceCapabilities(
        val cpuCores: Int,
        val isHighEnd: Boolean,
        val totalRamMB: Long,
        val hasNNAPI: Boolean,
        val hasHexagon: Boolean,
        val deviceName: String
    )

    private lateinit var deviceCaps: DeviceCapabilities

    fun initialize(context: android.content.Context) {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val cpuCores = Runtime.getRuntime().availableProcessors()
        val totalRamMB = memInfo.totalMem / (1024 * 1024)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        // Classify device tier based on RAM and CPU
        val isHighEnd = totalRamMB >= 8192 && cpuCores >= 8

        // Detect accelerator support
        val hasNNAPI = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val hasHexagon = deviceName.contains("SM-", ignoreCase = true) ||
                deviceName.contains("Pixel", ignoreCase = true)

        deviceCaps = DeviceCapabilities(
            cpuCores = cpuCores,
            isHighEnd = isHighEnd,
            totalRamMB = totalRamMB,
            hasNNAPI = hasNNAPI,
            hasHexagon = hasHexagon,
            deviceName = deviceName
        )

        Log.d(TAG, "Device: $deviceName")
        Log.d(TAG, "Tier: ${if (isHighEnd) "High-End" else "Mid-Range"}")
        Log.d(TAG, "RAM: ${totalRamMB}MB, Cores: $cpuCores")
        Log.d(TAG, "NNAPI: $hasNNAPI, Hexagon: $hasHexagon")
    }

    // --- Model & Detection Configuration ---
    val NAVIGABLE_OBJECTS = listOf(
        "chair", "door", "table", "bed", "couch", "toilet", "sink",
        "refrigerator", "stairs", "person", "bottle", "cup", "laptop",
        "phone", "keyboard", "mouse"
    )

    const val FLOOR_CONFIDENCE_THRESHOLD = 0.5f
    const val FLOOR_MASK_ALPHA = 128

    // --- Navigation & Logic Configuration ---
    const val PATHFINDING_GRID_SCALE = 15
    const val STUCK_TIME_THRESHOLD_MS = 3000L
    const val INSTRUCTION_LOCK_DURATION_MS = 1500L
    const val TARGET_REACHED_RADIUS_PX = 60f
    const val PP_LOOKAHEAD_DISTANCE_PX = 100f
    const val PP_SHARP_TURN_K = 0.05f
    const val PP_SLIGHT_TURN_K = 0.02f
    const val INFLATION_RADIUS_GRID = 1

    // --- ADAPTIVE PERFORMANCE SETTINGS ---

    // Target FPS based on device tier
    val TARGET_FPS: Int
        get() = if (deviceCaps.isHighEnd) 20 else 15

    val MIN_FRAME_INTERVAL_MS: Long
        get() = 1000L / TARGET_FPS

    // Camera resolution based on device tier
    val CAMERA_WIDTH: Int
        get() = if (deviceCaps.isHighEnd) 1280 else 960

    val CAMERA_HEIGHT: Int
        get() = if (deviceCaps.isHighEnd) 720 else 540

    // Detection thresholds
    val DETECTION_CONFIDENCE_THRESHOLD: Float
        get() = if (deviceCaps.isHighEnd) 0.30f else 0.35f

    const val DETECTION_IOU_THRESHOLD = 0.45f

    val MAX_DETECTIONS_PER_FRAME: Int
        get() = if (deviceCaps.isHighEnd) 10 else 8

    const val SKIP_FLOOR_WHEN_NO_TARGET = true
    const val MODEL_INPUT_SIZE = 640

    // Bitmap pooling
    const val ENABLE_BITMAP_POOLING = true

    val MAX_BITMAP_POOL_SIZE: Int
        get() = if (deviceCaps.isHighEnd) 5 else 3

    // Frame skipping for lower-end devices
    val SKIP_FRAMES: Boolean
        get() = !deviceCaps.isHighEnd

    val PROCESS_EVERY_N_FRAMES: Int
        get() = if (deviceCaps.isHighEnd) 1 else 2

    // Hardware acceleration
    val USE_GPU_DELEGATE: Boolean
        get() = true  // Available on most modern devices

    val USE_NNAPI: Boolean
        get() = deviceCaps.hasNNAPI && deviceCaps.isHighEnd

    val USE_HEXAGON_DSP: Boolean
        get() = deviceCaps.hasHexagon && deviceCaps.isHighEnd

    // ML inference threads based on CPU cores
    val ML_INFERENCE_THREADS: Int
        get() = when {
            deviceCaps.cpuCores >= 8 -> 4
            deviceCaps.cpuCores >= 6 -> 3
            deviceCaps.cpuCores >= 4 -> 2
            else -> 1
        }

    val ENABLE_FP16: Boolean
        get() = deviceCaps.isHighEnd

    // Get device info for logging
    fun getDeviceInfo(): String {
        return """
            Device: ${deviceCaps.deviceName}
            Tier: ${if (deviceCaps.isHighEnd) "High-End" else "Mid-Range"}
            RAM: ${deviceCaps.totalRamMB}MB
            CPU Cores: ${deviceCaps.cpuCores}
            Target FPS: $TARGET_FPS
            Camera: ${CAMERA_WIDTH}x${CAMERA_HEIGHT}
            ML Threads: $ML_INFERENCE_THREADS
            GPU Delegate: $USE_GPU_DELEGATE
            NNAPI: $USE_NNAPI
            Hexagon DSP: $USE_HEXAGON_DSP
            FP16: $ENABLE_FP16
        """.trimIndent()
    }
}
