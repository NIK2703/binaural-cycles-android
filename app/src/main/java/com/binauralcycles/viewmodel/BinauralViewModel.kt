package com.binauralcycles.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binauralcycles.R
import com.binauralcycles.service.BinauralPlaybackService
import com.binauralcycles.util.BatteryOptimizationHelper
import com.binaural.core.audio.model.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.PointIntentMemory
import com.binaural.core.audio.model.BinauralPreset
import com.binaural.core.audio.model.ChannelSwapMode
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.ChannelSwapTrendPoints
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyMath
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.NormalizationType
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
import com.binaural.core.audio.model.VolumeNormalizationSettings
import com.binaural.core.audio.stream.PacketMemoryBudget
import com.binaural.data.preferences.BinauralPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Высокочастотная телеметрия воспроизведения (обновляется 1–2 раза в секунду).
 *
 * Вынесена из [BinauralUiState] сознательно: `uiState` коллектится в корне
 * навигации и во всех экранах, поэтому каждое изменение частот перекомпоновало
 * ВСЁ дерево целиком — включая списки пресетов и экраны настроек, которым эти
 * частоты вообще не нужны. Разделение потоков сужает recomposition до тех
 * нескольких компонентов, которые реально рисуют телеметрию.
 *
 * Частоты и время — это «живые» значения для индикаторов; низкочастотные поля
 * (presets, настройки, редактируемая кривая) остаются в [BinauralUiState].
 */
data class PlaybackTelemetry(
    val isPlaying: Boolean = false,
    val currentBeatFrequency: Float = 0.0f,
    val currentCarrierFrequency: Float = 0.0f,
    val isChannelsSwapped: Boolean = false,
    val currentTime: LocalTime = LocalTime(12, 0),
    /**
     * СКРАБ: сдвиг оси времени суток, секунды [0, 86400). 0 — обычный режим.
     *
     * НЕ квантуется: это не время, а расстояние между [currentTime] и
     * [realTime] на графике, и округление сделало бы его рваным.
     */
    val scrubOffsetSeconds: Int = 0,
    /**
     * СКРАБ: РЕАЛЬНЫЙ момент времени суток — без сдвига предпросмотра.
     *
     * Отдельное поле, а не «[currentTime] минус [scrubOffsetSeconds]»:
     * ось и сдвиг доезжают до UI разными StateFlow в непредсказуемом
     * порядке, и вычитание смешивало бы разновозрастные значения — серая
     * линия уезжала на величину сдвига (§14.7 плана). Менеджер публикует
     * оба времени из одной базы, в одном вызове.
     *
     * Квантуется до 60 с тем же правилом, что и [currentTime]: оба значения
     * округляются вниз, поэтому расстояние между ними отличается от сдвига
     * не более чем на минуту (≈0.2 px ширины графика) и не «дышит» —
     * линии не дрожат друг относительно друга.
     *
     * `null` — «реальное сейчас ещё неизвестно» (сервис не подключён, поток
     * ещё ничего не опубликовал). Намеренно НЕ `LocalTime(12, 0)`: у времени
     * нет осмысленного значения по умолчанию, и любое такое значение
     * ОТРИСОВЫВАЕТСЯ как настоящее — серая линия встала бы в середину графика
     * и выглядела бы как правдоподобный момент, а не как отсутствие данных.
     * Получатель в этом случае подставляет ось (линии совпадают, серой нет).
     */
    val realTime: LocalTime? = null
)

data class BinauralUiState(
    // Список пресетов
    val presets: List<BinauralPreset> = emptyList(),
    val activePreset: BinauralPreset? = null,
    // Флаг воспроизведения дублируется в PlaybackTelemetry — здесь он НЕ хранится,
    // иначе любое его изменение снова перекомпонует всё дерево (см. Navigation).
    val volume: Float = 1.0f,
    val selectedPointIndex: Int? = null,
    // НОВОЕ: debug virtual time
    val debugVirtualTimeEnabled: Boolean = false,
    val debugTimeScale: Float = 1.0f,
    val debugVirtualTimeRunning: Boolean = true,
    // Редактируемая кривая (для экрана редактирования)
    val editingFrequencyCurve: FrequencyCurve? = null,
    // Исходный шаблон нового пресета: с ним сравнивается редактируемая кривая,
    // чтобы «несохранённые изменения» не срабатывали, когда пользователь
    // открыл создание пресета, но ничего не поменял в шаблоне. null — не новый пресет.
    val newPresetBaselineCurve: FrequencyCurve? = null,
    // ID редактируемого пресета (null для нового пресета)
    val editingPresetId: String? = null,
    // Диапазоны частот для редактирования
    val carrierRange: FrequencyRange = FrequencyRange.DEFAULT_CARRIER,
    val beatRange: FrequencyRange = FrequencyRange.DEFAULT_BEAT,
    // Настройки режима расслабления (для экрана редактирования)
    val editingRelaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    // Глобальные настройки нормализации громкости (не зависят от пресета)
    val volumeNormalizationSettings: VolumeNormalizationSettings = VolumeNormalizationSettings(),
    // Глобальные настройки перестановки каналов (не зависят от пресета)
    val channelSwapSettings: ChannelSwapSettings = ChannelSwapSettings(),
    // Общие настройки приложения
    val sampleRate: SampleRate = SampleRate.LOW,
    // Интервал генерации буфера в минутах (для оптимизации энергопотребления)
    val bufferGenerationMinutes: Int = 10,
    // Автоматическое расширение границ графика при редактировании (по умолчанию выключено)
    val autoExpandGraphRange: Boolean = false,
    // Флаг подключения к сервису
    val isServiceConnected: Boolean = false,
    // Флаг блокировки навигации во время SharedTransition анимации
    val isSharedTransitionRunning: Boolean = false,
    // Возобновление воспроизведения при подключении гарнитуры
    val resumeOnHeadsetConnect: Boolean = false,
    // Автовозобновление воспроизведения при запуске приложения
    val autoResumeOnAppStart: Boolean = false,
    // Приложение уже добавлено в исключения фонового энергосбережения (состояние системы,
    // а не настройка: перечитывается при каждом возврате приложения на экран)
    val isIgnoringBatteryOptimizations: Boolean = false,
    // Стартовое напоминание об энергосбережении уже показано.
    // null — значение ещё не прочитано из DataStore: до чтения напоминание не показываем,
    // иначе у тех, кто уже его закрыл, диалог мелькнёт на старте.
    val batteryOptimizationPromptShown: Boolean? = null,
    // true — прямо сейчас нужно показать стартовое напоминание
    val showBatteryOptimizationPrompt: Boolean = false,
    // Диалог «подключите наушники»
    val showHeadphoneDialog: Boolean = false,
    // ID пресета, который нужно запустить после подтверждения
    val pendingPresetId: String? = null,
    // Справка по управлению в редакторе уже показана (закрыта по «Понятно»).
    // null — значение ещё не прочитано из DataStore: до чтения справку не открываем
    // автоматически, чтобы диалог не мелькнул на старте экрана до загрузки флага.
    // false — ещё не показана, открыть автоматически при первом входе.
    // true — показана, больше не открывать автоматически.
    val gesturesHelpShown: Boolean? = null
)

/**
 * Дебаунс серии однотипных изменений настроек (протяжка слайдера, перемотка
 * debug-времени): пока серия идёт, ни одного кроссфейда не запускается —
 * применится только последнее значение. Ровно столько же ждал и старый
 * «stopWithFade -> play», но там это время звучал не старый поток, а тишина.
 */
private const val SETTINGS_FADE_DEBOUNCE_MS = 300L

/**
 * Интервал генерации буфера для потоков, пересобираемых правкой опций в
 * редакторе пресета: фиксированная 1 минута вместо пользовательской настройки.
 *
 * Правка опций редактируемого активного пресета слышна сразу — движок
 * пересобирает звучащий поток кроссфейдом на каждое изменение. При быстрой
 * смене опций (перетаскивание точки, слайдеры расслабления) каждая пересборка
 * генерировала бы буфер на весь пользовательский интервал (десять минут по
 * умолчанию — сотни мегабайт PCM и тяжёлая подготовка на каждый тик слайдера).
 * Минутный пакет делает такую пересборку дешёвой. Пользовательское значение
 * возвращается при завершении сессии редактирования
 * ([BinauralViewModel.restoreUserBufferInterval]) и никуда не сохраняется.
 */
private const val EDITOR_PREVIEW_BUFFER_INTERVAL_MS = 60_000

/**
 * Сборка [BinauralConfig] из кривой пресета и ГЛОБАЛЬНЫХ настроек — тех, что
 * не лежат в пресете (громкость, перестановка каналов, нормализация).
 *
 * Вынесена из [BinauralViewModel] потому, что ровно ту же сборку повторяет
 * отладочный исполнитель команд: он работает БЕЗ ViewModel (экран выключен,
 * активити уничтожена) и читает глобальные настройки напрямую из репозитория.
 * Две копии этой сборки разошлись бы при первом же новом поле конфига.
 */
internal fun buildPlaybackConfig(
    frequencyCurve: FrequencyCurve,
    volume: Float,
    channelSwap: ChannelSwapSettings,
    normalization: VolumeNormalizationSettings
): BinauralConfig = BinauralConfig(
    frequencyCurve = frequencyCurve,
    volume = volume,
    channelSwapEnabled = channelSwap.enabled,
    channelSwapIntervalSeconds = channelSwap.intervalSeconds,
    channelSwapMode = channelSwap.mode,
    channelSwapTrendPoints = channelSwap.trendPoints,
    channelSwapFadeEnabled = channelSwap.fadeEnabled,
    channelSwapFadeDurationMs = channelSwap.fadeDurationMs,
    channelSwapPauseDurationMs = channelSwap.pauseDurationMs,
    normalizationType = normalization.type,
    volumeNormalizationStrength = normalization.strength
)

@HiltViewModel
class BinauralViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: BinauralPreferencesRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private companion object {
        /**
         * Ключ цели редактирования в [SavedStateHandle].
         *
         * При пересоздании ViewModel (сворачивание приложения, блокировка/
         * разблокировка экрана, смерть процесса) [editingFrequencyCurve]
         * теряется, а `LaunchedEffect` экрана вызывает `startEditingPreset`,
         * который на ещё пустом списке `presets` (грузится асинхронно) отрабатывает
         * впустую и больше не перезапускается — виджет графика пропадает. Цель
         * редактирования, сохранённая здесь, переживает пересоздание, и сессия
         * восстанавливается, как только пресеты догрузятся (см. [maybeRestoreEditingSession]).
         */
        private const val KEY_EDITING_TARGET = "editing_target"
        /** Специальное значение цели: создаётся новый пресет (presetId отсутствует). */
        private const val EDITING_TARGET_NEW = "NEW"
    }

    private val _uiState = MutableStateFlow(BinauralUiState())
    val uiState: StateFlow<BinauralUiState> = _uiState.asStateFlow()

    /**
     * Память ЖЕЛАЕМЫХ значений точек редактора: несущей и частоты биений.
     *
     * Хранит не то, что получилось после обрезки у границы, а то, что задал
     * пользователь (или что сохранено в пресете, если он ещё ничего не менял).
     * Эффективное значение каждый раз выводится из желаемого — поэтому точка,
     * отодвинутая от границы, возвращает свою частоту биений, а при расширении
     * диапазона точки возвращаются туда, где их оставил пользователь.
     *
     * Живёт только внутри сессии редактирования: наполняется в
     * [startEditingPreset]/[startNewPreset], очищается в [cancelEditing]/
     * [finishEditing].
     */
    private val pointIntent = PointIntentMemory()

    /**
     * Отдельный поток телеметрии: его читают только те компоненты, которые
     * реально рисуют частоты/время. Изменения здесь НЕ перекомпонуют корень
     * навигации и экраны, подписанные на `uiState`.
     */
    private val _telemetry = MutableStateFlow(PlaybackTelemetry())
    val telemetry: StateFlow<PlaybackTelemetry> = _telemetry.asStateFlow()

    /**
     * Не-квантованное время суток — исключительно для DEBUG-панели управления
     * виртуальным временем (её слайдеру нужна посекундная точность). Берём
     * [BinauralPlaybackService.currentTimeOfDaySeconds] напрямую, минуя
     * 60-секундное квантование [telemetry] (см. U1). В release эта панель не
     * рендерится, поэтому в production лишних перекомпозиций от этого потока нет
     * (сборка начинается только при подписке из debug-панели).
     */
    val debugCurrentTime: StateFlow<LocalTime> =
        BinauralPlaybackService.currentTimeOfDaySeconds
            .map { LocalTime.fromSecondOfDay(it.coerceIn(0, 86399)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = LocalTime(12, 0)
            )
    
    // Ссылка на сервис (может быть null если сервис не привязан)
    private var playbackService: BinauralPlaybackService? = null
    
    // Флаг для отслеживания, было ли обработано автовозобновление
    private var autoResumeHandled = false
    
    // Job для отмены предыдущего перезапуска при быстром переключении настроек
    private var restartJob: kotlinx.coroutines.Job? = null

    /** Настройка, значение которой уже прочитано из DataStore (см. [loadedSettings]). */
    private enum class Setting {
        /** Список пресетов и активный пресет (кривая + режим расслабления). */
        PRESETS,
        VOLUME,
        SAMPLE_RATE,
        BUFFER_MINUTES,
        CHANNEL_SWAP,
        NORMALIZATION,
        HEADSET_RESUME
    }

    /**
     * Что уже прочитано из DataStore.
     *
     * Причина появления: при возврате в свёрнутое приложение активити и
     * ViewModel пересоздаются, а сервис ПРОДОЛЖАЕТ ИГРАТЬ. Свежий ViewModel
     * честно пушил в живой движок дефолты из [BinauralUiState] — громкость
     * 1.0, SampleRate.LOW, дефолтную кривую, отключённую нормализацию. Для
     * движка такой пуш неотличим от команды пользователя «всё поменяй», и он
     * пересобирал поток: кроссфейд (или два — дефолтами и настоящими
     * значениями, если чтение DataStore не уложилось в окно фейда) плюс
     * скачок громкости. Ровно тот «перезапуск», на который жаловались.
     *
     * Дедупликация внутри менеджера тут не помощник: она отсекает ПОВТОРЫ,
     * а дефолт для движка — полноценное другое значение.
     *
     * Повторные пуши настоящих значений дешевы: менеджер сравнивает их со
     * своими полями и не делает ничего.
     */
    private val loadedSettings = java.util.EnumSet.noneOf(Setting::class.java)

    /** Прочитано ли всё, из чего собирается [BinauralConfig]. */
    private val settingsReady: Boolean get() = loadedSettings.size == Setting.entries.size

    /**
     * Отметить настройку прочитанной.
     *
     * Конфиг зависит от четырёх источников сразу (кривая/расслабление,
     * громкость, перестановка каналов, нормализация), поэтому уходит один раз —
     * когда прочитан последний из них. Раньше он собирался четырежды (по одному
     * разу на каждый источник), и три из этих сборок были заведомо неполными.
     */
    private fun onSettingLoaded(setting: Setting) {
        val becameReady = loadedSettings.add(setting) && settingsReady
        if (becameReady) {
            android.util.Log.d("BinauralViewModel", "Настройки прочитаны полностью — собираем конфиг")
            updateAudioConfig()
        }
    }

    // Переключение пресета — синхронный кроссфейд: UPDATE config уходит в beginHandoff(),
    // NEXT начинает фейд-ин одновременно с фейд-аутом CURRENT. Никаких корутин, задержек
    // и стопов между пресетами, поэтому single-flight-джобы и guard'ы больше не нужны.
    
    // ServiceConnection для привязки к сервису
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BinauralPlaybackService.LocalBinder
            playbackService = binder?.getService()
            _uiState.update { it.copy(isServiceConnected = true) }
            android.util.Log.d("BinauralViewModel", "Service connected")
            
            // При подключении сервиса применяем те настройки, которые УЖЕ
            // прочитаны из DataStore. Сервис мог подключиться и раньше (тогда
            // их применит коллектор), и позже (тогда они ждут здесь).
            //
            // Пушить НЕпрочитанное нельзя ни в коем случае: см. [loadedSettings].
            // До правки здесь уходили ровно дефолты — потому что bindService
            // отвечает на главной нити за миллисекунды, а DataStore читает
            // диск десятки миллисекунд, и гонку выигрывал bind.
            val state = _uiState.value
            if (Setting.PRESETS in loadedSettings) {
                android.util.Log.d("BinauralViewModel", "Updating audio config for active preset: ${state.activePreset?.name}, channelSwap=${state.channelSwapSettings.enabled}")
                // Устанавливаем название активного пресета для уведомления
                playbackService?.setCurrentPresetName(state.activePreset?.name)
                playbackService?.setCurrentPresetId(state.activePreset?.id)
            }
            // Конфиг — в конце: он единственный, кто реально меняет звук, и
            // внутри него лежит guard на «все настройки прочитаны».
            updateAudioConfig()

            // Устанавливаем настройки, которые могли быть загружены до подключения сервиса
            if (Setting.BUFFER_MINUTES in loadedSettings) {
                playbackService?.setFrequencyUpdateInterval(state.bufferGenerationMinutes * 60 * 1000)
            }
            if (Setting.VOLUME in loadedSettings) playbackService?.setVolume(state.volume)
            if (Setting.SAMPLE_RATE in loadedSettings) playbackService?.setSampleRate(state.sampleRate)
            if (Setting.HEADSET_RESUME in loadedSettings) {
                playbackService?.setResumeOnHeadsetConnect(state.resumeOnHeadsetConnect)
            }
            
            // Устанавливаем список ID пресетов для переключения с гарнитуры
            if (Setting.PRESETS in loadedSettings) {
                playbackService?.setPresetIds(state.presets.map { it.id })
                playbackService?.setCurrentPresetId(state.activePreset?.id)
            }
            
            // Устанавливаем callback для переключения пресетов с гарнитуры
            playbackService?.onPresetSwitch = { presetId ->
                playPreset(presetId)
            }
            
            // Наблюдаем за состоянием воспроизведения из сервиса
            observeServiceState()
            
            // Проверяем необходимость автовозобновления
            tryAutoResumeOnAppStart()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            _uiState.update { it.copy(isServiceConnected = false) }
            android.util.Log.d("BinauralViewModel", "Service disconnected")
        }
    }

    init {
        bindToService()
        loadPreferences()
        observePlaybackState()
        // Состояние исключения энергосбережения читается синхронно и сразу:
        // от него зависит, нужно ли показывать стартовое напоминание
        refreshBatteryOptimizationState()
    }
    
    private fun bindToService() {
        val intent = Intent(context, BinauralPlaybackService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    // Наблюдение за состоянием сервиса перенесено в observePlaybackState()
    // для избежания дублирования
    private fun observeServiceState() {
        // Наблюдение уже настроено в observePlaybackState()
    }

    private var lastActivePresetId: String? = null  // Сохраняем ID последнего активного пресета
    
    private fun loadPreferences() {
        // Загружаем список пресетов и активный пресет последовательно
        viewModelScope.launch {
            // Сначала загружаем пресеты
            preferencesRepository.getPresets().collect { presets ->
                _uiState.update { it.copy(presets = presets) }
                
                // Обновляем список ID пресетов в сервисе для переключения с гарнитуры
                playbackService?.setPresetIds(presets.map { it.id })
                
                // После обновления списка пресетов проверяем активный пресет
                val activeId = preferencesRepository.getActivePresetId().first()
                if (activeId != null) {
                    lastActivePresetId = activeId  // Сохраняем для togglePlayback
                    val activePreset = presets.find { it.id == activeId }
                    if (activePreset != null) {
                        _uiState.update { 
                            it.copy(
                                activePreset = activePreset,
                                carrierRange = activePreset.frequencyCurve.carrierRange,
                                beatRange = activePreset.frequencyCurve.beatRange
                            )
                        }
                        // Устанавливаем название пресета для уведомления
                        playbackService?.setCurrentPresetName(activePreset.name)
                    }
                }
                // Отмечаем «пресеты прочитаны» ВСЕГДА (независимо от наличия
                // активного пресета). Иначе без активного пресета settingsReady
                // никогда не станет true и обновлённый конфиг не уйдёт в движок.
                // Ставим ПОСЛЕ блока активного пресета, чтобы к моменту
                // возможного срабатывания updateAudioConfig() activePreset
                // уже лежал в _uiState.
                onSettingLoaded(Setting.PRESETS)

                // Восстанавливаем сессию редактирования, если ViewModel была
                // пересоздана (сворачивание/блокировка/смерть процесса), а
                // editingFrequencyCurve потерялся и график пропал с экрана.
                maybeRestoreEditingSession()
            }
        }
        
        // Загружаем общие настройки приложения
        // Частота дискретизации
        viewModelScope.launch {
            preferencesRepository.getSampleRate().collect { rate ->
                val sampleRate = when (rate) {
                    8000 -> SampleRate.ULTRA_LOW
                    16000 -> SampleRate.VERY_LOW
                    22050 -> SampleRate.LOW
                    48000 -> SampleRate.HIGH
                    else -> SampleRate.MEDIUM
                }
                _uiState.update { it.copy(sampleRate = sampleRate) }
                // Страховка на старте: интервал буфера из хранилища мог быть
                // сохранён для ДРУГОЙ частоты (старая версия приложения или
                // сбой между двумя сохранениями) и не влезать в бюджет новой.
                // Усекаем до максимальной стопы загруженной частоты. Хранилище
                // не переписываем — усечение страховочное, честно сохранит
                // пользовательский setSampleRate. В сервис-менеджер интервал
                // уйдёт и из коллектора самих минут, здесь пушим только при
                // реальном усечении (дедупликацию менеджера не дёргаем).
                val stored = _uiState.value.bufferGenerationMinutes
                val clamped = PacketMemoryBudget.coerceMinutes(sampleRate.value, stored)
                if (clamped != stored) {
                    _uiState.update { it.copy(bufferGenerationMinutes = clamped) }
                    playbackService?.setFrequencyUpdateInterval(clamped * 60 * 1000)
                }
                playbackService?.setSampleRate(sampleRate)
                onSettingLoaded(Setting.SAMPLE_RATE)
            }
        }
        // Интервал генерации буфера (в минутах)
        viewModelScope.launch {
            preferencesRepository.getBufferGenerationMinutes().collect { minutes ->
                // Верхний предел зависит от частоты дискретизации: пакет —
                // это сырые PCM_FLOAT (8 байт на кадр), и один бюджет кучи
                // покупает разную длительность на разных SR. Больше 86% кучи
                // отдавать нельзя (см. PacketMemoryBudget). Слайдер уже
                // показывает только доступные стопы, здесь — страховка для
                // значений из хранилища старых версий и смены частоты.
                val rate = _uiState.value.sampleRate.value
                val clamped = PacketMemoryBudget.coerceMinutes(rate, minutes)
                _uiState.update { it.copy(bufferGenerationMinutes = clamped) }
                // Преобразуем минуты в миллисекунды для частоты обновления
                // Большой буфер = реже обновления = лучше энергопотребление
                playbackService?.setFrequencyUpdateInterval(clamped * 60 * 1000)
                onSettingLoaded(Setting.BUFFER_MINUTES)
            }
        }
        // Громкость — читаем из хранилища и применяем к движку, но ТОЛЬКО
        // когда значение реально прочитано (иначе пошлём дефолт 1.0, который
        // движок примет за «сделай громче» и пересоберёт поток).
        viewModelScope.launch {
            preferencesRepository.getVolume().collect { volume ->
                _uiState.update { it.copy(volume = volume) }
                playbackService?.setVolume(volume)
                onSettingLoaded(Setting.VOLUME)
            }
        }
        // Глобальные настройки перестановки каналов
        // Примечание: updateAudioConfig() вызывается из методов setChannelSwap*() с затуханием
        // Здесь только обновляем UI состояние при загрузке из preferences
        viewModelScope.launch {
            preferencesRepository.getChannelSwapSettings().collect { settings ->
                _uiState.update { it.copy(channelSwapSettings = settings) }
                onSettingLoaded(Setting.CHANNEL_SWAP)
            }
        }
        // Глобальные настройки нормализации громкости
        // Примечание: updateAudioConfig() вызывается из методов setVolumeNormalization*() с затуханием
        // Здесь только обновляем UI состояние при загрузке из preferences
        viewModelScope.launch {
            preferencesRepository.getVolumeNormalizationSettings().collect { settings ->
                _uiState.update { it.copy(volumeNormalizationSettings = settings) }
                onSettingLoaded(Setting.NORMALIZATION)
            }
        }
        // Возобновление воспроизведения при подключении гарнитуры
        viewModelScope.launch {
            preferencesRepository.getResumeOnHeadsetConnect().collect { enabled ->
                _uiState.update { it.copy(resumeOnHeadsetConnect = enabled) }
                // Уведомляем сервис об изменении настройки
                playbackService?.setResumeOnHeadsetConnect(enabled)
                onSettingLoaded(Setting.HEADSET_RESUME)
            }
        }
        // Автовозобновление воспроизведения при запуске приложения
        viewModelScope.launch {
            preferencesRepository.getAutoResumeOnAppStart().collect { enabled ->
                _uiState.update { it.copy(autoResumeOnAppStart = enabled) }
            }
        }
        // Признак «стартовое напоминание об энергосбережении уже показано»
        viewModelScope.launch {
            preferencesRepository.getBatteryOptimizationPromptShown().collect { shown ->
                _uiState.update { it.copy(batteryOptimizationPromptShown = shown) }
                // Видимость напоминания зависит от двух источников (система + этот флаг),
                // поэтому пересчитываем её при каждом изменении любого из них
                updateBatteryOptimizationPromptVisibility()
            }
        }
        // Признак «справка по управлению в редакторе уже показана» — чтобы больше
        // не открывать её автоматически при входе в редактор.
        viewModelScope.launch {
            preferencesRepository.getGesturesHelpShown().collect { shown ->
                _uiState.update { it.copy(gesturesHelpShown = shown) }
            }
        }
        // Автоматическое расширение границ графика при редактировании
        viewModelScope.launch {
            preferencesRepository.getAutoExpandGraphRange().collect { enabled ->
                _uiState.update { it.copy(autoExpandGraphRange = enabled) }
            }
        }
    }

    /**
     * Перечитать фактическое состояние исключения фонового энергосбережения.
     *
     * Вызывается при старте и при каждом возврате приложения на экран: системный
     * диалог не даёт результата, поэтому узнать, разрешил пользователь исключение
     * или нет, можно только повторным опросом системы.
     */
    fun refreshBatteryOptimizationState() {
        val ignoring = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        _uiState.update { it.copy(isIgnoringBatteryOptimizations = ignoring) }
        updateBatteryOptimizationPromptVisibility()
    }

    /**
     * Видимость стартового напоминания: показываем один раз и только тем,
     * кто ещё не добавил приложение в исключения.
     */
    private fun updateBatteryOptimizationPromptVisibility() {
        _uiState.update { state ->
            state.copy(
                showBatteryOptimizationPrompt =
                    state.batteryOptimizationPromptShown == false && !state.isIgnoringBatteryOptimizations
            )
        }
    }

    /**
     * «Ок» в стартовом напоминании: закрываем напоминание и открываем системный
     * диалог добавления в исключения фонового энергосбережения.
     */
    fun requestBatteryOptimizationExemption() {
        markBatteryOptimizationPromptHandled()
        openBatteryOptimizationScreen(enable = true)
    }

    /**
     * «Отмена» в стартовом напоминании: напоминание больше не показывается
     * ни при каком последующем запуске.
     */
    fun dismissBatteryOptimizationPrompt() {
        markBatteryOptimizationPromptHandled()
    }

    /**
     * «Понятно» в диалоге «Подключите наушники»: закрыть диалог без запуска.
     */
    fun dismissHeadphoneDialog() {
        _uiState.update { it.copy(showHeadphoneDialog = false, pendingPresetId = null) }
    }

    /**
     * «Запустить» в диалоге «Подключите наушники»: запустить воспроизведение
     * несмотря на отсутствие гарнитуры.
     */
    fun playPresetAnyway() {
        val presetId = _uiState.value.pendingPresetId ?: return
        _uiState.update { it.copy(showHeadphoneDialog = false, pendingPresetId = null) }
        startPreset(presetId, curveOverride = null, relaxationOverride = null)
    }

    /**
     * Переключатель «Бесперебойное воспроизведение в фоне» в настройках.
     *
     * Переключатель не хранит собственного состояния — оно целиком определяется
     * системой, а выдать или отозвать исключение может только пользователь.
     * Поэтому оба направления просто открывают нужный системный экран:
     * включение — прямой диалог подтверждения, выключение — список оптимизаций,
     * где исключение отзывается. Фактический результат подхватит
     * [refreshBatteryOptimizationState] при возврате на экран.
     */
    fun setBatteryOptimizationExemption(enabled: Boolean) {
        // Если пользователь дошёл до настроек, стартовое напоминание ему уже не нужно
        markBatteryOptimizationPromptHandled()
        openBatteryOptimizationScreen(enable = enabled)
    }

    private fun openBatteryOptimizationScreen(enable: Boolean) {
        val opened = if (enable) {
            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
        } else {
            BatteryOptimizationHelper.openBatteryOptimizationSettings(context)
        }
        if (!opened) {
            android.util.Log.w(
                "BinauralViewModel",
                "Не удалось открыть системный экран энергопотребления (enable=$enable)"
            )
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.battery_optimization_settings_unavailable),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun markBatteryOptimizationPromptHandled() {
        _uiState.update { it.copy(batteryOptimizationPromptShown = true) }
        updateBatteryOptimizationPromptVisibility()
        viewModelScope.launch {
            preferencesRepository.saveBatteryOptimizationPromptShown(true)
        }
    }

    /**
     * Пометить справку по управлению в редакторе как показанную.
     *
     * Вызывается при закрытии окна (кнопка «Понятно», системный «назад» или
     * касание мимо). После этого окно больше не открывается автоматически при
     * входе в редактор — пока пользователь не откроет его сам кнопкой справки.
     */
    fun markGesturesHelpShown() {
        _uiState.update { it.copy(gesturesHelpShown = true) }
        viewModelScope.launch {
            preferencesRepository.saveGesturesHelpShown(true)
        }
    }

    /**
     * Наблюдение за телеметрией сервиса.
     *
     * Раньше пять отдельных коллекторов писали каждый в свой `MutableStateFlow`
     * через `_uiState.update`, то есть за секунду могло быть до пяти эмиссий
     * монолитного состояния — и пять полных перекомпозиций всего дерева.
     * Теперь они сливаются в один [combine] и отсекаются [distinctUntilChanged],
     * а результат уходит в отдельный поток [telemetry].
     */
    private fun observePlaybackState() {
        viewModelScope.launch {
            combine(
                BinauralPlaybackService.isPlaying,
                BinauralPlaybackService.currentBeatFrequency,
                BinauralPlaybackService.currentCarrierFrequency,
                BinauralPlaybackService.isChannelsSwapped,
                BinauralPlaybackService.currentTimeOfDaySeconds
            ) { playing, beat, carrier, swapped, timeSeconds ->
                // U1: квантуем время суток до 60 с. Ни один production-экран не
                // показывает секунды, а MiniFrequencyGraph сдвигает указатель на
                // ~0.07% ширины за 60 с (неразличимо). Это режет эмиссии
                // телеметрии с ~1/с до ~1/мин, поэтому список пресетов и экраны
                // редактирования перекомпонуются в 60 раз реже в фоне и на паузе
                // (где beat/carrier постоянны и единственный движущийся сигнал —
                // это время). Debug-панель времени берёт отдельный неквантованный
                // поток (см. debugCurrentTime), поэтому её слайдер не страдает.
                val quantizedSeconds = (timeSeconds / 60) * 60
                PlaybackTelemetry(
                    isPlaying = playing,
                    currentBeatFrequency = beat,
                    currentCarrierFrequency = carrier,
                    isChannelsSwapped = swapped,
                    currentTime = LocalTime.fromSecondOfDay(quantizedSeconds.coerceIn(0, 86399))
                )
            }
                // Схлопываем повторы: на паузе сервис шлёт те же значения,
                // и без этого StateFlow будил бы подписчиков впустую.
                .distinctUntilChanged()
                .collect { snapshot ->
                    // ПОЛЯ СКРАБА ЭТОМУ КОЛЛЕКТОРУ НЕ ПРИНАДЛЕЖАТ: их пишут
                    // два отдельных коллектора ниже (§14.7 плана) — у combine
                    // нет перегрузки на шесть потоков, поэтому сдвиг и реальное
                    // «сейчас» живут рядом, а не внутри.
                    //
                    // ПОЛНАЯ ЗАМЕНА ОБЪЕКТА ЗДЕСЬ НЕДОПУСТИМА. Раньше было
                    // `_telemetry.value = telemetry`, и свежесобранный снимок
                    // (выше) не задавал [PlaybackTelemetry.realTime] — он
                    // принимал ЗНАЧЕНИЕ ПО УМОЛЧАНИЮ. Combine тикает ~1 Гц
                    // (частоты меняются каждую секунду), коллектор реального
                    // времени — тоже ~1 Гц, и они чередовались: серая линия
                    // мерцала между настоящим «сейчас» и 12:00. Ровно то же
                    // стирало бы и [PlaybackTelemetry.scrubOffsetSeconds].
                    //
                    // distinctUntilChanged выше по-прежнему сравнивает только
                    // «звуковую» часть снимка (оба её экземпляра содержат
                    // дефолтное realTime), то есть дедупликация не сломана.
                    _telemetry.update { current ->
                        snapshot.copy(
                            realTime = current.realTime,
                            scrubOffsetSeconds = current.scrubOffsetSeconds
                        )
                    }
                }
        }

        // СКРАБ живёт отдельным коллектором, а не шестым потоком в combine
        // выше: у combine нет перегрузки на шесть аргументов, а перевод всего
        // блока на combine(Array<Flow>) потерял бы типы. Отдельный коллектор
        // дешевле по эмиссиям: сдвиг меняется только по жесту пользователя,
        // тогда как combine тикает каждую секунду.
        // distinctUntilChanged здесь НЕ нужен и даже запрещён: StateFlow уже
        // сам отсекает дубли (operator fusion), а применение оператора к
        // StateFlow помечено устаревшим и роняет сборку.
        viewModelScope.launch {
            BinauralPlaybackService.scrubOffsetSeconds.collect { offset ->
                _telemetry.update { it.copy(scrubOffsetSeconds = offset) }
            }
        }

        // СКРАБ: реальное «сейчас» — своим коллектором ровно по тем же
        // причинам, что и сдвиг (шестой поток в combine выше не влез).
        // Отставание на одну эмиссию здесь безвредно: реальное время меняется
        // плавно и медленно, а ошибка в одну секунду на графике суток — это
        // сотая доля пикселя. Залипнуть оно не может: следующая эмиссия всё
        // поправит, в отличие от вычитания сдвига из оси на стороне UI.
        viewModelScope.launch {
            BinauralPlaybackService.unshiftedTimeOfDaySeconds.collect { seconds ->
                // null — менеджер ещё не публиковал реальное «сейчас». Поле
                // НЕ трогаем: подставлять вместо него midnight или «текущее
                // время по часам телефона» — значит снова выдать отсутствие
                // данных за правдоподобный момент. График в этом случае
                // считает реальным «сейчас» саму ось: линии совпадают, и
                // серой просто нечего рисовать.
                if (seconds == null) return@collect
                val quantized = (seconds / 60) * 60
                _telemetry.update {
                    it.copy(realTime = LocalTime.fromSecondOfDay(quantized.coerceIn(0, 86399)))
                }
            }
        }

        // Автоскрытие диалога «подключите наушники» при подключении гарнитуры
        viewModelScope.launch {
            BinauralPlaybackService.hasHeadset.collect { connected ->
                if (connected && _uiState.value.showHeadphoneDialog) {
                    val presetId = _uiState.value.pendingPresetId ?: return@collect
                    _uiState.update { it.copy(showHeadphoneDialog = false, pendingPresetId = null) }
                    startPreset(presetId, curveOverride = null, relaxationOverride = null)
                }
            }
        }
    }

    // ============= Скраб: предпросмотр другого времени суток =============

    /**
     * СКРАБ: прослушать, как пресет звучит в момент [time], а не сейчас.
     *
     * Сдвигается ВСЯ ось времени суток, а не «позиция трека»: кривая
     * продолжает эволюционировать, линия указателя едет вперёд со скоростью
     * 1×, просто со сдвигом. Передаётся абсолютное время суток — сдвиг обязан
     * считаться на нити актёра в момент применения, потому что «сейчас»
     * успевает уехать между касанием и постом.
     *
     * Каждый вызов — полный хэндофф (кроссфейд ~1 с). Поэтому из UI этот
     * метод вызывается по ОТПУСКАНИИ ручки, а не на каждом шаге жеста.
     */
    fun scrubTo(time: LocalTime) {
        playbackService?.scrubTo(time.toSecondOfDay())
    }

    /**
     * СКРАБ: вернуть прослушивание к реальному текущему моменту (кнопка
     * сброса на графике). Возврат СЛЫШИМЫЙ — через хэндофф, как сам скраб.
     */
    fun scrubReset() {
        playbackService?.scrubReset()
    }

    /**
     * СКРАБ: снять сдвиг, не трогая звук. Для выходов из редактора: там
     * состояние уже меняется другими путями, и лишний кроссфейд был бы
     * слышен как щелчок на ровном месте.
     *
     * ВАЖНО: сам по себе этот вызов звук НЕ возвращает — он только стирает
     * заданный сдвиг, а возврат разыгрывает попутный хэндофф (сохранение,
     * восстановление кривой, смена пресета). Попутного хэндоффа может не
     * быть (например, выход без правок кривой), поэтому каждый выход из
     * редактора обязан завершаться [releaseEditorScrub] — слышимой проверкой
     * «а вернулся ли звук».
     */
    private fun resetScrub() {
        playbackService?.resetScrub()
    }

    /**
     * СКРАБ: редактор закрыт — вернуть прослушивание к реальному «сейчас».
     *
     * ЕДИНАЯ точка возврата оси, идемпотентная: движок сам знает, сдвинута
     * ось звука или нет, и без сдвига не делает ничего. Благодаря этому её
     * можно (и нужно) вызывать из всех страховок сразу — явного выхода,
     * наблюдателя навигации и жизненного цикла: лишнего кроссфейда всё
     * равно не будет.
     *
     * ПОРЯДОК ВЫЗОВА ВАЖЕН: последним, ПОСЛЕ всей работы выхода
     * (сохранение / восстановление кривой). Тогда хэндофф от этой работы
     * успевает унести и обнулённый сдвиг (один кроссфейд вместо двух), а
     * вызов лишь добирает случай, когда никакой работы не было.
     */
    fun releaseEditorScrub() {
        playbackService?.scrubReset()
    }

    // ============= Методы для работы с пресетами =============
    
    /**
     * Воспроизвести пресет
     */
    fun playPreset(presetId: String) {
        // Проверяем подключение наушников (если сервис подключён)
        if (playbackService != null && !BinauralPlaybackService.hasHeadset.value) {
            _uiState.update { it.copy(showHeadphoneDialog = true, pendingPresetId = presetId) }
            return
        }
        startPreset(presetId, curveOverride = null, relaxationOverride = null)
    }

    /**
     * Запуск пресета с возможной подменой кривой и настроек расслабления.
     *
     * @param curveOverride кривая, которая должна зазвучать ВМЕСТО сохранённой
     *   в пресете. Единственный сценарий — несохранённые правки из редактора:
     *   график на экране и звук обязаны совпасть в момент переключения, а не
     *   только после первой же следующей правки. `null` — звучит пресет как
     *   сохранён (обычный запуск из списка).
     * @param relaxationOverride то же для настроек расслабления.
     */
    private fun startPreset(
        presetId: String,
        curveOverride: FrequencyCurve?,
        relaxationOverride: RelaxationModeSettings?
    ) {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        val state = _uiState.value
        
        // Если уже воспроизводится этот пресет - останавливаем с затуханием
        if (state.activePreset?.id == presetId && _telemetry.value.isPlaying) {
            playbackService?.stopWithFade()
            return
        }

        // Явный запуск пресета — не «редакторский» перезапуск: возвращаем
        // пользовательский интервал генерации буфера, если он был зафиксирован
        // правкой опций в редакторе (например, переключение с гарнитуры во
        // время редактирования). Повтор того же значения отсекает менеджер.
        restoreUserBufferInterval()

        // Кривая, которая реально зазвучит: подменённая (несохранённые правки
        // из редактора) или сохранённая в пресете.
        val soundingCurve = curveOverride ?: preset.frequencyCurve

        // СКРАБ: смена пресета стирает сдвиг оси. Сдвиг имел смысл только для
        // прослушивания конкретного пресета в конкретное время; на другом
        // пресете та же ось — уже не «предпросмотр», а просто ложные часы.
        resetScrub()

        // Устанавливаем активный пресет
        _uiState.update {
            it.copy(
                activePreset = preset,
                carrierRange = soundingCurve.carrierRange,
                beatRange = soundingCurve.beatRange
            )
        }
        
                // Устанавливаем название пресета для уведомления
                playbackService?.setCurrentPresetName(preset.name)
                
                // Обновляем текущий ID пресета в сервисе
                playbackService?.setCurrentPresetId(presetId)
        
        // Формируем конфиг из глобальных настроек каналов и нормализации
        val config = buildPlaybackConfig(
            frequencyCurve = soundingCurve,
            volume = state.volume,
            channelSwap = state.channelSwapSettings,
            normalization = state.volumeNormalizationSettings
        )

        val relaxationSettings = relaxationOverride ?: preset.relaxationModeSettings

        // ПЕРЕКЛЮЧЕНИЕ = КРОССФЕЙД, а не «стоп, потом старт».
        //
        // updateConfig() во время воспроизведения уходит в BinauralStreamManager
        // .requestHandoff() -> beginHandoff(): NEXT готовится и начинает фейд-ин
        // ОДНОВРЕМЕННО с фейд-аутом CURRENT. Окно перекрытия равно длительности
        // фейда (250 мс), тишины между пресетами нет, а часы сессии, точка на
        // кривой и фазы несущих переносятся на NEXT без скачка.
        //
        // Прежняя схема stopWithFade() -> delay(300) -> play() давала до 8 с
        // тишины: play() приходил в FADE_OUT_STOP и лишь откладывался в
        // pendingPlaySpec, а реальный старт дожидался, пока писатель выйдет из
        // track.write(WRITE_BLOCKING) на весь WRITE_CHUNK_MS.
        playbackService?.updateConfig(config, relaxationSettings)
        // Не воспроизводится (IDLE/PAUSED) — конфиг применён, нужен явный старт.
        // Во время воспроизведения play() был бы no-op: старт делает сам хэндофф.
        if (!_telemetry.value.isPlaying) {
            playbackService?.play()
        }
        
        // Сохраняем активный пресет
        lastActivePresetId = presetId
        viewModelScope.launch {
            preferencesRepository.saveActivePresetId(presetId)
        }
    }
    
    /**
     * Начать редактирование существующего пресета
     */
    fun startEditingPreset(presetId: String) {
        // Запоминаем цель редактирования: переживёт пересоздание ViewModel и
        // позволит восстановить сессию, когда пресеты догрузятся.
        savedStateHandle[KEY_EDITING_TARGET] = presetId
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        val isActivePreset = _uiState.value.activePreset?.id == presetId
        
        android.util.Log.d("BinauralViewModel", "startEditingPreset: presetId=$presetId, relaxationModeSettings=${preset.relaxationModeSettings}, smoothInterval=${preset.relaxationModeSettings.smoothIntervalMinutes}")
        
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = preset.frequencyCurve,
                newPresetBaselineCurve = null,
                editingPresetId = presetId,
                carrierRange = preset.frequencyCurve.carrierRange,
                beatRange = preset.frequencyCurve.beatRange,
                selectedPointIndex = null,  // Сбрасываем выбранную точку при начале редактирования
                editingRelaxationModeSettings = preset.relaxationModeSettings
            )
        }

        // Желаемой частотой биений становится сохранённая в пресете: пока
        // пользователь не задал её сам, это и есть его намерение.
        pointIntent.seedFrom(preset.frequencyCurve.points)

        // Обновляем кривую в сервисе только если редактируется активный пресет
        // Это позволяет слышать изменения в реальном времени при редактировании активного пресета
        if (isActivePreset) {
            playbackService?.updateFrequencyCurve(preset.frequencyCurve)
        }
        // Цель отработана — для восстановления больше не нужна.
        savedStateHandle.remove<String>(KEY_EDITING_TARGET)
    }
    
    /**
     * Начать создание нового пресета
     */
    fun startNewPreset() {
        savedStateHandle[KEY_EDITING_TARGET] = EDITING_TARGET_NEW
        val defaultCurve = FrequencyCurve.newPresetCurve()
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = defaultCurve,
                newPresetBaselineCurve = defaultCurve,
                editingPresetId = null,
                carrierRange = defaultCurve.carrierRange,
                beatRange = defaultCurve.beatRange,
                selectedPointIndex = null,
                editingRelaxationModeSettings = RelaxationModeSettings()
            )
        }
        // У нового пресета «пользовательского» значения ещё нет — желаемыми
        // становятся частоты биений кривой по умолчанию.
        pointIntent.seedFrom(defaultCurve.points)
        // Не обновляем кривую в сервисе при создании нового пресета
        // Воспроизведение продолжает использовать активный пресет
        // Цель отработана — для восстановления больше не нужна.
        savedStateHandle.remove<String>(KEY_EDITING_TARGET)
    }
    
    /**
     * Отменить редактирование и восстановить кривую активного пресета
     */
    fun cancelEditing() {
        val activePreset = _uiState.value.activePreset
        pointIntent.clear()
        savedStateHandle.remove<String>(KEY_EDITING_TARGET)
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = null,
                newPresetBaselineCurve = null,
                editingPresetId = null,
                selectedPointIndex = null,
                editingRelaxationModeSettings = RelaxationModeSettings()
            )
        }
        
        // Сессия редактирования завершена: возвращаем пользовательский интервал
        // генерации буфера ДО восстановления кривой — пересборка после отмены
        // должна идти уже с ним.
        restoreUserBufferInterval()

        // Восстанавливаем кривую активного пресета в сервисе
        if (activePreset != null) {
            playbackService?.updateFrequencyCurve(activePreset.frequencyCurve)
        }
    }
    
    /**
     * Восстановить кривую активного пресета в сервисе без очистки состояния редактирования.
     * Используется при выходе с экрана редактирования для плавной анимации.
     */
    fun cancelEditingInService() {
        // СКРАБ: выход из редактора стирает сдвиг оси. Сдвиг был «ложью о
        // времени» ради прослушивания правки; вне редактора про неё уже никто
        // не помнит, а звук, уехавший от настоящего «сейчас», — это нарушение
        // главного инварианта приложения. Снимаем ДО восстановления кривой:
        // buildSpec() подставит 0, и тот же хэндофф вернёт звук на реальную ось.
        resetScrub()
        // Сессия редактирования завершена: возвращаем пользовательский интервал
        // генерации буфера ДО восстановления кривой — пересборка после отмены
        // должна идти уже с ним.
        restoreUserBufferInterval()

        val activePreset = _uiState.value.activePreset
        // Восстанавливаем кривую активного пресета в сервисе
        if (activePreset != null) {
            playbackService?.updateFrequencyCurve(activePreset.frequencyCurve)
        }
    }
    
    /**
     * Завершить редактирование после успешного сохранения
     * Очищает состояние редактирования БЕЗ восстановления кривой в сервисе
     */
    fun finishEditing() {
        pointIntent.clear()
        // СКРАБ: см. cancelEditingInService — выход из редактора стирает сдвиг.
        resetScrub()
        _uiState.update { 
            it.copy(
                editingFrequencyCurve = null,
                newPresetBaselineCurve = null,
                editingPresetId = null,
                selectedPointIndex = null,
                editingRelaxationModeSettings = RelaxationModeSettings()
            )
        }
        // Не восстанавливаем кривую в сервисе - новые данные загрузятся через Flow
        savedStateHandle.remove<String>(KEY_EDITING_TARGET)
    }
    
    /**
     * Завершить редактирование без очистки состояния (для плавной анимации).
     * Используется после сохранения - данные загрузятся через Flow.
     */
    fun finishEditingWithoutClear() {
        // Ничего не делаем - состояние очистится при следующем редактировании
        // или при входе в другой экран редактирования через startEditingPreset/startNewPreset
    }

    /**
     * Восстанавливает сессию редактирования после пересоздания ViewModel
     * (сворачивание приложения, блокировка/разблокировка экрана, смерть
     * процесса). В этих случаях [editingFrequencyCurve] теряется, а
     * [startEditingPreset]/[startNewPreset], вызванные из `LaunchedEffect`
     * экрана, могут отработать впустую, пока пресеты ещё не догрузились из
     * хранилища (список пуст → пресет не найден → ранний выход). Цель
     * редактирования сохранена в [SavedStateHandle], поэтому здесь, как только
     * список пресетов появляется, сессия пересоздаётся и виджет графика
     * редактирования снова показывается.
     *
     * Вызывается из коллектора `getPresets()` — то есть срабатывает каждый раз,
     * когда пресеты (пере)загружаются, в том числе сразу после пересоздания
     * ViewModel.
     */
    private fun maybeRestoreEditingSession() {
        val target = savedStateHandle.get<String>(KEY_EDITING_TARGET) ?: return
        // Сессия уже восстановлена (или редактирование активно) — цель больше не нужна.
        if (_uiState.value.editingFrequencyCurve != null) {
            savedStateHandle.remove<String>(KEY_EDITING_TARGET)
            return
        }
        if (target == EDITING_TARGET_NEW) {
            startNewPreset()
            savedStateHandle.remove<String>(KEY_EDITING_TARGET)
            return
        }
        // Существующий пресет: если список уже непустой, а пресета в нём нет —
        // значит, он удалён, сессия невозможна, сдаёмся.
        val presets = _uiState.value.presets
        if (presets.isNotEmpty() && presets.none { it.id == target }) {
            savedStateHandle.remove<String>(KEY_EDITING_TARGET)
            return
        }
        startEditingPreset(target)
        if (_uiState.value.editingFrequencyCurve != null) {
            savedStateHandle.remove<String>(KEY_EDITING_TARGET)
        }
    }
    
    /**
     * Создать новый пресет
     */
    fun createPreset(
        name: String, 
        curve: FrequencyCurve, 
        relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
    ) {
        android.util.Log.d("BinauralViewModel", "createPreset: name=$name, relaxationModeSettings=$relaxationModeSettings, smoothInterval=${relaxationModeSettings.smoothIntervalMinutes}")
        val preset = BinauralPreset(
            name = name,
            frequencyCurve = curve,
            relaxationModeSettings = relaxationModeSettings
        )
        viewModelScope.launch {
            preferencesRepository.addPreset(preset)
        }
    }
    
    /**
     * Сохранить редактируемый пресет
     */
    fun saveEditingPreset(
        presetId: String, 
        name: String, 
        curve: FrequencyCurve, 
        relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
    ) {
        val existingPreset = _uiState.value.presets.find { it.id == presetId } ?: return
        val isActivePreset = _uiState.value.activePreset?.id == presetId
        
        val updatedPreset = existingPreset.copy(
            name = name,
            frequencyCurve = curve,
            relaxationModeSettings = relaxationModeSettings,
            updatedAt = System.currentTimeMillis()
        )
        
        // При сохранении НЕ делаем fade-рестарт: restartWithFadeIfNeeded ждал
        // 300 мс и затем «выстреливал» пакетом перекомпозиции (activePreset +
        // updateAudioConfig) как раз посередине shared-анимации сворачивания,
        // из-за чего переход дропал кадры. Активный пресет обновляем сразу:
        // меняем состояние и применяем конфиг к сервису, а запись в БД
        // асинхронна. updateAudioConfig применяет конфиг к сервису, а во время
        // воспроизведения это кроссфейд, так что звук не дёрнется.
        if (isActivePreset) {
            _uiState.update {
                it.copy(
                    activePreset = updatedPreset,
                    carrierRange = curve.carrierRange,
                    beatRange = curve.beatRange
                )
            }
            // СКРАБ: сохранение — тоже выход из редактора, сдвиг оси стирается.
            // Снимаем ДО updateAudioConfig(): его хэндофф и вернёт звук на
            // реальное «сейчас» (иначе buildSpec() унаследовал бы сдвиг).
            resetScrub()
            // Сессия редактирования завершена: возвращаем пользовательский
            // интервал генерации буфера ДО применения конфига, чтобы
            // кроссфейд-пересборка после сохранения сразу шла с ним.
            restoreUserBufferInterval()
            updateAudioConfig()
        }
        viewModelScope.launch {
            preferencesRepository.updatePreset(updatedPreset)
        }
    }
    
    /**
     * Удалить пресет
     */
    fun deletePreset(presetId: String) {
        // Если удаляем активный пресет - останавливаем воспроизведение с затуханием
        if (_uiState.value.activePreset?.id == presetId) {
            playbackService?.stopWithFade()
            // Сбрасываем имя активного пресета в уведомлении
            playbackService?.setCurrentPresetName(null)
            _uiState.update { it.copy(activePreset = null) }
            lastActivePresetId = null
            viewModelScope.launch {
                preferencesRepository.saveActivePresetId(null)
            }
        }
        
        viewModelScope.launch {
            preferencesRepository.deletePreset(presetId)
        }
    }

    // ============= Методы для редактирования кривой =============

    fun togglePlayback() {
        // Активна серия restartWithFade (fade-out идёт, isPlaying уже false):
        // нажатие "паузы" должно отменить серию и завершить паузой, а не резюмить звук
        if (restartJob?.isActive == true) {
            restartJob?.cancel()
            playbackService?.pauseWithFade()
            // Оптимистично гасим индикатор: сервис подтвердит через свой StateFlow
            _telemetry.update { it.copy(isPlaying = false) }
            return
        }

        val state = _uiState.value

        if (_telemetry.value.isPlaying) {
            // ПЛАВНАЯ ПАУЗА (soft-pause), а НЕ полная остановка.
            //
            // stopWithFade() утилизирует поток: следующий старт пересоздаёт
            // его заново (сотни миллисекунд тишины на подготовку). Мягкая
            // пауза лишь замораживает живой трек — звук снимается рампой, а
            // ресурсы, фазы и уже посчитанный PCM остаются.
            //
            // СЕМАНТИКА (важно): пауза НЕ «сохраняет место в треке», как у
            // музыкального плеера. СУТЬ ПРИЛОЖЕНИЯ — звук для ТЕКУЩЕГО момента
            // суток, поэтому возобновление играет ритм для «сейчас»:
            // замороженный пакет переиспользуется (с пропуском устаревшей
            // головы), только если текущий момент ещё внутри сгенерированного
            // окна, иначе поток пересобирается с якорем на now — см.
            // docs/analysis_resume_from_0_position.md.
            //
            // Полный стоп остаётся за уведомлением/headset-разрывом/сменой
            // пресета.
            playbackService?.pauseWithFade()
        } else {
            // Проверяем подключение наушников при попытке запуска воспроизведения
            if (playbackService != null && !BinauralPlaybackService.hasHeadset.value) {
                // Определяем, какой пресет пытаются запустить
                val pendingId = state.editingPresetId
                    ?: state.activePreset?.id
                    ?: lastActivePresetId
                if (pendingId != null) {
                    _uiState.update { it.copy(showHeadphoneDialog = true, pendingPresetId = pendingId) }
                    return
                }
            }

            // РЕДАКТОР: «продолжить» внутри редактора — это ПЕРЕКЛЮЧЕНИЕ на
            // редактируемую предустановку, а не возврат к звучавшей ранее.
            //
            // До этой правки кнопка просто возобновляла то, что играло (или
            // последний активный пресет): пользователь открывает пресет,
            // правит его, жмёт play — и слышит совсем другой пресет, который
            // в редакторе даже не показан. Услышать правку можно было только
            // выйдя из редактора и тапнув пресет в списке. Теперь нажатие
            // «play» при открытом редакторе всегда приводит прослушивание к
            // тому, что открыто на экране.
            //
            // Условие ровно по [editingPresetId]: он непуст только внутри
            // сессии редактирования СУЩЕСТВУЮЩЕГО пресета (на выходе из
            // редактора обнуляется). НОВЫЙ, ещё не сохранённый пресет
            // ([editingPresetId] == null) переключать некуда — его просто нет
            // в списке, поэтому там остаётся прежнее поведение.
            //
            // Кривую и настройки расслабления берём ИЗ РЕДАКТОРА, а не из
            // сохранённого пресета: иначе несохранённые правки зазвучали бы
            // лишь после следующей же правки (которая пушит кривую в движок),
            // а до неё звук расходился бы с графиком на экране.
            //
            // Отдельная проверка «пресет ещё есть в списке»: он мог быть удалён
            // (или список ещё не догружен после пересоздания ViewModel) — тогда
            // переключать некуда, и кнопка честно возобновляет прежний звук.
            val editingId = state.editingPresetId
            if (editingId != null &&
                editingId != state.activePreset?.id &&
                state.presets.any { it.id == editingId }
            ) {
                startPreset(editingId, state.editingFrequencyCurve, state.editingRelaxationModeSettings)
                return
            }

            // Если есть активный пресет - обновляем конфиг и продолжаем воспроизведение
            if (state.activePreset != null) {
                // Важно: сначала обновляем конфиг, т.к. при запуске приложения
                // конфиг в сервисе может быть дефолтным
                updateAudioConfig()
                playbackService?.resumeWithFade()
            } else {
                // Если нет активного пресета, но есть сохранённый lastActivePresetId
                // пытаемся восстановить и воспроизвести его
                val presetId = lastActivePresetId
                if (presetId != null) {
                    val preset = state.presets.find { it.id == presetId }
                    if (preset != null) {
                        // Восстанавливаем активный пресет и запускаем воспроизведение
                        playPreset(presetId)
                    }
                }
            }
        }
    }

    /**
     * Установить громкость мгновенно (без сохранения в preferences).
     * Вызывается при движении слайдера для мгновенного применения к аудио-движку.
     */
    fun setVolumeImmediate(volume: Float) {
        _uiState.update { it.copy(volume = volume) }
        playbackService?.setVolume(volume)
    }
    
    /**
     * Сохранить текущую громкость в preferences.
     * Вызывается при отпускании слайдера.
     */
    fun saveVolume() {
        viewModelScope.launch {
            preferencesRepository.saveVolume(_uiState.value.volume)
        }
    }

    fun selectPoint(index: Int) {
        _uiState.update { it.copy(selectedPointIndex = index) }
    }

    fun deselectPoint() {
        _uiState.update { it.copy(selectedPointIndex = null) }
    }

    // ============= Методы для редактирования точек (редактируемая кривая) =============
    
    /**
     * Изменить несущую ВЫБРАННОЙ точки.
     *
     * Ровно то же, что [updateEditingPointCarrierFrequencyDirect], но для
     * точки, отмеченной в [BinauralUiState.selectedPointIndex]. Реализация
     * не дублируется намеренно: частота биений выводится из желаемого
     * значения ([PointIntentMemory]), и две копии этой логики разошлись бы.
     */
    fun updateEditingPointCarrierFrequency(frequency: Float) {
        val index = _uiState.value.selectedPointIndex ?: return
        updateEditingPointCarrierFrequencyDirect(index, frequency)
    }

    /**
     * Изменить частоту биений ВЫБРАННОЙ точки — см.
     * [updateEditingPointBeatFrequencyDirect].
     */
    fun updateEditingPointBeatFrequency(frequency: Float) {
        val index = _uiState.value.selectedPointIndex ?: return
        updateEditingPointBeatFrequencyDirect(index, frequency)
    }
    
    fun updateEditingPointTimeDirect(index: Int, newTime: LocalTime) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            // Время не изменилось (повторный commit без правки: движение
            // каретки в заполненном поле триггерит валидацию по каждой
            // смене выделения) — не перестраиваем кривую и не пушим её
            // в движок впустую.
            if (newTime == oldPoint.time) return
            // Память желаемой частоты биений привязана к ВРЕМЕНИ точки,
            // поэтому переезд по оси времени переносит и её — иначе частота
            // «останется» на покинутой секунде, а переехавшая точка потеряет
            // своё желаемое значение.
            pointIntent.rekey(oldPoint.time, newTime)
            points[index] = FrequencyPoint(
                time = newTime,
                carrierFrequency = oldPoint.carrierFrequency,
                beatFrequency = oldPoint.beatFrequency
            )
            // Сортировка переставляет отредактированную точку на новое место
            // в списке, а выделение хранит ИНДЕКС. Без пересчёта окно после
            // сдвига времени показывало бы уже соседнюю точку: раньше сдвиг
            // приходил делениями по одному и это было незаметно, а теперь
            // весь жест применяется разом и точка может уехать далеко.
            // Порядок тот же, что у sortedBy (при равном времени — прежний
            // порядок, sortedBy устойчив), поэтому индекс считается однозначно.
            val sorted = points.withIndex().sortedWith(
                compareBy<IndexedValue<FrequencyPoint>> { it.value.time.toSecondOfDay() }
                    .thenBy { it.index }
            )
            val newIndex = sorted.indexOfFirst { it.index == index }
            updateEditingCurve(sorted.map { it.value }, curve.carrierRange, curve.beatRange, curve.interpolationType)
            // Выделение ведём за точкой, только если редактировалась именно
            // выделенная: индекс здесь явно передаёт вызывающий.
            if (newIndex >= 0 && state.selectedPointIndex == index) {
                _uiState.update { it.copy(selectedPointIndex = newIndex) }
            }
        }
    }
    
    fun updateEditingPointCarrierFrequencyDirect(index: Int, newCarrier: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            
            val (carrier, adjustedBeat, newCarrierRange) = if (state.autoExpandGraphRange) {
                // Автоматическое расширение границ (старое поведение)
                val clampedCarrier = newCarrier.coerceIn(
                    FrequencyMath.MIN_TONE_FREQUENCY, FrequencyMath.MAX_TONE_FREQUENCY)
                // Частота биений выводится из ЖЕЛАЕМОГО значения, а не из
                // текущего сохранённого: иначе точка, однажды прижатая к
                // границе, навсегда потеряла бы свою пульсацию. Здесь предел
                // только геометрический (20/2000 Гц) — границы расширяются.
                val beat = pointIntent.resolveBeat(oldPoint, clampedCarrier)

                // Модуль beat: при beat < 0 каналы меняются местами, а границы
                // графика должны расширяться по реально низкой/высокой боковой.
                val upperFrequency = clampedCarrier + FrequencyMath.beatMagnitude(beat) / 2.0f
                val lowerFrequency = clampedCarrier - FrequencyMath.beatMagnitude(beat) / 2.0f
                
                val newMin = if (lowerFrequency < curve.carrierRange.min) {
                    (lowerFrequency * 0.9f).coerceAtMost(lowerFrequency - 10.0f).coerceAtLeast(20.0f)
                } else {
                    curve.carrierRange.min
                }
                val newMax = if (upperFrequency > curve.carrierRange.max) {
                    (upperFrequency * 1.1f).coerceAtLeast(upperFrequency + 10.0f).coerceAtMost(2000.0f)
                } else {
                    curve.carrierRange.max
                }
                Triple(clampedCarrier, beat, FrequencyRange(newMin, newMax))
            } else {
                // Ограничение частот заданными границами графика (новое поведение по умолчанию).
                // Несущая, вышедшая за границу, НЕ отбрасывается: она встаёт на
                // саму границу, а частота биений в этой точке гаснет — у самой
                // границы каналам негде развернуться. Биения при этом не
                // теряются: желаемое значение памяти не перезаписывается, и
                // ровно оно возвращается, когда несущая (или граница)
                // отодвигается — см. PointIntentMemory.resolveBeat.
                val clampedCarrier = curve.carrierRange.clamp(newCarrier)
                // Желаемое значение обрезается по модулю под новое удаление
                // от границы — и ровно настолько же восстанавливается, когда
                // точка от границы отодвигается.
                val beat = pointIntent.resolveBeat(oldPoint, clampedCarrier, curve.carrierRange)
                Triple(clampedCarrier, beat, curve.carrierRange)
            }

            // ПРЯМАЯ правка несущей — источник желаемого значения. Запоминается
            // ЗАПРОШЕННОЕ значение, а не прижатое к границе: иначе несущая,
            // которая не влезла в диапазон, терялась бы навсегда и при
            // расширении границ осталась бы лежать у старого края. Точка
            // встала на границу сейчас — но вернётся туда, куда её тянул
            // пользователь, как только граница отодвинется. То же правило,
            // что и при создании точки (см. addEditingPoint).
            pointIntent.rememberCarrier(oldPoint.time, newCarrier)

            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = carrier,
                beatFrequency = adjustedBeat
            )
            updateEditingCurve(points, newCarrierRange, curve.beatRange, curve.interpolationType)
        }
    }
    
    fun updateEditingPointBeatFrequencyDirect(index: Int, newBeat: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val points = curve.points.toMutableList()
        if (index in points.indices) {
            val oldPoint = points[index]
            
            val beat: Float
            val carrier: Float
            val newCarrierRange: FrequencyRange
            if (state.autoExpandGraphRange) {
                // Автоматическое расширение границ (старое поведение)
                // Предел модуля — геометрический (20/2000 Гц), хранимый beatRange
                // в расчёт не входит: это масштаб маркеров, а не разрешённый предел.
                // Несущая не двигается: границы графика сами следом расширяются.
                beat = FrequencyMath.clampBeat(
                    oldPoint.carrierFrequency, newBeat)
                carrier = oldPoint.carrierFrequency

                // Границы расширяем по модулю beat (при знаке минус каналы меняются местами).
                val upperFrequency = carrier + FrequencyMath.beatMagnitude(beat) / 2.0f
                val lowerFrequency = carrier - FrequencyMath.beatMagnitude(beat) / 2.0f

                val newMin = if (lowerFrequency < curve.carrierRange.min) {
                    (lowerFrequency * 0.9f).coerceAtMost(lowerFrequency - 10.0f).coerceAtLeast(20.0f)
                } else {
                    curve.carrierRange.min
                }
                val newMax = if (upperFrequency > curve.carrierRange.max) {
                    (upperFrequency * 1.1f).coerceAtLeast(upperFrequency + 10.0f).coerceAtMost(2000.0f)
                } else {
                    curve.carrierRange.max
                }
                newCarrierRange = FrequencyRange(newMin, newMax)
            } else {
                // Границы графика заданы пресетом (поведение по умолчанию).
                // Частота биений НЕ режется: если при её увеличении канал
                // начинает заходить за границу, несущая ОТОДВИГАЕТСЯ от этой
                // границы внутрь диапазона — ровно настолько, насколько канал
                // вылез. Пользователь тянет пульсацию, он её и получает.
                // Обрезка остаётся только на ПОТОЛОК (ширина диапазона): выше
                // него разнос каналов не влезет ни при какой несущей, и тогда
                // несущая встаёт ровно посередине между границами.
                // См. FrequencyMath.fitBeatWithCarrierShift.
                val fit = FrequencyMath.fitBeatWithCarrierShift(
                    oldPoint.carrierFrequency, newBeat, curve.carrierRange)
                beat = fit.beatFrequency
                carrier = fit.carrierFrequency
                newCarrierRange = curve.carrierRange
            }

            // РУЧНАЯ установка — единственный источник «желаемого» значения.
            // Запоминается именно применённое значение: оно же осталось
            // в поле ввода, и расхождение выглядело бы обманом.
            pointIntent.rememberBeat(oldPoint.time, beat)
            // Несущая, отодвинутая от границы под пульсацию, — тоже воля
            // пользователя: он её видит на графике и в поле. Без запоминания
            // желаемым осталось бы ПРЕЖНЕЕ положение, и при следующей правке
            // диапазона точка отпрыгнула бы назад под уже увеличенные биения.
            pointIntent.rememberCarrier(oldPoint.time, carrier)

            points[index] = FrequencyPoint(
                time = oldPoint.time,
                carrierFrequency = carrier,
                beatFrequency = beat
            )
            updateEditingCurve(points, newCarrierRange, curve.beatRange, curve.interpolationType)
        }
    }

    fun addEditingPoint(time: LocalTime, carrierFrequency: Float, beatFrequency: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val clampedCarrier = curve.carrierRange.clamp(carrierFrequency)
        // Ограничение частоты биений по МОДУЛЮ (знак сохраняется — см. FrequencyMath):
        // 1. Нижняя боковая >= 20 Гц:   |beat| <= 2*(carrier - 20)
        // 2. Верхняя боковая <= 2000 Гц: |beat| <= 2*(2000 - carrier)
        // 3. Боковые не выходят за вертикальные границы графика: новая точка не
        //    должна провалиться ниже минимума частот пресета — иначе она
        //    нарушит инвариант, на который опираются виртуальные точки
        //    периодов расслабления.
        // Хранимый beatRange НЕ является пределом — он задаёт только масштаб
        // маркеров на графике.
        val clampedBeat = FrequencyMath.clampBeat(
            clampedCarrier, beatFrequency, carrierRange = curve.carrierRange)

        // Желаемыми становятся ЗАПРОШЕННЫЕ значения, а не обрезанные: точка,
        // рождённая у самой границы, всё равно наберёт свою пульсацию и вернёт
        // свою несущую, когда её отодвинут от края.
        pointIntent.rememberCarrier(time, carrierFrequency)
        pointIntent.rememberBeat(time, beatFrequency)

        val newPoint = FrequencyPoint(
            time = time,
            carrierFrequency = clampedCarrier,
            beatFrequency = clampedBeat
        )
        val points = curve.points.toMutableList()
        points.add(newPoint)
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
        val newIndex = sortedPoints.indexOfFirst { it === newPoint }.coerceAtLeast(0)
        updateEditingCurve(sortedPoints, curve.carrierRange, curve.beatRange, curve.interpolationType)
        // Если попап редактирования точки уже открыт — переключаем его на новую
        // точку (см. запрос): контекстное окно «переезжает» на свежесозданную.
        if (state.selectedPointIndex != null) {
            _uiState.update { it.copy(selectedPointIndex = newIndex) }
        }
    }

    fun removeEditingPoint(index: Int) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val points = curve.points.toMutableList()
        // Редактор позволяет удалить все точки кроме одной (одноточечная
        // кривая — допустимое состояние, см. FrequencyCurve.require(points.size >= 1)).
        if (points.size > 1 && index in points.indices) {
            pointIntent.forget(points[index].time)
            points.removeAt(index)
            updateEditingCurve(points, curve.carrierRange, curve.beatRange, curve.interpolationType)
            _uiState.update { it.copy(selectedPointIndex = null) }
        }
    }
    
    /**
     * Изменить вертикальные границы графика несущей.
     *
     * Точки приводятся к новому диапазону ПОЛНОСТЬЮ, а не только по несущей:
     * частота КАНАЛА (carrier − |beat|/2) тоже должна остаться внутри
     * [min; max], иначе кривая уедет за границы графика. Именно минимум
     * диапазона задаёт пол для виртуальных точек режима расслабления, поэтому
     * после смены границ точки обязаны пересчитаться — старые значения
     * оставлять нельзя.
     *
     * Приводятся ЖЕЛАЕМЫЕ значения ([PointIntentMemory]), а не текущие. Отсюда
     * симметрия с перетаскиванием точки: сузили диапазон — точка прижалась к
     * границе и биения погасли, вернули диапазон — точка вернулась туда, где
     * её оставил пользователь, и биения вернулись. Раньше обрезанные значения
     * перезаписывали точку, и обратное расширение диапазона уже ничего не
     * восстанавливало: прижатая несущая оставалась у границы навсегда.
     *
     * Порядок важен: сначала несущая (к желаемой, обрезанной по новым
     * границам), потом частота биений — под уже окончательное удаление несущей
     * от границы.
     *
     * Знак частоты биений сохраняется: клампится только модуль.
     */
    fun updateEditingCarrierRange(min: Float, max: Float) {
        if (max <= min) return

        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        val newRange = FrequencyRange(min, max)

        val updatedPoints = curve.points.map { point ->
            // Несущая берётся из ЖЕЛАЕМОЙ, а не из той, куда точку загнал
            // прошлый суженный диапазон: иначе при возврате диапазона она
            // осталась бы лежать у границы, к которой её прижало.
            val carrier = pointIntent.resolveCarrier(point, newRange)
            val beat = pointIntent.resolveBeat(point, carrier, newRange)
            point.copy(carrierFrequency = carrier, beatFrequency = beat)
        }

        updateEditingCurve(updatedPoints, newRange, curve.beatRange, curve.interpolationType)
    }

    /**
     * Редактируется ли именно тот пресет, который сейчас активен
     * (воспроизводится). Только в этом случае правки из редактора уходят
     * в движок и пересобирают звучащий поток.
     */
    private fun isEditingActivePreset(): Boolean {
        val state = _uiState.value
        return state.editingPresetId != null && state.editingPresetId == state.activePreset?.id
    }

    /**
     * Зафиксировать интервал генерации буфера на 1 минуте
     * ([EDITOR_PREVIEW_BUFFER_INTERVAL_MS]) для следующего пересобранного потока.
     *
     * Вызывается перед КАЖДЫМ пушем правки из редактора: пересборка после
     * изменения опций должна генерировать дешёвый минутный пакет, а не буфер
     * на весь пользовательский интервал. Повторные пуши того же значения
     * отсекает дедупликацией сам менеджер. Пользовательская настройка в базу
     * не пишется и возвращается при выходе из редактора
     * ([restoreUserBufferInterval]).
     */
    private fun armEditorPreviewBufferInterval() {
        playbackService?.setFrequencyUpdateInterval(EDITOR_PREVIEW_BUFFER_INTERVAL_MS)
    }

    /**
     * Вернуть пользовательский интервал генерации буфера после правки в
     * редакторе. Вызывается при завершении сессии редактирования (сохранение
     * или отмена), чтобы поток, пересобранный уже вне редактора, снова
     * генерировал буфер по настройке пользователя. Значение берётся из
     * состояния (прочитано из DataStore), в хранилище не пишется.
     */
    private fun restoreUserBufferInterval() {
        playbackService?.setFrequencyUpdateInterval(_uiState.value.bufferGenerationMinutes * 60 * 1000)
    }

    private fun updateEditingCurve(
        points: List<FrequencyPoint>,
        carrierRange: FrequencyRange,
        beatRange: FrequencyRange,
        interpolationType: InterpolationType = InterpolationType.LINEAR
    ) {
        try {
            val currentCurve = _uiState.value.editingFrequencyCurve
            val newCurve = FrequencyCurve(
                points = points,
                carrierRange = carrierRange,
                beatRange = beatRange,
                interpolationType = interpolationType,
                splineTension = currentCurve?.splineTension ?: 0.0f
            )
            _uiState.update { it.copy(editingFrequencyCurve = newCurve) }

            // Обновляем кривую в сервисе только если редактируется активный
            // пресет: звучащий поток пересобирается кроссфейдом, и правка
            // слышна сразу.
            if (isEditingActivePreset()) {
                armEditorPreviewBufferInterval()
                playbackService?.updateFrequencyCurve(newCurve)
            }
        } catch (e: IllegalArgumentException) {
            // Игнорируем ошибки валидации (например, несущая/биения вне
            // допустимых границ). Минимум точек — 1, поэтому удаление до
            // последней точки исключения больше не бросает.
        }
    }
    
    /**
     * Установить тип интерполяции для редактируемой кривой
     */
    fun setInterpolationType(type: InterpolationType) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val newCurve = FrequencyCurve(
            points = curve.points,
            carrierRange = curve.carrierRange,
            beatRange = curve.beatRange,
            interpolationType = type,
            splineTension = curve.splineTension
        )
        _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
        
        // Обновляем кривую в сервисе только если редактируется активный пресет
        if (isEditingActivePreset()) {
            armEditorPreviewBufferInterval()
            playbackService?.updateFrequencyCurve(newCurve)
        }
    }
    
    /**
     * Установить натяжение сплайна для редактируемой кривой
     */
    fun setSplineTension(tension: Float) {
        val state = _uiState.value
        val curve = state.editingFrequencyCurve ?: return
        
        val newCurve = FrequencyCurve(
            points = curve.points,
            carrierRange = curve.carrierRange,
            beatRange = curve.beatRange,
            interpolationType = curve.interpolationType,
            splineTension = tension.coerceIn(0f, 1f)
        )
        _uiState.update { it.copy(editingFrequencyCurve = newCurve) }
        
        // Обновляем кривую в сервисе только если редактируется активный пресет
        if (isEditingActivePreset()) {
            armEditorPreviewBufferInterval()
            playbackService?.updateFrequencyCurve(newCurve)
        }
    }

    // ============= Методы для редактирования режима расслабления =============

    /**
     * Пуш настроек расслабления из редактора в движок.
     *
     * Настройки расслабления — такие же опции редактора, как и точки кривой:
     * пока редактируется именно активный пресет, любое их изменение должно
     * пересобрать звучащий поток (кроссфейд), иначе слайдеры меняли бы только
     * сохраняемый пресет, а звук продолжал бы играть по-старому.
     * Вызывается из каждого setEditing-метода после обновления состояния.
     */
    private fun pushEditingRelaxationToService() {
        if (!isEditingActivePreset()) return
        armEditorPreviewBufferInterval()
        playbackService?.updateRelaxationModeSettings(_uiState.value.editingRelaxationModeSettings)
    }

    /**
     * Включить/выключить режим расслабления
     */
    fun setEditingRelaxationModeEnabled(enabled: Boolean) {
        val state = _uiState.value
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(enabled = enabled)
            )
        }
        pushEditingRelaxationToService()
    }
    
    /**
     * Установить процент снижения несущей частоты
     */
    fun setEditingCarrierReductionPercent(percent: Int) {
        val state = _uiState.value
        val clampedPercent = percent.coerceIn(0, RelaxationModeSettings.MAX_CARRIER_REDUCTION_PERCENT)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(carrierReductionPercent = clampedPercent)
            )
        }
        pushEditingRelaxationToService()
    }
    
    /**
     * Установить процент снижения частоты биений.
     *
     * Диапазон 0..200: ровно 100% гасит биения, выше — они нарастают снова с
     * обратным знаком (каналы меняются местами), на 200% модуль исходный.
     */
    fun setEditingBeatReductionPercent(percent: Int) {
        val state = _uiState.value
        val clampedPercent = percent.coerceIn(0, RelaxationModeSettings.MAX_BEAT_REDUCTION_PERCENT)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(beatReductionPercent = clampedPercent)
            )
        }
        pushEditingRelaxationToService()
    }
    
    /**
     * Установить режим расслабления (SIMPLE или ADVANCED)
     */
    fun setEditingRelaxationMode(mode: RelaxationMode) {
        val state = _uiState.value
        android.util.Log.d("BinauralViewModel", "setEditingRelaxationMode: mode=$mode, current smoothInterval=${state.editingRelaxationModeSettings.smoothIntervalMinutes}")
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(mode = mode)
            )
        }
        pushEditingRelaxationToService()
    }
    
    /**
     * Установить паузу между периодами расслабления (в минутах)
     */
    fun setEditingRelaxationGapMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(0, 120)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(gapBetweenRelaxationMinutes = clampedMinutes)
            )
        }
        pushEditingRelaxationToService()
    }
    
    /**
     * Установить длительность расслабления (в минутах)
     */
    fun setEditingRelaxationDurationMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(5, 60)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(relaxationDurationMinutes = clampedMinutes)
            )
        }
        pushEditingRelaxationToService()
    }
    
    /**
     * Установить период перехода (в минутах)
     */
    fun setEditingTransitionPeriodMinutes(minutes: Int) {
        val state = _uiState.value
        val clampedMinutes = minutes.coerceIn(1, 15)
        _uiState.update { 
            it.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(transitionPeriodMinutes = clampedMinutes)
            )
        }
        pushEditingRelaxationToService()
    }
    
    /**
     * Установить интервал между точками для SMOOTH режима (в минутах)
     */
    fun setEditingSmoothIntervalMinutes(minutes: Int) {
        val clampedMinutes = minutes.coerceIn(5, 120)
        _uiState.update { state ->
            android.util.Log.d("BinauralViewModel", "setEditingSmoothIntervalMinutes: minutes=$minutes, clamped=$clampedMinutes, current=${state.editingRelaxationModeSettings.smoothIntervalMinutes}")
            state.copy(
                editingRelaxationModeSettings = state.editingRelaxationModeSettings.copy(smoothIntervalMinutes = clampedMinutes)
            )
        }
        pushEditingRelaxationToService()
    }

    // ============= Методы для управления общими настройками приложения =============
    
    /**
     * Применить изменение настроек с КРОССФЕЙДОМ.
     *
     * Если воспроизводится — звук НЕ прерывается: [applyChanges] пушит новые
     * настройки в менеджер, а тот сам поднимает NEXT и гасит CURRENT
     * (requestHandoff -> beginHandoff); окно перекрытия равно длительности
     * фейда. Если не воспроизводится — изменения просто применяются.
     *
     * Каждое событие перезапускает ЕДИНСТВЕННЫЙ trailing-job: предыдущий
     * отменяется, новый после [SETTINGS_FADE_DEBOUNCE_MS] применяет изменения.
     * Это гарантирует применение после ЛЮБОЙ серии событий (например, drag
     * слайдера) — и ровно один кроссфейд на серию.
     *
     * Прежняя схема `stopWithFade() -> delay(300) -> play()` давала здесь до 8 с
     * тишины: play() приходил в FADE_OUT_STOP и лишь откладывался в
     * pendingPlaySpec, а реальный старт дожидался, пока писатель выйдет из
     * track.write(WRITE_BLOCKING) на весь WRITE_CHUNK_MS. Теперь старый поток
     * играет всё время дебаунса — гасить его незачем.
     */
    private fun restartWithFadeIfNeeded(applyChanges: () -> Unit) {
        // Незавершённый job означает продолжение серии событий:
        // воспроизведение было активным до её начала
        val hasPendingRestart = restartJob?.isActive == true
        val wasPlaying = hasPendingRestart ||
            (_telemetry.value.isPlaying && _uiState.value.isServiceConnected)

        restartJob?.cancel()

        if (!wasPlaying) {
            // Не воспроизводится - просто применяем изменения
            applyChanges()
            return
        }

        restartJob = viewModelScope.launch {
            // Дебаунс серии, а не ожидание тишины: старый поток звучит всё это время.
            kotlinx.coroutines.delay(SETTINGS_FADE_DEBOUNCE_MS)
            applyChanges()
            // play() больше НЕ нужен для перезапуска — хэндофф стартует NEXT сам.
            // Он остаётся страховкой: если поток успел погаснуть (отказ трека,
            // пауза), изменения применились бы в никуда. В RUNNING/FADE_IN/
            // HANDOFF play() идемпотентен.
            if (!_telemetry.value.isPlaying) playbackService?.play()
        }
    }
    
    fun setSampleRate(rate: SampleRate) {
        // Смена частоты дискретизации меняет и потолок длительности буфера:
        // тот же бюджет кучи покупает на 48 кГц вдвое меньше секунд, чем на
        // 16 кГц. Интервал, выбранный на прежней частоте, мог перестать
        // влезать — усекаем его до максимальной стопы новой частоты сразу
        // (не дожидаясь кроссфейда), чтобы слайдер, хранилище и движок не
        // расходились: иначе слайдер показал бы стопы, среди которых нет
        // выбранного значения, а движок молча урезал бы интервал.
        val currentMinutes = _uiState.value.bufferGenerationMinutes
        val clampedMinutes = PacketMemoryBudget.coerceMinutes(rate.value, currentMinutes)
        if (clampedMinutes != currentMinutes) {
            _uiState.update { it.copy(bufferGenerationMinutes = clampedMinutes) }
            playbackService?.setFrequencyUpdateInterval(clampedMinutes * 60 * 1000)
            viewModelScope.launch {
                preferencesRepository.saveBufferGenerationMinutes(clampedMinutes)
            }
        }
        restartWithFadeIfNeeded {
            _uiState.update { it.copy(sampleRate = rate) }
            playbackService?.setSampleRate(rate)
            viewModelScope.launch {
                preferencesRepository.saveSampleRate(rate.value)
            }
        }
    }
    
    /**
     * Установить интервал генерации буфера в минутах
     * Большой интервал = меньше пробуждений CPU = лучше энергопотребление
     */
    fun setBufferGenerationMinutes(minutes: Int) {
        // Верхний предел зависит от частоты (см. PacketMemoryBudget); округление
        // ВНИЗ по лестнице слайдера, чтобы сохранялось ровно то, что видно в UI.
        val rate = _uiState.value.sampleRate.value
        val clampedMinutes = PacketMemoryBudget.coerceMinutes(rate, minutes)
        _uiState.update { it.copy(bufferGenerationMinutes = clampedMinutes) }
        // Преобразуем минуты в миллисекунды
        playbackService?.setFrequencyUpdateInterval(clampedMinutes * 60 * 1000)
        viewModelScope.launch {
            preferencesRepository.saveBufferGenerationMinutes(clampedMinutes)
        }
    }
    
    // ============= Методы для управления глобальной нормализацией громкости =============
    
    /**
     * Включить/выключить нормализацию громкости (глобальная настройка)
     */
    fun setVolumeNormalizationEnabled(enabled: Boolean) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            // При включении устанавливаем CHANNEL, при выключении - NONE
            val newType = if (enabled) NormalizationType.CHANNEL else NormalizationType.NONE
            val newSettings = state.volumeNormalizationSettings.copy(type = newType)
            _uiState.update { it.copy(volumeNormalizationSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveVolumeNormalizationSettings(newSettings)
            }
        }
    }
    
    /**
     * Установить силу нормализации громкости (глобальная настройка)
     */
    fun setVolumeNormalizationStrength(strength: Float) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            val clampedStrength = strength.coerceIn(0f, 2f)
            val newSettings = state.volumeNormalizationSettings.copy(strength = clampedStrength)
            _uiState.update { it.copy(volumeNormalizationSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveVolumeNormalizationSettings(newSettings)
            }
        }
    }
    
    /**
     * Включить/выключить временную нормализацию (глобальная настройка)
     */
    fun setTemporalNormalizationEnabled(enabled: Boolean) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            // При включении временной нормализации устанавливаем TEMPORAL
            val newType = if (enabled) NormalizationType.TEMPORAL else NormalizationType.CHANNEL
            val newSettings = state.volumeNormalizationSettings.copy(type = newType)
            _uiState.update { it.copy(volumeNormalizationSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveVolumeNormalizationSettings(newSettings)
            }
        }
    }
    
    // ============= Методы для управления настройками перестановки каналов =============
    
    /**
     * Выбрать режим автоперестановки каналов одним чипом:
     * null = выключено, TIMER/TREND = включено с соответствующим режимом.
     * Одно событие -> один fade-перезапуск (вместо пары enabled+mode).
     */
    fun setChannelSwapSelection(mode: ChannelSwapMode?) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            val newSettings = state.channelSwapSettings.copy(
                enabled = mode != null,
                mode = mode ?: state.channelSwapSettings.mode
            )
            _uiState.update { it.copy(channelSwapSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveChannelSwapSettings(newSettings)
            }
        }
    }

    /**
     * Установить интервал перестановки каналов
     */
    fun setChannelSwapInterval(seconds: Int) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            val clampedSeconds = seconds.coerceIn(5, 3600)
            val newSettings = state.channelSwapSettings.copy(intervalSeconds = clampedSeconds)
            _uiState.update { it.copy(channelSwapSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveChannelSwapSettings(newSettings)
            }
        }
    }
    
    /**
     * Включить/выключить плавный переход при перестановке каналов
     */
    fun setChannelSwapFadeEnabled(enabled: Boolean) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            val newSettings = state.channelSwapSettings.copy(fadeEnabled = enabled)
            _uiState.update { it.copy(channelSwapSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveChannelSwapSettings(newSettings)
            }
        }
    }
    
    /**
     * Установить длительность плавного перехода при перестановке каналов
     */
    fun setChannelSwapFadeDuration(ms: Long) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            val clampedMs = ms.coerceIn(1000L, 15000L)
            val newSettings = state.channelSwapSettings.copy(fadeDurationMs = clampedMs)
            _uiState.update { it.copy(channelSwapSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveChannelSwapSettings(newSettings)
            }
        }
    }
    
    /**
     * Установить длительность паузы при переключении каналов (до 1 минуты)
     */
    fun setChannelSwapPauseDuration(ms: Long) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            val clampedMs = ms.coerceIn(0L, 60000L)
            val newSettings = state.channelSwapSettings.copy(pauseDurationMs = clampedMs)
            _uiState.update { it.copy(channelSwapSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveChannelSwapSettings(newSettings)
            }
        }
    }
    
    /**
     * Выбрать точки графика для перестановки каналов в TREND-режиме:
     * BOTH — на пиках и впадинах, PEAKS — только на пиках, TROUGHS — только на впадинах.
     */
    fun setChannelSwapTrendPoints(points: ChannelSwapTrendPoints) {
        restartWithFadeIfNeeded {
            val state = _uiState.value
            val newSettings = state.channelSwapSettings.copy(trendPoints = points)
            _uiState.update { it.copy(channelSwapSettings = newSettings) }
            updateAudioConfig()
            viewModelScope.launch {
                preferencesRepository.saveChannelSwapSettings(newSettings)
            }
        }
    }
    
    /**
     * Включить/выключить возобновление воспроизведения при подключении гарнитуры
     */
    fun setResumeOnHeadsetConnect(enabled: Boolean) {
        _uiState.update { it.copy(resumeOnHeadsetConnect = enabled) }
        playbackService?.setResumeOnHeadsetConnect(enabled)
        viewModelScope.launch {
            preferencesRepository.saveResumeOnHeadsetConnect(enabled)
        }
    }
    
    /**
     * Включить/выключить автовозобновление воспроизведения при запуске приложения
     */
    fun setAutoResumeOnAppStart(enabled: Boolean) {
        _uiState.update { it.copy(autoResumeOnAppStart = enabled) }
        viewModelScope.launch {
            preferencesRepository.saveAutoResumeOnAppStart(enabled)
        }
    }

    private fun updateAudioConfig() {
        // НЕ пушим конфиг, пока не прочитаны ВСЕ настоящие настройки из DataStore.
        // При пересоздании ViewModel (возврат из свёрнутого состояния) коллекторы
        // сначала эмитят значения по умолчанию из _uiState — и если отдать их в
        // живой движок, менеджер увидит «другой конфиг» и сделает кроссфейд/рестарт
        // уже звучащего потока. Ждём реальных значений (settingsReady).
        if (!settingsReady) {
            android.util.Log.d("BinauralViewModel", "updateAudioConfig: настройки ещё не прочитаны — пропускаем пуш в движок")
            return
        }
        // Намеренно без guard'а на «переключение пресета идёт»: смены настроек во
        // время воспроизведения — это тоже кроссфейд (updateConfig -> beginHandoff),
        // а не мгновенная подмена частот в звучащем потоке. Дубликаты отсекает сам
        // менеджер (updateConfig сравнивает конфиг и настройки расслабления).
        val state = _uiState.value
        
        // Используем настройки из редактируемого пресета если редактируется активный
        val isActivePresetEditing = state.editingPresetId != null && state.editingPresetId == state.activePreset?.id
        
        // Настройки каналов и нормализации всегда берём из глобального состояния
        val channelSwapSettings = state.channelSwapSettings
        val volumeNormalizationSettings = state.volumeNormalizationSettings
        
        val (frequencyCurve, relaxationModeSettings) = if (isActivePresetEditing) {
            Pair(
                state.editingFrequencyCurve ?: state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve(),
                state.editingRelaxationModeSettings
            )
        } else {
            Pair(
                state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve(),
                state.activePreset?.relaxationModeSettings ?: RelaxationModeSettings()
            )
        }
        
        val config = buildPlaybackConfig(
            frequencyCurve = frequencyCurve,
            volume = state.volume,
            channelSwap = channelSwapSettings,
            normalization = volumeNormalizationSettings
        )
        
        android.util.Log.d("BinauralViewModel", "updateAudioConfig: activePreset=${state.activePreset?.name}, " +
            "channelSwapEnabled=${channelSwapSettings.enabled}, " +
            "channelSwapInterval=${channelSwapSettings.intervalSeconds}s, " +
            "normalizationType=${volumeNormalizationSettings.type}, " +
            "relaxationEnabled=${relaxationModeSettings.enabled}, " +
            "isServiceConnected=${state.isServiceConnected}, " +
            "isActivePresetEditing=$isActivePresetEditing")
        
        playbackService?.updateConfig(config, relaxationModeSettings)
    }

    // ============= Методы для экспорта/импорта пресетов =============
    
    private val json = Json { 
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    
    /**
     * Экспортировать пресет в JSON строку
     */
    fun exportPresetToJson(presetId: String): String? {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return null
        return try {
            json.encodeToString(preset)
        } catch (e: Exception) {
            android.util.Log.e("BinauralViewModel", "Failed to export preset", e)
            null
        }
    }
    
    /**
     * Получить пресет для экспорта
     */
    fun getPresetForExport(presetId: String): BinauralPreset? {
        return _uiState.value.presets.find { it.id == presetId }
    }
    
    /**
     * Импортировать пресет из JSON
     * @return ID импортированного пресета или null при ошибке
     */
    fun importPresetFromJson(jsonString: String): String? {
        return try {
            val preset = json.decodeFromString<BinauralPreset>(jsonString)
            // Генерируем новый ID для импортированного пресета, чтобы избежать конфликтов
            val importedPreset = preset.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = generateUniqueName(preset.name),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            viewModelScope.launch {
                preferencesRepository.addPreset(importedPreset)
            }
            importedPreset.id
        } catch (e: Exception) {
            android.util.Log.e("BinauralViewModel", "Failed to import preset", e)
            null
        }
    }
    
    /**
     * Импортировать пресет из Uri файла
     * @return ID импортированного пресета или null при ошибке
     */
    fun importPresetFromUri(uri: Uri): String? {
        return try {
            val jsonString = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            } ?: return null
            
            importPresetFromJson(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("BinauralViewModel", "Failed to import preset from uri", e)
            null
        }
    }
    
    /**
     * Сгенерировать уникальное имя для дубликата/импортированного пресета
     * Извлекает базовое имя (без номера в скобках) и находит следующий доступный номер
     */
    private fun generateUniqueName(baseName: String): String {
        val existingNames = _uiState.value.presets.map { it.name }.toSet()
        
        // Пытаемся извлечь базовое имя и номер из строки вида "имя (N)"
        val regex = """^(.+?) \((\d+)\)$""".toRegex()
        val match = regex.find(baseName)
        
        // Если имя уже содержит номер в скобках, извлекаем базовое имя
        val actualBaseName = if (match != null) {
            match.groupValues[1]
        } else {
            baseName
        }
        
        // Ищем все существующие имена с таким же базовым именем
        val usedNumbers = mutableSetOf<Int>()
        var hasExactBaseName = false
        
        for (name in existingNames) {
            if (name == actualBaseName) {
                hasExactBaseName = true
            } else {
                val nameMatch = regex.find(name)
                if (nameMatch != null && nameMatch.groupValues[1] == actualBaseName) {
                    usedNumbers.add(nameMatch.groupValues[2].toInt())
                }
            }
        }
        
        // Если базовое имя свободно, используем его
        if (!hasExactBaseName && actualBaseName !in existingNames) {
            return actualBaseName
        }
        
        // Находим минимальный свободный номер, начиная с 1
        var counter = 1
        while (counter in usedNumbers) {
            counter++
        }
        
        return "$actualBaseName ($counter)"
    }
    
    /**
     * Дублировать пресет
     */
    fun duplicatePreset(presetId: String) {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        val duplicatedPreset = preset.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = generateUniqueName(preset.name),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            preferencesRepository.addPreset(duplicatedPreset)
        }
    }
    
    // ============= Методы для управления блокировкой навигации =============
    
    /**
     * Начать SharedTransition анимацию (блокирует навигацию)
     */
    fun startSharedTransition() {
        _uiState.update { it.copy(isSharedTransitionRunning = true) }
    }
    
    /**
     * Завершить SharedTransition анимацию (разблокирует навигацию)
     */
    fun endSharedTransition() {
        _uiState.update { it.copy(isSharedTransitionRunning = false) }
    }
    
    /**
     * Попытка автовозобновления воспроизведения при запуске приложения.
     * Вызывается после подключения сервиса, если включена соответствующая настройка.
     */
    private fun tryAutoResumeOnAppStart() {
        val state = _uiState.value
        
        // Проверяем, что:
        // 1. Автовозобновление включено
        // 2. Есть активный пресет
        // 3. Сервис подключен
        // 4. Воспроизведение не идёт
        // 5. Мы ещё не обрабатывали автовозобновление
        if (state.autoResumeOnAppStart &&
            state.activePreset != null &&
            state.isServiceConnected &&
            !_telemetry.value.isPlaying &&
            !autoResumeHandled) {
            
            autoResumeHandled = true
            android.util.Log.d("BinauralViewModel", "Auto-resuming playback on app start for preset: ${state.activePreset.name}")
            
            // Запускаем воспроизведение с fade-in
            playbackService?.resumeWithFade()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Зануляем callback, чтобы сервис не держал ссылку на уничтоженный ViewModel
        playbackService?.onPresetSwitch = null
        try {
            context.unbindService(serviceConnection)
        } catch (e: Exception) {
            // Сервис уже отвязан
        }
    }

    // ============= Debug virtual time (только debug) =============

    fun setDebugVirtualTimeEnabled(enabled: Boolean) {
        playbackService?.debugSetVirtualTimeEnabled(enabled)
        _uiState.update { it.copy(debugVirtualTimeEnabled = enabled) }
    }

    fun debugScrubTime(timeSeconds: Int) {
        val clamped = timeSeconds.coerceIn(0, 86399)
        // Перезапуск с затуханием, как и для остальных настроек:
        // новый "виртуальный момент" применяется к аудио сразу, без ожидания границы буфера
        restartWithFadeIfNeeded {
            playbackService?.debugScrub(clamped)
            // Мгновенное отражение в UI, не дожидаясь 1-секундного поллинга
            _telemetry.update { it.copy(currentTime = LocalTime.fromSecondOfDay(clamped)) }
        }
    }

    fun debugSetTimeScale(scale: Float) {
        val clamped = scale.coerceIn(1f, 60f)
        // Перезапуск с затуханием, как и для остальных настроек
        restartWithFadeIfNeeded {
            playbackService?.debugSetTimeScale(clamped)
            _uiState.update { it.copy(debugTimeScale = clamped) }
        }
    }

    fun debugSetVirtualTimeRunning(running: Boolean) {
        playbackService?.debugSetRunning(running)
        _uiState.update { it.copy(debugVirtualTimeRunning = running) }
    }

    fun debugResetToRealTime() {
        // Перезапуск с затуханием: возврат к реальному времени применяется сразу
        restartWithFadeIfNeeded {
            playbackService?.debugResetToRealTime()
        }
    }
}