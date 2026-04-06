package com.binaural.core.domain.usecase

import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.service.PlaybackController
import kotlinx.coroutines.flow.first

/**
 * UseCase для удаления пресета.
 * Инкапсулирует логику удаления пресета со сбросом активного состояния при необходимости.
 */
class DeletePresetUseCase(
    private val presetRepository: PresetRepository,
    private val playbackStateRepository: PlaybackStateRepository,
    private val playbackController: PlaybackController
) {
    /**
     * Удалить пресет по ID.
     * Если удаляемый пресет активен — останавливает воспроизведение и сбрасывает activePresetId.
     * 
     * @return имя удалённого пресета или null если пресет не найден
     */
    suspend operator fun invoke(presetId: String): String? {
        val preset = presetRepository.getPresetById(presetId) ?: return null
        val presetName = preset.name
        
        // Проверяем, является ли удаляемый пресет активным
        val activePresetId = presetRepository.getActivePresetId().first()
        if (activePresetId == presetId) {
            // Останавливаем воспроизведение
            playbackController.stopWithFade()
            
            // Сбрасываем активный пресет
            presetRepository.setActivePresetId(null)
            playbackController.setCurrentPresetName(null)
            playbackController.setCurrentPresetId(null)
            
            // Обновляем состояние
            playbackStateRepository.updateState { 
                it.copy(currentPresetId = null, currentPresetName = null) 
            }
        }
        
        presetRepository.deletePreset(presetId)
        return presetName
    }
}
