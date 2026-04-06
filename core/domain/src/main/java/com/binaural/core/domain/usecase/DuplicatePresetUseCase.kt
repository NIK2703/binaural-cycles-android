package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.repository.PresetRepository
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * UseCase для дублирования пресета.
 * Содержит логику генерации уникального имени для копии.
 */
class DuplicatePresetUseCase(
    private val presetRepository: PresetRepository
) {
    /**
     * Дублировать пресет с новым ID и модифицированным именем.
     * 
     * @param presetId ID пресета для дублирования
     * @return новый пресет или null если исходный не найден
     */
    suspend operator fun invoke(presetId: String): BinauralPreset? {
        val originalPreset = presetRepository.getPresetById(presetId) ?: return null
        val existingNames = presetRepository.getPresets().first().map { it.name }.toSet()
        val newName = generateUniqueName(originalPreset.name, existingNames)
        
        val newPreset = originalPreset.copy(
            id = UUID.randomUUID().toString(),
            name = newName
        )
        
        presetRepository.addPreset(newPreset)
        return newPreset
    }
    
    /**
     * Генерирует уникальное имя на основе базового имени.
     * Формат: "basename (n)" где n - минимальный доступный номер.
     */
    fun generateUniqueName(baseName: String, existingNames: Set<String>): String {
        // Если базовое имя свободно - используем его
        if (baseName !in existingNames) {
            return baseName
        }
        
        // Паттерн для поиска существующих копий: "name (n)"
        val copyPattern = Regex("^${Regex.escape(baseName)} \\((\\d+)\\)$")
        val existingNumbers = existingNames
            .mapNotNull { name -> copyPattern.find(name)?.groupValues?.get(1)?.toIntOrNull() }
            .toSortedSet()
        
        // Находим минимальный свободный номер
        var number = 1
        while (number in existingNumbers) {
            number++
        }
        
        return "$baseName ($number)"
    }
}