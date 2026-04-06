package com.binaural.data.repository

import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.model.BinauralPreset
import com.binaural.data.datasource.local.PresetLocalDataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация PresetRepository.
 * Отвечает только за работу с пресетами.
 */
@Singleton
class PresetRepositoryImpl @Inject constructor(
    private val presetLocalDataSource: PresetLocalDataSource
) : PresetRepository {
    
    override fun getPresets(): Flow<List<BinauralPreset>> {
        return presetLocalDataSource.getPresets()
    }
    
    override suspend fun getPresetById(id: String): BinauralPreset? {
        return presetLocalDataSource.getPresetById(id)
    }
    
    override fun getActivePresetId(): Flow<String?> {
        return presetLocalDataSource.getActivePresetId()
    }
    
    override suspend fun setActivePresetId(id: String?) {
        presetLocalDataSource.setActivePresetId(id)
    }
    
    override suspend fun addPreset(preset: BinauralPreset) {
        presetLocalDataSource.addPreset(preset)
    }
    
    override suspend fun updatePreset(preset: BinauralPreset) {
        presetLocalDataSource.updatePreset(preset)
    }
    
    override suspend fun deletePreset(presetId: String) {
        presetLocalDataSource.deletePreset(presetId)
    }
}