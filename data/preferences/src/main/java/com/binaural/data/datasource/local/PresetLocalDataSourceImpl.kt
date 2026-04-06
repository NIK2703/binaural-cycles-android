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
import com.binaural.core.domain.model.RelaxationMode
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.data.preferences.R
import com.binaural.data.preferences.SerializableFrequencyCurve
import com.binaural.data.preferences.SerializableFrequencyPoint
import com.binaural.data.preferences.SerializableFrequencyRange
import com.binaural.data.preferences.SerializablePreset
import com.binaural.data.preferences.SerializableRelaxationModeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация PresetLocalDataSource через DataStore.
 * Инкапсулирует работу с пресетами и их сериализацией.
 */
@Singleton
class PresetLocalDataSourceImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val context: Context
) : PresetLocalDataSource {
    
    companion object {
        private val PRESETS_KEY = stringPreferencesKey("presets")
        private val ACTIVE_PRESET_ID_KEY = stringPreferencesKey("active_preset_id")
    }
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override fun getPresets(): Flow<List<BinauralPreset>> {
        return dataStore.data.map { preferences ->
            preferences[PRESETS_KEY]?.let { jsonString ->
                deserializePresets(jsonString)
            } ?: getLocalizedDefaultPresets()
        }
    }
    
    override suspend fun getPresetById(id: String): BinauralPreset? {
        return getPresets().first().find { it.id == id }
    }
    
    override suspend fun savePresets(presets: List<BinauralPreset>) {
        dataStore.edit { preferences ->
            preferences[PRESETS_KEY] = serializePresets(presets)
        }
    }
    
    override suspend fun addPreset(preset: BinauralPreset) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        currentPresets.add(preset)
        savePresets(currentPresets)
    }
    
    override suspend fun updatePreset(preset: BinauralPreset) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        val index = currentPresets.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            currentPresets[index] = preset
            savePresets(currentPresets)
        }
    }
    
    override suspend fun deletePreset(presetId: String) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        currentPresets.removeAll { it.id == presetId }
        savePresets(currentPresets)
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
    
    private fun serializePresets(presets: List<BinauralPreset>): String {
        val serializable = presets.map { preset ->
            SerializablePreset(
                id = preset.id,
                name = preset.name,
                curve = SerializableFrequencyCurve(
                    points = preset.frequencyCurve.points.map { point ->
                        SerializableFrequencyPoint(
                            hour = point.time.hour,
                            minute = point.time.minute,
                            carrierFrequency = point.carrierFrequency,
                            beatFrequency = point.beatFrequency
                        )
                    },
                    carrierRange = SerializableFrequencyRange(
                        preset.frequencyCurve.carrierRange.min,
                        preset.frequencyCurve.carrierRange.max
                    ),
                    beatRange = SerializableFrequencyRange(
                        preset.frequencyCurve.beatRange.min,
                        preset.frequencyCurve.beatRange.max
                    ),
                    interpolationType = preset.frequencyCurve.interpolationType.name,
                    splineTension = preset.frequencyCurve.splineTension
                ),
                relaxationModeSettings = SerializableRelaxationModeSettings(
                    enabled = preset.relaxationModeSettings.enabled,
                    mode = preset.relaxationModeSettings.mode.name,
                    carrierReductionPercent = preset.relaxationModeSettings.carrierReductionPercent,
                    beatReductionPercent = preset.relaxationModeSettings.beatReductionPercent,
                    gapBetweenRelaxationMinutes = preset.relaxationModeSettings.gapBetweenRelaxationMinutes,
                    transitionPeriodMinutes = preset.relaxationModeSettings.transitionPeriodMinutes,
                    relaxationDurationMinutes = preset.relaxationModeSettings.relaxationDurationMinutes,
                    smoothIntervalMinutes = preset.relaxationModeSettings.smoothIntervalMinutes
                ),
                createdAt = preset.createdAt,
                updatedAt = preset.updatedAt
            )
        }
        return json.encodeToString(serializable)
    }
    
    private fun deserializePresets(jsonString: String): List<BinauralPreset> {
        return try {
            val serializableList = json.decodeFromString<List<SerializablePreset>>(jsonString)
            serializableList.map { serializable ->
                BinauralPreset(
                    id = serializable.id,
                    name = serializable.name,
                    frequencyCurve = FrequencyCurve(
                        points = serializable.curve.points.map { point ->
                            FrequencyPoint(
                                time = LocalTime(point.hour, point.minute),
                                carrierFrequency = point.carrierFrequency,
                                beatFrequency = point.beatFrequency
                            )
                        },
                        carrierRange = serializable.curve.carrierRange?.let {
                            FrequencyRange(it.min, it.max)
                        } ?: FrequencyRange.DEFAULT_CARRIER,
                        beatRange = serializable.curve.beatRange?.let {
                            FrequencyRange(it.min, it.max)
                        } ?: FrequencyRange.DEFAULT_BEAT,
                        interpolationType = serializable.curve.interpolationType?.let {
                            try { InterpolationType.valueOf(it) } catch (e: Exception) { InterpolationType.LINEAR }
                        } ?: InterpolationType.LINEAR,
                        splineTension = serializable.curve.splineTension ?: 0.0f
                    ),
                    relaxationModeSettings = serializable.relaxationModeSettings?.let {
                        val gapMinutes = it.gapBetweenRelaxationMinutes ?:
                            (it.relaxationIntervalMinutes?.let { interval ->
                                val fullPeriod = 2 * it.transitionPeriodMinutes + it.relaxationDurationMinutes
                                (interval - fullPeriod).coerceAtLeast(0)
                            } ?: 24)
                        
                        RelaxationModeSettings(
                            enabled = it.enabled,
                            mode = try {
                                RelaxationMode.valueOf(it.mode)
                            } catch (e: Exception) {
                                RelaxationMode.SMOOTH
                            },
                            carrierReductionPercent = it.carrierReductionPercent,
                            beatReductionPercent = it.beatReductionPercent,
                            gapBetweenRelaxationMinutes = gapMinutes,
                            transitionPeriodMinutes = it.transitionPeriodMinutes,
                            relaxationDurationMinutes = it.relaxationDurationMinutes,
                            smoothIntervalMinutes = it.smoothIntervalMinutes
                        )
                    } ?: RelaxationModeSettings(),
                    createdAt = serializable.createdAt,
                    updatedAt = serializable.updatedAt
                )
            }
        } catch (e: Exception) {
            BinauralPreset.defaultPresets()
        }
    }
}