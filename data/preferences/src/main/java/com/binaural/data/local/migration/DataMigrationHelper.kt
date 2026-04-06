package com.binaural.data.local.migration

import android.content.Context
import android.util.Log
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
import com.binaural.data.local.dao.FrequencyPointDao
import com.binaural.data.local.dao.PresetDao
import com.binaural.data.local.entity.FrequencyPointEntity
import com.binaural.data.local.entity.PresetEntity
import com.binaural.data.preferences.SerializablePreset
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Помощник для миграции данных из DataStore в Room Database.
 * Выполняется однократно при первом запуске после обновления.
 */
@Singleton
class DataMigrationHelper @Inject constructor(
    private val presetDao: PresetDao,
    private val frequencyPointDao: FrequencyPointDao,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val TAG = "DataMigrationHelper"
        private val PRESETS_KEY = stringPreferencesKey("presets")
        private val MIGRATION_COMPLETED_KEY = stringPreferencesKey("room_migration_completed")
    }
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Выполнить миграцию, если она ещё не была выполнена.
     * @return true если миграция была выполнена, false если уже была выполнена ранее
     */
    suspend fun migrateIfNeeded(): Boolean {
        // Проверяем, была ли уже выполнена миграция
        val migrationCompleted = dataStore.data.first()[MIGRATION_COMPLETED_KEY]
        if (migrationCompleted == "true") {
            Log.d(TAG, "Migration already completed, skipping")
            return false
        }
        
        // Проверяем, есть ли данные в DataStore
        val presetsJson = dataStore.data.first()[PRESETS_KEY]
        if (presetsJson.isNullOrBlank()) {
            Log.d(TAG, "No data in DataStore, marking migration as completed")
            markMigrationCompleted()
            return false
        }
        
        // Проверяем, пуста ли база Room
        val roomCount = presetDao.getPresetCount()
        if (roomCount > 0) {
            Log.d(TAG, "Room database already has data, marking migration as completed")
            markMigrationCompleted()
            return false
        }
        
        // Выполняем миграцию
        return try {
            Log.d(TAG, "Starting migration from DataStore to Room")
            val presets = deserializePresets(presetsJson)
            migratePresets(presets)
            markMigrationCompleted()
            Log.d(TAG, "Migration completed successfully, migrated ${presets.size} presets")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            // Не отмечаем миграцию как завершённую, чтобы можно было повторить
            false
        }
    }
    
    private suspend fun migratePresets(presets: List<BinauralPreset>) {
        presets.forEach { preset ->
            // Вставляем пресет
            presetDao.insertPreset(preset.toEntity())
            
            // Вставляем точки
            val pointEntities = preset.frequencyCurve.points.map { point ->
                FrequencyPointEntity(
                    presetId = preset.id,
                    hour = point.time.hour,
                    minute = point.time.minute,
                    carrierFrequency = point.carrierFrequency,
                    beatFrequency = point.beatFrequency
                )
            }
            frequencyPointDao.insertPoints(pointEntities)
        }
    }
    
    private suspend fun markMigrationCompleted() {
        dataStore.edit { preferences ->
            preferences[MIGRATION_COMPLETED_KEY] = "true"
        }
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
            Log.e(TAG, "Failed to deserialize presets from DataStore", e)
            emptyList()
        }
    }
    
    /**
     * Преобразовать BinauralPreset в PresetEntity.
     */
    private fun BinauralPreset.toEntity(): PresetEntity {
        return PresetEntity(
            id = this.id,
            name = this.name,
            carrierRangeMin = this.frequencyCurve.carrierRange.min,
            carrierRangeMax = this.frequencyCurve.carrierRange.max,
            beatRangeMin = this.frequencyCurve.beatRange.min,
            beatRangeMax = this.frequencyCurve.beatRange.max,
            interpolationType = this.frequencyCurve.interpolationType.name,
            splineTension = this.frequencyCurve.splineTension,
            relaxationEnabled = this.relaxationModeSettings.enabled,
            relaxationMode = this.relaxationModeSettings.mode.name,
            carrierReductionPercent = this.relaxationModeSettings.carrierReductionPercent,
            beatReductionPercent = this.relaxationModeSettings.beatReductionPercent,
            gapBetweenRelaxationMinutes = this.relaxationModeSettings.gapBetweenRelaxationMinutes,
            transitionPeriodMinutes = this.relaxationModeSettings.transitionPeriodMinutes,
            relaxationDurationMinutes = this.relaxationModeSettings.relaxationDurationMinutes,
            smoothIntervalMinutes = this.relaxationModeSettings.smoothIntervalMinutes,
            createdAt = this.createdAt,
            updatedAt = this.updatedAt
        )
    }
}