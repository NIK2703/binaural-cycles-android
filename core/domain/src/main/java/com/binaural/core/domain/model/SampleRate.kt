package com.binaural.core.domain.model

/**
 * Частота дискретизации аудио
 */
enum class SampleRate(val value: Int) {
    ULTRA_LOW(8000),
    VERY_LOW(16000),
    LOW(22050),
    MEDIUM(44100),
    HIGH(48000);
    
    companion object {
        fun fromValue(value: Int): SampleRate = entries.find { it.value == value } ?: MEDIUM
    }
}