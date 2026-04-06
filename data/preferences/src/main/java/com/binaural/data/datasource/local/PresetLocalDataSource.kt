package com.binaural.data.datasource.local

import com.binaural.core.domain.model.BinauralPreset
import kotlinx.coroutines.flow.Flow

/**
 * Интерфейс для работы с пресетами локально.
 * Скрывает детали хранения (DataStore, Room, файлы).
 */
interface PresetLocalDataSource {
    
    /**
     * Получить все пресеты
     */
    fun getPresets(): Flow<List<BinauralPreset>>
    
    /**
     * Получить пресет по ID
     */
    suspend fun getPresetById(id: String): BinauralPreset?
    
    /**
     * Сохранить список пресетов
     */
    suspend fun savePresets(presets: List<BinauralPreset>)
    
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