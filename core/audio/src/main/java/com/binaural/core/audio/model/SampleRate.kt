package com.binaural.core.audio.model

/**
 * Частота дискретизации аудио.
 *
 * Живёт в `model`, а не в `engine`: это единица настроек, которую читают и UI,
 * и поток. Раньше enum лежал в `engine/BinauralAudioEngine.kt` — единственном
 * живом символе в мёртвом 1447-строчном файле, из-за чего удалить его было
 * нельзя. Перенесён сюда, файл удалён.
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
