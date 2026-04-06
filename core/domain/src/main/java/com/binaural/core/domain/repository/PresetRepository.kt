package com.binaural.core.domain.repository

import com.binaural.core.domain.model.BinauralPreset
import kotlinx.coroutines.flow.Flow

/**
 * Интерфейс репозитория для управления пресетами.
 * Скрывает детали хранения (DataStore, файлы, сеть).
 */
interface PresetRepository {
    
    /**
     * Получить все пресеты
     */
    fun getPresets(): Flow<List<BinauralPreset>>
    
    /**
     * Получить пресет по ID
     */
    suspend fun getPresetById(id: String): BinauralPreset?
    
    /**
     * Добавить новый пресет
     */
    suspend fun addPreset(preset: BinauralPreset)
    
    /**
     * Обновить существующий пресет
     */
    suspend fun updatePreset(preset: BinauralPreset)
    
    /**
     * Удалить пресет
     */
    suspend fun deletePreset(presetId: String)
    
    /**
     * Получить ID активного пресета
     */
    fun getActivePresetId(): Flow<String?>
    
    /**
     * Установить активный пресет
     */
    suspend fun setActivePresetId(id: String?)
}