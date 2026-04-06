package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.service.PlaybackController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * UseCase для управления пресетами.
 * Инкапсулирует бизнес-логику работы с пресетами.
 */
class PresetUseCase(
    private val presetRepository: PresetRepository,
    private val playbackController: PlaybackController
) {
    /**
     * Получить все пресеты
     */
    fun getPresets(): Flow<List<BinauralPreset>> = presetRepository.getPresets()
    
    /**
     * Получить пресет по ID
     */
    suspend fun getPresetById(id: String): BinauralPreset? = presetRepository.getPresetById(id)
    
    /**
     * Получить ID активного пресета
     */
    fun getActivePresetId(): Flow<String?> = presetRepository.getActivePresetId()
    
    /**
     * Создать новый пресет
     */
    suspend fun createPreset(
        name: String,
        frequencyCurve: FrequencyCurve,
        relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
    ): BinauralPreset {
        val preset = BinauralPreset(
            name = name,
            frequencyCurve = frequencyCurve,
            relaxationModeSettings = relaxationModeSettings
        )
        presetRepository.addPreset(preset)
        return preset
    }
    
    /**
     * Обновить существующий пресет
     */
    suspend fun updatePreset(preset: BinauralPreset) {
        presetRepository.updatePreset(preset)
    }
    
    /**
     * Удалить пресет
     */
    suspend fun deletePreset(presetId: String) {
        presetRepository.deletePreset(presetId)
    }
    
    /**
     * Установить активный пресет и применить его к воспроизведению
     */
    suspend fun setActivePreset(preset: BinauralPreset, config: BinauralConfig) {
        presetRepository.setActivePresetId(preset.id)
        playbackController.setCurrentPresetName(preset.name)
        playbackController.setCurrentPresetId(preset.id)
        playbackController.updateConfig(config, preset.relaxationModeSettings)
    }
    
    /**
     * Очистить активный пресет
     */
    suspend fun clearActivePreset() {
        presetRepository.setActivePresetId(null)
        playbackController.setCurrentPresetName(null)
        playbackController.setCurrentPresetId(null)
    }
    
    /**
     * Дублировать пресет
     */
    suspend fun duplicatePreset(presetId: String): BinauralPreset? {
        val preset = presetRepository.getPresetById(presetId) ?: return null
        val existingPresets = presetRepository.getPresets().first()
        val existingNames = existingPresets.map { it.name }.toSet()
        
        val newName = generateUniqueName(preset.name, existingNames)
        val duplicatedPreset = preset.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = newName,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        presetRepository.addPreset(duplicatedPreset)
        return duplicatedPreset
    }
    
    /**
     * Генерировать уникальное имя для дубликата
     */
    private fun generateUniqueName(baseName: String, existingNames: Set<String>): String {
        val regex = """^(.+?) \((\d+)\)$""".toRegex()
        val match = regex.find(baseName)
        
        val actualBaseName = if (match != null) {
            match.groupValues[1]
        } else {
            baseName
        }
        
        val usedNumbers = mutableSetOf<Int>()
        var hasExactBaseName = false
        
        for (name in existingNames) {
            if (name == actualBaseName) {
                hasExactBaseName = true
            } else {
                val nameMatch = regex.find(name)
                if (nameMatch != null && nameMatch.groupValues[1] == actualBaseName) {
                    usedNumbers.add(nameMatch.groupValues[2].toInt())
                }
            }
        }
        
        if (!hasExactBaseName && actualBaseName !in existingNames) {
            return actualBaseName
        }
        
        var counter = 1
        while (counter in usedNumbers) {
            counter++
        }
        
        return "$actualBaseName ($counter)"
    }
    
    /**
     * Обновить список ID пресетов в playback controller (для переключения next/previous)
     */
    suspend fun updatePresetIdsForPlayback() {
        val presets = presetRepository.getPresets().first()
        playbackController.setPresetIds(presets.map { it.id })
    }
}