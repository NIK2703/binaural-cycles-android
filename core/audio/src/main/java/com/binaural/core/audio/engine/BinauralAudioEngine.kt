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
 * - VolumeShaper обеспечивает плавные переходы громкости
 */
class BinauralAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "BinauralAudioEngine"
        private const val BUFFER_SIZE_MS = 1000
        private const val WAKE_LOCK_TAG = "BinauralBeats:PlaybackWakeLock"
        private const val THREAD_NAME = "BinauralAudioThread"
        private const val MIN_VOLUME = 0.001f
        private const val PLAYBACK_FADE_DURATION_MS = 250L
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
    
    // Текущие настройки
    private var sampleRate: Int = SampleRate.MEDIUM.value
    private var frequencyUpdateIntervalMs: Int = 10000

    // Предварительно выделенный буфер для генерации аудио
    private var audioBuffer = FloatArray(0)
    private var maxSamplesPerChannel = 0

    // AudioTrack
    private var audioTrack: AudioTrack? = null

    // HandlerThread для генерации аудио
    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    private var isGenerating = false

    // WakeLock для предотвращения засыпания
    private var wakeLock: PowerManager.WakeLock? = null

    // VolumeShaper для плавного изменения громкости
    @Volatile
    private var volumeShaper: VolumeShaper? = null
    
    // Текущая громкость fade (0.0 - 1.0)
    private var currentFadeVolume: Float = 0.0f
    
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
        
        // Инициализируем нативный движок
        nativeEngine = NativeAudioEngine()
        nativeEngine?.initialize()
        nativeEngine?.setSampleRate(sampleRate)
        nativeEngine?.setFrequencyUpdateInterval(frequencyUpdateIntervalMs)
        
        Log.d(TAG, "Audio engine initialized on thread: ${audioThread?.name}")
    }

    /**
     * Обновить конфигурацию (потокобезопасно)
     */
    fun updateConfig(config: BinauralConfig) {
        configRef.set(config)
        _currentConfig.value = config
        nativeEngine?.updateConfig(config)
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
            
            val savedVolume = getVolumeFromShaper()
            
            try {
                volumeShaper?.close()
                volumeShaper = null
                audioTrack?.setVolume(0.0f)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting volume to 0: ${e.message}")
            }
            
            currentFadeVolume = savedVolume
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
        if (isActive.get()) return
        
        isActive.set(true)
        _isPlaying.value = true
        playbackStartTime = System.currentTimeMillis()
        
        nativeEngine?.resetState()
        nativeEngine?.play()
        
        acquireWakeLock()
        handler.post(::startPlayback)
    }
    
    private fun startPlayback() {
        if (!isActive.get()) return

        Log.d(TAG, "startPlayback() on thread: ${Thread.currentThread().name}")

        try {
            maxSamplesPerChannel = sampleRate * 60
            if (audioBuffer.size < maxSamplesPerChannel * 2) {
                audioBuffer = FloatArray(maxSamplesPerChannel * 2)
            }
            
            createAudioTrack()
            createVolumeShaper(PLAYBACK_FADE_DURATION_MS, targetVolume = 1.0f)
            audioTrack?.play()
            startVolumeShaper()
            
            generateAudioLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Playback error", e)
        } finally {
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

        audioTrack?.setVolume(1.0f)
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
                volumeShaper?.volume ?: currentFadeVolume
            } catch (e: Exception) {
                currentFadeVolume
            }
        } else {
            currentFadeVolume
        }
    }
    
    private fun createVolumeShaper(durationMs: Long, targetVolume: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        try {
            volumeShaper?.close()
            
            val startVolume = currentFadeVolume.coerceIn(MIN_VOLUME, 1.0f)
            val clampedTarget = targetVolume.coerceIn(0.0f, 1.0f)
            
            if (kotlin.math.abs(startVolume - clampedTarget) < 0.01f) {
                currentFadeVolume = clampedTarget
                isFadeInProgress = false
                audioTrack?.setVolume(clampedTarget)
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
        val engine = nativeEngine ?: return

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

            val success = engine.generateBuffer(audioBuffer, samplesPerChannel)
            
            if (!success) {
                Log.e(TAG, "Native buffer generation failed")
                break
            }

            // Обновляем StateFlows из нативного движка
            val beatFreq = engine.getCurrentBeatFrequency()
            val carrierFreq = engine.getCurrentCarrierFrequency()
            
            if (_currentBeatFrequency.value != beatFreq) {
                _currentBeatFrequency.value = beatFreq
            }
            if (_currentCarrierFrequency.value != carrierFreq) {
                _currentCarrierFrequency.value = carrierFreq
            }
            
            val swapped = engine.isChannelsSwapped()
            if (_isChannelsSwapped.value != swapped) {
                _isChannelsSwapped.value = swapped
            }
            
            _elapsedSeconds.value = engine.getElapsedSeconds()

            if (!isActive.get()) break

            val currentAudioTrack = audioTrack
            if (currentAudioTrack == null) {
                Log.d(TAG, "AudioTrack is null, stopping")
                break
            }

            val result = try {
                currentAudioTrack.write(audioBuffer, 0, samplesPerChannel * 2, AudioTrack.WRITE_BLOCKING)
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
            currentFadeVolume = getVolumeFromShaper()
            
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
        Log.d(TAG, "stopWithFade() called")
        
        if (!_isPlaying.value) return
        
        currentFadeVolume = getVolumeFromShaper()
        _isPlaying.value = false
        startFadeOutImmediate(PLAYBACK_FADE_DURATION_MS)
    }
    
    private fun startFadeOutImmediate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val startVolume = currentFadeVolume.coerceIn(MIN_VOLUME, 1.0f)
                
                if (startVolume <= MIN_VOLUME) {
                    isActive.set(false)
                    audioHandler?.post(::stopPlayback)
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
                        if (isActive.get()) {
                            isActive.set(false)
                            stopPlayback()
                        }
                    }, adjustedDuration + 50)
                } else {
                    isActive.set(false)
                    audioHandler?.post(::stopPlayback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startFadeOutImmediate error: ${e.message}")
                isActive.set(false)
                audioHandler?.post(::stopPlayback)
            }
        } else {
            isActive.set(false)
            audioHandler?.post(::stopPlayback)
        }
    }
    
    private fun stopPlayback() {
        val finalVolume = getVolumeFromShaper()
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        volumeShaper?.close()
        volumeShaper = null
        isFadeInProgress = false
        
        currentFadeVolume = if (finalVolume < 0.05f) 0.0f else finalVolume
        
        releaseWakeLock()
        resetState()
        
        _isPlaying.value = false
        isActive.set(false)
        
        Log.d(TAG, "stopPlayback() completed")
    }
    
    private fun cleanupPlayback() {
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
        
        Log.d(TAG, "cleanupPlayback() completed")
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
        Log.d(TAG, "pauseWithFade() called")
        
        if (!_isPlaying.value) return
        
        currentFadeVolume = getVolumeFromShaper()
        pauseWithFadeRequested.set(true)
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
     * Установить громкость
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        val currentConfig = configRef.get()
        configRef.set(currentConfig.copy(volume = clampedVolume))
        _currentConfig.value = configRef.get()
        Log.d(TAG, "Volume set to $clampedVolume")
    }
    
    /**
     * Установить частоту дискретизации
     */
    fun setSampleRate(rate: SampleRate) {
        Log.d(TAG, "setSampleRate() called: ${rate.value} Hz")
        
        if (sampleRate == rate.value) return
        
        val wasPlaying = _isPlaying.value
        
        if (wasPlaying) {
            currentFadeVolume = getVolumeFromShaper()
            
            isActive.set(false)
            _isPlaying.value = false
            
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }
            
            sampleRate = rate.value
            nativeEngine?.setSampleRate(sampleRate)
            Log.d(TAG, "Sample rate changed to ${sampleRate} Hz")
            
            audioHandler?.post {
                audioTrack?.release()
                audioTrack = null
                Thread.sleep(50)
                play()
            }
        } else {
            sampleRate = rate.value
            nativeEngine?.setSampleRate(sampleRate)
        }
    }
    
    fun getSampleRate(): SampleRate = SampleRate.fromValue(sampleRate)
    
    /**
     * Установить интервал обновления частот
     */
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        val clampedInterval = intervalMs.coerceIn(1000, 60000)
        pendingFrequencyUpdateIntervalMs.set(clampedInterval)
        nativeEngine?.setFrequencyUpdateInterval(clampedInterval)
    }

    fun getFrequencyUpdateInterval(): Int = frequencyUpdateIntervalMs

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