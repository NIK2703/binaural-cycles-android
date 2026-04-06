package com.binauralcycles.ui.theme

/**
 * Constants for frequency graph rendering.
 * Centralized to ensure consistency across graph components.
 */
object GraphConstants {
    // Point appearance (in pixels for Canvas drawing)
    const val POINT_RADIUS_DEFAULT = 5f
    const val POINT_RADIUS_SELECTED = 8f
    const val POINT_INNER_RADIUS_BASE = 2f
    const val POINT_INNER_RADIUS_MAX = 4f
    
    // Stroke widths (in pixels)
    const val STROKE_THIN = 0.5f
    const val STROKE_NORMAL = 1f
    const val STROKE_THICK = 1.5f
    const val STROKE_VERY_THICK = 2f
    
    // Grid
    const val GRID_LINE_WIDTH = 1f
    const val GRID_VERTICAL_STEP_HOURS = 3
    const val GRID_ALPHA = 0.1f
    
    // Interpolation
    const val MIN_SAMPLES_INTERPOLATION = 400
    const val MIN_SAMPLES_INTERPOLATION_EDIT = 500
    const val SAMPLES_PER_POINT_MULTIPLIER = 4
    
    // Label positioning
    const val LABEL_OFFSET_X = 25f
    const val LABEL_OFFSET_Y = 8f
    const val LABEL_MIN_Y = 15f
    const val AXIS_PADDING = 20f
    const val AXIS_TEXT_SIZE_SP = 10
    const val LABEL_TEXT_SIZE_SP = 8
    
    // Dash pattern for base curve
    const val DASH_PATTERN_ON = 6f
    const val DASH_PATTERN_OFF = 6f
    
    // Playback indicator
    const val INDICATOR_RADIUS = 6f
    
    // Graph opacity values
    const val CARRIER_PATH_ALPHA = 0.6f
    const val BEAT_AREA_ALPHA = 0.15f
    const val BEAT_STROKE_ALPHA = 0.3f
    const val BASE_CURVE_ALPHA = 0.3f
    const val INDICATOR_ALPHA = 0.7f
    const val INDICATOR_BEAT_ALPHA = 0.5f
    const val LABEL_ALPHA = 0.8f
    
    // Seconds in day for calculations
    const val SECONDS_PER_DAY = 24 * 3600L
    const val HOURS_PER_DAY = 24
    const val MINUTES_PER_HOUR = 60
    const val SECONDS_PER_HOUR = 3600
}