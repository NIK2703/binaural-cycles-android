package com.binaural.core.domain.usecase

import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.service.PlaybackController

/**
 * UseCase для воспроизведения пресета.
 * Инкапсулирует логику запуска воспроизведения пресета.
 * 
 * Примечание: Конфигурация (BinauralConfig) должна быть установлена отдельно
 * через PlaybackController.updateConfig(), так как требует настроек из SettingsRepository.
 */
class PlayPresetUseCase(
    private val presetRepository: PresetRepository,
    private val playbackStateRepository: PlaybackStateRepository,
    private val playbackController: PlaybackController
) {
    /**
     * Начать воспроизведение пресета.
     * 
     * @param presetId ID пресета для воспроизведения
     * @return true если пресет найден и активирован
     */
    suspend operator fun invoke(presetId: String): Boolean {
        val preset = presetRepository.getPresetById(presetId) ?: return false
        
        // Устанавливаем активный пресет
        presetRepository.setActivePresetId(presetId)
        playbackController.setCurrentPresetId(presetId)
        playbackController.setCurrentPresetName(preset.name)
        
        // Обновляем состояние
        playbackStateRepository.updateState { 
            it.copy(
                currentPresetId = presetId,
                currentPresetName = preset.name
            ) 
        }
        
        return true
    }
}