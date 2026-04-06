package com.binauralcycles.ui.theme

/**
 * Audio-related constants used across UI components.
 * Centralized to ensure consistency and easy maintenance.
 */
object AudioConstants {
    // Frequency bounds
    const val MIN_AUDIBLE_FREQUENCY = 20.0f
    const val MAX_FREQUENCY = 2000.0f
    
    // Graph interaction
    const val DRAG_DIRECTION_THRESHOLD = 10f
    const val TIME_STEP_MINUTES = 5
    const val MIN_SAMPLES_FOR_INTERPOLATION = 500
    
    // Timing
    const val NAVIGATION_BLOCK_DURATION_MS = 500L
    const val TIME_UPDATE_INTERVAL_MS = 5000L
}