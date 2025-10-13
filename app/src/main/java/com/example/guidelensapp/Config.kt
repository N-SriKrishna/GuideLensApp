package com.example.guidelensapp

// A singleton object to hold all configuration constants
object Config {
    // --- Model & Detection Configuration ---
    // List of objects that make sense to navigate to
    val NAVIGABLE_OBJECTS = listOf(
        "chair",
        "door",
        "table",
        "bed",
        "couch",
        "toilet",
        "sink",
        "refrigerator",
        "stairs",
        "person",
        "bottle",
        "cup",
        "laptop",
        "phone",
        "keyboard",
        "mouse"
    )

    const val FLOOR_CONFIDENCE_THRESHOLD = 0.5f
    const val FLOOR_MASK_ALPHA = 128

    // --- Navigation & Logic Configuration ---
    const val PATHFINDING_GRID_SCALE = 15
    const val STUCK_TIME_THRESHOLD_MS = 3000L
    const val INSTRUCTION_LOCK_DURATION_MS = 1500L
    const val TARGET_REACHED_RADIUS_PX = 60f

    // --- Pure Pursuit Configuration --- (ADDED)
    const val PP_LOOKAHEAD_DISTANCE_PX = 100f  // Lookahead distance for path following
    const val PP_SHARP_TURN_K = 0.05f          // Curvature threshold for sharp turns
    const val PP_SLIGHT_TURN_K = 0.02f         // Curvature threshold for slight turns

    // --- Safety & Inflation ---
    const val INFLATION_RADIUS_GRID = 2

    // --- Display Configuration ---
    const val DRAW_DETECTIONS = true
    const val DRAW_FLOOR_MASK = true
    const val DRAW_PATH = true

    // --- Performance Configuration ---
    const val DETECTION_CONFIDENCE_THRESHOLD = 0.3f
    const val MAX_DETECTIONS = 20
}
