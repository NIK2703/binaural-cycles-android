package com.binaural.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.binaural.core.domain.model.BinauralPreset
import com.binaural.data.preferences.R
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.FrequencyRange
import com.binaural.core.domain.model.InterpolationType
import com.binaural.core.domain.model.NormalizationType
import com.binaural.core.domain.model.RelaxationMode
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.model.VolumeNormalizationSettings
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.repository.SettingsRepository
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
    val carrierFrequency: Float,
    val beatFrequency: Float
)

/**
 * Сериализуемый диапазон частот
 */
@Serializable
data class SerializableFrequencyRange(
    val min: Float,
    val max: Float
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
    val type: String = "CHANNEL",  // NONE, CHANNEL, TEMPORAL
    val strength: Float = 1.0f
)

/**
 * Сериализуемые настройки режима расслабления
 */
@Serializable
data class SerializableRelaxationModeSettings(
    val enabled: Boolean = false,
    val mode: String = "SMOOTH",  // SMOOTH или ADVANCED
    val carrierReductionPercent: Int = 20,
    val beatReductionPercent: Int = 20,
    val gapBetweenRelaxationMinutes: Int = 24,
    val transitionPeriodMinutes: Int = 3,
    val relaxationDurationMinutes: Int = 15,
    // Интервал между точками для SMOOTH режима
    val smoothIntervalMinutes: Int = 30,
    // Для обратной совместимости со старыми пресетами
    val relaxationIntervalMinutes: Int? = null
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
    val relaxationModeSettings: SerializableRelaxationModeSettings? = null,
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
 * Репозиторий для хранения настроек бинауральных ритмов.
 * Имплементирует интерфейсы PresetRepository и SettingsRepository из domain слоя.
 */
@Singleton
class BinauralPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val context: Context
) : PresetRepository, SettingsRepository {
    companion object {
        private val FREQUENCY_CURVE_KEY = stringPreferencesKey("frequency_curve")
        private val VOLUME_KEY = stringPreferencesKey("volume")
        // Настройки перестановки каналов
        private val CHANNEL_SWAP_ENABLED_KEY = booleanPreferencesKey("channel_swap_enabled")
        private val CHANNEL_SWAP_INTERVAL_KEY = intPreferencesKey("channel_swap_interval")
        private val CHANNEL_SWAP_FADE_ENABLED_KEY = booleanPreferencesKey("channel_swap_fade_enabled")
        private val CHANNEL_SWAP_FADE_DURATION_KEY = intPreferencesKey("channel_swap_fade_duration")
        private val CHANNEL_SWAP_PAUSE_DURATION_KEY = intPreferencesKey("channel_swap_pause_duration")
        // Настройки нормализации громкости
        private val VOLUME_NORMALIZATION_TYPE_KEY = stringPreferencesKey("volume_normalization_type")
        private val VOLUME_NORMALIZATION_STRENGTH_KEY = floatPreferencesKey("volume_normalization_strength")
        // Частота дискретизации
        private val SAMPLE_RATE_KEY = intPreferencesKey("sample_rate")
        // Интервал генерации буфера (в минутах) - для оптимизации энергопотребления
        private val BUFFER_GENERATION_MINUTES_KEY = intPreferencesKey("buffer_generation_minutes")
        // Wavetable оптимизация (быстрая генерация синусоид)
        private val WAVETABLE_OPTIMIZATION_KEY = booleanPreferencesKey("wavetable_optimization")
        // Размер таблицы волн (качество)
        private val WAVETABLE_SIZE_KEY = intPreferencesKey("wavetable_size")
        // Автоматическое расширение границ графика при редактировании
        private val AUTO_EXPAND_GRAPH_RANGE_KEY = booleanPreferencesKey("auto_expand_graph_range")
        // Возобновление воспроизведения при подключении гарнитуры
        private val RESUME_ON_HEADSET_CONNECT_KEY = booleanPreferencesKey("resume_on_headset_connect")
        // Автовозобновление воспроизведения при запуске приложения
        private val AUTO_RESUME_ON_APP_START_KEY = booleanPreferencesKey("auto_resume_on_app_start")
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
    
    // ========== SettingsRepository implementation ==========
    
    /**
     * Получить громкость воспроизведения
     * По умолчанию 0.7 (70%)
     */
    override fun getVolume(): Flow<Float> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_KEY]?.toFloatOrNull() ?: 0.7f
        }
    }
    
    /**
     * Сохранить громкость воспроизведения
     * @param volume уровень громкости (0.0 - 1.0)
     */
    override suspend fun saveVolume(volume: Float) {
        dataStore.edit { preferences ->
            preferences[VOLUME_KEY] = volume.toString()
        }
    }
    
    /**
     * Получить частоту дискретизации
     */
    override fun getSampleRate(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[SAMPLE_RATE_KEY] ?: 22050 // 22050 по умолчанию
        }
    }
    
    /**
     * Сохранить частоту дискретизации
     */
    override suspend fun saveSampleRate(rate: Int) {
        dataStore.edit { preferences ->
            preferences[SAMPLE_RATE_KEY] = rate
        }
    }
    
    /**
     * Получить интервал генерации буфера в минутах
     */
    override fun getBufferGenerationMinutes(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[BUFFER_GENERATION_MINUTES_KEY] ?: 10 // 10 минут по умолчанию
        }
    }
    
    /**
     * Сохранить интервал генерации буфера
     */
    override suspend fun saveBufferGenerationMinutes(minutes: Int) {
        dataStore.edit { preferences ->
            preferences[BUFFER_GENERATION_MINUTES_KEY] = minutes.coerceIn(1, 60)
        }
    }
    
    /**
     * Получить все настройки перестановки каналов одним Flow
     */
    override fun getChannelSwapSettings(): Flow<ChannelSwapSettings> {
        return dataStore.data.map { preferences ->
            ChannelSwapSettings(
                enabled = preferences[CHANNEL_SWAP_ENABLED_KEY] ?: false,
                intervalSeconds = preferences[CHANNEL_SWAP_INTERVAL_KEY] ?: 60,
                fadeEnabled = true, // Всегда включено
                fadeDurationMs = preferences[CHANNEL_SWAP_FADE_DURATION_KEY]?.toLong() ?: 2000L,
                pauseDurationMs = preferences[CHANNEL_SWAP_PAUSE_DURATION_KEY]?.toLong() ?: 0L
            )
        }
    }
    
    /**
     * Сохранить все настройки перестановки каналов
     */
    override suspend fun saveChannelSwapSettings(settings: ChannelSwapSettings) {
        dataStore.edit { preferences ->
            preferences[CHANNEL_SWAP_ENABLED_KEY] = settings.enabled
            preferences[CHANNEL_SWAP_INTERVAL_KEY] = settings.intervalSeconds
            // fadeEnabled всегда true, не сохраняем
            preferences[CHANNEL_SWAP_FADE_DURATION_KEY] = settings.fadeDurationMs.toInt()
            preferences[CHANNEL_SWAP_PAUSE_DURATION_KEY] = settings.pauseDurationMs.toInt()
        }
    }
    
    // Методы для нормализации громкости
    
    /**
     * Получить тип нормализации громкости
     */
    fun getVolumeNormalizationType(): Flow<NormalizationType> {
        return dataStore.data.map { preferences ->
            preferences[VOLUME_NORMALIZATION_TYPE_KEY]?.let {
                try { NormalizationType.valueOf(it) } catch (e: Exception) { NormalizationType.TEMPORAL }
            } ?: NormalizationType.TEMPORAL
        }
    }
    
    suspend fun saveVolumeNormalizationType(type: NormalizationType) {
        dataStore.edit { preferences ->
            preferences[VOLUME_NORMALIZATION_TYPE_KEY] = type.name
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
    
    /**
     * Получить все настройки нормализации громкости одним Flow
     */
    override fun getVolumeNormalizationSettings(): Flow<VolumeNormalizationSettings> {
        return dataStore.data.map { preferences ->
            VolumeNormalizationSettings(
                type = preferences[VOLUME_NORMALIZATION_TYPE_KEY]?.let {
                    try { NormalizationType.valueOf(it) } catch (e: Exception) { NormalizationType.TEMPORAL }
                } ?: NormalizationType.TEMPORAL,
                strength = preferences[VOLUME_NORMALIZATION_STRENGTH_KEY] ?: 1.0f
            )
        }
    }
    
    /**
     * Сохранить все настройки нормализации громкости
     */
    override suspend fun saveVolumeNormalizationSettings(settings: VolumeNormalizationSettings) {
        dataStore.edit { preferences ->
            preferences[VOLUME_NORMALIZATION_TYPE_KEY] = settings.type.name
            preferences[VOLUME_NORMALIZATION_STRENGTH_KEY] = settings.strength
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
    
    // Методы для автоматического расширения границ графика
    
    /**
     * Получить статус автоматического расширения границ графика
     * @return true если границы расширяются автоматически при редактировании,
     *         false если значения ограничиваются заданными границами (по умолчанию)
     */
    override fun getAutoExpandGraphRange(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[AUTO_EXPAND_GRAPH_RANGE_KEY] ?: false // false по умолчанию - ограничивать значения
        }
    }
    
    /**
     * Сохранить статус автоматического расширения границ графика
     * @param enabled true = автоматически расширять границы при выходе за пределы
     *                false = ограничивать значения заданными границами
     */
    override suspend fun saveAutoExpandGraphRange(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_EXPAND_GRAPH_RANGE_KEY] = enabled
        }
    }
    
    // Методы для возобновления воспроизведения при подключении гарнитуры
    
    /**
     * Получить настройку возобновления воспроизведения при подключении гарнитуры
     * @return true если воспроизведение должно возобновляться автоматически
     */
    override fun getResumeOnHeadsetConnect(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[RESUME_ON_HEADSET_CONNECT_KEY] ?: false // По умолчанию выключено
        }
    }
    
    /**
     * Сохранить настройку возобновления воспроизведения при подключении гарнитуры
     * @param enabled true если воспроизведение должно возобновляться автоматически
     */
    override suspend fun saveResumeOnHeadsetConnect(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[RESUME_ON_HEADSET_CONNECT_KEY] = enabled
        }
    }
    
    // Методы для автовозобновления воспроизведения при запуске приложения
    
    /**
     * Получить настройку автовозобновления воспроизведения при запуске приложения
     * @return true если воспроизведение должно возобновляться автоматически при запуске
     */
    override fun getAutoResumeOnAppStart(): Flow<Boolean> {
        return dataStore.data.map { preferences ->
            preferences[AUTO_RESUME_ON_APP_START_KEY] ?: false // По умолчанию выключено
        }
    }
    
    /**
     * Сохранить настройку автовозобновления воспроизведения при запуске приложения
     * @param enabled true если воспроизведение должно возобновляться автоматически при запуске
     */
    override suspend fun saveAutoResumeOnAppStart(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AUTO_RESUME_ON_APP_START_KEY] = enabled
        }
    }
    
    // Методы для работы с пресетами
    
    // ========== PresetRepository implementation ==========
    
    /**
     * Получить список всех пресетов
     */
    override fun getPresets(): Flow<List<BinauralPreset>> {
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
    
    /**
     * Сохранить список пресетов
     */
    suspend fun savePresets(presets: List<BinauralPreset>) {
        dataStore.edit { preferences ->
            preferences[PRESETS_KEY] = serializePresets(presets)
        }
    }
    
    /**
     * Получить пресет по ID
     */
    override suspend fun getPresetById(id: String): BinauralPreset? {
        return getPresets().first().find { it.id == id }
    }
    
    /**
     * Получить ID активного пресета
     */
    override fun getActivePresetId(): Flow<String?> {
        return dataStore.data.map { preferences ->
            preferences[ACTIVE_PRESET_ID_KEY]
        }
    }
    
    /**
     * Сохранить ID активного пресета
     */
    override suspend fun setActivePresetId(id: String?) {
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
    override suspend fun addPreset(preset: BinauralPreset) {
        val currentPresets = getPresets().map { it.toMutableList() }.first()
        currentPresets.add(preset)
        savePresets(currentPresets)
    }
    
    /**
     * Обновить пресет
     */
    override suspend fun updatePreset(preset: BinauralPreset) {
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
    override suspend fun deletePreset(presetId: String) {
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
                        // Обратная совместимость: если есть старый relaxationIntervalMinutes, 
                        // вычисляем gapBetweenRelaxationMinutes из него
                        val gapMinutes = it.gapBetweenRelaxationMinutes ?: 
                            (it.relaxationIntervalMinutes?.let { interval ->
                                // Старая логика: interval = полный цикл
                                // Новый gap = interval - (2*transition + duration)
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
