package com.binaural.core.audio.engine

import android.util.Log
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.NormalizationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JNI обёртка для C++ аудиодвижка.
 * Предоставляет тот же интерфейс, что и BinauralAudioEngine,
 * но использует нативный код для генерации аудио.
 */
class NativeAudioEngine : NativeAudioEngineCallback {
    
    companion object {
        private const val TAG = "NativeAudioEngine"
        
        init {
            try {
                System.loadLibrary("binaural-engine")
                Log.d(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }
    
    // StateFlows для UI (аналогично BinauralAudioEngine)
    private val _currentBeatFrequency = MutableStateFlow(0.0)
    val currentBeatFrequency: StateFlow<Double> = _currentBeatFrequency.asStateFlow()
    
    private val _currentCarrierFrequency = MutableStateFlow(0.0)
    val currentCarrierFrequency: StateFlow<Double> = _currentCarrierFrequency.asStateFlow()
    
    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
    
    private val _isChannelsSwapped = MutableStateFlow(false)
    val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()
    
    // Текущая конфигурация
    private var currentConfig: BinauralConfig? = null
    private var isInitialized = false
    
    // Нативные методы
    private external fun nativeInitialize(callback: NativeAudioEngineCallback)
    private external fun nativeRelease()
    private external fun nativeSetConfig(
        timePoints: IntArray,
        carrierFreqs: DoubleArray,
        beatFreqs: DoubleArray,
        interpolationType: Int,
        splineTension: Float,
        volume: Float,
        channelSwapEnabled: Boolean,
        channelSwapIntervalSec: Int,
        channelSwapFadeEnabled: Boolean,
        channelSwapFadeDurationMs: Long,
        normalizationType: Int,
        volumeNormalizationStrength: Float
    )
    private external fun nativeSetSampleRate(sampleRate: Int)
    private external fun nativeSetFrequencyUpdateInterval(intervalMs: Int)
    private external fun nativeGetFrequencyUpdateInterval(): Int
    private external fun nativeGetRecommendedBufferSize(): Int
    private external fun nativeResetState()
    private external fun nativeSetPlaying(playing: Boolean)
    private external fun nativeSetPlaybackStartTime(startTimeMs: Long)
    
    // FloatArray версия (с копированием) - для обратной совместимости
    private external fun nativeGenerateBuffer(buffer: FloatArray, samplesPerChannel: Int, frequencyUpdateIntervalMs: Int): Boolean
    
    // Zero-copy версия через DirectByteBuffer - ОПТИМИЗИРОВАНО
    private external fun nativeGenerateBufferDirect(buffer: java.nio.ByteBuffer, samplesPerChannel: Int, frequencyUpdateIntervalMs: Int): Boolean
    
    private external fun nativeGetCurrentBeatFrequency(): Double
    private external fun nativeGetCurrentCarrierFrequency(): Double
    private external fun nativeGetElapsedSeconds(): Int
    private external fun nativeIsChannelsSwapped(): Boolean
    private external fun nativeUpdateElapsedTime()
    
    /**
     * Инициализация движка
     */
    fun initialize() {
        if (!isInitialized) {
            nativeInitialize(this)
            isInitialized = true
            Log.d(TAG, "Native engine initialized")
        }
    }
    
    /**
     * Освобождение ресурсов
     */
    fun release() {
        if (isInitialized) {
            nativeRelease()
            isInitialized = false
            Log.d(TAG, "Native engine released")
        }
    }
    
    /**
     * Установить конфигурацию
     */
    fun updateConfig(config: BinauralConfig) {
        currentConfig = config
        
        val curve = config.frequencyCurve
        val numPoints = curve.points.size
        
        val timePoints = IntArray(numPoints) { curve.points[it].time.toSecondOfDay() }
        val carrierFreqs = DoubleArray(numPoints) { curve.points[it].carrierFrequency }
        val beatFreqs = DoubleArray(numPoints) { curve.points[it].beatFrequency }
        
        val interpolationType = when (curve.interpolationType) {
            InterpolationType.LINEAR -> 0
            InterpolationType.CARDINAL -> 1
            InterpolationType.MONOTONE -> 2
            InterpolationType.STEP -> 3
        }
        
        val normalizationType = when (config.normalizationType) {
            NormalizationType.NONE -> 0
            NormalizationType.CHANNEL -> 1
            NormalizationType.TEMPORAL -> 2
        }
        
        nativeSetConfig(
            timePoints = timePoints,
            carrierFreqs = carrierFreqs,
            beatFreqs = beatFreqs,
            interpolationType = interpolationType,
            splineTension = curve.splineTension,
            volume = config.volume,
            channelSwapEnabled = config.channelSwapEnabled,
            channelSwapIntervalSec = config.channelSwapIntervalSeconds,
            channelSwapFadeEnabled = config.channelSwapFadeEnabled,
            channelSwapFadeDurationMs = config.channelSwapFadeDurationMs,
            normalizationType = normalizationType,
            volumeNormalizationStrength = config.volumeNormalizationStrength
        )
    }
    
    /**
     * Установить частоту дискретизации
     */
    fun setSampleRate(sampleRate: Int) {
        nativeSetSampleRate(sampleRate)
    }
    
    /**
     * Сбросить состояние
     */
    fun resetState() {
        nativeResetState()
        _elapsedSeconds.value = 0
        _currentBeatFrequency.value = 0.0
        _currentCarrierFrequency.value = 0.0
        _isChannelsSwapped.value = false
    }
    
    /**
     * Начать воспроизведение
     */
    fun play() {
        nativeSetPlaybackStartTime(System.currentTimeMillis())
        nativeSetPlaying(true)
    }
    
    /**
     * Остановить воспроизведение
     */
    fun stop() {
        nativeSetPlaying(false)
    }
    
    /**
     * Сгенерировать буфер аудио (FloatArray версия - с копированием)
     * @param buffer буфер для заполнения (interleaved stereo, размер = samplesPerChannel * 2)
     * @param samplesPerChannel количество сэмплов на канал
     * @param frequencyUpdateIntervalMs интервал обновления частот в мс (для интерполяции)
     * @return true если генерация успешна
     */
    fun generateBuffer(buffer: FloatArray, samplesPerChannel: Int, frequencyUpdateIntervalMs: Int): Boolean {
        return nativeGenerateBuffer(buffer, samplesPerChannel, frequencyUpdateIntervalMs)
    }
    
    /**
     * Сгенерировать буфер аудио (Zero-copy через DirectByteBuffer)
     * ОПТИМИЗАЦИЯ: Избегает копирования данных между Java и C++
     * 
     * @param directBuffer прямой буфер из ByteBuffer.allocateDirect()
     *                     должен быть размером samplesPerChannel * 2 * 4 байт (float)
     * @param samplesPerChannel количество сэмплов на канал
     * @param frequencyUpdateIntervalMs интервал обновления частот в мс (для интерполяции)
     * @return true если генерация успешна
     */
    fun generateBufferDirect(
        directBuffer: java.nio.ByteBuffer, 
        samplesPerChannel: Int, 
        frequencyUpdateIntervalMs: Int
    ): Boolean {
        return nativeGenerateBufferDirect(directBuffer, samplesPerChannel, frequencyUpdateIntervalMs)
    }
    
    /**
     * Получить текущую частоту биений
     */
    fun getCurrentBeatFrequency(): Double = nativeGetCurrentBeatFrequency()
    
    /**
     * Получить текущую несущую частоту
     */
    fun getCurrentCarrierFrequency(): Double = nativeGetCurrentCarrierFrequency()
    
    /**
     * Получить прошедшее время в секундах
     */
    fun getElapsedSeconds(): Int = nativeGetElapsedSeconds()
    
    /**
     * Проверить, переставлены ли каналы
     */
    fun isChannelsSwapped(): Boolean = nativeIsChannelsSwapped()
    
    /**
     * Установить интервал обновления частот
     * @param intervalMs интервал в миллисекундах (1000-60000)
     * 
     * Этот параметр определяет размер порции генерации буфера:
     * - Большой интервал (10-60 сек): меньше прерываний, лучше энергоэффективность
     * - Малый интервал (1-5 сек): более частое обновление UI, но выше нагрузка
     */
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        nativeSetFrequencyUpdateInterval(intervalMs.coerceIn(1000, 60000))
        Log.d(TAG, "Frequency update interval set to $intervalMs ms")
    }
    
    /**
     * Получить интервал обновления частот
     */
    fun getFrequencyUpdateInterval(): Int = nativeGetFrequencyUpdateInterval()
    
    /**
     * Получить рекомендуемый размер буфера в сэмплах на канал
     * на основе интервала обновления частот и частоты дискретизации
     */
    fun getRecommendedBufferSize(): Int = nativeGetRecommendedBufferSize()
    
    // === Callback'и из C++ ===
    
    /**
     * Вызывается из C++ при изменении частот
     */
    override fun onFrequencyChanged(beatFreq: Double, carrierFreq: Double) {
        _currentBeatFrequency.value = beatFreq
        _currentCarrierFrequency.value = carrierFreq
    }
    
    /**
     * Вызывается из C++ при перестановке каналов
     */
    override fun onChannelsSwapped(swapped: Boolean) {
        _isChannelsSwapped.value = swapped
        Log.d(TAG, "Channels swapped: $swapped")
    }
    
    /**
     * Вызывается из C++ при изменении прошедшего времени
     */
    override fun onElapsedChanged(elapsedSeconds: Int) {
        _elapsedSeconds.value = elapsedSeconds
    }
}

/**
 * Интерфейс для callback'ов из C++
 */
interface NativeAudioEngineCallback {
    fun onFrequencyChanged(beatFreq: Double, carrierFreq: Double)
    fun onChannelsSwapped(swapped: Boolean)
    fun onElapsedChanged(elapsedSeconds: Int)
}