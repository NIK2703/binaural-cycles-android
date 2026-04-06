package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.service.FileStorageService
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * UseCase для импорта пресета.
 * Использует FileStorageService для абстракции от Android-зависимостей.
 */
class ImportPresetUseCase(
    private val presetRepository: PresetRepository,
    private val fileStorageService: FileStorageService,
    private val duplicatePresetUseCase: DuplicatePresetUseCase
) {
    /**
     * Импортировать пресет из JSON-строки.
     * Генерирует уникальное имя если пресет с таким именем уже существует.
     * 
     * @param jsonString JSON-строка с данными пресета
     * @return импортированный пресет или null при ошибке
     */
    suspend operator fun invoke(jsonString: String): BinauralPreset? {
        val importedPreset = fileStorageService.importFromJson(jsonString) ?: return null
        
        // Получаем существующие имена
        val existingNames = presetRepository.getPresets().first().map { it.name }.toSet()
        
        // Генерируем уникальное имя если нужно
        val uniqueName = duplicatePresetUseCase.generateUniqueName(importedPreset.name, existingNames)
        
        // Создаём новый пресет с уникальным ID, именем и текущими временными метками
        val currentTime = System.currentTimeMillis()
        val newPreset = importedPreset.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueName,
            createdAt = currentTime,
            updatedAt = currentTime
        )
        
        presetRepository.addPreset(newPreset)
        return newPreset
    }
    
    /**
     * Импортировать пресет из Uri.
     * 
     * @param uriString Uri для чтения (как строка)
     * @return импортированный пресет или null при ошибке
     */
    suspend fun fromUri(uriString: String): BinauralPreset? {
        val jsonString = fileStorageService.readFromUri(uriString) ?: return null
        return invoke(jsonString)
    }
}