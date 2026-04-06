package com.binaural.core.domain.service

import kotlinx.coroutines.flow.StateFlow

/**
 * Интерфейс аудио-движка для domain-слоя.
 * Абстракция позволяет UseCase'ам не зависеть от конкретной реализации аудио-движка.
 */
interface AudioEngineInterface {
    /**
     * Текущая частота бинаурального ритма (Гц).
     */
    val currentBeatFrequency: StateFlow<Float>
    
    /**
     * Текущая несущая частота (Гц).
     */
    val currentCarrierFrequency: StateFlow<Float>
    
    /**
     * Обновить текущие частоты из lookup table.
     * Вызывается периодически для отображения актуальных частот.
     */
    fun updateCurrentFrequencies()
}