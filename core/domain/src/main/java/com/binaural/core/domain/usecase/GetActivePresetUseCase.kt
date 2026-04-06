package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * UseCase для получения активного пресета.
 * Инкапсулирует логику получения активного пресета из репозитория.
 */
class GetActivePresetUseCase(
    private val presetRepository: PresetRepository,
    private val playbackStateRepository: PlaybackStateRepository
) {
    /**
     * Получить активный пресет (suspend версия)
     */
    suspend operator fun invoke(): BinauralPreset? {
        val activeId = presetRepository.getActivePresetId().first() ?: return null
        return presetRepository.getPresetById(activeId)
    }
    
    /**
     * Наблюдать за активным пресетом (Flow версия)
     */
    fun observe(): Flow<BinauralPreset?> {
        return presetRepository.getActivePresetId().map { activeId ->
            activeId?.let { presetRepository.getPresetById(it) }
        }
    }
    
    /**
     * Получить только ID активного пресета
     */
    fun observeActiveId(): Flow<String?> = presetRepository.getActivePresetId()
}