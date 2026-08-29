package com.binaural.core.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeShaper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Частота дискретизации аудио
 */
enum class SampleRate(val value: Int) {
    ULTRA_LOW(8000),
    VERY_LOW(16000),
    LOW(22050),
    MEDIUM(44100),
    HIGH(48000);

    companion object {
        // Стерео float: 2 канала × 4 байта = 8 байт на сэмпл
        private const val BYTES_PER_SAMPLE = 8

        /**
         * Реальный предел длительности одного генерируемого пакета (в минутах)
         * для этой частоты дискретизации.
         *
         * Движок капает direct-буфер по [BinauralAudioEngine.MAX_BUFFER_BYTES],
         * поэтому «60 минут» из настроек физически достижимы только на 8000 Гц.
         * На 22050 Гц предел ~25 мин, на 44100 Гц ~12 мин, на 48000 Гц ~11 мин.
         * Без этого UI предлагал недостижимые значения и молча урезал их в лог.
         */
        fun maxBufferMinutes(sampleRate: SampleRate): Int = minOf(
            BinauralAudioEngine.MAX_BUFFER_MINUTES,
            BinauralAudioEngine.MAX_BUFFER_BYTES / (sampleRate.value * BYTES_PER_SAMPLE) / 60
        ).coerceAtLeast(1)

        fun fromValue(value: Int): SampleRate = entries.find { it.value == value } ?: MEDIUM
    }
}

/**
 * Движок для генерации и воспроизведения бинауральных ритмов.
 * Работает в отдельном потоке (HandlerThread) для исключения задержек в UI.
 * 
 * АРХИТЕКТУРА:
 * - Генерация аудио делегируется в NativeAudioEngine (C++)
 * - Этот класс управляет AudioTrack и VolumeShaper для воспроизведения
 * - VolumeShaper обеспечивает плавные переходы при старте/остановке
 */
class BinauralAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "BinauralAudioEngine"
        // Запас 3 секунды (фикс Qwen, P3): генерация следующего пакета занимает
        // ~30 мс CPU-спайком; при буфере в 1 с любой спайк/GC со стороны другого
        // процесса или системы даёт underrun на границе пакетов (слышимый стык).
        private const val BUFFER_SIZE_MS = 3000
        private const val WAKE_LOCK_TAG = "BinauralBeats:PlaybackWakeLock"
        private const val THREAD_NAME = "BinauralAudioThread"
        private const val MIN_VOLUME = 0.001f
        private const val PLAYBACK_FADE_DURATION_MS = 250L
        // C5: короткий fade-in при resume из мягкой паузы (защита от щелчка)
        private const val RESUME_FADE_DURATION_MS = 150L

        // Множитель интервала при Battery Saver (3x = 30 сек вместо 10 сек)
        private const val POWER_SAVE_INTERVAL_MULTIPLIER = 3

        // C2: максимальный размер буфера в минутах — верхняя граница настроек.
        // public: SampleRate.maxBufferMinutes() считает по нему достижимый предел.
        const val MAX_BUFFER_MINUTES = 60

        // C2: жёсткий байтовый кап на direct-буфер (применяется и в старте, и при реаллокации).
        // При 48000 Гц стерео float: 8 байт/с на сэмпл-канал => ~11.6 мин аудио
        // public: SampleRate.maxBufferMinutes() использует его для честного UI-диапазона.
        const val MAX_BUFFER_BYTES = 256 * 1024 * 1024

        // Токен для отмены callbacks при переключении частоты дискретизации
        private val RESTART_PLAYBACK_TOKEN = Any()
    }
    
    // Атомарные ссылки для потокобезопасного доступа
    private val configRef = AtomicReference(BinauralConfig())
    private val isActive = AtomicBoolean(false)
    private val pendingSampleRate = AtomicReference<SampleRate?>(null)
    private val pendingFrequencyUpdateIntervalMs = AtomicReference<Int?>(null)

    // Запросы на операции с fade (потокобезопасные)
    private val stopWithFadeRequested = AtomicBoolean(false)
    private val pauseWithFadeRequested = AtomicBoolean(false)
    private val presetSwitchRequested = AtomicReference<BinauralConfig?>(null)

    // C7: запрос на перестроение нативной конфигурации после живого редактирования кривой
    private val pendingCurveUpdate = AtomicBoolean(false)

    // C7: последние применённые настройки релаксации — нужны для корректной
    // развёртки виртуальных точек при отложенном применении кривой
    @Volatile
    private var lastRelaxationSettings = RelaxationModeSettings()

    // Пресет, ожидающий применения через fade-перезапуск (см. checkFadeRequests)
    @Volatile
    private var pendingPresetConfig: BinauralConfig? = null

    // Флаг для отслеживания запланированной операции перезапуска после смены частоты
    @Volatile
    private var restartPlaybackScheduled = false

    // Токен для debounce операций переключения частоты дискретизации
    private val SAMPLE_RATE_CHANGE_TOKEN = Any()

    // Текущие настройки.
    // Дефолт 22 050 Гц согласован с PreferencesRepository и BinauralUiState:
    // сигнал < 2 кГц, Найквист 11 кГц — вдвое меньше памяти и DSP, чем 44 100,
    // без слышимой разницы (см. docs/battery_hotpaths_analysis.md §4.1).
    private var sampleRate: Int = SampleRate.LOW.value
    private var frequencyUpdateIntervalMs: Int = 600_000

    // C4: последний пользовательский интервал (для восстановления после debug-времени)
    @Volatile
    private var lastUserIntervalMs: Int = 600_000

    // C4: включён ли debug-виртуальный таймлайн (интервал форсируется в 250 мс)
    @Volatile
    private var debugVirtualTimeEnabled = false

    // DirectByteBuffer для zero-copy генерации
    // Запись в AudioTrack выполняется порциями не больше audioTrackBufferSize
    private var directAudioBuffer: java.nio.ByteBuffer? = null
    private var audioTrackBufferSize = 0  // Размер буфера AudioTrack в байтах

    // AudioTrack
    private var audioTrack: AudioTrack? = null

    // HandlerThread для генерации аудио
    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    private var isGenerating = false

    // WakeLock для предотвращения засыпания (K5: volatile — доступ из 3 потоков)
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    // VolumeShaper для плавного изменения громкости при старте/остановке
    @Volatile
    private var volumeShaper: VolumeShaper? = null
    
    // Громкость, установленная пользователем (0.0 - 1.0)
    // Сохраняется между сессиями воспроизведения и не изменяется при fade
    private var userVolume: Float = 1.0f
    
    // Текущая громкость (0.0 - 1.0) - используется для отслеживания состояния fade
    private var currentVolume: Float = 1.0f
    
    // Трекинг параметров fade (C12: доступны из разных потоков)
    @Volatile
    private var fadeStartTime: Long = 0L
    @Volatile
    private var fadeDurationMs: Long = 0L
    @Volatile
    private var fadeStartVolume: Float = 0.0f
    @Volatile
    private var fadeTargetVolume: Float = 1.0f
    @Volatile
    private var isFadeInProgress: Boolean = false

    // F5: epoch-ms, когда fade-in шейпер можно закрыть; 0 = активного fade-in нет.
    // Заменяет отложенные токены: посты в audioHandler не доставляются из живого цикла.
    @Volatile
    private var fadeShaperCloseAtMs: Long = 0L

    // Время начала воспроизведения
    private var playbackStartTime = 0L
    
    // Накопленное время для pause/resume
    private var accumulatedElapsedMs = 0L

    // Нативный движок (C++) - всегда используется для генерации аудио
    private var nativeEngine: NativeAudioEngine? = null

    // StateFlows для UI
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentConfig = MutableStateFlow(BinauralConfig())
    val currentConfig: StateFlow<BinauralConfig> = _currentConfig.asStateFlow()

    private val _currentBeatFrequency = MutableStateFlow(0.0f)
    val currentBeatFrequency: StateFlow<Float> = _currentBeatFrequency.asStateFlow()

    private val _currentCarrierFrequency = MutableStateFlow(0.0f)
    val currentCarrierFrequency: StateFlow<Float> = _currentCarrierFrequency.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
    
    // НОВОЕ: текущее время суток (реальное или виртуальное в debug)
    private val _currentTimeOfDaySeconds = MutableStateFlow(0)
    val currentTimeOfDaySeconds: StateFlow<Int> = _currentTimeOfDaySeconds.asStateFlow()
    
    private val _isChannelsSwapped = MutableStateFlow(false)
    val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()

    /**
     * Получить текущие частоты по текущему времени суток.
     * O(1) операция - использует предвычисленную lookup table в C++.
     * @return Pair(beatFrequency, carrierFrequency) или null если конфиг не установлен
     */
    fun getFrequenciesAtCurrentTime(): Pair<Float, Float>? {
        return nativeEngine?.getFrequenciesAtCurrentTime()
    }
    
    /**
     * Обновить текущие частоты из lookup table.
     * Вызывается периодически из UI для отображения актуальных частот.
     */
    fun updateCurrentFrequencies() {
        val result = getFrequenciesAtCurrentTime()
        if (result != null) {
            _currentBeatFrequency.value = result.first
            _currentCarrierFrequency.value = result.second
        }
        // НОВОЕ: время суток (реальное или виртуальное из нативного движка)
        nativeEngine?.let {
            _currentTimeOfDaySeconds.value = it.getCurrentTimeOfDay()
        }
    }

    /**
     * Инициализация движка. Должна вызываться один раз при создании.
     */
    fun initialize() {
        // THREAD_PRIORITY_AUDIO (-16) обеспечивает наилучший приоритет для аудио-потока
        // Это предотвращает задержки и прерывания при генерации звука
        audioThread = HandlerThread(THREAD_NAME, android.os.Process.THREAD_PRIORITY_AUDIO).apply { start() }
        audioHandler = Handler(audioThread!!.looper)
        
        // Инициализируем нативный движок
        nativeEngine = NativeAudioEngine()
        nativeEngine?.initialize()
        nativeEngine?.setSampleRate(sampleRate)

        // Отпечаток версии: позволяет убедиться, что на устройстве НОВАЯ сборка
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                     else @Suppress("DEPRECATION") pi.versionCode.toLong()
            Log.i(TAG, "ENGINE_INIT: pkg=${context.packageName} ver=${pi.versionName} code=$vc")
        }

        Log.d(TAG, "Audio engine initialized on thread: ${audioThread?.name}")
    }

    /**
     * Обновить конфигурацию (потокобезопасно)
     */
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        // Диагностика стыков: кто подменяет конфиг во время воспроизведения?
        // Стек покажет триггер (relaxation / Flow / UI), если частоты скачут.
        if (isActive.get()) {
            Log.w(TAG, "updateConfig() while playing", Throwable("updateConfig stacktrace"))
        }
        configRef.set(config)
        _currentConfig.value = config
        lastRelaxationSettings = relaxationSettings
        // ВАЖНО: НЕ обновляем userVolume из config.volume!
        // userVolume управляется ТОЛЬКО через setVolume() от слайдера пользователя.
        // VolumeShaper используется исключительно для плавного затухания/восстановления
        // и не должен менять базовую громкость воспроизведения.
        // config.volume используется только при сохранении/загрузке настроек.
        nativeEngine?.updateConfig(config, relaxationSettings)
        // НЕ обновляем частоты здесь - native engine может ещё не иметь актуальных данных.
        // Частоты обновляются ежесекундно в startUiFrequencyUpdateJob() через lookup table.
    }
    
    /**
     * Обновить настройки режима расслабления (потокобезопасно)
     */
    fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        lastRelaxationSettings = settings
        nativeEngine?.updateRelaxationModeSettings(settings)
    }

    /**
     * Обновить кривую частот (потокобезопасно).
     * C7: нативная конфигурация перестраивается в generateAudioLoop (pendingCurveUpdate).
     */
    fun updateFrequencyCurve(curve: FrequencyCurve) {
        val currentConfig = configRef.get()
        configRef.set(currentConfig.copy(frequencyCurve = curve))
        _currentConfig.value = configRef.get()
        pendingCurveUpdate.set(true)
    }

    /**
     * Начать воспроизведение (потокобезопасно)
     */
    fun play() {
        Log.d(TAG, "play() called, isPlaying=${_isPlaying.value}, isActive=${isActive.get()}")
        
        val handler = audioHandler
        if (handler == null) {
            Log.e(TAG, "AudioHandler is null! Cannot start playback")
            return
        }
        
        // Если идёт fade-out, прерываем его
        if (isActive.get() && !_isPlaying.value) {
            Log.d(TAG, "Interrupting fade-out")
            handler.removeCallbacksAndMessages(null)
            stopWithFadeRequested.set(false)
            pauseWithFadeRequested.set(false)
            
            // При прерывании восстанавливаем пользовательскую громкость
            // (не currentVolume, которая может быть MIN_VOLUME)
            try {
                volumeShaper?.close()
                volumeShaper = null
                audioTrack?.setVolume(userVolume)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting volume: ${e.message}")
            }
            
            currentVolume = userVolume
            isActive.set(false)
            isFadeInProgress = false
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }
            
            handler.postDelayed({ startNewPlayback(handler) }, 100)
            return
        }
        
        if (isActive.get()) {
            Log.d(TAG, "Already active, returning")
            return
        }
        
        startNewPlayback(handler)
    }
    
    private fun startNewPlayback(handler: Handler) {
        Log.d(TAG, "startNewPlayback() called, isActive=${isActive.get()}")
        
        if (isActive.get()) {
            Log.w(TAG, "startNewPlayback() - already active, returning")
            return
        }
        
        isActive.set(true)
        playbackStartTime = System.currentTimeMillis()

        // Применяем отложенный пресет (смена через fade-перезапуск, F5)
        pendingPresetConfig?.let { cfg ->
            nativeEngine?.updateConfig(cfg, lastRelaxationSettings)
            configRef.set(cfg)
            _currentConfig.value = cfg
            pendingPresetConfig = null
            Log.d(TAG, "startNewPlayback(): applied pending preset config")
        }

        Log.d(TAG, "startNewPlayback() - calling nativeEngine.resetState() and play()")

        // Гонка со старым циклом генерации: startNewPlayback выполняется в
        // вызывающем потоке, а generateAudioLoop живёт на audioHandler-потоке.
        // Если цикл ещё не вышел (isActive уже false, но текущий
        // nativeGenerateBufferDirect/запись в трек досматривается), resetState()+play()
        // разорвут m_state нативного движка (фазы свопа, timeline, TREND-init).
        // Ждём завершения цикла ограниченно; таймаут — деградация к прежнему
        // поведению с предупреждением в лог.
        if (isGenerating) {
            val waitStartMs = System.currentTimeMillis()
            while (isGenerating && System.currentTimeMillis() - waitStartMs < 1000) {
                Thread.sleep(10)
            }
            if (isGenerating) {
                Log.w(TAG, "startNewPlayback() - generation loop still running after 1s, proceeding anyway")
            } else {
                Log.d(TAG, "startNewPlayback() - waited ${System.currentTimeMillis() - waitStartMs}ms for old loop to finish")
            }
        }

        nativeEngine?.resetState()
        nativeEngine?.play()
        // НЕ обновляем частоты здесь - native engine может ещё не иметь актуальных данных.
        // Частоты обновляются ежесекундно в startUiFrequencyUpdateJob() через lookup table.
        
        // Сообщаем UI о начале воспроизведения
        _isPlaying.value = true

        acquireWakeLock()
        Log.d(TAG, "startNewPlayback() - posting startPlayback to handler")
        handler.post(::startPlayback)
    }

    // Вызывается только через handler.post на единственном аудио-потоке —
    // сериализация обеспечена лупером; @Synchronized здесь создавал дедлок:
    // монитор удерживался всю сессию (включая sleep паузы), а
    // acquireWakeLock из resumeWithFade (main/binder) ждал бы вечно.
    private fun startPlayback() {
        Log.d(TAG, "startPlayback() called, isActive=${isActive.get()}")

        // Проверяем что не идёт перезапуск с другой частотой
        if (restartPlaybackScheduled) {
            Log.w(TAG, "startPlayback() - restart is scheduled, skipping")
            return
        }

        if (!isActive.get()) {
            Log.w(TAG, "startPlayback() - isActive is false, returning")
            return
        }

        Log.d(TAG, "startPlayback() on thread: ${Thread.currentThread().name}")

        try {
            // Максимальный размер буфера в сэмплах (из MAX_BUFFER_MINUTES)
            val maxSamplesPerChannelLimit = sampleRate * 60 * MAX_BUFFER_MINUTES
            
            // Учитываем отложенный интервал при создании буфера
            // pendingFrequencyUpdateIntervalMs может быть установлен до нажатия play
            val effectiveIntervalMs = pendingFrequencyUpdateIntervalMs.get() ?: frequencyUpdateIntervalMs
            
            // Вычисляем размер буфера на основе эффективного интервала с ограничением
            val requestedSamplesPerChannel = (sampleRate.toLong() * effectiveIntervalMs / 1000).toInt()
            val samplesPerChannel = minOf(requestedSamplesPerChannel, maxSamplesPerChannelLimit)

            // F7: честность капа — реальный интервал урезан лимитом буфера
            val effectiveBufferIntervalMs = samplesPerChannel * 1000L / sampleRate
            if (effectiveBufferIntervalMs < effectiveIntervalMs - 999) {
                Log.w(TAG, "Buffer interval capped by buffer limit: requested=${effectiveIntervalMs}ms, effective=${effectiveBufferIntervalMs}ms")
            }

            // Создаём DirectByteBuffer для zero-copy генерации
            // Размер: samplesPerChannel * 2 канала * 4 байта на float
            // Дополнительно ограничиваем MAX_BUFFER_BYTES для защиты от OOM
            val directBufferSize = minOf(
                samplesPerChannel * 2 * 4,
                MAX_BUFFER_BYTES
            )

            if (directAudioBuffer == null || directAudioBuffer!!.capacity() < directBufferSize) {
                directAudioBuffer = allocateDirectBuffer(directBufferSize, sampleRate)
                if (directAudioBuffer != null) {
                    Log.d(TAG, "Created DirectByteBuffer: $directBufferSize bytes (${directBufferSize / 1024 / 1024} MB) for interval ${effectiveBufferIntervalMs}ms")
                } else {
                    Log.e(TAG, "Failed to allocate DirectByteBuffer ($directBufferSize bytes)")
                }
            }
            
            createAudioTrack()
            // Fade-in от MIN_VOLUME до полной громкости (1.0 как множитель)
            // AudioTrack уже имеет базовую громкость userVolume,
            // VolumeShaper работает как множитель: итоговая = userVolume × VolumeShaper.value
            currentVolume = MIN_VOLUME  // Начинаем с минимума для плавного нарастания
            createVolumeShaper(PLAYBACK_FADE_DURATION_MS, targetVolume = 1.0f)
            audioTrack?.play()
            startVolumeShaper()
            
            // ВАЖНО: Не используем отложенный callback для установки userVolume!
            // Если пользователь изменит громкость во время fade-in, callback перезапишет
            // новое значение на старое. Вместо этого VolumeShaper плавно приводит громкость
            // к userVolume, и после завершения шейпера просто освобождаем ресурсы.
            // Громкость установлена через AudioTrack.setVolume(userVolume) в createAudioTrack()
            // или через setVolume() от слайдера пользователя.
            // F6: закрытие шейпера после fade-in выполняет generateAudioLoop
            // (fadeShaperCloseAtMs) — посты в audioHandler не доставляются из живого цикла.
            fadeShaperCloseAtMs = System.currentTimeMillis() + PLAYBACK_FADE_DURATION_MS + 50
            
            generateAudioLoop()
            Log.d(TAG, "startPlayback() - generateAudioLoop() completed normally")
        } catch (e: Exception) {
            Log.e(TAG, "Playback error", e)
        } finally {
            Log.d(TAG, "startPlayback() - finally block, calling cleanupPlayback()")
            cleanupPlayback()
        }
    }
    
    private fun createAudioTrack() {
        val encoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioFormat.ENCODING_PCM_FLOAT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            encoding
        )
        
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val bufferSize = maxOf(minBufferSize, sampleRate * 2 * bytesPerSample * BUFFER_SIZE_MS / 1000)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Устанавливаем громкость пользователя как базовую.
        // userVolume - единственный источник истины для громкости.
        // VolumeShaper работает как множитель поверх AudioTrack.setVolume():
        // итоговая_громкость = AudioTrack.volume × VolumeShaper.value
        // При fade-in: userVolume × [0.001→1.0] = плавное нарастание от MIN_VOLUME до userVolume
        audioTrack?.setVolume(userVolume)
        audioTrackBufferSize = bufferSize
        Log.d(TAG, "AudioTrack created: sampleRate=$sampleRate, bufferSize=$bufferSize")
    }

    /**
     * F8: аллокация direct-буфера с защитой от OOM — при нехватке памяти размер
     * уменьшается вдвое до минимума max(audioTrackBufferSize, 1с аудио).
     * @return буфер или null, если недоступен даже минимальный размер
     */
    private fun allocateDirectBuffer(sizeBytes: Int, rateHz: Int): java.nio.ByteBuffer? {
        val minSize = maxOf(audioTrackBufferSize, rateHz * 2 * 4)
        var size = sizeBytes
        while (true) {
            try {
                return java.nio.ByteBuffer.allocateDirect(size)
                    .order(java.nio.ByteOrder.nativeOrder())
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OutOfMemoryError allocating ${size / 1024 / 1024} MB direct buffer")
                if (size <= minSize) return null
                size = maxOf(minSize, size / 2)
            }
        }
    }
    
    private fun getVolumeFromShaper(): Float {
        if (isFadeInProgress && fadeDurationMs > 0) {
            val elapsed = System.currentTimeMillis() - fadeStartTime
            val progress = (elapsed.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
            return fadeStartVolume + (fadeTargetVolume - fadeStartVolume) * progress
        }
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                volumeShaper?.volume ?: currentVolume
            } catch (e: Exception) {
                currentVolume
            }
        } else {
            currentVolume
        }
    }
    
    private fun createVolumeShaper(durationMs: Long, targetVolume: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            volumeShaper?.close()
            
            val startVolume = currentVolume.coerceIn(MIN_VOLUME, 1.0f)
            val clampedTarget = targetVolume.coerceIn(0.0f, 1.0f)
            
            if (kotlin.math.abs(startVolume - clampedTarget) < 0.01f) {
                currentVolume = clampedTarget
                isFadeInProgress = false
                // При fade-in (target = 1.0) устанавливаем userVolume как базовую громкость
                // При fade-out (target = 0.0) устанавливаем 0
                val finalVolume = if (clampedTarget >= 0.99f) userVolume else clampedTarget
                audioTrack?.setVolume(finalVolume)
                return
            }
            
            val volumeChange = kotlin.math.abs(clampedTarget - startVolume)
            val adjustedDuration = (durationMs * volumeChange).toLong().coerceAtLeast(50)
            
            val config = VolumeShaper.Configuration.Builder()
                .setDuration(adjustedDuration)
                .setCurve(floatArrayOf(0f, 1f), floatArrayOf(startVolume, clampedTarget))
                .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                .build()
            
            volumeShaper = audioTrack?.createVolumeShaper(config)
            
            fadeStartTime = System.currentTimeMillis()
            fadeDurationMs = adjustedDuration
            fadeStartVolume = startVolume
            fadeTargetVolume = clampedTarget
            
            Log.d(TAG, "VolumeShaper created: $startVolume → $clampedTarget, duration=${adjustedDuration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VolumeShaper: ${e.message}")
            volumeShaper = null
            isFadeInProgress = false
            audioTrack?.setVolume(targetVolume.coerceIn(0.0f, 1.0f))
        }
    }
    
    private fun startVolumeShaper() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            val shaper = volumeShaper
            if (shaper != null) {
                shaper.apply(VolumeShaper.Operation.PLAY)
                isFadeInProgress = true
                Log.d(TAG, "VolumeShaper started")
            } else {
                isFadeInProgress = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VolumeShaper: ${e.message}")
            isFadeInProgress = false
        }
    }
    
    private fun generateAudioLoop() {
        Log.d(TAG, "generateAudioLoop() started, isActive=${isActive.get()}")

        val engine = nativeEngine
        if (engine == null) {
            Log.e(TAG, "generateAudioLoop() - nativeEngine is null, returning")
            return
        }

        // Локальная копия sampleRate для защиты от изменений во время работы
        val localSampleRate = sampleRate
        // Максимальный размер буфера в сэмплах (из MAX_BUFFER_MINUTES)
        val maxSamplesPerChannelLimit = localSampleRate * 60 * MAX_BUFFER_MINUTES
        // C2: тот же байтовый кап, что и в startPlayback — стерео float = 8 байт/сэмпл
        val maxSamplesByBytesLimit = MAX_BUFFER_BYTES / 8

        var currentIntervalMs = frequencyUpdateIntervalMs
        var samplesPerChannel = minOf(
            (localSampleRate.toLong() * currentIntervalMs / 1000).toInt(),
            maxSamplesPerChannelLimit,
            maxSamplesByBytesLimit
        )

        isGenerating = true
        Log.d(TAG, "generateAudioLoop() - entering main loop, isActive=${isActive.get()}, audioTrack=$audioTrack")

        while (isActive.get() && audioTrack != null) {
            // C6: признак ожидающих изменений собираем ДО их потребления,
            // чтобы эта итерация сгенерировала короткий пакет (~1 с) и
            // изменение прозвучало быстро, а не через полный интервал.
            val hasPendingChanges = pendingSampleRate.get() != null ||
                pendingFrequencyUpdateIntervalMs.get() != null ||
                pendingCurveUpdate.get() ||
                presetSwitchRequested.get() != null ||
                pendingPresetConfig != null ||
                stopWithFadeRequested.get() ||
                pauseWithFadeRequested.get()

            applyPendingSettings()

            if (frequencyUpdateIntervalMs != currentIntervalMs) {
                currentIntervalMs = frequencyUpdateIntervalMs
                samplesPerChannel = minOf(
                    (localSampleRate.toLong() * currentIntervalMs / 1000).toInt(),
                    maxSamplesPerChannelLimit,
                    maxSamplesByBytesLimit
                )

                // F7: честность капа — реальный интервал урезан лимитом буфера
                val effectiveBufferIntervalMs = samplesPerChannel * 1000L / localSampleRate
                if (effectiveBufferIntervalMs < currentIntervalMs - 999) {
                    Log.w(TAG, "Buffer interval capped by buffer limit: requested=${currentIntervalMs}ms, effective=${effectiveBufferIntervalMs}ms")
                }

                // Пересоздаём буфер если нужно больше места
                val requiredSize = samplesPerChannel * 2 * 4
                if (directAudioBuffer == null || directAudioBuffer!!.capacity() < requiredSize) {
                    directAudioBuffer = allocateDirectBuffer(requiredSize, localSampleRate)
                    if (directAudioBuffer != null) {
                        Log.d(TAG, "Resized DirectByteBuffer: $requiredSize bytes (${requiredSize / 1024 / 1024} MB) for interval ${effectiveBufferIntervalMs}ms")
                    } else {
                        Log.e(TAG, "Failed to resize DirectByteBuffer ($requiredSize bytes)")
                    }
                }
            }

            checkFadeRequests()

            // F5: закрываем шейпер завершённого fade-in. ВАЖНО: выше guard-sleep,
            // иначе брошенный (например резюмом) шейпер не закрылся бы на паузе
            val shaperCloseAtMs = fadeShaperCloseAtMs
            if (shaperCloseAtMs > 0 && System.currentTimeMillis() >= shaperCloseAtMs) {
                volumeShaper?.close()
                volumeShaper = null
                isFadeInProgress = false
                fadeShaperCloseAtMs = 0L
                Log.d(TAG, "Fade-in completed, shaper closed")
            }

            // Мягкая пауза: трек уже на паузе — запись в него заблокирует WRITE_BLOCKING.
            // Держим цикл живым для мягкого resume, но не генерируем и не пишем.
            if (!_isPlaying.value) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                continue
            }

            // C6: короткий пакет при ожидающих изменениях. Таймлайн непрерывен:
            // движок генерирует ровно effectiveSamples по sample-driven оси.
            val effectiveSamples = if (hasPendingChanges) {
                minOf(samplesPerChannel, localSampleRate)
            } else {
                samplesPerChannel
            }

            // C8: продление WakeLock на длинных сессиях
            acquireWakeLock()

            // Получаем актуальный буфер (может измениться при ресайзе)
            val directBuffer = directAudioBuffer
            if (directBuffer == null) {
                Log.e(TAG, "generateAudioLoop() - directAudioBuffer is null, returning")
                break
            }

            // Zero-copy генерация через DirectByteBuffer.
            // ВАЖНО: generated может быть немного меньше samplesPerChannel
            // (целочисленное округление длительностей сегментов в нативном
            // планировщике). Ниже пишем в AudioTrack ровно generated сэмплов.
            directBuffer.clear()
            val generated = engine.generateBufferDirect(directBuffer, effectiveSamples)
            
            if (generated <= 0) {
                Log.e(TAG, "Native buffer generation failed or not playing")
                break
            }

            // ВНИМАНИЕ: Частоты НЕ обновляем здесь!
            // Это вызывало мерцание некорректных значений при старте/смене пресета.
            // Частоты обновляются только через updateCurrentFrequencies() в:
            // - startUiFrequencyUpdateJob() - каждую секунду (когда приложение на экране)
            // - startNotificationUpdateJob() - каждые 10 секунд (всегда)
            
            val swapped = engine.isChannelsSwapped()
            if (_isChannelsSwapped.value != swapped) {
                _isChannelsSwapped.value = swapped
            }
            
            val elapsed = engine.getElapsedSeconds()
            if (_elapsedSeconds.value != elapsed) {
                _elapsedSeconds.value = elapsed
            }

            if (!isActive.get()) break

            val currentAudioTrack = audioTrack
            if (currentAudioTrack == null) {
                Log.d(TAG, "AudioTrack is null, stopping")
                break
            }

            // Запись в AudioTrack через DirectByteBuffer.
            // КРИТИЧНО: пишем ровно generated сэмплов (generated * 2 канала * 4 байта),
            // а не полный размер буфера. Остаток буфера содержит мусор от прошлого
            // пакета — его запись и была причиной щелчка + скачка частот на стыке.
            directBuffer.position(0)
            val sizeInBytes = generated * 2 * 4

            // Диагностика HAL: позиция головы воспроизведения и underrun'ы
            // до/после записи пакета.
            val headBefore = currentAudioTrack.playbackHeadPosition
            val underrunBefore = currentAudioTrack.underrunCount

            val writeResult = try {
                // Записываем порциями не больше audioTrackBufferSize
                var totalWritten = 0
                var interruptedByRequest = false
                while (totalWritten < sizeInBytes && isActive.get()) {
                    // C3: управление должно работать посреди досыпки пакета,
                    // а не после дренирования всех 10 минут. Недописанный остаток
                    // доигрывает буфер AudioTrack сам, флаги потребит
                    // checkFadeRequests следующей итерации.
                    if (pauseWithFadeRequested.get() ||
                        stopWithFadeRequested.get() ||
                        presetSwitchRequested.get() != null
                    ) {
                        interruptedByRequest = true
                        Log.i(TAG, "PKG_WRITE interrupted by control request: " +
                              "$totalWritten/$sizeInBytes bytes")
                        break
                    }
                    val remaining = sizeInBytes - totalWritten
                    // Кап чанка ~1с аудио — гранулярность реакции на флаги
                    val chunkSize = minOf(
                        remaining,
                        audioTrackBufferSize,
                        localSampleRate * 2 * 4
                    )

                    directBuffer.position(totalWritten)
                    directBuffer.limit(totalWritten + chunkSize)

                    val written = currentAudioTrack.write(directBuffer, chunkSize, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.e(TAG, "DirectByteBuffer write failed at offset $totalWritten: $written")
                        break
                    }
                    totalWritten += written
                }

                // Частичная запись по запросу управления — НЕ ошибка:
                // иначе разрыв внешнего цикла убивал сессию без fade/pause
                when {
                    interruptedByRequest -> totalWritten
                    totalWritten == sizeInBytes -> sizeInBytes
                    else -> -1
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack write error: ${e.message}")
                -1
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack write exception: ${e.message}")
                -1
            }

            if (writeResult < 0) {
                Log.d(TAG, "Write failed, result=$writeResult")
                break
            }

            // Диагностика HAL после записи пакета
            val headAfter = currentAudioTrack.playbackHeadPosition
            val underrunAfter = currentAudioTrack.underrunCount
            Log.i(TAG, "PKG_WRITE: gen=$generated headDelta=${headAfter - headBefore} " +
                  "underrun=$underrunBefore->$underrunAfter " +
                  "capFrames=${currentAudioTrack.bufferCapacityInFrames} " +
                  "trackSr=${currentAudioTrack.sampleRate} sysMs=${System.currentTimeMillis()}")
        }

        isGenerating = false
        Log.d(TAG, "generateAudioLoop() ended")
    }
    
    private fun checkFadeRequests() {
        if (stopWithFadeRequested.get()) {
            Log.d(TAG, "Starting fade-out for stop")
            // F1/F3: фейд и остановка выполняются инлайн — посты из живого
            // цикла генерации не доставляются (лупер занят startPlayback)
            runFadeOutInline(PLAYBACK_FADE_DURATION_MS)
            if (isActive.get()) {
                isActive.set(false)
                if (pendingPresetConfig != null) {
                    // Если ждала смена пресета — после fade-out перезапускаем
                    // с новым конфигом (свежий таймлайн), а не останавливаемся.
                    // Цикл выйдет по !isActive, после чего пост будет доставлен.
                    audioHandler?.post { play() }
                } else {
                    stopPlayback()
                }
            }
            stopWithFadeRequested.set(false)
            return
        }

        if (pauseWithFadeRequested.get()) {
            Log.d(TAG, "Starting fade-out for pause")
            // F1/F2: фейд и пауза выполняются инлайн, без мёртвых постов
            runFadeOutInline(PLAYBACK_FADE_DURATION_MS)
            if (isActive.get()) {
                executePause()
            }
            pauseWithFadeRequested.set(false)
            return
        }
        
        presetSwitchRequested.getAndSet(null)?.let { newConfig ->
            // НЕ применяем конфиг мгновенно на границе пакетов — это давало
            // резкую смену частот без перехода. Маршрутизируем через fade-out →
            // перезапуск: конфиг применится в startNewPlayback().
            Log.w(TAG, "PRESET_SWITCH requested -> will apply via fade-restart at sysTimeMs=${System.currentTimeMillis()}")
            pendingPresetConfig = newConfig
            stopWithFadeRequested.set(true)
        }
    }
    
    /**
     * F1: инлайн fade-out — создаёт и запускает VolumeShaper синхронно
     * в вызывающем потоке и ждёт окончания затухания. Пост-колбэк здесь
     * недопустим: он не доставлялся бы из живого цикла генерации.
     */
    private fun runFadeOutInline(durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        currentVolume = getVolumeFromShaper()

        val adjustedDuration = if (currentVolume <= MIN_VOLUME) {
            0L
        } else {
            (durationMs * currentVolume).toLong().coerceAtLeast(50)
        }

        createVolumeShaper(durationMs, targetVolume = 0.0f)
        startVolumeShaper()

        try {
            Thread.sleep(adjustedDuration + 50)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun executePause() {
        // C2: resume успел выполниться в UI-потоке, пока мы спали в
        // runFadeOutInline — отменяем паузу и прибираем брошенный шейпер.
        if (_isPlaying.value) {
            Log.d(TAG, "executePause: resume won the race during inline fade, pausing aborted")
            try {
                volumeShaper?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing orphaned shaper: ${e.message}")
            }
            volumeShaper = null
            isFadeInProgress = false
            fadeShaperCloseAtMs = 0L
            try {
                audioTrack?.setVolume(userVolume)
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring volume: ${e.message}")
            }
            currentVolume = userVolume
            return
        }

        _isPlaying.value = false

        val currentSessionMs = System.currentTimeMillis() - playbackStartTime
        accumulatedElapsedMs += currentSessionMs
        Log.d(TAG, "executePause: accumulatedElapsedMs=$accumulatedElapsedMs")

        // F2: прямой вызов — пост в audioHandler не доставился бы из живого цикла
        try {
            audioTrack?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing AudioTrack: ${e.message}")
        }

        // Пауза не должна держать WakeLock до истечения TTL; resume перевыделяет
        releaseWakeLock()
    }
    
    private fun applyPendingSettings() {
        pendingSampleRate.getAndSet(null)?.let { newRate ->
            if (sampleRate != newRate.value) {
                sampleRate = newRate.value
                Log.d(TAG, "Applied pending sample rate: ${newRate.value}")
            }
        }
        
        pendingFrequencyUpdateIntervalMs.getAndSet(null)?.let { newInterval ->
            frequencyUpdateIntervalMs = newInterval
            Log.d(TAG, "Applied pending frequency update interval: ${newInterval}ms")
        }

        // C7: перестройка нативной lookup-таблицы между записями в AudioTrack —
        // live-редактирование кривой активного пресета становится слышимым
        if (pendingCurveUpdate.compareAndSet(true, false)) {
            nativeEngine?.updateConfig(configRef.get(), lastRelaxationSettings)
            Log.d(TAG, "Applied pending curve update")
        }
    }

    /**
     * Остановить воспроизведение немедленно
     */
    fun stop() {
        Log.d(TAG, "stop() called")
        
        isActive.set(false)
        _isPlaying.value = false
        stopWithFadeRequested.set(false)
        pauseWithFadeRequested.set(false)

        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
        }
        
        audioHandler?.removeCallbacksAndMessages(null)
        audioHandler?.post(::stopPlayback)
    }
    
    /**
     * Остановить воспроизведение с плавным затуханием
     */
    fun stopWithFade() {
        Log.d(TAG, "stopWithFade() called, isActive=${isActive.get()}, isPlaying=${_isPlaying.value}")

        // Проверяем isActive, а не _isPlaying!
        // isActive остаётся true во время fade-out, что предотвращает
        // накопление нескольких fade-out операций при быстрых переключениях
        if (!isActive.get()) {
            Log.d(TAG, "stopWithFade() - not active, returning")
            return
        }

        currentVolume = getVolumeFromShaper()
        _isPlaying.value = false
        // K2: через флаг — checkFadeRequests в следующей (сокращённой до ~1с)
        // итерации запустит fade-out; completion не голодает на длинном пакете
        stopWithFadeRequested.set(true)
    }

    private fun stopPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        volumeShaper?.close()
        volumeShaper = null
        isFadeInProgress = false
        fadeShaperCloseAtMs = 0L
        
        // Восстанавливаем currentVolume до userVolume для корректного
        // последующего перезапуска. userVolume - единственный источник истины.
        currentVolume = userVolume

        // Сброс нативного m_isPlaying: без него флаг остаётся true до следующего
        // startNewPlayback — окно рассинхрона getCurrentTimeOfDaySeconds/J4.
        // Безопасно: setPlaying(false,*) меняет только атомик, таймлайн не трогает.
        nativeEngine?.stop()

        releaseWakeLock()
        resetState()
        
        _isPlaying.value = false
        isActive.set(false)
        
        Log.d(TAG, "stopPlayback() completed, userVolume=$userVolume")
    }
    
    private fun cleanupPlayback() {
        Log.d(TAG, "cleanupPlayback() called, isActive=${isActive.get()}, isGenerating=$isGenerating")
        
        Log.d(TAG, "cleanupPlayback() - stopping and releasing AudioTrack")
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        volumeShaper?.close()
        volumeShaper = null

        releaseWakeLock()
        
        _isPlaying.value = false
        isActive.set(false)
        isGenerating = false
        isFadeInProgress = false
        fadeShaperCloseAtMs = 0L
        stopWithFadeRequested.set(false)
        pauseWithFadeRequested.set(false)
        
        // Восстанавливаем currentVolume до userVolume для корректного
        // последующего перезапуска. userVolume - единственный источник истины.
        currentVolume = userVolume
        
        Log.d(TAG, "cleanupPlayback() completed, userVolume=$userVolume")
    }
    
    private fun resetState() {
        _elapsedSeconds.value = 0
        accumulatedElapsedMs = 0L
        Log.d(TAG, "resetState() completed")
    }

    /**
     * Приостановить воспроизведение с плавным затуханием
     */
    fun pauseWithFade() {
        Log.d(TAG, "pauseWithFade() called, isActive=${isActive.get()}, isPlaying=${_isPlaying.value}")

        // Проверяем isActive, а не _isPlaying - аналогично stopWithFade()
        if (!isActive.get()) {
            Log.d(TAG, "pauseWithFade() - not active, returning")
            return
        }

        currentVolume = getVolumeFromShaper()
        _isPlaying.value = false
        // K2: через флаг — checkFadeRequests в следующей (сокращённой до ~1с)
        // итерации запустит fade-out и executePause без голодания лупера
        pauseWithFadeRequested.set(true)
    }

    /**
     * Возобновить воспроизведение с плавным нарастанием
     */
    fun resumeWithFade() {
        Log.d(TAG, "resumeWithFade() called, isActive=${isActive.get()}")
        
        if (_isPlaying.value) return
        
        // МЯГКАЯ ПАУЗА (isActive==true, трек на паузе, цикл генерации жив):
        // продолжаем с ТОГО ЖЕ места кривой и той же фазой — без resetState и
        // без ре-янкора таймлайна на wall-clock (иначе скачок частот + щелчок).
        // F4: всё последовательно в вызывающем потоке, без постов в audioHandler
        // (они не доставлялись бы из живого цикла генерации).
        if (isActive.get()) {
            _isPlaying.value = true

            // F9: восстанавливаем опорную точку таймлайна перед снятием нативной паузы
            playbackStartTime = System.currentTimeMillis() - accumulatedElapsedMs
            nativeEngine?.setPlaybackStartTime(playbackStartTime)

            nativeEngine?.play(preserveTimeline = true)
            audioTrack?.play()
            // C5: после fade-out currentVolume==0 — резкий старт даст щелчок
            // (особенно после scrub-в-паузе, когда частота кривой сменилась).
            currentVolume = MIN_VOLUME
            createVolumeShaper(RESUME_FADE_DURATION_MS, targetVolume = 1.0f)
            startVolumeShaper()
            // F5: закрытие шейпера выполнит generateAudioLoop (посты недоставляемы)
            fadeShaperCloseAtMs = System.currentTimeMillis() + RESUME_FADE_DURATION_MS + 50
            acquireWakeLock()
            return
        }
        
        play()
    }

    /**
     * Переключить пресет с плавным затуханием/нарастанием
     */
    fun switchPresetWithFade(config: BinauralConfig) {
        Log.d(TAG, "switchPresetWithFade() called")
        
        if (!isActive.get()) {
            updateConfig(config)
            return
        }
        
        presetSwitchRequested.set(config)
    }

    /**
     * Установить громкость в реальном времени.
     * Использует прямую установку AudioTrack.setVolume() для мгновенного отклика.
     * 
     * ПРИМЕЧАНИЕ: VolumeShaper НЕ используется для слайдера громкости, потому что:
     * - При быстрых движениях слайдера создаётся множество VolumeShaper
     * - VolumeShaper работает асинхронно и не успевает завершиться
     * - Это приводит к рассинхронизации реальной громкости с позицией слайдера
     * 
     * VolumeShaper используется только для плавных переходов при:
     * - Старт воспроизведения (fade-in)
     * - Остановка/пауза воспроизведения (fade-out)
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        userVolume = clampedVolume  // Сохраняем пользовательскую громкость
        
        val track = audioTrack
        if (track != null && isActive.get()) {
            // Прямая установка громкости - мгновенный отклик
            // Мастер-громкость управляется только через AudioTrack;
            // в нативном движке volume всегда 1.0 для корректной работы fade
            track.setVolume(clampedVolume)
            currentVolume = clampedVolume
            Log.d(TAG, "Volume set to $clampedVolume (userVolume)")
        }
    }
    
    /**
     * Установить частоту дискретизации
     *
     * АСИНХРОННАЯ РЕАЛИЗАЦИЯ:
     * - Все операции выполняются в audioHandler потоке
     * - Debounce предотвращает множественные перезапуски при быстрых переключениях
     * - Нет блокировок (synchronized) и Thread.sleep
     */
    fun setSampleRate(rate: SampleRate) {
        Log.d(TAG, "setSampleRate() called: ${rate.value} Hz")

        val handler = audioHandler
        if (handler == null) {
            Log.w(TAG, "setSampleRate() - audioHandler is null, updating sampleRate directly")
            if (sampleRate != rate.value) {
                sampleRate = rate.value
                nativeEngine?.setSampleRate(sampleRate)
            }
            return
        }

        // Отменяем предыдущие запросы на переключение (debounce)
        handler.removeCallbacksAndMessages(SAMPLE_RATE_CHANGE_TOKEN)

        // Если уже идёт перезапуск, просто обновляем pending rate
        if (restartPlaybackScheduled) {
            Log.d(TAG, "setSampleRate() - restart already scheduled, updating pending rate to ${rate.value} Hz")
            pendingSampleRate.set(rate)
            return
        }

        // Проверяем, нужно ли вообще переключение
        if (sampleRate == rate.value) {
            Log.d(TAG, "setSampleRate() - already at ${rate.value} Hz, skipping")
            return
        }

        // Планируем переключение с небольшой задержкой для debounce
        handler.postAtTime({
            executeSampleRateChange(rate)
        }, SAMPLE_RATE_CHANGE_TOKEN, System.currentTimeMillis() + 50)
    }

    /**
     * Выполнить переключение частоты дискретизации
     * Вызывается только из audioHandler потока
     */
    private fun executeSampleRateChange(rate: SampleRate) {
        Log.d(TAG, "executeSampleRateChange() called: ${rate.value} Hz")

        // Проверяем, не изменилось ли значение пока ждали в очереди
        if (sampleRate == rate.value) {
            Log.d(TAG, "executeSampleRateChange() - already at ${rate.value} Hz, skipping")
            return
        }

        val wasPlaying = _isPlaying.value

        if (wasPlaying) {
            // Сохраняем текущую громкость для восстановления после перезапуска
            currentVolume = getVolumeFromShaper()

            // Останавливаем генерацию
            isActive.set(false)
            _isPlaying.value = false
            restartPlaybackScheduled = true

            // Отменяем все запланированные операции restart с другим токеном
            audioHandler?.removeCallbacksAndMessages(RESTART_PLAYBACK_TOKEN)

            // Останавливаем AudioTrack
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }

            // Обновляем частоту дискретизации
            sampleRate = rate.value
            nativeEngine?.setSampleRate(sampleRate)
            Log.d(TAG, "Sample rate changed to ${sampleRate} Hz")

            // Освобождаем AudioTrack и перезапускаем воспроизведение
            audioHandler?.postAtTime({
                Log.d(TAG, "Executing scheduled restart: release AudioTrack and play")
                restartPlaybackScheduled = false

                // Проверяем, не запросили ли другую частоту пока выполнялся restart
                val pendingRate = pendingSampleRate.getAndSet(null)
                if (pendingRate != null && pendingRate.value != sampleRate) {
                    Log.d(TAG, "Pending sample rate change detected: ${pendingRate.value} Hz, executing")
                    executeSampleRateChange(pendingRate)
                    return@postAtTime
                }

                // Освобождаем и пересоздаём AudioTrack
                audioTrack?.release()
                audioTrack = null

                // Запускаем воспроизведение с новой частотой
                play()
            }, RESTART_PLAYBACK_TOKEN, System.currentTimeMillis() + 50)
        } else {
            // Если не воспроизводится, просто обновляем параметр
            sampleRate = rate.value
            nativeEngine?.setSampleRate(sampleRate)
            Log.d(TAG, "Sample rate changed to ${sampleRate} Hz (not playing)")
        }
    }
    
    fun getSampleRate(): SampleRate = SampleRate.fromValue(sampleRate)
    
    /**
     * Установить интервал генерации буфера
     * @param intervalMs интервал в миллисекундах (от 1 секунды до 60 минут)
     */
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        // Максимум 60 минут = 3,600,000 мс
        val clampedInterval = intervalMs.coerceIn(1000, 60 * 60 * 1000)
        // C4: запоминаем пользовательский выбор — debug-время форсирует 250 мс,
        // и без этого восстановление вернуло бы не то, что выбрал пользователь
        if (!debugVirtualTimeEnabled) {
            lastUserIntervalMs = clampedInterval
        }
        pendingFrequencyUpdateIntervalMs.set(clampedInterval)
        Log.d(TAG, "Buffer generation interval set to $clampedInterval ms (${clampedInterval / 60000} min)")
    }

    fun getFrequencyUpdateInterval(): Int = frequencyUpdateIntervalMs
    
    /**
     * Проверить, включён ли режим энергосбережения
     */
    fun isPowerSaveMode(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isPowerSaveMode
    }
    
    /**
     * Получить адаптивный интервал обновления с учётом Battery Saver
     * В режиме энергосбережения интервал увеличивается в 3 раза
     */
    fun getAdaptiveFrequencyUpdateInterval(): Int {
        val baseInterval = frequencyUpdateIntervalMs
        return if (isPowerSaveMode()) {
            (baseInterval * POWER_SAVE_INTERVAL_MULTIPLIER).coerceAtMost(60000)
        } else {
            baseInterval
        }
    }
    
    /**
     * Применить адаптивный интервал при изменении режима энергосбережения
     * Вызывается из сервиса при получении ACTION_POWER_SAVE_MODE_CHANGED
     */
    fun applyPowerSaveMode() {
        val adaptiveInterval = getAdaptiveFrequencyUpdateInterval()
        if (adaptiveInterval != frequencyUpdateIntervalMs) {
            pendingFrequencyUpdateIntervalMs.set(adaptiveInterval)
            Log.d(TAG, "Power save mode changed, interval adjusted to $adaptiveInterval ms")
        }
    }

    // C1: отдельный лок для WakeLock — @Synchronized(this) блокировал бы
    // вызывающий поток resume на весь монитор сессии (ANR)
    private val wakeLockLock = Any()

    private fun acquireWakeLock() = synchronized(wakeLockLock) {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            }
            // K3: TTL должен покрывать запись пакета целиком (до 60 мин),
            // иначе лок истекает в середине длинной записи
            val ttlMs = maxOf(10 * 60 * 1000L, frequencyUpdateIntervalMs.toLong() + 120_000L)
            // C8: продлеваем только когда истёк — сессии дольше 10 минут остаются защищёнными
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(ttlMs)
                Log.d(TAG, "WakeLock acquired for ${ttlMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() = synchronized(wakeLockLock) {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    fun release() {
        stop()
        nativeEngine?.release()
        nativeEngine = null
        audioThread?.quitSafely()
        audioThread = null
        audioHandler = null
        Log.d(TAG, "Audio engine released")
    }

    // ============ Debug virtual time (только debug-сборка) ============

    fun debugSetVirtualTimeEnabled(enabled: Boolean) {
        nativeEngine?.debugSetVirtualTimeEnabled(enabled)
        if (enabled) {
            debugVirtualTimeEnabled = true
            lastUserIntervalMs = frequencyUpdateIntervalMs
            // 250 мс: низкая латентность scrub + плотное следование кривой при scale до 60
            // (каждый буфер покрывает лишь scale*0.25 виртуальных секунд линейного рампа).
            pendingFrequencyUpdateIntervalMs.set(250)
            // Отключаем батч-генерацию, чтобы не было больших "замороженных" кусков.
            nativeEngine?.setBatchDurationMinutes(0)
        } else {
            debugVirtualTimeEnabled = false
            // C4: возврат к ПОЛЬЗОВАТЕЛЬСКОМУ интервалу (не хардкод)
            pendingFrequencyUpdateIntervalMs.set(lastUserIntervalMs)
        }
    }

    fun debugScrub(timeSeconds: Int) {
        nativeEngine?.debugScrub(timeSeconds)
        _currentTimeOfDaySeconds.value = timeSeconds
    }

    fun debugSetTimeScale(scale: Float) {
        nativeEngine?.debugSetTimeScale(scale)
    }

    fun debugSetRunning(running: Boolean) {
        nativeEngine?.debugSetRunning(running)
    }

    fun debugResetToRealTime() {
        nativeEngine?.debugResetToRealTime()
    }
}