package com.binaural.core.domain.service

import com.binaural.core.domain.model.BinauralPreset

/**
 * Интерфейс для работы с файловой системой.
 * Абстрагирует Android-зависимости от domain-слоя.
 */
interface FileStorageService {
    /**
     * Экспортировать пресет в JSON-строку
     */
    fun exportToJson(preset: BinauralPreset): String?
    
    /**
     * Импортировать пресет из JSON-строки
     */
    fun importFromJson(jsonString: String): BinauralPreset?
    
    /**
     * Создать файл для экспорта и вернуть Uri как строку
     */
    suspend fun createExportFile(presetName: String): String?
    
    /**
     * Записать данные в файл по Uri
     */
    suspend fun writeToUri(uriString: String, data: String): Boolean
    
    /**
     * Прочитать данные из Uri
     */
    suspend fun readFromUri(uriString: String): String?
}