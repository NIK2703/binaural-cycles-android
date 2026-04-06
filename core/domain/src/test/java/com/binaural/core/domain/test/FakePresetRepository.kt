package com.binaural.core.domain.test

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.repository.PresetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Fake реализация PresetRepository для тестирования.
 * Хранит пресеты в памяти.
 */
class FakePresetRepository : PresetRepository {
    
    private val presets = MutableStateFlow<List<BinauralPreset>>(emptyList())
    private var activePresetId = MutableStateFlow<String?>(null)
    
    /**
     * Установить начальный список пресетов для теста
     */
    fun setPresets(presetList: List<BinauralPreset>) {
        presets.value = presetList
    }
    
    /**
     * Добавить пресет напрямую (для подготовки теста)
     */
    fun addPresetDirectly(preset: BinauralPreset) {
        presets.value = presets.value + preset
    }
    
    override fun getPresets(): Flow<List<BinauralPreset>> {
        return presets.asStateFlow()
    }
    
    override suspend fun getPresetById(id: String): BinauralPreset? {
        return presets.value.find { it.id == id }
    }
    
    override suspend fun addPreset(preset: BinauralPreset) {
        presets.value = presets.value + preset
    }
    
    override suspend fun updatePreset(preset: BinauralPreset) {
        presets.value = presets.value.map { 
            if (it.id == preset.id) preset else it 
        }
    }
    
    override suspend fun deletePreset(presetId: String) {
        presets.value = presets.value.filter { it.id != presetId }
        // Если удаляем активный пресет, сбрасываем активный
        if (activePresetId.value == presetId) {
            activePresetId.value = null
        }
    }
    
    override fun getActivePresetId(): Flow<String?> {
        return activePresetId.asStateFlow()
    }
    
    override suspend fun setActivePresetId(id: String?) {
        activePresetId.value = id
    }
    
    /**
     * Сбросить состояние (для очистки между тестами)
     */
    fun reset() {
        presets.value = emptyList()
        activePresetId.value = null
    }
    
    /**
     * Получить текущий список пресетов (для тестов)
     */
    fun getPresetsList(): List<BinauralPreset> = presets.value
    
    /**
     * Получить Flow пресетов для тестов
     */
    fun getPresetsFlow(): Flow<List<BinauralPreset>> = presets.asStateFlow()
}
