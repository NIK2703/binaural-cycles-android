package com.binaural.core.domain.test

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.service.FileStorageService
import kotlinx.serialization.json.Json

/**
 * Fake реализация FileStorageService для тестирования.
 * Хранит данные в памяти.
 */
class FakeFileStorageService : FileStorageService {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    // Хранилище для симуляции файловой системы
    private val fileStorage = mutableMapOf<String, String>()
    
    // Флаг для симуляции ошибок
    var shouldFailExport = false
    var shouldFailImport = false
    var shouldFailWrite = false
    var shouldFailRead = false
    
    /**
     * Установить содержимое "файла" для теста
     */
    fun setFileContent(uriString: String, content: String) {
        fileStorage[uriString] = content
    }
    
    /**
     * Получить содержимое "файла"
     */
    fun getFileContent(uriString: String): String? = fileStorage[uriString]
    
    /**
     * Очистить хранилище
     */
    fun reset() {
        fileStorage.clear()
        shouldFailExport = false
        shouldFailImport = false
        shouldFailWrite = false
        shouldFailRead = false
    }
    
    override fun exportToJson(preset: BinauralPreset): String? {
        if (shouldFailExport) return null
        
        return try {
            json.encodeToString(BinauralPreset.serializer(), preset)
        } catch (e: Exception) {
            null
        }
    }
    
    override fun importFromJson(jsonString: String): BinauralPreset? {
        if (shouldFailImport) return null
        
        return try {
            json.decodeFromString<BinauralPreset>(jsonString)
        } catch (e: Exception) {
            null
        }
    }
    
    override suspend fun createExportFile(presetName: String): String? {
        if (shouldFailExport) return null
        val uriString = "file://test/$presetName.json"
        fileStorage[uriString] = ""
        return uriString
    }
    
    override suspend fun writeToUri(uriString: String, data: String): Boolean {
        if (shouldFailWrite) return false
        fileStorage[uriString] = data
        return true
    }
    
    override suspend fun readFromUri(uriString: String): String? {
        if (shouldFailRead) return null
        return fileStorage[uriString]
    }
    
    // ========== Методы для тестов ==========
    
    /**
     * Список экспортированных пресетов (для проверки в тестах)
     */
    private val _exportedPresets = mutableListOf<BinauralPreset>()
    val exportedPresets: List<BinauralPreset> get() = _exportedPresets.toList()
    
    /**
     * Подготовить пресет для импорта (симуляция файла)
     */
    fun preparePresetForImport(preset: BinauralPreset): String {
        val uriString = "file://test/import/${preset.id}.json"
        val presetJson = exportToJson(preset) ?: "{}"
        fileStorage[uriString] = presetJson
        return uriString
    }
    
    /**
     * Сбросить состояние экспорта
     */
    fun resetExportState() {
        _exportedPresets.clear()
    }
}
