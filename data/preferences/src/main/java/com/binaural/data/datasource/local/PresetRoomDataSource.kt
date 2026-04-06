package com.binaural.data.datasource.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.FrequencyRange
import com.binaural.core.domain.model.InterpolationType
import com.binaural.data.local.dao.FrequencyPointDao
import com.binaural.data.local.dao.PresetDao
import com.binaural.data.local.mapper.PresetMapper
import com.binaural.data.preferences.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация PresetLocalDataSource через Room Database.
 * Активный пресет хранится в DataStore для обратной совместимости.
 */
@Singleton
class PresetRoomDataSource @Inject constructor(
    private val presetDao: PresetDao,
    private val frequencyPointDao: FrequencyPointDao,
    private val dataStore: DataStore<Preferences>,
    private val context: Context
) : PresetLocalDataSource {
    
    companion object {
        private val ACTIVE_PRESET_ID_KEY = stringPreferencesKey("active_preset_id")
    }
    
    override fun getPresets(): Flow<List<BinauralPreset>> = channelFlow {
        // Проверяем при первом подключении - пуста ли база
        val initialEntities = presetDao.getAllPresets().first()
        
        if (initialEntities.isEmpty()) {
            // База пуста - сохраняем дефолтные пресеты
            val defaultPresets = getLocalizedDefaultPresets()
            savePresets(defaultPresets)
        }
        
        // Подписываемся на изменения в БД (реактивный Flow)
        presetDao.getAllPresets().collect { entities ->
            val presets = entities.map { entity ->
                val points = frequencyPointDao.getPointsForPresetSync(entity.id)
                PresetMapper.toDomain(entity, points)
            }
            send(presets)
        }
    }
    
    override suspend fun getPresetById(id: String): BinauralPreset? {
        val entity = presetDao.getPresetById(id) ?: return null
        val points = frequencyPointDao.getPointsForPresetSync(id)
        return PresetMapper.toDomain(entity, points)
    }
    
    override suspend fun savePresets(presets: List<BinauralPreset>) {
        presets.forEach { preset ->
            presetDao.insertPreset(PresetMapper.toEntity(preset))
            frequencyPointDao.replacePointsForPreset(
                preset.id,
                PresetMapper.toEntityList(preset.frequencyCurve.points, preset.id)
            )
        }
    }
    
    override suspend fun addPreset(preset: BinauralPreset) {
        presetDao.insertPreset(PresetMapper.toEntity(preset))
        frequencyPointDao.insertPoints(
            PresetMapper.toEntityList(preset.frequencyCurve.points, preset.id)
        )
    }
    
    override suspend fun updatePreset(preset: BinauralPreset) {
        presetDao.insertPreset(PresetMapper.toEntity(preset))
        frequencyPointDao.replacePointsForPreset(
            preset.id,
            PresetMapper.toEntityList(preset.frequencyCurve.points, preset.id)
        )
    }
    
    override suspend fun deletePreset(presetId: String) {
        presetDao.deletePresetById(presetId)
        // Точки удаляются автоматически благодаря CASCADE
    }
    
    override fun getActivePresetId(): Flow<String?> {
        return dataStore.data.map { preferences ->
            preferences[ACTIVE_PRESET_ID_KEY]
        }
    }
    
    override suspend fun setActivePresetId(id: String?) {
        dataStore.edit { preferences ->
            if (id != null) {
                preferences[ACTIVE_PRESET_ID_KEY] = id
            } else {
                preferences.remove(ACTIVE_PRESET_ID_KEY)
            }
        }
    }
    
    // ========== Private methods ==========
    
    private fun getLocalizedDefaultPresets(): List<BinauralPreset> {
        return listOf(
            BinauralPreset(
                id = BinauralPreset.DEFAULT_PRESET_ID,
                name = context.getString(R.string.preset_circadian_rhythm),
                frequencyCurve = FrequencyCurve.defaultCurve()
            ),
            BinauralPreset(
                id = BinauralPreset.GAMMA_PRESET_ID,
                name = context.getString(R.string.preset_gamma_productivity),
                frequencyCurve = FrequencyCurve(
                    points = listOf(
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 220.0f, beatFrequency = 1.5f),
                        FrequencyPoint.fromHours(3, 0, carrierFrequency = 250.0f, beatFrequency = 5.0f),
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 340.0f, beatFrequency = 9.0f),
                        FrequencyPoint.fromHours(9, 0, carrierFrequency = 400.0f, beatFrequency = 18.0f),
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 380.0f, beatFrequency = 14.0f),
                        FrequencyPoint.fromHours(15, 0, carrierFrequency = 440.0f, beatFrequency = 40.0f),
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 300.0f, beatFrequency = 7.5f),
                        FrequencyPoint.fromHours(21, 0, carrierFrequency = 240.0f, beatFrequency = 4.0f),
                    ),
                    carrierRange = FrequencyRange(100.0f, 600.0f),
                    interpolationType = InterpolationType.MONOTONE
                )
            ),
            BinauralPreset(
                id = BinauralPreset.DAILY_CYCLE_PRESET_ID,
                name = context.getString(R.string.preset_daily_cycle),
                frequencyCurve = FrequencyCurve(
                    points = listOf(
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 200.0f, beatFrequency = 2.0f),
                        FrequencyPoint.fromHours(3, 0, carrierFrequency = 200.0f, beatFrequency = 3.0f),
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 300.0f, beatFrequency = 10.0f),
                        FrequencyPoint.fromHours(9, 0, carrierFrequency = 400.0f, beatFrequency = 18.0f),
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 300.0f, beatFrequency = 6.0f),
                        FrequencyPoint.fromHours(15, 0, carrierFrequency = 400.0f, beatFrequency = 25.0f),
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 300.0f, beatFrequency = 9.0f),
                        FrequencyPoint.fromHours(21, 0, carrierFrequency = 250.0f, beatFrequency = 5.0f),
                    ),
                    carrierRange = FrequencyRange(100.0f, 600.0f),
                    interpolationType = InterpolationType.MONOTONE
                )
            )
        )
    }
}