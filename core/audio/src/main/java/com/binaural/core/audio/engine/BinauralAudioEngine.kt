package com.binaural.core.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
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

/**
 * Частота дискретизации аудио
 */
enum class SampleRate(val value: Int) {
    LOW(22050),    // Низкое качество, меньше расход батареи
    MEDIUM(44100), // Стандартное качество
    HIGH(48000);   // Высокое качество
    
    companion object {
        fun fromValue(value: Int): SampleRate {
            return entries.find { it.value == value } ?: MEDIUM
        }
    }
}

/**
 * Движок для генерации и воспроизведения бинауральных ритмов.
 * Работает в отдельном потоке (HandlerThread) для исключения задержек в UI.
 * 
 * ВАЖНО: Не использовать как Singleton - должен создаваться только в Service!
 */
class BinauralAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "BinauralAudioEngine"
        private const val BUFFER_SIZE_MS = 1000 // 1000мс буфер для плавности при выключенном экране
        private const val WAKE_LOCK_TAG = "BinauralBeats:PlaybackWakeLock"
        private const val THREAD_NAME = "BinauralAudioThread"
    }
    
    // Атомарные ссылки для потокобезопасного доступа из HandlerThread
    private val configRef = AtomicReference(BinauralConfig())
    private val isActive = AtomicBoolean(false)
    private val pendingSampleRate = AtomicReference<SampleRate?>(null)
    private val pendingFrequencyUpdateIntervalMs = AtomicReference<Int?>(null)
    
    // Текущие настройки (доступны только из audioThread)
    private var sampleRate: Int = SampleRate.MEDIUM.value
    private var frequencyUpdateIntervalMs: Int = 100
    
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
    
    // Состояние fade при смене каналов
    private var fadeStartSample = 0L
    private var isFadingOut = true
    private var isFading = false
    
    // Общий счётчик сэмплов для плавного fade
    private var totalSamplesGenerated = 0L
    
    // Время начала воспроизведения
    private var playbackStartTime = 0L

    // StateFlows для UI (обновляются из audioThread, читаются из UI потока)
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
        audioThread = HandlerThread(THREAD_NAME, Thread.MAX_PRIORITY).apply {
            start()
        }
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
     */
    fun play() {
        Log.d(TAG, "play() called, isPlaying=${_isPlaying.value}")
        
        if (_isPlaying.value) {
            Log.d(TAG, "Already playing, returning")
            return
        }
        
        val handler = audioHandler
        if (handler == null) {
            Log.e(TAG, "AudioHandler is null! Cannot start playback")
            return
        }
        
        isActive.set(true)
        _isPlaying.value = true
        playbackStartTime = System.currentTimeMillis()
        
        acquireWakeLock()
        
        // Запускаем генерацию в отдельном потоке
        handler.post(::startPlayback)
    }
    
    private fun startPlayback() {
        if (!isActive.get()) return
        
        Log.d(TAG, "startPlayback() on thread: ${Thread.currentThread().name}")
        
        try {
            createAudioTrack()
            audioTrack?.play()
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

        audioTrack?.setVolume(configRef.get().volume)
        Log.d(TAG, "AudioTrack created: sampleRate=$sampleRate, bufferSize=$bufferSize")
    }
    
    private fun generateAudioLoop() {
        val handler = audioHandler ?: return
        var wakeLockRenewCounter = 0
        val wakeLockRenewInterval = 600 // каждую минуту
        
        var currentIntervalMs = frequencyUpdateIntervalMs
        var samplesPerChannel = sampleRate * currentIntervalMs / 1000
        var buffer = ShortArray(samplesPerChannel * 2)
        
        isGenerating = true
        
        while (isActive.get() && audioTrack != null) {
            // Применяем отложенные настройки
            applyPendingSettings()
            
            // Пересоздаём буфер если изменился интервал
            if (frequencyUpdateIntervalMs != currentIntervalMs) {
                currentIntervalMs = frequencyUpdateIntervalMs
                samplesPerChannel = sampleRate * currentIntervalMs / 1000
                buffer = ShortArray(samplesPerChannel * 2)
            }
            
            // Получаем текущие частоты
            val now = Clock.System.now()
            val zone = TimeZone.currentSystemDefault()
            val localDateTime = now.toLocalDateTime(zone)
            val currentTime = localDateTime.time
            
            val config = configRef.get()
            val (beatFreq, carrierFreq) = config.getFrequenciesAt(currentTime)
            
            // Обновляем StateFlows (это потокобезопасно)
            _currentBeatFrequency.value = beatFreq
            _currentCarrierFrequency.value = carrierFreq
            
            // Проверяем перестановку каналов
            checkChannelSwap(config)
            
            // Генерируем буфер
            val fadeDurationSamples = (config.channelSwapFadeDurationMs.coerceAtLeast(100L) * sampleRate / 1000).toInt()
            val fadeResult = generateBuffer(buffer, carrierFreq, beatFreq, samplesPerChannel, fadeDurationSamples, config)
            
            totalSamplesGenerated += samplesPerChannel
            
            if (fadeResult.fadePhaseCompleted && isFading) {
                isFading = false
                isFadingOut = true
                Log.d(TAG, "Fade completed")
            }
            
            // Проверяем активность перед записью
            if (!isActive.get()) break
            
            // Записываем в AudioTrack
            val currentAudioTrack = audioTrack
            if (currentAudioTrack == null) {
                Log.d(TAG, "AudioTrack is null, stopping")
                break
            }
            
            val result = try {
                currentAudioTrack.write(buffer, 0, buffer.size)
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
            
            // Обновляем elapsed time
            _elapsedSeconds.value = ((System.currentTimeMillis() - playbackStartTime) / 100).toInt()
            
            // Периодически обновляем WakeLock
            wakeLockRenewCounter++
            if (wakeLockRenewCounter >= wakeLockRenewInterval) {
                renewWakeLock()
                wakeLockRenewCounter = 0
            }
        }
        
        isGenerating = false
        Log.d(TAG, "generateAudioLoop() ended")
    }
    
    private fun checkChannelSwap(config: BinauralConfig) {
        if (config.channelSwapEnabled && !isFading) {
            val currentElapsedMs = _elapsedSeconds.value * 100L
            val swapIntervalMs = config.channelSwapIntervalSeconds * 1000L
            
            if (currentElapsedMs - lastSwapElapsedMs >= swapIntervalMs) {
                if (config.channelSwapFadeEnabled) {
                    isFading = true
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
                // Для изменения sampleRate нужно пересоздать AudioTrack
                // Это делается при следующем play()
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
    
    private fun generateBuffer(
        buffer: ShortArray,
        carrierFreq: Double,
        beatFreq: Double,
        samplesPerChannel: Int,
        fadeDurationSamples: Int,
        config: BinauralConfig
    ): FadeResult {
        val leftFreq = carrierFreq - beatFreq / 2.0
        val rightFreq = carrierFreq + beatFreq / 2.0
        
        val (leftAmplitude, rightAmplitude) = calculateNormalizedAmplitudes(leftFreq, rightFreq, config)
        
        val leftOmega = 2.0 * PI * leftFreq / sampleRate
        val rightOmega = 2.0 * PI * rightFreq / sampleRate
        
        val swapChannelsAtStart = config.channelSwapEnabled && channelsSwapped
        var fadePhaseCompleted = false
        var swapAtSample = -1L
        
        var localIsFadingOut = isFadingOut
        var localFadeStartSample = fadeStartSample
        var localChannelsSwapped = channelsSwapped
        
        for (i in 0 until samplesPerChannel) {
            val currentSample = totalSamplesGenerated + i
            var fadeMultiplier = 1.0
            
            if (isFading) {
                val elapsedSamples = currentSample - localFadeStartSample
                val progress = elapsedSamples.toDouble() / fadeDurationSamples
                
                if (localIsFadingOut) {
                    if (progress >= 1.0) {
                        fadeMultiplier = 0.0
                        if (swapAtSample < 0) {
                            swapAtSample = currentSample
                            localChannelsSwapped = !localChannelsSwapped
                            _isChannelsSwapped.value = localChannelsSwapped
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
            
            val leftSample = sin(leftPhase)
            leftPhase += leftOmega
            if (leftPhase > 2.0 * PI) leftPhase -= 2.0 * PI
            
            val rightSample = sin(rightPhase)
            rightPhase += rightOmega
            if (rightPhase > 2.0 * PI) rightPhase -= 2.0 * PI
            
            val baseAmplitude = 0.5 * Short.MAX_VALUE * fadeMultiplier
            val leftAmp = baseAmplitude * leftAmplitude
            val rightAmp = baseAmplitude * rightAmplitude
            
            val swapForSample = if (swapAtSample >= 0 && currentSample >= swapAtSample) {
                config.channelSwapEnabled && localChannelsSwapped
            } else {
                swapChannelsAtStart
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
        
        return FadeResult(fadePhaseCompleted)
    }
    
    private fun calculateNormalizedAmplitudes(
        leftFreq: Double,
        rightFreq: Double,
        config: BinauralConfig
    ): Pair<Double, Double> {
        if (!config.volumeNormalizationEnabled) {
            return Pair(1.0, 1.0)
        }
        
        val strength = config.volumeNormalizationStrength.coerceIn(0f, 1f)
        val minFreq = minOf(leftFreq, rightFreq)
        
        val leftNormalized = minFreq / leftFreq
        val rightNormalized = minFreq / rightFreq
        
        val leftAmplitude = 1.0 + strength * (leftNormalized - 1.0)
        val rightAmplitude = 1.0 + strength * (rightNormalized - 1.0)
        
        return Pair(leftAmplitude, rightAmplitude)
    }

    /**
     * Остановить воспроизведение (потокобезопасно)
     */
    fun stop() {
        Log.d(TAG, "stop() called")
        
        isActive.set(false)
        _isPlaying.value = false
        
        // Немедленно останавливаем AudioTrack, чтобы разблокировать write()
        // AudioTrack.stop() потокобезопасен и заставит write() немедленно вернуться
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
        }
        
        // Отменяем все pending сообщения и запланированную остановку
        audioHandler?.removeCallbacksAndMessages(null)
        audioHandler?.post(::stopPlayback)
    }
    
    private fun stopPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        releaseWakeLock()
        resetState()
        
        Log.d(TAG, "stopPlayback() completed")
    }
    
    private fun cleanupPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        releaseWakeLock()
        
        _isPlaying.value = false
        isActive.set(false)
        isGenerating = false
        
        Log.d(TAG, "cleanupPlayback() completed")
    }
    
    private fun resetState() {
        _elapsedSeconds.value = 0
        leftPhase = 0.0
        rightPhase = 0.0
        channelsSwapped = false
        lastSwapElapsedMs = 0L
        _isChannelsSwapped.value = false
        isFading = false
        fadeStartSample = 0L
        isFadingOut = true
        totalSamplesGenerated = 0L
    }

    /**
     * Приостановить воспроизведение
     */
    fun pause() {
        if (!_isPlaying.value) return
        
        _isPlaying.value = false
        isActive.set(false)
        
        audioHandler?.post {
            audioTrack?.pause()
        }
    }

    /**
     * Установить громкость (потокобезопасно)
     */
    fun setVolume(volume: Float) {
        val currentConfig = configRef.get()
        configRef.set(currentConfig.copy(volume = volume.coerceIn(0f, 1f)))
        _currentConfig.value = configRef.get()
        
        audioHandler?.post {
            audioTrack?.setVolume(volume.coerceIn(0f, 1f))
        }
    }
    
    /**
     * Установить частоту дискретизации (потокобезопасно)
     */
    fun setSampleRate(rate: SampleRate) {
        val wasPlaying = _isPlaying.value
        if (wasPlaying) {
            stop()
        }
        sampleRate = rate.value
        // Перезапуск если нужно
        if (wasPlaying) {
            // Небольшая задержка для корректного освобождения ресурсов
            audioHandler?.postDelayed({ play() }, 100)
        }
    }
    
    /**
     * Получить текущую частоту дискретизации
     */
    fun getSampleRate(): SampleRate {
        return SampleRate.fromValue(sampleRate)
    }
    
    /**
     * Установить интервал обновления частот (потокобезопасно)
     */
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        pendingFrequencyUpdateIntervalMs.set(intervalMs.coerceIn(100, 5000))
    }
    
    /**
     * Получить текущий интервал обновления частот
     */
    fun getFrequencyUpdateInterval(): Int = frequencyUpdateIntervalMs

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                )
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

    /**
     * Освободить все ресурсы
     */
    fun release() {
        stop()
        
        audioThread?.quitSafely()
        audioThread = null
        audioHandler = null
        
        Log.d(TAG, "Audio engine released")
    }
}