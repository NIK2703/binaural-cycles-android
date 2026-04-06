package com.binauralcycles.ui.theme

/**
 * Default values for discrete sliders across the app.
 * Centralized to ensure consistency and easy maintenance.
 */
object SliderDefaults {
    // Channel swap intervals (seconds): 30s, 1m, 2m, 5m, 10m, 15m, 30m, 1h
    val SWAP_INTERVALS_SECONDS = listOf(30, 60, 120, 300, 600, 900, 1800, 3600)
    
    // Fade durations (milliseconds): 1s to 15s in 1s steps
    val FADE_DURATIONS_MS = listOf(
        1000L, 2000L, 3000L, 4000L, 5000L, 6000L, 7000L, 8000L, 9000L, 
        10000L, 11000L, 12000L, 13000L, 14000L, 15000L
    )
    
    // Pause durations (milliseconds): 0 to 60s
    val PAUSE_DURATIONS_MS = listOf(0L, 1000L, 2000L, 3000L, 5000L, 10000L, 20000L, 30000L, 60000L)
    
    // Buffer generation intervals (minutes): 1m to 1h
    val BUFFER_INTERVALS_MINUTES = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60)
    
    // Relaxation mode intervals (minutes)
    val RELAXATION_GAP_INTERVALS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    val RELAXATION_DURATION_INTERVALS = listOf(5, 10, 15, 20, 30, 45, 60)
    val RELAXATION_TRANSITION_INTERVALS = listOf(1, 2, 3, 5, 7, 10)
    val RELAXATION_SMOOTH_INTERVALS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    
    // Slider value ranges
    const val NORMALIZATION_STRENGTH_MIN = 0f
    const val NORMALIZATION_STRENGTH_MAX = 2f
    
    const val CARRIER_REDUCTION_MIN = 0
    const val CARRIER_REDUCTION_MAX = 50
    
    const val BEAT_REDUCTION_MIN = 0
    const val BEAT_REDUCTION_MAX = 100
    
    // Default values
    const val DEFAULT_SWAP_INTERVAL_SECONDS = 300  // 5 minutes
    const val DEFAULT_FADE_DURATION_MS = 5000L     // 5 seconds
    const val DEFAULT_PAUSE_DURATION_MS = 0L       // No pause
    const val DEFAULT_BUFFER_MINUTES = 10          // 10 minutes
}