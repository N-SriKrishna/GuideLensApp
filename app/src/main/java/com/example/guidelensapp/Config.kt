package com.example.guidelensapp

// A singleton object to hold all configuration constants, mirroring config.py
object Config {

    // --- Model & Detection Configuration ---
    const val TARGET_OBJECT_LABEL = "chair" // The navigation goal object label.
    const val FLOOR_CONFIDENCE_THRESHOLD = 0.5f
    const val FLOOR_MASK_ALPHA = 128

    // --- Navigation & Logic Configuration ---
    const val PATHFINDING_GRID_SCALE = 15 // Downscale factor for the A* grid. Larger is faster but less precise.
    const val STUCK_TIME_THRESHOLD_MS = 3000L // Time in milliseconds to detect being stuck.
    const val INSTRUCTION_LOCK_DURATION_MS = 1500L // Min time between new navigation commands.
    const val TARGET_REACHED_RADIUS_PX = 60f // Radius around target to consider it "reached".


    // --- Safety & Inflation ---
    // Instead of a full distance transform, we'll use a simpler grid inflation method.
    // This value is in grid units (not pixels).
    const val INFLATION_RADIUS_GRID = 2 // Expand obstacles by 2 grid cells in every direction.

    // --- Pure Pursuit Configuration ---
    const val PP_LOOKAHEAD_DISTANCE_PX = 120f // Lookahead distance for the Pure Pursuit algorithm in pixels.
    const val PP_SLIGHT_TURN_K = 0.005f // Curvature thresholds for turning commands.
    const val PP_SHARP_TURN_K = 0.015f
}