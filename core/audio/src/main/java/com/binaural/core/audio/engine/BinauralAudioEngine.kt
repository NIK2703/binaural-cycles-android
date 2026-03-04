package com.binaural.core.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.PowerManager
import com.binaural.core.audio.AudioConstants
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
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
 * Движок для генерации и воспроизведения бинауральных ритмов
 */
@Singleton
class BinauralAudioEngine @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

    companion object {
        private const val BUFFER_SIZE_MS = 1000 // 1000мс буфер для плавности при выключенном экране
        private const val SAMPLE_RATE = 44100
        private const val WAKE_LOCK_TAG = "BinauralBeats:PlaybackWakeLock"
        private const val GENERATION_BUFFER_MS = 250 // 250мс буфер генерации
        private const val DEFAULT_FADE_DURATION_MS = 1000L // 1 секунда затухания/нарастания по умолчанию
    }
    
    // Текущая частота дискретизации - по умолчанию 44100, может быть изменено через setSampleRate()
    private var sampleRate: Int = SampleRate.MEDIUM.value
    
    // Интервал обновления частот в миллисекундах (100-5000мс)
    private var frequencyUpdateIntervalMs: Int = 100

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var scope: CoroutineScope? = null
    
    // WakeLock для предотвращения засыпания при воспроизведении
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Флаг для атомарной проверки активности воспроизведения
    private val isActive = AtomicBoolean(false)

    // Фаза для непрерывной генерации
    private var leftPhase = 0.0
    private var rightPhase = 0.0
    
    // Переменная для отслеживания перестановки каналов
    private var channelsSwapped = false
    private var lastSwapElapsedMs = 0L
    
    // Состояние fade при смене каналов
    private var fadeStartSample = 0L // номер сэмпла начала fade
    private var isFadingOut = true // true = fade out, false = fade in
    private var isFading = false
    
    // Общий счётчик сэмплов для плавного fade
    private var totalSamplesGenerated = 0L
    
    // Блокировка для синхронизации доступа к AudioTrack
    private val audioLock = Any()

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
    
    // Публичное состояние перестановки каналов
    private val _isChannelsSwapped = MutableStateFlow(false)
    val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()

    /**
     * Инициализация движка
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Обновить конфигурацию
     */
    fun updateConfig(config: BinauralConfig) {
        _currentConfig.value = config
    }

    /**
     * Обновить кривую частот
     */
    fun updateFrequencyCurve(curve: FrequencyCurve) {
        _currentConfig.value = _currentConfig.value.copy(frequencyCurve = curve)
    }

    /**
     * Начать воспроизведение
     */
    fun play() {
        android.util.Log.d("BinauralAudioEngine", "play() called, isPlaying=${_isPlaying.value}, scope=$scope")
        
        synchronized(audioLock) {
            if (_isPlaying.value) {
                android.util.Log.d("BinauralAudioEngine", "Already playing, returning")
                return
            }
            
            val scope = this.scope
            if (scope == null) {
                android.util.Log.e("BinauralAudioEngine", "Scope is null! Cannot start playback")
                return
            }
            
            android.util.Log.d("BinauralAudioEngine", "Starting playback...")
            isActive.set(true)
            _isPlaying.value = true
            createAudioTrack()
            acquireWakeLock()
            
            playbackJob = scope.launch(Dispatchers.Default) {
                android.util.Log.d("BinauralAudioEngine", "Playback coroutine started")
                try {
                    synchronized(audioLock) {
                        audioTrack?.play()
                    }
                    generateAudio()
                } catch (e: Exception) {
                    android.util.Log.e("BinauralAudioEngine", "Playback error", e)
                } finally {
                    android.util.Log.d("BinauralAudioEngine", "Playback coroutine ended")
                }
            }
        }
    }

    /**
     * Остановить воспроизведение
     */
    fun stop() {
        synchronized(audioLock) {
            android.util.Log.d("BinauralAudioEngine", "stop() called")
            isActive.set(false)
            _isPlaying.value = false
            playbackJob?.cancel()
            playbackJob = null
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            releaseWakeLock()
            _elapsedSeconds.value = 0
            // Сбрасываем фазу
            leftPhase = 0.0
            rightPhase = 0.0
            // Сбрасываем перестановку каналов
            channelsSwapped = false
            lastSwapElapsedMs = 0L
            _isChannelsSwapped.value = false
            // Сбрасываем fade состояние
            isFading = false
            fadeStartSample = 0L
            isFadingOut = true
            totalSamplesGenerated = 0L
        }
    }

    /**
     * Приостановить воспроизведение
     */
    fun pause() {
        synchronized(audioLock) {
            if (!_isPlaying.value) return
            audioTrack?.pause()
            _isPlaying.value = false
            isActive.set(false)
            playbackJob?.cancel()
            playbackJob = null
        }
    }

    /**
     * Установить громкость
     */
    fun setVolume(volume: Float) {
        _currentConfig.value = _currentConfig.value.copy(volume = volume.coerceIn(0f, 1f))
        synchronized(audioLock) {
            audioTrack?.setVolume(_currentConfig.value.volume)
        }
    }
    
    /**
     * Установить частоту дискретизации
     * Применяется при следующем запуске воспроизведения
     */
    fun setSampleRate(rate: SampleRate) {
        val wasPlaying = _isPlaying.value
        if (wasPlaying) {
            stop()
        }
        sampleRate = rate.value
        if (wasPlaying) {
            play()
        }
    }
    
    /**
     * Получить текущую частоту дискретизации
     */
    fun getSampleRate(): SampleRate {
        return SampleRate.fromValue(sampleRate)
    }
    
    /**
     * Установить интервал обновления частот
     * @param intervalMs интервал в миллисекундах (100-5000)
     */
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        frequencyUpdateIntervalMs = intervalMs.coerceIn(100, 5000)
    }
    
    /**
     * Получить текущий интервал обновления частот
     */
    fun getFrequencyUpdateInterval(): Int = frequencyUpdateIntervalMs

    private fun createAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        // Используем больший буфер для плавности
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

        audioTrack?.setVolume(_currentConfig.value.volume)
    }

    private suspend fun generateAudio() {
        // Используем настраиваемый интервал для генерации
        // Меньше интервал = плавнее, больше интервал = энергосбережение
        val samplesPerChannel = sampleRate * frequencyUpdateIntervalMs / 1000
        val buffer = ShortArray(samplesPerChannel * 2)
        val bufferDurationMs = frequencyUpdateIntervalMs.toLong()
        
        // Счётчик для периодического обновления WakeLock (каждую минуту)
        var wakeLockRenewCounter = 0
        val wakeLockRenewInterval = 600 // каждую минуту (600 * 100мс)
        
        // Время начала для расчёта elapsed
        val startTime = System.currentTimeMillis()
        
        while (isActive.get() && _isPlaying.value) {
            try {
                coroutineContext.ensureActive()
            } catch (e: Exception) {
                android.util.Log.d("BinauralAudioEngine", "Coroutine cancelled")
                break
            }
            
            // Проверяем активность перед генерацией
            if (!isActive.get()) {
                android.util.Log.d("BinauralAudioEngine", "isActive is false, stopping generation")
                break
            }
            
            val now = Clock.System.now()
            val zone = TimeZone.currentSystemDefault()
            val localDateTime = now.toLocalDateTime(zone)
            val currentTime = localDateTime.time
            
            val config = _currentConfig.value
            val (beatFreq, carrierFreq) = config.getFrequenciesAt(currentTime)
            _currentBeatFrequency.value = beatFreq
            _currentCarrierFrequency.value = carrierFreq
            
            // Проверяем необходимость перестановки каналов
            if (config.channelSwapEnabled && !isFading) {
                val currentElapsedMs = _elapsedSeconds.value * 100L
                val swapIntervalMs = config.channelSwapIntervalSeconds * 1000L
                
                if (currentElapsedMs - lastSwapElapsedMs >= swapIntervalMs) {
                    if (config.channelSwapFadeEnabled) {
                        // Начинаем fade out перед сменой канала
                        isFading = true
                        isFadingOut = true
                        fadeStartSample = totalSamplesGenerated
                        lastSwapElapsedMs = currentElapsedMs
                        android.util.Log.d("BinauralAudioEngine", "Starting fade out before channel swap at sample $fadeStartSample")
                    } else {
                        // Мгновенная смена без fade
                        channelsSwapped = !channelsSwapped
                        _isChannelsSwapped.value = channelsSwapped
                        lastSwapElapsedMs = currentElapsedMs
                        android.util.Log.d("BinauralAudioEngine", "Channels swapped instantly: $channelsSwapped")
                    }
                }
            }
            
            // Вычисляем параметры fade для передачи в generateBuffer
            val fadeDurationSamples = (config.channelSwapFadeDurationMs.coerceAtLeast(100L) * sampleRate / 1000).toInt()
            
            val fadeResult = generateBuffer(
                buffer = buffer,
                carrierFreq = carrierFreq,
                beatFreq = beatFreq,
                samplesPerChannel = samplesPerChannel,
                fadeDurationSamples = fadeDurationSamples,
                config = config
            )
            
            // Обновляем счётчик сэмплов после генерации буфера
            totalSamplesGenerated += samplesPerChannel
            
            // Обновляем состояние после генерации буфера
            if (fadeResult.fadePhaseCompleted && isFading) {
                // Fade (out + in) полностью завершён
                isFading = false
                isFadingOut = true // Готов к следующему циклу
                android.util.Log.d("BinauralAudioEngine", "Fade completed (out + in)")
            }
            
            // Блокирующая запись в AudioTrack - write() блокирует пока данные не будут
            // записаны в буфер, поэтому НЕ нужен delay() - это и вызывало спайки!
            val result = synchronized(audioLock) {
                if (!isActive.get() || audioTrack == null) {
                    android.util.Log.d("BinauralAudioEngine", "AudioTrack no longer available")
                    -1
                } else {
                    try {
                        // Используем блокирующий write без таймаута
                        // AudioTrack сам будет ждать освобождения буфера
                        audioTrack?.write(buffer, 0, buffer.size) ?: -1
                    } catch (e: IllegalStateException) {
                        android.util.Log.e("BinauralAudioEngine", "AudioTrack write error: ${e.message}")
                        -1
                    }
                }
            }
            
            if (result < 0) {
                android.util.Log.d("BinauralAudioEngine", "Write failed or stopped, result=$result")
                break
            }
            
            // Обновляем elapsed time на основе реального времени
            _elapsedSeconds.value = ((System.currentTimeMillis() - startTime) / 100).toInt()
            
            // Периодически обновляем WakeLock
            wakeLockRenewCounter++
            if (wakeLockRenewCounter >= wakeLockRenewInterval) {
                renewWakeLock()
                wakeLockRenewCounter = 0
            }
            
            // НЕ используем delay() - write() уже блокирует на нужное время!
            // Небольшая пауза только для проверки cancellation
            delay(1) // минимальная задержка для coroutine cancellation check
        }
        
        android.util.Log.d("BinauralAudioEngine", "generateAudio() loop ended")
    }

    /**
     * Результат генерации буфера
     */
    private data class FadeResult(
        val fadePhaseCompleted: Boolean // true если fade out/in фаза завершена
    )
    
    private fun generateBuffer(
        buffer: ShortArray,
        carrierFreq: Double,
        beatFreq: Double,
        samplesPerChannel: Int,
        fadeDurationSamples: Int,
        config: BinauralConfig
    ): FadeResult {
        // Симметричное распределение частот вокруг несущей: carrier ± beat/2
        val leftFreq = carrierFreq - beatFreq / 2.0
        val rightFreq = carrierFreq + beatFreq / 2.0
        
        // Вычисляем амплитуды для нормализации
        val (leftAmplitude, rightAmplitude) = calculateNormalizedAmplitudes(
            leftFreq, rightFreq, config
        )
        
        // Угловые частоты
        val leftOmega = 2.0 * PI * leftFreq / sampleRate
        val rightOmega = 2.0 * PI * rightFreq / sampleRate
        
        // Определяем, нужно ли поменять каналы местами в начале буфера
        val swapChannelsAtStart = config.channelSwapEnabled && channelsSwapped
        
        // Флаг для отслеживания завершения всего процесса fade (out + in)
        var fadePhaseCompleted = false
        
        // Точка (в сэмплах) где происходит переключение каналов
        var swapAtSample = -1L
        
        // Локальное отслеживание состояния fade для мгновенного перехода
        var localIsFadingOut = isFadingOut
        var localFadeStartSample = fadeStartSample
        var localChannelsSwapped = channelsSwapped
        
        for (i in 0 until samplesPerChannel) {
            // Номер текущего сэмпла в общем потоке
            val currentSample = totalSamplesGenerated + i
            
            // Вычисляем fade multiplier для каждого сэмпла
            var fadeMultiplier = 1.0
            
            if (isFading) {
                val elapsedSamples = currentSample - localFadeStartSample
                val progress = elapsedSamples.toDouble() / fadeDurationSamples
                
                if (localIsFadingOut) {
                    // Фаза fade out
                    if (progress >= 1.0) {
                        // Fade out завершён - мгновенно переключаем каналы и начинаем fade in
                        fadeMultiplier = 0.0
                        if (swapAtSample < 0) {
                            swapAtSample = currentSample
                            localChannelsSwapped = !localChannelsSwapped
                            _isChannelsSwapped.value = localChannelsSwapped
                            // Мгновенный переход к fade in
                            localIsFadingOut = false
                            localFadeStartSample = currentSample
                        }
                    } else if (progress >= 0.0) {
                        // Fade out в процессе: 1.0 -> 0.0
                        fadeMultiplier = 1.0 - progress
                    }
                } else {
                    // Фаза fade in
                    if (progress >= 1.0) {
                        // Fade in завершён
                        fadeMultiplier = 1.0
                        fadePhaseCompleted = true
                    } else if (progress >= 0.0) {
                        // Fade in в процессе: 0.0 -> 1.0
                        fadeMultiplier = progress
                    }
                }
            }
            
            // Левый канал - непрерывная фаза
            val leftSample = sin(leftPhase)
            leftPhase += leftOmega
            if (leftPhase > 2.0 * PI) leftPhase -= 2.0 * PI
            
            // Правый канал - непрерывная фаза
            val rightSample = sin(rightPhase)
            rightPhase += rightOmega
            if (rightPhase > 2.0 * PI) rightPhase -= 2.0 * PI
            
            // Базовая амплитуда с учетом fade
            val baseAmplitude = 0.5 * Short.MAX_VALUE * fadeMultiplier
            
            // Применяем нормализацию громкости
            val leftAmp = baseAmplitude * leftAmplitude
            val rightAmp = baseAmplitude * rightAmplitude
            
            // Определяем, нужно ли поменять каналы для этого сэмпла
            // После точки переключения (swapAtSample) каналы меняются местами
            val swapForSample = if (swapAtSample >= 0 && currentSample >= swapAtSample) {
                config.channelSwapEnabled && localChannelsSwapped
            } else {
                swapChannelsAtStart
            }
            
            // Генерируем сэмплы с учетом перестановки каналов
            if (swapForSample) {
                // При перестановке левый и правый каналы меняются местами
                buffer[i * 2] = (rightSample * rightAmp).toInt().toShort()
                buffer[i * 2 + 1] = (leftSample * leftAmp).toInt().toShort()
            } else {
                buffer[i * 2] = (leftSample * leftAmp).toInt().toShort()
                buffer[i * 2 + 1] = (rightSample * rightAmp).toInt().toShort()
            }
        }
        
        // Сохраняем обновлённое состояние для следующего буфера
        channelsSwapped = localChannelsSwapped
        
        return FadeResult(fadePhaseCompleted = fadePhaseCompleted)
    }
    
    /**
     * Вычисляет нормализованные амплитуды для каналов
     * Принцип: канал с меньшей частотой остаётся на 100%,
     * канал с большей частотой уменьшается пропорционально
     * Отношение: минимальная частота / частота канала
     * Сила нормализации определяет, насколько сильно применяется эффект
     */
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
        
        // Нормализация: канал с меньшей частотой = 100% (1.0)
        // Канал с большей частотой = мин/макс (меньше 1.0)
        // Пример: левый 122.8 Гц, правый 1122.8 Гц
        // левый = 122.8/122.8 = 1.0 = 100%
        // правый = 122.8/1122.8 = 0.109 = 10.9%
        
        val leftNormalized = minFreq / leftFreq  // будет 1.0 если левый минимальный
        val rightNormalized = minFreq / rightFreq  // будет 1.0 если правый минимальный
        
        // Интерполируем между единичной амплитудой и нормализованной
        // При strength = 0: амплитуда = 1
        // При strength = 1: амплитуда = normalized
        val leftAmplitude = 1.0 + strength * (leftNormalized - 1.0)
        val rightAmplitude = 1.0 + strength * (rightNormalized - 1.0)
        
        return Pair(leftAmplitude, rightAmplitude)
    }

    /**
     * Получить WakeLock для предотвращения засыпания CPU
     * Используем 10-минутный таймаут с периодическим обновлением
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                )
            }
            // Acquire with 10 min timeout - will be re-acquired in playback loop
            wakeLock?.acquire(10 * 60 * 1000L)
            android.util.Log.d("BinauralAudioEngine", "WakeLock acquired (10 min timeout)")
        } catch (e: Exception) {
            android.util.Log.e("BinauralAudioEngine", "Failed to acquire WakeLock", e)
        }
    }
    
    /**
     * Обновить WakeLock во время воспроизведения
     */
    private fun renewWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    // Освобождаем и захватываем заново
                    it.release()
                    it.acquire(10 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BinauralAudioEngine", "Failed to renew WakeLock", e)
        }
    }
    
    /**
     * Освободить WakeLock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    android.util.Log.d("BinauralAudioEngine", "WakeLock released")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BinauralAudioEngine", "Failed to release WakeLock", e)
        }
    }

    /**
     * Освободить ресурсы
     */
    fun release() {
        stop()
        scope = null
    }
}
