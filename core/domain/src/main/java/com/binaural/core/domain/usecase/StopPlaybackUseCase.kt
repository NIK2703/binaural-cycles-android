package com.binaural.core.domain.usecase

import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.service.PlaybackController

/**
 * UseCase для остановки воспроизведения.
 * Инкапсулирует логику остановки воспроизведения и сброса состояния.
 */
class StopPlaybackUseCase(
    private val playbackController: PlaybackController,
    private val presetRepository: PresetRepository,
    private val playbackStateRepository: PlaybackStateRepository
) {
    /**
     * Остановить воспроизведение.
     * Останавливает аудио и сбрасывает состояние активного пресета.
     */
    suspend operator fun invoke() {
        // Останавливаем воспроизведение с fade
        playbackController.stopWithFade()
        
        // Сбрасываем активный пресет
        presetRepository.setActivePresetId(null)
        playbackController.setCurrentPresetId(null)
        playbackController.setCurrentPresetName(null)
        
        // Обновляем состояние
        playbackStateRepository.updateState { 
            it.copy(
                currentPresetId = null,
                currentPresetName = null
            ) 
        }
    }
}