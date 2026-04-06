package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.repository.PresetRepository

/**
 * UseCase для сохранения пресета.
 * Инкапсулирует логику создания и обновления пресетов.
 */
class SavePresetUseCase(
    private val presetRepository: PresetRepository
) {
    /**
     * Сохранить пресет (создать новый или обновить существующий)
     */
    suspend operator fun invoke(preset: BinauralPreset) {
        val existingPreset = presetRepository.getPresetById(preset.id)
        if (existingPreset != null) {
            presetRepository.updatePreset(preset)
        } else {
            presetRepository.addPreset(preset)
        }
    }
    
    /**
     * Создать новый пресет
     */
    suspend fun create(preset: BinauralPreset) {
        presetRepository.addPreset(preset)
    }
    
    /**
     * Обновить существующий пресет
     */
    suspend fun update(preset: BinauralPreset) {
        presetRepository.updatePreset(preset)
    }
}