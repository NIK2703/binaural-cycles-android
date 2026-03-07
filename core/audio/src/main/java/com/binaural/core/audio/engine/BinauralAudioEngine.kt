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
    ULTRA_LOW(8000),   // Ультра-низкое (8 kHz) — максимальная экономия батареи
    VERY_LOW(16000),   // Очень низкое (16 kHz)
    LOW(22050),        // Низкое качество, меньше расход батареи
    MEDIUM(44100),     // Стандартное качество
    HIGH(48000);       // Высокое качество
    
    companion object {
        fun fromValue(value: Int): SampleRate {
            return entries.find { it.value == value } ?: MEDIUM
        }
    }
}

/**
 * Тип операции fade
 */
private enum class FadeOperation {
    NONE,           // Нет активного fade
    CHANNEL_SWAP,   // Перестановка каналов
    PRESET_SWITCH,  // Переключение пресета
    PAUSE,          // Пауза
    STOP            // Остановка
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
    
    // Запросы на операции с fade (потокобезопасные)
    private val stopWithFadeRequested = AtomicBoolean(false)
    private val pauseWithFadeRequested = AtomicBoolean(false)
    private val presetSwitchRequested = AtomicReference<BinauralConfig?>(null)
    
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
    
    // Состояние fade (доступны только из audioThread)
    private var fadeStartSample = 0L
    private var isFadingOut = true
    private var currentFadeOperation = FadeOperation.NONE
    
    // Состояние fade-in при старте
    private var isFadingIn = false
    
    // Отложенный конфиг для переключения пресета
    private var pendingPresetConfig: BinauralConfig? = null
    
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
        
        // Запускаем fade-in при старте
        isFadingIn = true
        fadeStartSample = 0L
        totalSamplesGenerated = 0L
        
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

        // Устанавливаем максимальную громкость AudioTrack, 
        // реальная громкость применяется в generateBuffer() через config.volume
        audioTrack?.setVolume(1.0f)
        Log.d(TAG, "AudioTrack created: sampleRate=$sampleRate, bufferSize=$bufferSize")
    }
    
    private fun generateAudioLoop() {
        val handler = audioHandler ?: return
        var wakeLockRenewCounter = 0
        val wakeLockRenewInterval = 600 // каждую минуту
        
        var currentIntervalMs = frequencyUpdateIntervalMs
        var samplesPerChannel = (sampleRate.toLong() * currentIntervalMs / 1000).toInt()
        var buffer = ShortArray(samplesPerChannel * 2)
        
        isGenerating = true
        
        while (isActive.get() && audioTrack != null) {
            // Применяем отложенные настройки
            applyPendingSettings()
            
            // Пересоздаём буфер если изменился интервал
            if (frequencyUpdateIntervalMs != currentIntervalMs) {
                currentIntervalMs = frequencyUpdateIntervalMs
                samplesPerChannel = (sampleRate.toLong() * currentIntervalMs / 1000).toInt()
                buffer = ShortArray(samplesPerChannel * 2)
            }
            
            // Проверяем запросы на операции с fade
            checkFadeRequests()
            
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
            
            // Проверяем перестановку каналов (только если нет активного fade)
            if (currentFadeOperation == FadeOperation.NONE) {
                checkChannelSwap(config)
            }
            
            // Генерируем буфер
            val fadeDurationSamples = (config.channelSwapFadeDurationMs.coerceAtLeast(100L) * sampleRate / 1000).toInt()
            val fadeResult = generateBuffer(buffer, carrierFreq, beatFreq, samplesPerChannel, fadeDurationSamples, config)
            
            totalSamplesGenerated += samplesPerChannel
            
            // Обрабатываем завершение fade-in
            if (fadeResult.fadePhaseCompleted && isFadingIn) {
                isFadingIn = false
                Log.d(TAG, "Fade-in completed")
            }
            
            // Обрабатываем завершение fade для различных операций
            if (fadeResult.fadePhaseCompleted && currentFadeOperation != FadeOperation.NONE) {
                handleFadeOperationCompleted()
            }
            
            // Обрабатываем завершение fade-out для остановки или паузы
            if (fadeResult.fadeOutCompleted) {
                if (currentFadeOperation == FadeOperation.STOP) {
                    Log.d(TAG, "Fade-out completed, stopping playback")
                    break
                } else if (currentFadeOperation == FadeOperation.PAUSE) {
                    Log.d(TAG, "Fade-out completed, pausing playback")
                    executePause()
                    break
                }
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
    
    private fun checkFadeRequests() {
        // Сначала проверяем, есть ли уже активная fade операция
        if (currentFadeOperation != FadeOperation.NONE) {
            return // Уже выполняется fade, ждём завершения
        }
        
        // Проверяем запрос на остановку с fade-out
        if (stopWithFadeRequested.get()) {
            currentFadeOperation = FadeOperation.STOP
            isFadingOut = true
            fadeStartSample = totalSamplesGenerated
            Log.d(TAG, "Starting fade-out for stop")
            return
        }
        
        // Проверяем запрос на паузу с fade-out
        if (pauseWithFadeRequested.get()) {
            currentFadeOperation = FadeOperation.PAUSE
            isFadingOut = true
            fadeStartSample = totalSamplesGenerated
            Log.d(TAG, "Starting fade-out for pause")
            return
        }
        
        // Проверяем запрос на переключение пресета с fade
        presetSwitchRequested.getAndSet(null)?.let { newConfig ->
            pendingPresetConfig = newConfig
            currentFadeOperation = FadeOperation.PRESET_SWITCH
            isFadingOut = true
            fadeStartSample = totalSamplesGenerated
            Log.d(TAG, "Starting fade-out for preset switch, config volume=${newConfig.volume}")
        }
    }
    
    private fun handleFadeOperationCompleted() {
        when (currentFadeOperation) {
            FadeOperation.CHANNEL_SWAP -> {
                // Каналы уже переключены в generateBuffer(), просто логируем
                Log.d(TAG, "Channel swap fade completed, swapped=$channelsSwapped")
            }
            FadeOperation.PRESET_SWITCH -> {
                // Конфиг уже применён в generateBuffer(), просто логируем
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

    private data class FadeResult(val fadePhaseCompleted: Boolean, val fadeOutCompleted: Boolean = false)
    
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
        
        // Запоминаем начальное состояние каналов
        val channelsSwappedAtStart = channelsSwapped
        var fadePhaseCompleted = false
        var fadeOutCompleted = false
        var swapExecutedAtSample = -1L  // Сэмпл, на котором выполнен swap
        var configSwitchAtSample = -1L
        
        var localIsFadingOut = isFadingOut
        var localFadeStartSample = fadeStartSample
        var localChannelsSwapped = channelsSwapped
        var localFadeOperation = currentFadeOperation
        var localPendingPresetConfig: BinauralConfig? = pendingPresetConfig
        
        // Интервал проверки запросов на переключение пресета (каждые ~10мс)
        val presetCheckInterval = sampleRate / 100
        var lastPresetCheck = 0
        
        for (i in 0 until samplesPerChannel) {
            val currentSample = totalSamplesGenerated + i
            var fadeMultiplier = 1.0
            
            // Проверяем запрос на переключение пресета каждые ~10мс
            // Это позволяет мгновенно реагировать на переключение без ожидания конца буфера
            if (i - lastPresetCheck >= presetCheckInterval) {
                lastPresetCheck = i
                presetSwitchRequested.getAndSet(null)?.let { newConfig ->
                    if (localFadeOperation == FadeOperation.NONE && !isFadingIn) {
                        localPendingPresetConfig = newConfig
                        localFadeOperation = FadeOperation.PRESET_SWITCH
                        localIsFadingOut = true
                        localFadeStartSample = currentSample
                    }
                }
            }
            
            // Fade-in при старте воспроизведения
            if (isFadingIn) {
                val progress = currentSample.toDouble() / fadeDurationSamples
                if (progress >= 1.0) {
                    fadeMultiplier = 1.0
                    fadePhaseCompleted = true
                } else if (progress >= 0.0) {
                    fadeMultiplier = progress
                }
            }
            // Fade-out для остановки или паузы
            else if (localFadeOperation == FadeOperation.STOP || localFadeOperation == FadeOperation.PAUSE) {
                val elapsedSamples = currentSample - localFadeStartSample
                val progress = elapsedSamples.toDouble() / fadeDurationSamples
                if (progress >= 1.0) {
                    fadeMultiplier = 0.0
                    fadeOutCompleted = true
                } else if (progress >= 0.0) {
                    fadeMultiplier = 1.0 - progress
                }
            }
            // Fade для смены каналов или переключения пресета
            else if (localFadeOperation == FadeOperation.CHANNEL_SWAP || localFadeOperation == FadeOperation.PRESET_SWITCH) {
                val elapsedSamples = currentSample - localFadeStartSample
                val progress = elapsedSamples.toDouble() / fadeDurationSamples
                
                if (localIsFadingOut) {
                    if (progress >= 1.0) {
                        fadeMultiplier = 0.0
                        // Выполняем операцию в точке полной тишины
                        if (localFadeOperation == FadeOperation.CHANNEL_SWAP && swapExecutedAtSample < 0) {
                            swapExecutedAtSample = currentSample
                            localChannelsSwapped = !localChannelsSwapped
                            _isChannelsSwapped.value = localChannelsSwapped
                            localIsFadingOut = false
                            localFadeStartSample = currentSample
                            Log.d(TAG, "Channel swap executed at sample $currentSample, now swapped=$localChannelsSwapped")
                        } else if (localFadeOperation == FadeOperation.PRESET_SWITCH && configSwitchAtSample < 0) {
                            configSwitchAtSample = currentSample
                            localPendingPresetConfig?.let { newConfig ->
                                configRef.set(newConfig)
                                _currentConfig.value = newConfig
                                Log.d(TAG, "Preset config applied at sample $currentSample")
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
            
            val leftSample = sin(leftPhase)
            leftPhase += leftOmega
            if (leftPhase > 2.0 * PI) leftPhase -= 2.0 * PI
            
            val rightSample = sin(rightPhase)
            rightPhase += rightOmega
            if (rightPhase > 2.0 * PI) rightPhase -= 2.0 * PI
            
            // Применяем громкость из конфига и fadeMultiplier
            val baseAmplitude = 0.5 * Short.MAX_VALUE * fadeMultiplier * config.volume
            val leftAmp = baseAmplitude * leftAmplitude
            val rightAmp = baseAmplitude * rightAmplitude
            
            // Определяем, нужно ли менять каналы для этого сэмпла
            // Используем localChannelsSwapped которое обновляется в процессе генерации буфера
            val swapForSample = if (config.channelSwapEnabled) {
                // Если swap был выполнен в этом буфере, используем актуальное состояние
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
        
        return FadeResult(fadePhaseCompleted, fadeOutCompleted)
    }
    
    private fun calculateNormalizedAmplitudes(
        leftFreq: Double,
        rightFreq: Double,
        config: BinauralConfig
    ): Pair<Double, Double> {
        if (!config.volumeNormalizationEnabled) {
            return Pair(1.0, 1.0)
        }

        // Strength from 0 to 2.0 (0% - 200%)
        val strength = config.volumeNormalizationStrength.coerceIn(0f, 2f)
        val minFreq = minOf(leftFreq, rightFreq)

        val leftNormalized = minFreq / leftFreq
        val rightNormalized = minFreq / rightFreq

        // Exponential normalization formula:
        // amplitude = normalized ^ strength
        // 
        // Examples for normalized = 0.5 (freq ratio 2:1):
        // - strength = 0.0 → amplitude = 1.0 (no normalization)
        // - strength = 0.5 → amplitude = 0.71 (29% reduction)
        // - strength = 1.0 → amplitude = 0.5 (50% reduction - original behavior)
        // - strength = 1.5 → amplitude = 0.35 (65% reduction)
        // - strength = 2.0 → amplitude = 0.25 (75% reduction - 2x stronger than 100%)
        //
        // This formula naturally scales without clipping and provides smooth control
        val leftAmplitude = Math.pow(leftNormalized, strength.toDouble())
        val rightAmplitude = Math.pow(rightNormalized, strength.toDouble())

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
    
    /**
     * Остановить воспроизведение с плавным затуханием (потокобезопасно)
     * Использует ту же длительность fade, что и при смене каналов
     */
    fun stopWithFade() {
        Log.d(TAG, "stopWithFade() called")
        
        // Если воспроизведение не активно, просто выходим
        if (!_isPlaying.value) {
            Log.d(TAG, "Not playing, nothing to stop")
            return
        }
        
        // Устанавливаем флаг для запуска fade-out
        stopWithFadeRequested.set(true)
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
        stopWithFadeRequested.set(false)
        pauseWithFadeRequested.set(false)
        isFadingIn = false
        currentFadeOperation = FadeOperation.NONE
        
        Log.d(TAG, "cleanupPlayback() completed")
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
        isFadingIn = false
        stopWithFadeRequested.set(false)
        pauseWithFadeRequested.set(false)
        pendingPresetConfig = null
    }

    /**
     * Приостановить воспроизведение с плавным затуханием (потокобезопасно)
     */
    fun pauseWithFade() {
        Log.d(TAG, "pauseWithFade() called")
        
        if (!_isPlaying.value) {
            Log.d(TAG, "Not playing, nothing to pause")
            return
        }
        
        pauseWithFadeRequested.set(true)
    }

    /**
     * Возобновить воспроизведение с плавным нарастанием (потокобезопасно)
     */
    fun resumeWithFade() {
        Log.d(TAG, "resumeWithFade() called")
        
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
        Log.d(TAG, "switchPresetWithFade() called, isPlaying=${_isPlaying.value}, isActive=${isActive.get()}")
        
        // Проверяем реальное состояние воспроизведения
        if (!isActive.get()) {
            // Если не воспроизводится, просто применяем конфиг
            updateConfig(config)
            Log.d(TAG, "Not active, applying config directly")
            return
        }
        
        // Устанавливаем запрос - он будет обработан в generateAudioLoop
        presetSwitchRequested.set(config)
        Log.d(TAG, "Preset switch request queued")
    }

    /**
     * Установить громкость (потокобезопасно)
     * Громкость применяется напрямую в генерируемых сэмплах
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        val currentConfig = configRef.get()
        configRef.set(currentConfig.copy(volume = clampedVolume))
        _currentConfig.value = configRef.get()
        // Громкость применяется в generateBuffer() через config.volume
        // Не используем AudioTrack.setVolume() чтобы избежать двойного применения
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
            // Останавливаем воспроизведение и планируем перезапуск с новым sample rate
            isActive.set(false)
            _isPlaying.value = false
            
            // Немедленно останавливаем AudioTrack
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }
            
            // Устанавливаем новый sample rate
            sampleRate = rate.value
            Log.d(TAG, "Sample rate changed to ${sampleRate} Hz")
            
            // Планируем перезапуск в audioThread
            audioHandler?.post {
                // Освобождаем старый AudioTrack
                audioTrack?.release()
                audioTrack = null
                
                // Небольшая пауза для освобождения ресурсов
                Thread.sleep(50)
                
                // Перезапускаем воспроизведение
                Log.d(TAG, "Restarting playback with new sample rate: ${sampleRate} Hz")
                play()
            }
        } else {
            // Просто меняем sample rate для следующего воспроизведения
            sampleRate = rate.value
            Log.d(TAG, "Sample rate set to ${sampleRate} Hz (not playing)")
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
        pendingFrequencyUpdateIntervalMs.set(intervalMs.coerceIn(1000, 60000))
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