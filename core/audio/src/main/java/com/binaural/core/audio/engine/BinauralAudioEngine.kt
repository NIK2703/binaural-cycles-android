package com.binaural.core.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeShaper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.NormalizationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp

// Константа 2π для оптимизации вычислений
private const val TWO_PI = 2.0 * PI
private const val INV_TWO_PI = 1.0 / TWO_PI

/**
 * Быстрая аппроксимация функции pow(x, n) для положительных x и n в диапазоне [0, 2].
 * Использует комбинацию exp и ln для вычисления: x^n = exp(n * ln(x))
 * 
 * Оптимизация: избегаем дорогостоящего Math.pow путём прямого использования
 * аппроксимированных функций exp/ln из kotlin.math.
 * 
 * @param x основание (должно быть положительным)
 * @param n показатель степени (обычно 0.0-2.0 для нормализации)
 * @return x^n
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun fastPow(x: Double, n: Double): Double {
    // Для n=0 возвращаем 1, для n=1 возвращаем x
    if (n == 0.0) return 1.0
    if (n == 1.0) return x
    if (x <= 0.0) return 0.0
    
    // x^n = exp(n * ln(x))
    return exp(n * ln(x))
}

/**
 * Wavetable для быстрой генерации синусоид с линейной интерполяцией
 * Линейная интерполяция обеспечивает высокую точность при малом размере таблицы
 * Эквивалентная точность: размер таблицы × 32
 */
private object Wavetable {
    private const val DEFAULT_TABLE_SIZE = 2048
    private var currentTableSize = DEFAULT_TABLE_SIZE
    private var sineTable = FloatArray(DEFAULT_TABLE_SIZE) { i ->
        sin(2.0 * PI * i / DEFAULT_TABLE_SIZE).toFloat()
    }
    private var scaleFactor = DEFAULT_TABLE_SIZE / (2.0 * PI)

    fun initialize(size: Int) {
        currentTableSize = size
        sineTable = FloatArray(size) { i ->
            sin(2.0 * PI * i / size).toFloat()
        }
        scaleFactor = size / (2.0 * PI)
    }

    @JvmStatic
    @JvmName("fastSin")
    inline fun fastSin(phase: Double): Float {
        val phaseScaled = phase * scaleFactor
        val index = phaseScaled.toInt() and (currentTableSize - 1)
        val fraction = (phaseScaled - phaseScaled.toInt()).toFloat()
        val indexNext = (index + 1) and (currentTableSize - 1)
        return sineTable[index] * (1.0f - fraction) + sineTable[indexNext] * fraction
    }

    @JvmStatic
    @JvmName("getTableSize")
    fun getTableSize(): Int = currentTableSize
}

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
        fun fromValue(value: Int): SampleRate = entries.find { it.value == value } ?: MEDIUM
    }
}

/**
 * Тип операции fade для внутренних нужд движка
 */
private enum class FadeOperation {
    NONE,
    CHANNEL_SWAP,
    PRESET_SWITCH
}

/**
 * Движок для генерации и воспроизведения бинауральных ритмов.
 * Работает в отдельном потоке (HandlerThread) для исключения задержек в UI.
 * 
 * АРХИТЕКТУРА УПРАВЛЕНИЯ ГРОМКОСТЬЮ:
 * 
 * Громкость управляется через VolumeShaper (API 26+), который обеспечивает плавные переходы.
 * 
 * Ключевая переменная: currentFadeVolume (0.0 - 1.0)
 * - Хранит текущий уровень громкости для следующей операции fade
 * - При остановке: сохраняется текущая громкость от VolumeShaper
 * - При запуске: fade-in начинается с currentFadeVolume до 1.0
 * - При первом запуске: currentFadeVolume = 0 (fade-in с тишины)
 * 
 * Операции симметричны:
 * - stopWithFade(): fade-out от текущей громкости до 0, сохраняет финальную громкость
 * - play(): fade-in от сохранённой громкости до 1
 * - Быстрое play после stop: продолжается fade с прерванной позиции
 */
class BinauralAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "BinauralAudioEngine"
        private const val BUFFER_SIZE_MS = 1000
        private const val WAKE_LOCK_TAG = "BinauralBeats:PlaybackWakeLock"
        private const val THREAD_NAME = "BinauralAudioThread"
        private const val MIN_VOLUME = 0.001f  // Минимальная громкость для VolumeShaper
        private const val PLAYBACK_FADE_DURATION_MS = 250L  // Фиксированная длительность fade при остановке/возобновлении
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
    
    // Текущие настройки (доступны только из audioThread)
    private var sampleRate: Int = SampleRate.MEDIUM.value
    private var frequencyUpdateIntervalMs: Int = 100

    // Предварительно выделенный буфер для генерации аудио
    private var audioBuffer = ShortArray(0)
    private var maxSamplesPerChannel = 0

    // AudioTrack (доступен только из audioThread)
    private var audioTrack: AudioTrack? = null

    // HandlerThread для генерации аудио
    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    private var isGenerating = false

    // WakeLock для предотвращения засыпания при воспроизведении
    private var wakeLock: PowerManager.WakeLock? = null

    // Фаза для непрерывной генерации (доступны только из audioThread)
    private var leftPhase = 0.0
    private var rightPhase = 0.0

    // Переменная для отслеживания перестановки каналов
    private var channelsSwapped = false
    private var lastSwapElapsedMs = 0L

    // Состояние fade для операций внутри буфера (доступны только из audioThread)
    private var fadeStartSample = 0L
    private var isFadingOut = true
    private var currentFadeOperation = FadeOperation.NONE
    private var pendingPresetConfig: BinauralConfig? = null
    private var totalSamplesGenerated = 0L
    
    // VolumeShaper для плавного изменения громкости
    @Volatile
    private var volumeShaper: VolumeShaper? = null
    
    // Текущая громкость fade (0.0 - 1.0)
    // Единая точка истины для начальной громкости любой операции fade
    // Изначально 0 - при первом запуске fade-in начинается с тишины
    private var currentFadeVolume: Float = 0.0f
    
    // Трекинг параметров fade для вычисления текущей громкости по времени
    // Это обеспечивает надёжное получение громкости даже если VolumeShaper недоступен
    private var fadeStartTime: Long = 0L  // System.currentTimeMillis()
    private var fadeDurationMs: Long = 0L
    private var fadeStartVolume: Float = 0.0f
    private var fadeTargetVolume: Float = 1.0f
    private var isFadeInProgress: Boolean = false

    // Время начала воспроизведения
    private var playbackStartTime = 0L

    // Кэшированное время для избежания частых вызовов Clock.System
    private var cachedTimeSeconds = -1
    private var cachedLocalTime: LocalTime = LocalTime(0, 0)

    // Быстрая генерация звука (табличный синтез с линейной интерполяцией)
    private var useWavetable = true
    private var wavetableSize = 2048
    
    // Кэш для нормализации TEMPORAL - минимальные и максимальные частоты каналов
    // Обновляется при смене конфигурации
    private var cachedMinLowerFreq: Double = 0.0
    private var cachedMaxLowerFreq: Double = 0.0
    private var cachedMinUpperFreq: Double = 0.0
    private var cachedMaxUpperFreq: Double = 0.0
    private var cachedCurveHash: Int = -1

    // StateFlows для UI
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentConfig = MutableStateFlow(BinauralConfig())
    val currentConfig: StateFlow<BinauralConfig> = _currentConfig.asStateFlow()

    private val _currentBeatFrequency = MutableStateFlow(0.0)
    val currentBeatFrequency: StateFlow<Double> = _currentBeatFrequency.asStateFlow()

    private val _currentCarrierFrequency = MutableStateFlow(0.0)
    val currentCarrierFrequency: StateFlow<Double> = _currentCarrierFrequency.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
    
    private val _isChannelsSwapped = MutableStateFlow(false)
    val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()

    /**
     * Инициализация движка. Должна вызываться один раз при создании.
     */
    fun initialize() {
        audioThread = HandlerThread(THREAD_NAME, Thread.MAX_PRIORITY).apply { start() }
        audioHandler = Handler(audioThread!!.looper)
        Log.d(TAG, "Audio engine initialized on thread: ${audioThread?.name}")
    }

    /**
     * Обновить конфигурацию (потокобезопасно)
     */
    fun updateConfig(config: BinauralConfig) {
        configRef.set(config)
        _currentConfig.value = config
    }

    /**
     * Обновить кривую частот (потокобезопасно)
     */
    fun updateFrequencyCurve(curve: FrequencyCurve) {
        val currentConfig = configRef.get()
        configRef.set(currentConfig.copy(frequencyCurve = curve))
        _currentConfig.value = configRef.get()
    }

    /**
     * Начать воспроизведение (потокобезопасно)
     * 
     * Если вызывается во время fade-out (быстрое нажатие play после stop):
     * 1. Запоминает текущую громкость от VolumeShaper
     * 2. Резко устанавливает громкость в 0 (чтобы избежать щелчка)
     * 3. Прерывает текущий fade-out
     * 4. Начинает fade-in с запомненной громкости
     */
    fun play() {
        Log.d(TAG, "play() called, isPlaying=${_isPlaying.value}, isActive=${isActive.get()}, currentFadeVolume=$currentFadeVolume")
        
        val handler = audioHandler
        if (handler == null) {
            Log.e(TAG, "AudioHandler is null! Cannot start playback")
            return
        }
        
        // Если идёт fade-out (isActive=true, isPlaying=false), прерываем его
        if (isActive.get() && !_isPlaying.value) {
            Log.d(TAG, "Interrupting fade-out, currentFadeVolume=$currentFadeVolume, isFadeInProgress=$isFadeInProgress")
            handler.removeCallbacksAndMessages(null)
            stopWithFadeRequested.set(false)
            pauseWithFadeRequested.set(false)
            
            // 1. Запоминаем текущую громкость от VolumeShaper ДО любых изменений
            val savedVolume = getVolumeFromShaper()
            Log.d(TAG, "Saved current volume from shaper: $savedVolume")
            
            // 2. Резко устанавливаем громкость в 0 для избежания щелчка
            // Это делаем ДО остановки AudioTrack
            try {
                volumeShaper?.close()
                volumeShaper = null
                audioTrack?.setVolume(0.0f)
                Log.d(TAG, "Set volume to 0 immediately to avoid click")
            } catch (e: Exception) {
                Log.e(TAG, "Error setting volume to 0: ${e.message}")
            }
            
            // 3. Сохраняем громкость для fade-in при следующем запуске
            currentFadeVolume = savedVolume
            
            isActive.set(false)
            isFadeInProgress = false
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }
            
            // Планируем запуск с задержкой для завершения generateAudioLoop
            handler.postDelayed({ startNewPlayback(handler) }, 100)
            return
        }
        
        if (isActive.get()) {
            Log.d(TAG, "Already active (playing), returning")
            return
        }
        
        startNewPlayback(handler)
    }
    
    private fun startNewPlayback(handler: Handler) {
        if (isActive.get()) {
            Log.d(TAG, "startNewPlayback: already active, returning")
            return
        }
        
        isActive.set(true)
        _isPlaying.value = true
        playbackStartTime = System.currentTimeMillis()
        totalSamplesGenerated = 0L
        
        acquireWakeLock()
        handler.post(::startPlayback)
    }
    
    private fun startPlayback() {
        if (!isActive.get()) return

        Log.d(TAG, "startPlayback() on thread: ${Thread.currentThread().name}, currentFadeVolume=$currentFadeVolume")

        try {
            maxSamplesPerChannel = sampleRate * 60
            if (audioBuffer.size < maxSamplesPerChannel * 2) {
                audioBuffer = ShortArray(maxSamplesPerChannel * 2)
            }
            
            createAudioTrack()
            
            // Создаём VolumeShaper для fade-in от currentFadeVolume до 1.0
            // Фиксированная длительность для остановки/возобновления
            createVolumeShaper(PLAYBACK_FADE_DURATION_MS, targetVolume = 1.0f)
            
            audioTrack?.play()
            
            // Запускаем fade-in
            startVolumeShaper()
            
            generateAudioLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Playback error", e)
        } finally {
            cleanupPlayback()
        }
    }
    
    private fun createAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        val bufferSize = maxOf(minBufferSize, sampleRate * 2 * 2 * BUFFER_SIZE_MS / 1000)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // VolumeShaper работает как МНОЖИТЕЛЬ к громкости AudioTrack.
        // Поэтому AudioTrack всегда должен быть на максимальной громкости (1.0),
        // а VolumeShaper будет масштабировать её через кривую.
        // 
        // Например, для fade от 0.2 до 1.0:
        // - AudioTrack volume = 1.0
        // - VolumeShaper curve: 0.2 → 1.0
        // - Результат: 1.0 × 0.2 = 0.2 в начале, 1.0 × 1.0 = 1.0 в конце
        
        audioTrack?.setVolume(1.0f)
        Log.d(TAG, "AudioTrack created: sampleRate=$sampleRate, bufferSize=$bufferSize, volume=1.0 (will be controlled by VolumeShaper)")
    }
    
    /**
     * Получить текущую громкость, вычисляя её по времени и параметрам fade.
     * 
     * Приоритет:
     * 1. Если fade в процессе - вычисляем по прошедшему времени
     * 2. Иначе берём из VolumeShaper (если доступен)
     * 3. Иначе используем сохранённое currentFadeVolume
     */
    private fun getVolumeFromShaper(): Float {
        // Если fade в процессе, вычисляем громкость по времени
        if (isFadeInProgress && fadeDurationMs > 0) {
            val elapsed = System.currentTimeMillis() - fadeStartTime
            val progress = (elapsed.toFloat() / fadeDurationMs).coerceIn(0f, 1f)
            val calculatedVolume = fadeStartVolume + (fadeTargetVolume - fadeStartVolume) * progress
            Log.d(TAG, "getVolumeFromShaper: calculated from time, elapsed=$elapsed ms, progress=$progress, volume=$calculatedVolume")
            return calculatedVolume
        }
        
        // Иначе пытаемся получить от VolumeShaper
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val shaperVolume = volumeShaper?.volume
                if (shaperVolume != null) {
                    Log.d(TAG, "getVolumeFromShaper: from VolumeShaper = $shaperVolume")
                    shaperVolume
                } else {
                    Log.d(TAG, "getVolumeFromShaper: from currentFadeVolume = $currentFadeVolume")
                    currentFadeVolume
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get volume from VolumeShaper: ${e.message}")
                currentFadeVolume
            }
        } else {
            Log.d(TAG, "getVolumeFromShaper: API < 26, using currentFadeVolume = $currentFadeVolume")
            currentFadeVolume
        }
    }
    
    /**
     * Создать VolumeShaper для fade от currentFadeVolume до targetVolume
     * 
     * @param durationMs полная длительность fade в миллисекундах
     * @param targetVolume целевая громкость (1.0 для fade-in, 0.0 для fade-out)
     */
    private fun createVolumeShaper(durationMs: Long, targetVolume: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            volumeShaper?.close()
            
            val startVolume = currentFadeVolume.coerceIn(MIN_VOLUME, 1.0f)
            val clampedTarget = targetVolume.coerceIn(0.0f, 1.0f)
            
            // Если стартовая и целевая громкость одинаковы, нет смысла создавать shaper
            if (kotlin.math.abs(startVolume - clampedTarget) < 0.01f) {
                Log.d(TAG, "VolumeShaper skipped: startVolume=$startVolume ≈ targetVolume=$clampedTarget")
                // Обновляем currentFadeVolume до целевого значения
                currentFadeVolume = clampedTarget
                isFadeInProgress = false
                // Явно устанавливаем громкость AudioTrack в целевое значение
                audioTrack?.setVolume(clampedTarget)
                Log.d(TAG, "Set AudioTrack volume to $clampedTarget (no fade needed)")
                return
            }
            
            // Длительность пропорциональна изменению громкости
            val volumeChange = kotlin.math.abs(clampedTarget - startVolume)
            val adjustedDuration = (durationMs * volumeChange).toLong().coerceAtLeast(50)
            
            val config = VolumeShaper.Configuration.Builder()
                .setDuration(adjustedDuration)
                .setCurve(floatArrayOf(0f, 1f), floatArrayOf(startVolume, clampedTarget))
                .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                .build()
            
            volumeShaper = audioTrack?.createVolumeShaper(config)
            
            // Записываем параметры fade для вычисления громкости по времени
            fadeStartTime = System.currentTimeMillis()
            fadeDurationMs = adjustedDuration
            fadeStartVolume = startVolume
            fadeTargetVolume = clampedTarget
            // isFadeInProgress будет установлен в true при запуске shaper
            
            Log.d(TAG, "VolumeShaper created: startVolume=$startVolume → targetVolume=$clampedTarget, duration=${adjustedDuration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VolumeShaper: ${e.message}")
            volumeShaper = null
            isFadeInProgress = false
            // При ошибке устанавливаем целевую громкость напрямую
            audioTrack?.setVolume(targetVolume.coerceIn(0.0f, 1.0f))
        }
    }
    
    /**
     * Запустить VolumeShaper
     */
    private fun startVolumeShaper() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            val shaper = volumeShaper
            if (shaper != null) {
                shaper.apply(VolumeShaper.Operation.PLAY)
                isFadeInProgress = true
                Log.d(TAG, "VolumeShaper started, fadeInProgress=true")
            } else {
                // VolumeShaper не был создан (skipped), fade не нужен
                isFadeInProgress = false
                Log.d(TAG, "VolumeShaper is null, fadeInProgress=false")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VolumeShaper: ${e.message}")
            isFadeInProgress = false
        }
    }
    
    private fun generateAudioLoop() {
        val handler = audioHandler ?: return
        var wakeLockRenewCounter = 0
        val wakeLockRenewInterval = 600

        var currentIntervalMs = frequencyUpdateIntervalMs
        var samplesPerChannel = (sampleRate.toLong() * currentIntervalMs / 1000).toInt()

        isGenerating = true

        while (isActive.get() && audioTrack != null) {
            applyPendingSettings()

            if (frequencyUpdateIntervalMs != currentIntervalMs) {
                currentIntervalMs = frequencyUpdateIntervalMs
                samplesPerChannel = (sampleRate.toLong() * currentIntervalMs / 1000).toInt()
            }

            checkFadeRequests()

            // Кэширование времени
            val currentElapsedSeconds = _elapsedSeconds.value
            if (currentElapsedSeconds != cachedTimeSeconds) {
                cachedTimeSeconds = currentElapsedSeconds
                val now = Clock.System.now()
                val zone = TimeZone.currentSystemDefault()
                cachedLocalTime = now.toLocalDateTime(zone).time
            }

            val config = configRef.get()
            val (lowerFreq, upperFreq) = config.getChannelFrequenciesAt(cachedLocalTime)
            val carrierFreq = (upperFreq + lowerFreq) / 2.0
            val beatFreq = upperFreq - lowerFreq

            if (_currentBeatFrequency.value != beatFreq) {
                _currentBeatFrequency.value = beatFreq
            }
            if (_currentCarrierFrequency.value != carrierFreq) {
                _currentCarrierFrequency.value = carrierFreq
            }

            if (currentFadeOperation == FadeOperation.NONE) {
                checkChannelSwap(config)
            }

            // Длительность fade для смены каналов из конфигурации
            // Для переключения пресета тоже используется значение из конфигурации
            val channelSwapFadeDurationSamples = (config.channelSwapFadeDurationMs.coerceAtLeast(100L) * sampleRate / 1000).toInt()
            val presetSwitchFadeDurationSamples = (config.channelSwapFadeDurationMs.coerceAtLeast(100L) * sampleRate / 1000).toInt()
            val fadeResult = generateBuffer(audioBuffer, carrierFreq, beatFreq, samplesPerChannel, channelSwapFadeDurationSamples, presetSwitchFadeDurationSamples, config, cachedLocalTime)

            totalSamplesGenerated += samplesPerChannel

            if (fadeResult.fadePhaseCompleted && currentFadeOperation != FadeOperation.NONE) {
                handleFadeOperationCompleted()
            }

            if (!isActive.get()) break

            val currentAudioTrack = audioTrack
            if (currentAudioTrack == null) {
                Log.d(TAG, "AudioTrack is null, stopping")
                break
            }

            val result = try {
                currentAudioTrack.write(audioBuffer, 0, samplesPerChannel * 2)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack write error: ${e.message}")
                -1
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack write exception: ${e.message}")
                -1
            }

            if (result < 0) {
                Log.d(TAG, "Write failed, result=$result")
                break
            }

            _elapsedSeconds.value = ((System.currentTimeMillis() - playbackStartTime) / 100).toInt()

            wakeLockRenewCounter++
            if (wakeLockRenewCounter >= wakeLockRenewInterval) {
                renewWakeLock()
                wakeLockRenewCounter = 0
            }
        }

        isGenerating = false
        Log.d(TAG, "generateAudioLoop() ended")
    }
    
    private fun checkFadeRequests() {
        if (currentFadeOperation != FadeOperation.NONE) return
        
        // Фиксированная длительность fade для stop/pause
        val fadeDuration = PLAYBACK_FADE_DURATION_MS
        
        // Остановка с fade-out через VolumeShaper
        if (stopWithFadeRequested.get()) {
            Log.d(TAG, "Starting fade-out for stop via VolumeShaper")
            startFadeOut(fadeDuration) {
                if (isActive.get()) {
                    Log.d(TAG, "Fade-out completed, stopping playback")
                    isActive.set(false)
                    audioHandler?.post(::stopPlayback)
                }
            }
            stopWithFadeRequested.set(false)
            return
        }
        
        // Пауза с fade-out через VolumeShaper
        if (pauseWithFadeRequested.get()) {
            Log.d(TAG, "Starting fade-out for pause via VolumeShaper")
            startFadeOut(fadeDuration) {
                if (isActive.get()) {
                    Log.d(TAG, "Fade-out completed, pausing playback")
                    executePause()
                }
            }
            pauseWithFadeRequested.set(false)
            return
        }
        
        // Переключение пресета с fade через модификацию буфера
        presetSwitchRequested.getAndSet(null)?.let { newConfig ->
            pendingPresetConfig = newConfig
            currentFadeOperation = FadeOperation.PRESET_SWITCH
            isFadingOut = true
            fadeStartSample = totalSamplesGenerated
            Log.d(TAG, "Starting fade-out for preset switch")
        }
    }
    
    /**
     * Начать fade-out через VolumeShaper
     * 
     * @param durationMs полная длительность fade
     * @param callback будет вызван после завершения fade
     */
    private fun startFadeOut(durationMs: Long, callback: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Сохраняем текущую громкость от VolumeShaper
            currentFadeVolume = getVolumeFromShaper()
            Log.d(TAG, "startFadeOut: currentFadeVolume=$currentFadeVolume")
            
            val adjustedDuration = if (currentFadeVolume <= MIN_VOLUME) {
                0L
            } else {
                (durationMs * currentFadeVolume).toLong().coerceAtLeast(50)
            }
            
            createVolumeShaper(durationMs, targetVolume = 0.0f)
            startVolumeShaper()
            
            if (adjustedDuration == 0L) {
                audioHandler?.post(callback)
            } else {
                audioHandler?.postDelayed(callback, adjustedDuration)
            }
        } else {
            callback()
        }
    }
    
    private fun handleFadeOperationCompleted() {
        when (currentFadeOperation) {
            FadeOperation.CHANNEL_SWAP -> {
                Log.d(TAG, "Channel swap fade completed, swapped=$channelsSwapped")
            }
            FadeOperation.PRESET_SWITCH -> {
                Log.d(TAG, "Preset switch fade completed")
                pendingPresetConfig = null
            }
            else -> {}
        }
        currentFadeOperation = FadeOperation.NONE
        isFadingOut = true
    }
    
    private fun executePause() {
        _isPlaying.value = false
        currentFadeOperation = FadeOperation.NONE
        pauseWithFadeRequested.set(false)
        
        audioHandler?.post {
            audioTrack?.pause()
        }
    }
    
    private fun checkChannelSwap(config: BinauralConfig) {
        if (config.channelSwapEnabled) {
            val currentElapsedMs = _elapsedSeconds.value * 100L
            val swapIntervalMs = config.channelSwapIntervalSeconds * 1000L
            
            if (currentElapsedMs - lastSwapElapsedMs >= swapIntervalMs) {
                if (config.channelSwapFadeEnabled) {
                    currentFadeOperation = FadeOperation.CHANNEL_SWAP
                    isFadingOut = true
                    fadeStartSample = totalSamplesGenerated
                    lastSwapElapsedMs = currentElapsedMs
                    Log.d(TAG, "Starting fade out before channel swap")
                } else {
                    channelsSwapped = !channelsSwapped
                    _isChannelsSwapped.value = channelsSwapped
                    lastSwapElapsedMs = currentElapsedMs
                    Log.d(TAG, "Channels swapped: $channelsSwapped")
                }
            }
        }
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
    }

    private data class FadeResult(val fadePhaseCompleted: Boolean)
    
    /**
     * Вычислить частоты каналов для заданного времени с учётом смещения в миллисекундах
     */
    private fun getChannelFrequenciesAtTime(
        config: BinauralConfig,
        baseTime: LocalTime,
        offsetMs: Long
    ): Pair<Double, Double> {
        // Вычисляем время с учётом смещения
        val baseSeconds = baseTime.toSecondOfDay()
        val offsetSeconds = offsetMs / 1000.0
        val totalSeconds = baseSeconds + offsetSeconds
        
        // Нормализуем в пределах суток (86400 секунд)
        val normalizedSeconds = ((totalSeconds % 86400) + 86400) % 86400
        val adjustedTime = LocalTime.fromSecondOfDay(normalizedSeconds.toInt())
        
        return config.getChannelFrequenciesAt(adjustedTime)
    }
    
    private fun generateBuffer(
        buffer: ShortArray,
        carrierFreq: Double,
        beatFreq: Double,
        samplesPerChannel: Int,
        channelSwapFadeDurationSamples: Int,
        presetSwitchFadeDurationSamples: Int,
        config: BinauralConfig,
        currentTime: LocalTime
    ): FadeResult {
        // Начальные частоты (в текущий момент)
        val startLeftFreq = carrierFreq - beatFreq / 2.0
        val startRightFreq = carrierFreq + beatFreq / 2.0
        
        // Конечные частоты (через интервал обновления)
        // Интервал обновления в миллисекундах
        val bufferDurationMs = (samplesPerChannel.toLong() * 1000) / sampleRate
        val (endLowerFreq, endUpperFreq) = getChannelFrequenciesAtTime(config, currentTime, bufferDurationMs)
        val endLeftFreq = endLowerFreq
        val endRightFreq = endUpperFreq
        
        // Вычисляем начальные и конечные амплитуды для временной нормализации
        val (startLeftAmplitude, startRightAmplitude) = calculateNormalizedAmplitudes(startLeftFreq, startRightFreq, config, currentTime)
        
        // Конечные амплитуды (через интервал обновления)
        val (endLeftAmplitude, endRightAmplitude) = if (config.normalizationType == NormalizationType.TEMPORAL) {
            // Вычисляем амплитуды для конечного времени
            val endTimeAdjusted = LocalTime.fromSecondOfDay(
                ((currentTime.toSecondOfDay() + bufferDurationMs / 1000) % 86400).toInt()
            )
            calculateNormalizedAmplitudes(endLeftFreq, endRightFreq, config, endTimeAdjusted)
        } else {
            startLeftAmplitude to startRightAmplitude
        }
        
        // ===== ОПТИМИЗАЦИЯ: Предвычисление констант вне цикла =====
        val twoPiOverSampleRate = 2.0 * PI / sampleRate
        val baseVolumeFactor = 0.5 * Short.MAX_VALUE * config.volume
        
        // Начальные и конечные фазовые инкременты (омега) - вычисляем один раз
        var leftOmega = twoPiOverSampleRate * startLeftFreq
        var rightOmega = twoPiOverSampleRate * startRightFreq
        val endLeftOmega = twoPiOverSampleRate * endLeftFreq
        val endRightOmega = twoPiOverSampleRate * endRightFreq
        
        // Шаги изменения фазовых инкрементов на каждый сэмпл (инкрементальное обновление)
        val omegaStepLeft = (endLeftOmega - leftOmega) / samplesPerChannel
        val omegaStepRight = (endRightOmega - rightOmega) / samplesPerChannel
        
        // Текущие амплитуды (будут обновляться инкрементально)
        var leftAmplitude = startLeftAmplitude
        var rightAmplitude = startRightAmplitude
        
        // Шаги изменения амплитуд на каждый сэмпл (инкрементальное обновление)
        val ampStepLeft = (endLeftAmplitude - startLeftAmplitude) / samplesPerChannel
        val ampStepRight = (endRightAmplitude - startRightAmplitude) / samplesPerChannel
        // ===== КОНЕЦ ОПТИМИЗАЦИИ =====
        
        val channelsSwappedAtStart = channelsSwapped
        var fadePhaseCompleted = false
        var swapExecutedAtSample = -1L
        var configSwitchAtSample = -1L
        
        var localIsFadingOut = isFadingOut
        var localFadeStartSample = fadeStartSample
        var localChannelsSwapped = channelsSwapped
        var localFadeOperation = currentFadeOperation
        var localPendingPresetConfig: BinauralConfig? = pendingPresetConfig
        
        val presetCheckInterval = sampleRate / 100
        var lastPresetCheck = 0
        
        // Кэшируем проверку channelSwapEnabled
        val channelSwapEnabled = config.channelSwapEnabled
        
        // ОПТИМИЗАЦИЯ: Кэшируем функцию генерации синуса вне цикла
        val sinFunc: (Double) -> Float = if (useWavetable) {
            { phase -> Wavetable.fastSin(phase) }
        } else {
            { phase -> sin(phase).toFloat() }
        }
        
        for (i in 0 until samplesPerChannel) {
            val currentSample = totalSamplesGenerated + i
            var fadeMultiplier = 1.0
            
            if (i - lastPresetCheck >= presetCheckInterval) {
                lastPresetCheck = i
                presetSwitchRequested.getAndSet(null)?.let { newConfig ->
                    if (localFadeOperation == FadeOperation.NONE) {
                        localPendingPresetConfig = newConfig
                        localFadeOperation = FadeOperation.PRESET_SWITCH
                        localIsFadingOut = true
                        localFadeStartSample = currentSample
                    }
                }
            }
            
            if (localFadeOperation == FadeOperation.CHANNEL_SWAP || localFadeOperation == FadeOperation.PRESET_SWITCH) {
                // Выбираем длительность fade в зависимости от типа операции
                val fadeDurationSamples = if (localFadeOperation == FadeOperation.CHANNEL_SWAP) {
                    channelSwapFadeDurationSamples
                } else {
                    presetSwitchFadeDurationSamples
                }
                val elapsedSamples = currentSample - localFadeStartSample
                val progress = elapsedSamples.toDouble() / fadeDurationSamples
                
                if (localIsFadingOut) {
                    if (progress >= 1.0) {
                        fadeMultiplier = 0.0
                        if (localFadeOperation == FadeOperation.CHANNEL_SWAP && swapExecutedAtSample < 0) {
                            swapExecutedAtSample = currentSample
                            localChannelsSwapped = !localChannelsSwapped
                            _isChannelsSwapped.value = localChannelsSwapped
                            localIsFadingOut = false
                            localFadeStartSample = currentSample
                        } else if (localFadeOperation == FadeOperation.PRESET_SWITCH && configSwitchAtSample < 0) {
                            configSwitchAtSample = currentSample
                            localPendingPresetConfig?.let { newConfig ->
                                configRef.set(newConfig)
                                _currentConfig.value = newConfig
                            }
                            localPendingPresetConfig = null
                            localIsFadingOut = false
                            localFadeStartSample = currentSample
                        }
                    } else if (progress >= 0.0) {
                        fadeMultiplier = 1.0 - progress
                    }
                } else {
                    if (progress >= 1.0) {
                        fadeMultiplier = 1.0
                        fadePhaseCompleted = true
                    } else if (progress >= 0.0) {
                        fadeMultiplier = progress
                    }
                }
            }
            
            // ===== ОПТИМИЗАЦИЯ: Генерация сэмпла с инкрементальным обновлением =====
            val leftSample = sinFunc(leftPhase)
            leftPhase += leftOmega
            if (leftPhase > TWO_PI) leftPhase -= TWO_PI

            val rightSample = sinFunc(rightPhase)
            rightPhase += rightOmega
            if (rightPhase > TWO_PI) rightPhase -= TWO_PI
            
            // Предвычисленная базовая амплитуда с fade
            val baseAmplitude = baseVolumeFactor * fadeMultiplier
            val leftAmp = baseAmplitude * leftAmplitude
            val rightAmp = baseAmplitude * rightAmplitude
            
            // Инкрементальное обновление для следующего сэмпла
            leftOmega += omegaStepLeft
            rightOmega += omegaStepRight
            leftAmplitude += ampStepLeft
            rightAmplitude += ampStepRight
            // ===== КОНЕЦ ОПТИМИЗАЦИИ =====
            
            // ОПТИМИЗАЦИЯ: Оптимизированное ветвление для swap
            val swapForSample = if (channelSwapEnabled) {
                if (swapExecutedAtSample >= 0 && currentSample >= swapExecutedAtSample) {
                    localChannelsSwapped
                } else {
                    channelsSwappedAtStart
                }
            } else {
                false
            }
            
            if (swapForSample) {
                buffer[i * 2] = (rightSample * rightAmp).toInt().toShort()
                buffer[i * 2 + 1] = (leftSample * leftAmp).toInt().toShort()
            } else {
                buffer[i * 2] = (leftSample * leftAmp).toInt().toShort()
                buffer[i * 2 + 1] = (rightSample * rightAmp).toInt().toShort()
            }
        }
        
        channelsSwapped = localChannelsSwapped
        isFadingOut = localIsFadingOut
        fadeStartSample = localFadeStartSample
        currentFadeOperation = localFadeOperation
        pendingPresetConfig = localPendingPresetConfig
        
        return FadeResult(fadePhaseCompleted)
    }
    
    private fun calculateNormalizedAmplitudes(
        leftFreq: Double,
        rightFreq: Double,
        config: BinauralConfig,
        currentTime: LocalTime
    ): Pair<Double, Double> {
        // Базовые амплитуды без нормализации
        var leftAmplitude = 1.0
        var rightAmplitude = 1.0
        
        val strength = config.volumeNormalizationStrength.coerceIn(0f, 2f)
        
        when (config.normalizationType) {
            com.binaural.core.audio.model.NormalizationType.NONE -> {
                // Без нормализации - амплитуды остаются 1.0
            }
            com.binaural.core.audio.model.NormalizationType.CHANNEL -> {
                // Канальная нормализация (уравнивание между левым и правым каналом)
                val minFreq = minOf(leftFreq, rightFreq)

                val leftNormalized = minFreq / leftFreq
                val rightNormalized = minFreq / rightFreq

                // ОПТИМИЗАЦИЯ: Используем fastPow вместо Math.pow
                leftAmplitude = fastPow(leftNormalized, strength.toDouble())
                rightAmplitude = fastPow(rightNormalized, strength.toDouble())
            }
            com.binaural.core.audio.model.NormalizationType.TEMPORAL -> {
                // Временная нормализация (уравнивание громкости во времени)
                // Масштабирует амплитуду по всему динамическому диапазону графика
                // 
                // Формула: amplitude = (minFreq / currentFreq) ^ strength
                // 
                // На минимальной частоте графика: amplitude = 1.0 (максимум)
                // На максимальной частоте графика: amplitude = min/max (минимум)
                // 
                // Это обеспечивает одинаковую воспринимаемую громкость на протяжении
                // всего графика, компенсируя изменение частоты тона.
                
                val curve = config.frequencyCurve
                
                // ОПТИМИЗАЦИЯ: Кэшируем min/max частоты для кривой
                // Вычисляем только если кривая изменилась (по хешу)
                val curveHash = curve.hashCode()
                if (curveHash != cachedCurveHash) {
                    // Нижний канал (левый): carrier - beat/2
                    cachedMinLowerFreq = curve.points.minOf { point ->
                        (point.carrierFrequency - point.beatFrequency / 2.0).coerceAtLeast(0.0)
                    }
                    cachedMaxLowerFreq = curve.points.maxOf { point ->
                        point.carrierFrequency - point.beatFrequency / 2.0
                    }
                    
                    // Верхний канал (правый): carrier + beat/2
                    cachedMinUpperFreq = curve.points.minOf { point ->
                        point.carrierFrequency + point.beatFrequency / 2.0
                    }
                    cachedMaxUpperFreq = curve.points.maxOf { point ->
                        point.carrierFrequency + point.beatFrequency / 2.0
                    }
                    cachedCurveHash = curveHash
                }
                
                // Рассчитываем коэффициенты по фактическим частотам в текущий момент
                // coeff = minFreq / currentFreq
                // Это даёт 1.0 на минимальной частоте и min/max на максимальной
                val leftNormalized = if (leftFreq > 0) cachedMinLowerFreq / leftFreq else 1.0
                val rightNormalized = if (rightFreq > 0) cachedMinUpperFreq / rightFreq else 1.0
                
                // Применяем strength (экспоненциально)
                // strength = 0: без нормализации (всё 1.0)
                // strength = 1: полная нормализация
                // strength > 1: усиленный эффект
                // ОПТИМИЗАЦИЯ: Используем fastPow вместо Math.pow
                leftAmplitude = fastPow(leftNormalized, strength.toDouble())
                rightAmplitude = fastPow(rightNormalized, strength.toDouble())
            }
        }

        return Pair(leftAmplitude, rightAmplitude)
    }

    /**
     * Остановить воспроизведение немедленно (потокобезопасно)
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
     * Остановить воспроизведение с плавным затуханием (потокобезопасно)
     * 
     * После завершения fade-out:
     * - currentFadeVolume сохраняется для следующего запуска
     * - При быстром нажатии play() fade-in начнётся с сохранённой громкости
     */
    fun stopWithFade() {
        Log.d(TAG, "stopWithFade() called, isPlaying=${_isPlaying.value}, currentFadeVolume=$currentFadeVolume")
        
        if (!_isPlaying.value) {
            Log.d(TAG, "Not playing, nothing to stop")
            return
        }
        
        // Сохраняем текущую громкость ДО начала fade-out
        // Это важно для случая, если play() будет вызван во время fade-out
        currentFadeVolume = getVolumeFromShaper()
        Log.d(TAG, "stopWithFade: saved currentFadeVolume=$currentFadeVolume")
        
        // Мгновенный отклик UI
        _isPlaying.value = false
        
        // Запускаем fade-out немедленно через VolumeShaper
        // VolumeShaper работает независимо от write(), поэтому fade начнётся сразу
        // даже если write() заблокирован на воспроизведении большого буфера
        startFadeOutImmediate(PLAYBACK_FADE_DURATION_MS)
    }
    
    /**
     * Немедленный запуск fade-out через VolumeShaper
     * Вызывается из UI потока через stopWithFade()
     */
    private fun startFadeOutImmediate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val startVolume = currentFadeVolume.coerceIn(MIN_VOLUME, 1.0f)
                
                if (startVolume <= MIN_VOLUME) {
                    // Громкость уже на минимуме - останавливаем сразу
                    Log.d(TAG, "startFadeOutImmediate: volume already at minimum, stopping immediately")
                    isActive.set(false)
                    audioHandler?.post(::stopPlayback)
                    return
                }
                
                val adjustedDuration = (durationMs * startVolume).toLong().coerceAtLeast(50)
                
                // Закрываем предыдущий VolumeShaper если есть
                volumeShaper?.close()
                volumeShaper = null
                
                // Создаём новый VolumeShaper для fade-out
                val config = VolumeShaper.Configuration.Builder()
                    .setDuration(adjustedDuration)
                    .setCurve(floatArrayOf(0f, 1f), floatArrayOf(startVolume, 0.0f))
                    .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                    .build()
                
                val track = audioTrack
                if (track != null) {
                    volumeShaper = track.createVolumeShaper(config)
                    volumeShaper?.apply(VolumeShaper.Operation.PLAY)
                    
                    // Записываем параметры fade для отслеживания
                    fadeStartTime = System.currentTimeMillis()
                    fadeDurationMs = adjustedDuration
                    fadeStartVolume = startVolume
                    fadeTargetVolume = 0.0f
                    isFadeInProgress = true
                    
                    Log.d(TAG, "startFadeOutImmediate: VolumeShaper started, startVolume=$startVolume, duration=${adjustedDuration}ms")
                    
                    // Планируем остановку после завершения fade
                    audioHandler?.postDelayed({
                        Log.d(TAG, "Fade-out completed, stopping playback")
                        if (isActive.get()) {
                            isActive.set(false)
                            stopPlayback()
                        }
                    }, adjustedDuration + 50) // Небольшой запас
                } else {
                    Log.w(TAG, "startFadeOutImmediate: AudioTrack is null, stopping directly")
                    isActive.set(false)
                    audioHandler?.post(::stopPlayback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startFadeOutImmediate error: ${e.message}")
                isActive.set(false)
                audioHandler?.post(::stopPlayback)
            }
        } else {
            // Для API < 26 останавливаем сразу
            isActive.set(false)
            audioHandler?.post(::stopPlayback)
        }
    }
    
    private fun stopPlayback() {
        // Сохраняем финальную громкость от VolumeShaper
        val finalVolume = getVolumeFromShaper()
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        volumeShaper?.close()
        volumeShaper = null
        isFadeInProgress = false
        
        // Если громкость очень низкая, сбрасываем в 0 для чистого запуска в следующий раз
        // Иначе сохраняем для плавного продолжения при быстром переключении
        currentFadeVolume = if (finalVolume < 0.05f) 0.0f else finalVolume
        
        releaseWakeLock()
        resetState()
        
        _isPlaying.value = false
        isActive.set(false)
        
        Log.d(TAG, "stopPlayback() completed, finalVolume=$finalVolume, currentFadeVolume=$currentFadeVolume")
    }
    
    private fun cleanupPlayback() {
        // Сохраняем текущую громкость перед очисткой
        currentFadeVolume = getVolumeFromShaper()
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        releaseWakeLock()
        
        _isPlaying.value = false
        isActive.set(false)
        isGenerating = false
        isFadeInProgress = false
        stopWithFadeRequested.set(false)
        pauseWithFadeRequested.set(false)
        currentFadeOperation = FadeOperation.NONE
        
        Log.d(TAG, "cleanupPlayback() completed, currentFadeVolume=$currentFadeVolume")
    }
    
    private fun resetState() {
        _elapsedSeconds.value = 0
        leftPhase = 0.0
        rightPhase = 0.0
        channelsSwapped = false
        lastSwapElapsedMs = 0L
        _isChannelsSwapped.value = false
        currentFadeOperation = FadeOperation.NONE
        fadeStartSample = 0L
        isFadingOut = true
        totalSamplesGenerated = 0L
        isGenerating = false
        stopWithFadeRequested.set(false)
        pauseWithFadeRequested.set(false)
        pendingPresetConfig = null
        cachedTimeSeconds = -1
        cachedLocalTime = LocalTime(0, 0)
        // НЕ сбрасываем currentFadeVolume - сохраняем для плавного перехода при следующем play()
        Log.d(TAG, "resetState() completed, isGenerating=$isGenerating")
    }

    /**
     * Приостановить воспроизведение с плавным затуханием (потокобезопасно)
     */
    fun pauseWithFade() {
        Log.d(TAG, "pauseWithFade() called, currentFadeVolume=$currentFadeVolume")
        
        if (!_isPlaying.value) {
            Log.d(TAG, "Not playing, nothing to pause")
            return
        }
        
        // Сохраняем текущую громкость
        currentFadeVolume = getVolumeFromShaper()
        Log.d(TAG, "pauseWithFade: saved currentFadeVolume=$currentFadeVolume")
        
        pauseWithFadeRequested.set(true)
    }

    /**
     * Возобновить воспроизведение с плавным нарастанием (потокобезопасно)
     */
    fun resumeWithFade() {
        Log.d(TAG, "resumeWithFade() called, currentFadeVolume=$currentFadeVolume")
        
        if (_isPlaying.value) {
            Log.d(TAG, "Already playing, returning")
            return
        }
        
        play()
    }

    /**
     * Переключить пресет с плавным затуханием/нарастанием (потокобезопасно)
     */
    fun switchPresetWithFade(config: BinauralConfig) {
        Log.d(TAG, "switchPresetWithFade() called, isPlaying=${_isPlaying.value}")
        
        if (!isActive.get()) {
            updateConfig(config)
            Log.d(TAG, "Not active, applying config directly")
            return
        }
        
        presetSwitchRequested.set(config)
        Log.d(TAG, "Preset switch request queued")
    }

    /**
     * Установить громкость (потокобезопасно)
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        val currentConfig = configRef.get()
        configRef.set(currentConfig.copy(volume = clampedVolume))
        _currentConfig.value = configRef.get()
        Log.d(TAG, "Volume set to $clampedVolume")
    }
    
    /**
     * Установить частоту дискретизации (потокобезопасно)
     */
    fun setSampleRate(rate: SampleRate) {
        Log.d(TAG, "setSampleRate() called: ${rate.value} Hz, current=${sampleRate} Hz")
        
        if (sampleRate == rate.value) {
            Log.d(TAG, "Sample rate already set to ${rate.value} Hz, skipping")
            return
        }
        
        val wasPlaying = _isPlaying.value
        Log.d(TAG, "wasPlaying=$wasPlaying")
        
        if (wasPlaying) {
            // Сохраняем текущую громкость перед перезапуском
            currentFadeVolume = getVolumeFromShaper()
            
            isActive.set(false)
            _isPlaying.value = false
            
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }
            
            sampleRate = rate.value
            Log.d(TAG, "Sample rate changed to ${sampleRate} Hz, currentFadeVolume=$currentFadeVolume")
            
            audioHandler?.post {
                audioTrack?.release()
                audioTrack = null
                Thread.sleep(50)
                Log.d(TAG, "Restarting playback with new sample rate: ${sampleRate} Hz")
                play()
            }
        } else {
            sampleRate = rate.value
            Log.d(TAG, "Sample rate set to ${sampleRate} Hz (not playing)")
        }
    }
    
    fun getSampleRate(): SampleRate = SampleRate.fromValue(sampleRate)
    
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        pendingFrequencyUpdateIntervalMs.set(intervalMs.coerceIn(1000, 60000))
    }

    fun getFrequencyUpdateInterval(): Int = frequencyUpdateIntervalMs

    fun setWavetableOptimizationEnabled(enabled: Boolean) {
        useWavetable = enabled
        Log.d(TAG, "Быстрая генерация звука: ${if (enabled) "ВКЛ" else "ВЫКЛ"}")
    }

    fun setWavetableSize(size: Int) {
        wavetableSize = size
        Wavetable.initialize(size)
        Log.d(TAG, "Размер таблицы волн: $size")
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            }
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }
    
    private fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    it.acquire(10 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to renew WakeLock", e)
        }
    }
    
    private fun releaseWakeLock() {
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
        audioThread?.quitSafely()
        audioThread = null
        audioHandler = null
        Log.d(TAG, "Audio engine released")
    }
}