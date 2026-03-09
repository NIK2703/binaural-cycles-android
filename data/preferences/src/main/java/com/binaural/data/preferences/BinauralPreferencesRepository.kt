package com.binaural.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.binaural.core.audio.model.BinauralPreset
import com.binaural.data.preferences.R
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.VolumeNormalizationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сериализуемая точка частоты для хранения
 */
@Serializable
data class SerializableFrequencyPoint(
    val hour: Int,
    val minute: Int,
    val carrierFrequency: Double,
    val beatFrequency: Double
)

/**
 * Сериализуемый диапазон частот
 */
@Serializable
data class SerializableFrequencyRange(
    val min: Double,
    val max: Double
)

/**
 * Сериализуемая кривая частоты для хранения
 */
@Serializable
data class SerializableFrequencyCurve(
    val points: List<SerializableFrequencyPoint>,
    val carrierRange: SerializableFrequencyRange? = null,
    val beatRange: SerializableFrequencyRange? = null,
    val interpolationType: String? = null,
    val splineTension: Float? = null
)

/**
 * Сериализуемые настройки перестановки каналов
 */
@Serializable
data class SerializableChannelSwapSettings(
    val enabled: Boolean = false,
    val intervalSeconds: Int = 300,
    val fadeEnabled: Boolean = true,
    val fadeDurationMs: Long = 1000L
)

/**
 * Сериализуемые настройки нормализации громкости
 */
@Serializable
data class SerializableVolumeNormalizationSettings(
    val enabled: Boolean = true,
    val strength: Float = 0.5f
)

/**
 * Сериализуемый пресет для хранения
 */
@Serializable
data class SerializablePreset(
    val id: String,
    val name: String,
    val curve: SerializableFrequencyCurve,
    val channelSwapSettings: SerializableChannelSwapSettings? = null,
    val volumeNormalizationSettings: SerializableVolumeNormalizationSettings? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Сериализуемый список пресетов
 */
@Serializable
data class SerializablePresetList(
    val presets: List<SerializablePreset>,
    val activePresetId: String? = null
)

/**
 * Репозиторий для хранения настроек бинауральных ритмов
 */
@Singleton
class BinauralPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val context: Context
) {
    companion object {
        private val FREQUENCY_CURVE_KEY = stringPreferencesKey("frequency_curve")
        private val VOLUME_KEY = stringPreferencesKey("volume")
        // Настройки перестановки каналов
        private val CHANNEL_SWAP_ENABLED_KEY = booleanPreferencesKey("channel_swap_enabled")
        private val CHANNEL_SWAP_INTERVAL_KEY = intPreferencesKey("channel_swap_interval")
        private val CHANNEL_SWAP_FADE_ENABLED_KEY = booleanPreferencesKey("channel_swap_fade_enabled")
        private val CHANNEL_SWAP_FADE_DURATION_KEY = intPreferencesKey("channel_swap_fade_duration")
        // Настройки нормализации громкости
        private val VOLUME_NORMALIZATION_ENABLED_KEY = booleanPreferencesKey("volume_normalization_enabled")
        private val VOLUME_NORMALIZATION_STRENGTH_KEY = floatPreferencesKey("volume_normalization_strength")
        // Частота дискретизации
        private val SAMPLE_RATE_KEY = intPreferencesKey("sample_rate")
        // Интервал обновления частот (мс)
        private val FREQUENCY_UPDATE_INTERVAL_KEY = intPreferencesKey("frequency_update_interval")
        // Wavetable оптимизация (быстрая генерация синусоид)
        private val WAVETABLE_OPTIMIZATION_KEY = booleanPreferencesKey("wavetable_optimization")
        // Размер таблицы волн (качество)
        private val WAVETABLE_SIZE_KEY = intPreferencesKey("wavetable_size")
        // Пресеты
        private val PRESETS_KEY = stringPreferencesKey("presets")
        private val ACTIVE_PRESET_ID_KEY = stringPreferencesKey("active_preset_id")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Получить кривую частот
     */
    fun getFrequencyCurve(): Flow<FrequencyCurve> {
        return dataStore.data.map { preferences ->
            preferences[FREQUENCY_CURVE_KEY]?.let { jsonString ->
                deserializeCurve(jsonString)
            } ?: FrequencyCurve.defaultCurve()
        }
    }

    /**
     * Сохранить кривую частот
     */
    suspend fun saveFrequencyCurve(curve: FrequencyCurve) {
        dataStore.edit { preferences ->
            preferences[FREQUENCY_CURVE_KEY] = serializeCurve(curve)
        }
    }

    private fun serializeCurve(curve: FrequencyCurve): String {
        val serializable = SerializableFrequencyCurve(
            points = curve.points.map { point ->
                SerializableFrequencyPoint(
                    hour = point.time.hour,
                    minute = point.time.minute,
                    carrierFrequency = point.carrierFrequency,
                    beatFrequency = point.beatFrequency
                )
            },
            carrierRange = SerializableFrequencyRange(curve.carrierRange.min, curve.carrierRange.max),
            beatRange = SerializableFrequencyRange(curve.beatRange.min, curve.beatRange.max),
            interpolationType = curve.interpolationType.name,
            splineTension = curve.splineTension
        )
        return json.encodeToString(serializable)
    }

    private fun deserializeCurve(jsonString: String): FrequencyCurve {
        return try {
            val serializable = json.decodeFromString<SerializableFrequencyCurve>(jsonString)
            FrequencyCurve(
                points = serializable.points.map { point ->
                    FrequencyPoint(
                        time = LocalTime(point.hour, point.minute),
                        carrierFrequency = point.carrierFrequency,
                        beatFrequency = point.beatFrequency
                    )
                },
                carrierRange = serializable.carrierRange?.let { 
                    FrequencyRange(it.min, it.max) 
                } ?: FrequencyRange.DEFAULT_CARRIER,
                beatRange = serializable.beatRange?.let { 
                    FrequencyRange(it.min, it.max) 
                } ?: FrequencyRange.DEFAULT_BEAT,
                interpolationType = serializable.interpolationType?.let {
                    try { InterpolationType.valueOf(it) } catch (e: Exception) { InterpolationType.LINEAR }
                } ?: InterpolationType.LINEAR,
                splineTension = serializable.splineTension ?: 0.0f
            )
        } catch (e: Exception) {
            FrequencyCurve.defaultCurve()
        }
    }
    
    // Методы для перестановки каналов
    
    fun getChannelSwapEnabled(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[CHANNEL_SWAP_ENABLED_KEY] ?: false
        }
    }
    
    suspend fun saveChannelSwapEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_ENABLED_KEY] = enabled
        }
    }
    
    fun getChannelSwapInterval(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[CHANNEL_SWAP_INTERVAL_KEY] ?: 60 // 1 минута по умолчанию
        }
    }
    
    suspend fun saveChannelSwapInterval(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_INTERVAL_KEY] = seconds
        }
    }
    
    fun getChannelSwapFadeEnabled(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[CHANNEL_SWAP_FADE_ENABLED_KEY] ?: true // включено по умолчанию
        }
    }
    
    suspend fun saveChannelSwapFadeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_FADE_ENABLED_KEY] = enabled
        }
    }
    
    fun getChannelSwapFadeDuration(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[CHANNEL_SWAP_FADE_DURATION_KEY] ?: 2000 // 2 секунды по умолчанию
        }
    }
    
    suspend fun saveChannelSwapFadeDuration(durationMs: Int) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_FADE_DURATION_KEY] = durationMs
        }
    }
    
    // Методы для нормализации громкости
    
    fun getVolumeNormalizationEnabled(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_NORMALIZATION_ENABLED_KEY] ?: true  // Включено по умолчанию
        }
    }
    
    suspend fun saveVolumeNormalizationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VOLUME_NORMALIZATION_ENABLED_KEY] = enabled
        }
    }
    
    fun getVolumeNormalizationStrength(): Flow<Float> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_NORMALIZATION_STRENGTH_KEY] ?: 1.0f // 100% по умолчанию
        }
    }
    
    suspend fun saveVolumeNormalizationStrength(strength: Float) {
        dataStore.edit { preferences ->
            preferences[VOLUME_NORMALIZATION_STRENGTH_KEY] = strength
        }
    }
    
    // Методы для частоты дискретизации

    fun getSampleRate(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[SAMPLE_RATE_KEY] ?: 22050 // 22050 по умолчанию (оптимально для бинауральных ритмов)
        }
    }
    
    suspend fun saveSampleRate(rate: Int) {
        dataStore.edit { preferences ->
            preferences[SAMPLE_RATE_KEY] = rate
        }
    }
    
    // Методы для интервала обновления частот
    
    /**
     * Получить интервал обновления частот в миллисекундах
     * По умолчанию 1000мс (1 секунда)
     */
    fun getFrequencyUpdateInterval(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[FREQUENCY_UPDATE_INTERVAL_KEY] ?: 10000 // 10 секунд по умолчанию
        }
    }
    
    /**
     * Сохранить интервал обновления частот
     * @param intervalMs интервал в миллисекундах (1000-60000)
     */
    suspend fun saveFrequencyUpdateInterval(intervalMs: Int) {
        dataStore.edit { preferences ->
            preferences[FREQUENCY_UPDATE_INTERVAL_KEY] = intervalMs.coerceIn(1000, 60000)
        }
    }

    // Методы для wavetable оптимизации

    /**
     * Получить статус wavetable оптимизации
     * @return true если оптимизация включена (быстрая генерация с интерполяцией)
     */
    fun getWavetableOptimizationEnabled(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[WAVETABLE_OPTIMIZATION_KEY] ?: true // true = wavetable по умолчанию
        }
    }

    /**
     * Сохранить статус wavetable оптимизации
     * @param enabled true = использовать wavetable (быстрее, возможен фон)
     *                false = использовать Math.sin (медленнее, чище)
     */
    suspend fun saveWavetableOptimizationEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[WAVETABLE_OPTIMIZATION_KEY] = enabled
        }
    }

    /**
     * Получить размер таблицы волн (качество)
     * @return размер таблицы (512, 1024, 2048, 4096)
     */
    fun getWavetableSize(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[WAVETABLE_SIZE_KEY] ?: 2048 // 2048 по умолчанию (с интерполяцией ≡ 65536)
        }
    }

    /**
     * Сохранить размер таблицы волн
     * @param size размер таблицы (512, 1024, 2048, 4096)
     */
    suspend fun saveWavetableSize(size: Int) {
        dataStore.edit { preferences ->
            preferences[WAVETABLE_SIZE_KEY] = size
        }
    }
    
    // Методы для громкости
    
    /**
     * Получить громкость воспроизведения
     * По умолчанию 0.7 (70%)
     */
    fun getVolume(): Flow<Float> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_KEY]?.toFloatOrNull() ?: 0.7f
        }
    }
    
    /**
     * Сохранить громкость воспроизведения
     * @param volume уровень громкости (0.0 - 1.0)
     */
    suspend fun saveVolume(volume: Float) {
        dataStore.edit { preferences ->
            preferences[VOLUME_KEY] = volume.toString()
        }
    }
    
    // Методы для работы с пресетами
    
    /**
     * Получить список всех пресетов
     */
    fun getPresets(): Flow<List<BinauralPreset>> {
        return dataStore.data.map { preferences ->
            preferences[PRESETS_KEY]?.let { jsonString ->
                deserializePresets(jsonString)
            } ?: getLocalizedDefaultPresets()
        }
    }
    
    /**
     * Получить локализованные пресеты по умолчанию
     */
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
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(0, 0, carrierFrequency = 220.0, beatFrequency = 1.5),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(3, 0, carrierFrequency = 250.0, beatFrequency = 5.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(6, 0, carrierFrequency = 340.0, beatFrequency = 9.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(9, 0, carrierFrequency = 400.0, beatFrequency = 18.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(12, 0, carrierFrequency = 380.0, beatFrequency = 14.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(15, 0, carrierFrequency = 440.0, beatFrequency = 40.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(18, 0, carrierFrequency = 300.0, beatFrequency = 7.5),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(21, 0, carrierFrequency = 240.0, beatFrequency = 4.0),
                    )
                )
            ),
            BinauralPreset(
                id = BinauralPreset.DAILY_CYCLE_PRESET_ID,
                name = context.getString(R.string.preset_daily_cycle),
                frequencyCurve = FrequencyCurve(
                    points = listOf(
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(0, 0, carrierFrequency = 200.0, beatFrequency = 2.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(3, 0, carrierFrequency = 200.0, beatFrequency = 3.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(6, 0, carrierFrequency = 300.0, beatFrequency = 10.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(9, 0, carrierFrequency = 400.0, beatFrequency = 18.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(12, 0, carrierFrequency = 300.0, beatFrequency = 6.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(15, 0, carrierFrequency = 400.0, beatFrequency = 25.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(18, 0, carrierFrequency = 300.0, beatFrequency = 9.0),
                        com.binaural.core.audio.model.FrequencyPoint.fromHours(21, 0, carrierFrequency = 250.0, beatFrequency = 5.0),
                    )
                )
            )
        )
    }
    
    /**
     * Сохранить список пресетов
     */
    suspend fun savePresets(presets: List<BinauralPreset>) {
        dataStore.edit { preferences ->
            preferences[PRESETS_KEY] = serializePresets(presets)
        }
    }
    
    /**
     * Получить ID активного пресета
     */
    fun getActivePresetId(): Flow<String?> {
        return dataStore.data.map { preferences ->
            preferences[ACTIVE_PRESET_ID_KEY]
        }
    }
    
    /**
     * Сохранить ID активного пресета
     */
    suspend fun saveActivePresetId(id: String?) {
        dataStore.edit { preferences ->
            if (id != null) {
                preferences[ACTIVE_PRESET_ID_KEY] = id
            } else {
                preferences.remove(ACTIVE_PRESET_ID_KEY)
            }
        }
    }
    
    /**
     * Добавить новый пресет
     */
    suspend fun addPreset(preset: BinauralPreset) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        currentPresets.add(preset)
        savePresets(currentPresets)
    }
    
    /**
     * Обновить пресет
     */
    suspend fun updatePreset(preset: BinauralPreset) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        val index = currentPresets.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            currentPresets[index] = preset
            savePresets(currentPresets)
        }
    }
    
    /**
     * Удалить пресет
     */
    suspend fun deletePreset(presetId: String) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        currentPresets.removeAll { it.id == presetId }
        savePresets(currentPresets)
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
                channelSwapSettings = SerializableChannelSwapSettings(
                    enabled = preset.channelSwapSettings.enabled,
                    intervalSeconds = preset.channelSwapSettings.intervalSeconds,
                    fadeEnabled = preset.channelSwapSettings.fadeEnabled,
                    fadeDurationMs = preset.channelSwapSettings.fadeDurationMs
                ),
                volumeNormalizationSettings = SerializableVolumeNormalizationSettings(
                    enabled = preset.volumeNormalizationSettings.enabled,
                    strength = preset.volumeNormalizationSettings.strength
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
                    channelSwapSettings = serializable.channelSwapSettings?.let {
                        ChannelSwapSettings(
                            enabled = it.enabled,
                            intervalSeconds = it.intervalSeconds,
                            fadeEnabled = it.fadeEnabled,
                            fadeDurationMs = it.fadeDurationMs
                        )
                    } ?: ChannelSwapSettings(),
                    volumeNormalizationSettings = serializable.volumeNormalizationSettings?.let {
                        VolumeNormalizationSettings(
                            enabled = it.enabled,
                            strength = it.strength
                        )
                    } ?: VolumeNormalizationSettings(),
                    createdAt = serializable.createdAt,
                    updatedAt = serializable.updatedAt
                )
            }
        } catch (e: Exception) {
            BinauralPreset.defaultPresets()
        }
    }
}
