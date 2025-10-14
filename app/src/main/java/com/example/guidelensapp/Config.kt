package com.example.guidelensapp

object Config {
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

    // --- Safety Configuration ---
    const val INFLATION_RADIUS_GRID = 1  // ADD THIS LINE - Grid cells to inflate around obstacles

    // --- PERFORMANCE OPTIMIZATION SETTINGS ---
    const val TARGET_FPS = 20
    const val MIN_FRAME_INTERVAL_MS = 1000L / TARGET_FPS

    const val CAMERA_WIDTH = 1280
    const val CAMERA_HEIGHT = 720

    const val DETECTION_CONFIDENCE_THRESHOLD = 0.30f
    const val DETECTION_IOU_THRESHOLD = 0.45f
    const val MAX_DETECTIONS_PER_FRAME = 10

    const val SKIP_FLOOR_WHEN_NO_TARGET = true
    const val MODEL_INPUT_SIZE = 640

    const val ENABLE_BITMAP_POOLING = true
    const val MAX_BITMAP_POOL_SIZE = 5

    const val SKIP_FRAMES = false
    const val PROCESS_EVERY_N_FRAMES = 1

    const val USE_GPU_DELEGATE = true
    const val USE_NNAPI = true
    const val USE_HEXAGON_DSP = true

    const val ML_INFERENCE_THREADS = 4
    const val ENABLE_FP16 = true
}
