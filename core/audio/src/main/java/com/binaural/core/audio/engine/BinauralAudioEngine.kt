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
        private const val BUFFER_SIZE_MS = 1000
        private const val WAKE_LOCK_TAG = "BinauralBeats:PlaybackWakeLock"
        private const val THREAD_NAME = "BinauralAudioThread"
        private const val MIN_VOLUME = 0.001f
        private const val PLAYBACK_FADE_DURATION_MS = 250L

        // Множитель интервала при Battery Saver (3x = 30 сек вместо 10 сек)
        private const val POWER_SAVE_INTERVAL_MULTIPLIER = 3

        // Максимальный размер буфера в минутах (ограничен памятью Android)
        // При 22050 Гц: 10 мин = 13,230,000 сэмплов × 2 канала × 4 байта = ~106 МБ
        private const val MAX_BUFFER_MINUTES = 10

        // Максимальный размер буфера в байтах (дополнительная защита)
        // ~500 МБ - безопасный лимит для современных Android устройств
        private const val MAX_BUFFER_BYTES = 500 * 1024 * 1024

        // Токен для отмены callbacks при переключении частоты дискретизации
        private val RESTART_PLAYBACK_TOKEN = Any()
        
        // Токен для отмены callback завершения fade-in
        private val FADE_IN_COMPLETE_TOKEN = Any()
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

    // Флаг для отслеживания запланированной операции перезапуска после смены частоты
    @Volatile
    private var restartPlaybackScheduled = false

    // Токен для debounce операций переключения частоты дискретизации
    private val SAMPLE_RATE_CHANGE_TOKEN = Any()

    // Текущие настройки
    private var sampleRate: Int = SampleRate.MEDIUM.value
    private var frequencyUpdateIntervalMs: Int = 10000

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

    // WakeLock для предотвращения засыпания
    private var wakeLock: PowerManager.WakeLock? = null

    // VolumeShaper для плавного изменения громкости при старте/остановке
    @Volatile
    private var volumeShaper: VolumeShaper? = null
    
    // Громкость, установленная пользователем (0.0 - 1.0)
    // Сохраняется между сессиями воспроизведения и не изменяется при fade
    private var userVolume: Float = 1.0f
    
    // Текущая громкость (0.0 - 1.0) - используется для отслеживания состояния fade
    private var currentVolume: Float = 1.0f
    
    // Трекинг параметров fade
    private var fadeStartTime: Long = 0L
    private var fadeDurationMs: Long = 0L
    private var fadeStartVolume: Float = 0.0f
    private var fadeTargetVolume: Float = 1.0f
    private var isFadeInProgress: Boolean = false

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
        
        Log.d(TAG, "Audio engine initialized on thread: ${audioThread?.name}")
    }

    /**
     * Обновить конфигурацию (потокобезопасно)
     */
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        configRef.set(config)
        _currentConfig.value = config
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
        nativeEngine?.updateRelaxationModeSettings(settings)
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

        Log.d(TAG, "startNewPlayback() - calling nativeEngine.resetState() and play()")
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

    @Synchronized
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
            
            // Создаём DirectByteBuffer для zero-copy генерации
            // Размер: samplesPerChannel * 2 канала * 4 байта на float
            // Дополнительно ограничиваем MAX_BUFFER_BYTES для защиты от OOM
            val directBufferSize = minOf(
                samplesPerChannel * 2 * 4,
                MAX_BUFFER_BYTES
            )
            
            if (directAudioBuffer == null || directAudioBuffer!!.capacity() < directBufferSize) {
                directAudioBuffer = java.nio.ByteBuffer.allocateDirect(directBufferSize)
                    .order(java.nio.ByteOrder.nativeOrder())
                Log.d(TAG, "Created DirectByteBuffer: $directBufferSize bytes (${directBufferSize / 1024 / 1024} MB) for interval ${effectiveIntervalMs}ms")
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
            audioHandler?.postAtTime({
                if (isActive.get()) {
                    volumeShaper?.close()
                    volumeShaper = null
                    isFadeInProgress = false
                    Log.d(TAG, "Fade-in completed, userVolume=$userVolume")
                }
            }, FADE_IN_COMPLETE_TOKEN, System.currentTimeMillis() + PLAYBACK_FADE_DURATION_MS + 50)
            
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

        var currentIntervalMs = frequencyUpdateIntervalMs
        var samplesPerChannel = minOf(
            (localSampleRate.toLong() * currentIntervalMs / 1000).toInt(),
            maxSamplesPerChannelLimit
        )

        isGenerating = true
        Log.d(TAG, "generateAudioLoop() - entering main loop, isActive=${isActive.get()}, audioTrack=$audioTrack")

        while (isActive.get() && audioTrack != null) {
            applyPendingSettings()

            if (frequencyUpdateIntervalMs != currentIntervalMs) {
                currentIntervalMs = frequencyUpdateIntervalMs
                samplesPerChannel = minOf(
                    (localSampleRate.toLong() * currentIntervalMs / 1000).toInt(),
                    maxSamplesPerChannelLimit
                )

                // Пересоздаём буфер если нужно больше места
                val requiredSize = samplesPerChannel * 2 * 4
                if (directAudioBuffer == null || directAudioBuffer!!.capacity() < requiredSize) {
                    directAudioBuffer = java.nio.ByteBuffer.allocateDirect(requiredSize)
                        .order(java.nio.ByteOrder.nativeOrder())
                    Log.d(TAG, "Resized DirectByteBuffer: $requiredSize bytes (${requiredSize / 1024 / 1024} MB)")
                }
            }

            checkFadeRequests()

            // Получаем актуальный буфер (может измениться при ресайзе)
            val directBuffer = directAudioBuffer
            if (directBuffer == null) {
                Log.e(TAG, "generateAudioLoop() - directAudioBuffer is null, returning")
                break
            }

            // Zero-copy генерация через DirectByteBuffer
            directBuffer.clear()
            val success = engine.generateBufferDirect(directBuffer, samplesPerChannel)
            
            if (!success) {
                Log.e(TAG, "Native buffer generation failed")
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

            // Запись в AudioTrack через DirectByteBuffer
            directBuffer.flip()
            val sizeInBytes = samplesPerChannel * 2 * 4
            
            val writeResult = try {
                // Записываем порциями не больше audioTrackBufferSize
                var totalWritten = 0
                while (totalWritten < sizeInBytes && isActive.get()) {
                    val remaining = sizeInBytes - totalWritten
                    val chunkSize = minOf(remaining, audioTrackBufferSize)
                    
                    directBuffer.position(totalWritten)
                    directBuffer.limit(totalWritten + chunkSize)
                    
                    val written = currentAudioTrack.write(directBuffer, chunkSize, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.e(TAG, "DirectByteBuffer write failed at offset $totalWritten: $written")
                        break
                    }
                    totalWritten += written
                }
                
                if (totalWritten == sizeInBytes) sizeInBytes else -1
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
        }

        isGenerating = false
        Log.d(TAG, "generateAudioLoop() ended")
    }
    
    private fun checkFadeRequests() {
        if (stopWithFadeRequested.get()) {
            Log.d(TAG, "Starting fade-out for stop")
            startFadeOut(PLAYBACK_FADE_DURATION_MS) {
                if (isActive.get()) {
                    isActive.set(false)
                    audioHandler?.post(::stopPlayback)
                }
            }
            stopWithFadeRequested.set(false)
            return
        }
        
        if (pauseWithFadeRequested.get()) {
            Log.d(TAG, "Starting fade-out for pause")
            startFadeOut(PLAYBACK_FADE_DURATION_MS) {
                if (isActive.get()) {
                    executePause()
                }
            }
            pauseWithFadeRequested.set(false)
            return
        }
        
        presetSwitchRequested.getAndSet(null)?.let { newConfig ->
            nativeEngine?.updateConfig(newConfig)
            configRef.set(newConfig)
            _currentConfig.value = newConfig
            Log.d(TAG, "Preset switched")
        }
    }
    
    private fun startFadeOut(durationMs: Long, callback: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentVolume = getVolumeFromShaper()
            
            val adjustedDuration = if (currentVolume <= MIN_VOLUME) {
                0L
            } else {
                (durationMs * currentVolume).toLong().coerceAtLeast(50)
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
    
    private fun executePause() {
        _isPlaying.value = false
        
        val currentSessionMs = System.currentTimeMillis() - playbackStartTime
        accumulatedElapsedMs += currentSessionMs
        Log.d(TAG, "executePause: accumulatedElapsedMs=$accumulatedElapsedMs")
        
        audioHandler?.post {
            audioTrack?.pause()
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

    /**
     * Остановить воспроизведение немедленно
     */
    fun stop() {
        Log.d(TAG, "stop() called")
        
        isActive.set(false)
        _isPlaying.value = false
        stopWithFadeRequested.set(false)
        pauseWithFadeRequested.set(false)
        
        // Отменяем callback завершения fade-in
        audioHandler?.removeCallbacksAndMessages(FADE_IN_COMPLETE_TOKEN)
        
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
        
        // Отменяем callback завершения fade-in, чтобы он не выполнился
        // на новом AudioTrack после перезапуска воспроизведения
        audioHandler?.removeCallbacksAndMessages(FADE_IN_COMPLETE_TOKEN)
        
        // Проверяем isActive, а не _isPlaying!
        // isActive остаётся true во время fade-out, что предотвращает
        // накопление нескольких fade-out операций при быстрых переключениях
        if (!isActive.get()) {
            Log.d(TAG, "stopWithFade() - not active, returning")
            return
        }
        
        currentVolume = getVolumeFromShaper()
        _isPlaying.value = false
        startFadeOutImmediate(PLAYBACK_FADE_DURATION_MS) {
            isActive.set(false)
            stopPlayback()
        }
    }
    
    private fun startFadeOutImmediate(durationMs: Long, onComplete: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val startVolume = currentVolume.coerceIn(MIN_VOLUME, 1.0f)
                
                if (startVolume <= MIN_VOLUME) {
                    onComplete()
                    return
                }
                
                val adjustedDuration = (durationMs * startVolume).toLong().coerceAtLeast(50)
                
                volumeShaper?.close()
                volumeShaper = null
                
                val config = VolumeShaper.Configuration.Builder()
                    .setDuration(adjustedDuration)
                    .setCurve(floatArrayOf(0f, 1f), floatArrayOf(startVolume, 0.0f))
                    .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                    .build()
                
                val track = audioTrack
                if (track != null) {
                    volumeShaper = track.createVolumeShaper(config)
                    volumeShaper?.apply(VolumeShaper.Operation.PLAY)
                    
                    fadeStartTime = System.currentTimeMillis()
                    fadeDurationMs = adjustedDuration
                    fadeStartVolume = startVolume
                    fadeTargetVolume = 0.0f
                    isFadeInProgress = true
                    
                    audioHandler?.postDelayed({
                        onComplete()
                    }, adjustedDuration + 50)
                } else {
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "startFadeOutImmediate error: ${e.message}")
                onComplete()
            }
        } else {
            onComplete()
        }
    }
    
    private fun stopPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        volumeShaper?.close()
        volumeShaper = null
        isFadeInProgress = false
        
        // Восстанавливаем currentVolume до userVolume для корректного
        // последующего перезапуска. userVolume - единственный источник истины.
        currentVolume = userVolume
        
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
        
        releaseWakeLock()
        
        _isPlaying.value = false
        isActive.set(false)
        isGenerating = false
        isFadeInProgress = false
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
        
        // Отменяем callback завершения fade-in, чтобы он не выполнился
        // на новом AudioTrack после возобновления воспроизведения
        audioHandler?.removeCallbacksAndMessages(FADE_IN_COMPLETE_TOKEN)
        
        // Проверяем isActive, а не _isPlaying - аналогично stopWithFade()
        if (!isActive.get()) {
            Log.d(TAG, "pauseWithFade() - not active, returning")
            return
        }
        
        // Немедленно запускаем fade-out, не дожидаясь цикла генерации
        currentVolume = getVolumeFromShaper()
        _isPlaying.value = false
        
        startFadeOutImmediate(PLAYBACK_FADE_DURATION_MS) {
            executePause()
        }
    }

    /**
     * Возобновить воспроизведение с плавным нарастанием
     */
    fun resumeWithFade() {
        Log.d(TAG, "resumeWithFade() called")
        
        if (_isPlaying.value) return
        
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

            // Отменяем callback завершения fade-in
            audioHandler?.removeCallbacksAndMessages(FADE_IN_COMPLETE_TOKEN)
            
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
        nativeEngine?.release()
        nativeEngine = null
        audioThread?.quitSafely()
        audioThread = null
        audioHandler = null
        Log.d(TAG, "Audio engine released")
    }
}