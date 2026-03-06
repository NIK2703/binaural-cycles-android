package com.binaural.core.audio

/**
 * Общие константы для аудио-модуля
 */
object AudioConstants {
    /**
     * Минимальная слышимая человеком частота (Гц)
     */
    const val MIN_AUDIBLE_FREQUENCY = 20.0
    
    /**
     * Максимальная слышимая человеком частота (Гц)
     */
    const val MAX_AUDIBLE_FREQUENCY = 20000.0
    
    /**
     * Максимальная несущая частота по умолчанию (Гц)
     */
    const val DEFAULT_MAX_CARRIER_FREQUENCY = 500.0
    
    /**
     * Минимальная частота биений по умолчанию (Гц)
     */
    const val DEFAULT_MIN_BEAT_FREQUENCY = 0.0
    
    /**
     * Максимальная частота биений по умолчанию (Гц)
     */
    const val DEFAULT_MAX_BEAT_FREQUENCY = 1000.0
}