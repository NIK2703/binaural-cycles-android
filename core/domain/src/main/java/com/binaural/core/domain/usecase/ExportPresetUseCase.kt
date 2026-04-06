package com.binaural.core.domain.usecase

import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.service.FileStorageService

/**
 * UseCase для экспорта пресета.
 * Использует FileStorageService для абстракции от Android-зависимостей.
 */
class ExportPresetUseCase(
    private val presetRepository: PresetRepository,
    private val fileStorageService: FileStorageService
) {
    /**
     * Экспортировать пресет в JSON-строку.
     * 
     * @param presetId ID пресета для экспорта
     * @return JSON-строка или null если пресет не найден
     */
    suspend operator fun invoke(presetId: String): String? {
        val preset = presetRepository.getPresetById(presetId) ?: return null
        return fileStorageService.exportToJson(preset)
    }
    
    /**
     * Записать JSON-данные в Uri.
     * 
     * @param uriString Uri для записи (как строка)
     * @param presetId ID пресета для экспорта
     * @return true если запись успешна
     */
    suspend fun writeToUri(uriString: String, presetId: String): Boolean {
        val jsonData = invoke(presetId) ?: return false
        return fileStorageService.writeToUri(uriString, jsonData)
    }
}