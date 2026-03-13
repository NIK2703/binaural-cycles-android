package com.binaural.core.audio.engine

import android.util.Log
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.NormalizationType
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalTime

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
    
    // Настройки режима расслабления
    private var relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
    
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
    
    // === Нативные методы для интерполяции (используются в UI для графика) ===
    
    private external fun nativeInterpolate(
        p0: Double, p1: Double, p2: Double, p3: Double,
        t: Double,
        interpolationType: Int,
        tension: Float
    ): Double
    
    private external fun nativeGenerateInterpolatedCurve(
        timePoints: IntArray,
        values: DoubleArray,
        numOutputPoints: Int,
        interpolationType: Int,
        tension: Float
    ): DoubleArray?
    
    private external fun nativeGetChannelFrequencies(
        timePoints: IntArray,
        carrierFreqs: DoubleArray,
        beatFreqs: DoubleArray,
        targetTimeSeconds: Int,
        interpolationType: Int,
        tension: Float
    ): DoubleArray?
    
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
     * Установить конфигурацию с настройками режима расслабления
     */
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        currentConfig = config
        relaxationModeSettings = relaxationSettings
        
        val curve = config.frequencyCurve
        
        // Генерируем виртуальные точки расслабления и объединяем с реальными
        val allPoints = if (relaxationSettings.enabled && curve.points.size >= 2) {
            val virtualPoints = generateRelaxationVirtualPoints(curve.points, relaxationSettings)
            (curve.points + virtualPoints).sortedBy { it.time.toSecondOfDay() }
        } else {
            curve.points
        }
        
        val numPoints = allPoints.size
        
        val timePoints = IntArray(numPoints) { allPoints[it].time.toSecondOfDay() }
        val carrierFreqs = DoubleArray(numPoints) { allPoints[it].carrierFrequency }
        val beatFreqs = DoubleArray(numPoints) { allPoints[it].beatFrequency }
        
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
        
        Log.d(TAG, "Config updated with ${curve.points.size} real points, " +
            "${if (relaxationSettings.enabled) "relaxation mode enabled" else "relaxation mode disabled"}")
    }
    
    /**
     * Генерирует виртуальные точки режима расслабления между реальными точками.
     * Виртуальные точки создаются посередине между каждой парой соседних точек.
     */
    private fun generateRelaxationVirtualPoints(
        points: List<FrequencyPoint>,
        settings: RelaxationModeSettings
    ): List<FrequencyPoint> {
        if (!settings.enabled || points.size < 2) return emptyList()
        
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
        val virtualPoints = mutableListOf<FrequencyPoint>()
        
        val carrierReduction = settings.carrierReductionPercent / 100.0
        val beatReduction = settings.beatReductionPercent / 100.0
        
        for (i in 0 until sortedPoints.size) {
            val currentPoint = sortedPoints[i]
            val nextPoint = sortedPoints[(i + 1) % sortedPoints.size]
            
            // Вычисляем время посередине между точками
            val currentTimeSeconds = currentPoint.time.toSecondOfDay()
            var nextTimeSeconds = nextPoint.time.toSecondOfDay()
            
            // Обработка перехода через полночь
            if (nextTimeSeconds <= currentTimeSeconds) {
                nextTimeSeconds += 24 * 3600
            }
            
            val midTimeSeconds = (currentTimeSeconds + nextTimeSeconds) / 2
            val midTime = LocalTime.fromSecondOfDay(midTimeSeconds % (24 * 3600))
            
            // Интерполируем значения на середине
            val ratio = 0.5
            val midCarrier = currentPoint.carrierFrequency + (nextPoint.carrierFrequency - currentPoint.carrierFrequency) * ratio
            val midBeat = currentPoint.beatFrequency + (nextPoint.beatFrequency - currentPoint.beatFrequency) * ratio
            
            // Применяем снижение частот для режима расслабления
            val relaxedCarrier = midCarrier * (1.0 - carrierReduction)
            val relaxedBeat = midBeat * (1.0 - beatReduction)
            
            virtualPoints.add(
                FrequencyPoint(
                    time = midTime,
                    carrierFrequency = relaxedCarrier,
                    beatFrequency = relaxedBeat
                )
            )
        }
        
        return virtualPoints
    }
    
    /**
     * Обновить настройки режима расслабления
     */
    fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        relaxationModeSettings = settings
        
        // Если есть текущая конфигурация, обновляем с новыми настройками расслабления
        currentConfig?.let { config ->
            updateConfig(config, settings)
        }
        
        Log.d(TAG, "Relaxation mode settings updated: enabled=${settings.enabled}")
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
     */
    fun generateBuffer(buffer: FloatArray, samplesPerChannel: Int, frequencyUpdateIntervalMs: Int): Boolean {
        return nativeGenerateBuffer(buffer, samplesPerChannel, frequencyUpdateIntervalMs)
    }
    
    /**
     * Сгенерировать буфер аудио (Zero-copy через DirectByteBuffer)
     * ОПТИМИЗАЦИЯ: Избегает копирования данных между Java и C++
     */
    fun generateBufferDirect(
        directBuffer: java.nio.ByteBuffer, 
        samplesPerChannel: Int, 
        frequencyUpdateIntervalMs: Int
    ): Boolean {
        return nativeGenerateBufferDirect(directBuffer, samplesPerChannel, frequencyUpdateIntervalMs)
    }
    
    fun getCurrentBeatFrequency(): Double = nativeGetCurrentBeatFrequency()
    fun getCurrentCarrierFrequency(): Double = nativeGetCurrentCarrierFrequency()
    fun getElapsedSeconds(): Int = nativeGetElapsedSeconds()
    fun isChannelsSwapped(): Boolean = nativeIsChannelsSwapped()
    
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        nativeSetFrequencyUpdateInterval(intervalMs.coerceIn(1000, 60000))
        Log.d(TAG, "Frequency update interval set to $intervalMs ms")
    }
    
    fun getFrequencyUpdateInterval(): Int = nativeGetFrequencyUpdateInterval()
    fun getRecommendedBufferSize(): Int = nativeGetRecommendedBufferSize()
    
    // === Публичные методы для интерполяции (используются в UI для графика) ===
    
    /**
     * Выполнить интерполяцию одного значения через C++
     */
    fun interpolate(
        p0: Double, p1: Double, p2: Double, p3: Double,
        t: Double,
        interpolationType: InterpolationType,
        tension: Float = 0.0f
    ): Double {
        val typeInt = when (interpolationType) {
            InterpolationType.LINEAR -> 0
            InterpolationType.CARDINAL -> 1
            InterpolationType.MONOTONE -> 2
            InterpolationType.STEP -> 3
        }
        return nativeInterpolate(p0, p1, p2, p3, t, typeInt, tension)
    }
    
    /**
     * Генерация массива интерполированных значений для графика
     * @param timePoints массив временных точек (секунды с начала суток)
     * @param values массив значений в этих точках
     * @param numOutputPoints количество выходных точек (обычно 100 для графика)
     * @param interpolationType тип интерполяции
     * @param tension параметр натяжения для CARDINAL
     * @return массив интерполированных значений или null при ошибке
     */
    fun generateInterpolatedCurve(
        timePoints: IntArray,
        values: DoubleArray,
        numOutputPoints: Int,
        interpolationType: InterpolationType,
        tension: Float = 0.0f
    ): DoubleArray? {
        val typeInt = when (interpolationType) {
            InterpolationType.LINEAR -> 0
            InterpolationType.CARDINAL -> 1
            InterpolationType.MONOTONE -> 2
            InterpolationType.STEP -> 3
        }
        return nativeGenerateInterpolatedCurve(timePoints, values, numOutputPoints, typeInt, tension)
    }
    
    /**
     * Получение частот каналов для заданного времени (для UI)
     * @return Pair(нижняя частота, верхняя частота) или null при ошибке
     */
    fun getChannelFrequenciesAt(
        timePoints: IntArray,
        carrierFreqs: DoubleArray,
        beatFreqs: DoubleArray,
        targetTimeSeconds: Int,
        interpolationType: InterpolationType,
        tension: Float = 0.0f
    ): Pair<Double, Double>? {
        val typeInt = when (interpolationType) {
            InterpolationType.LINEAR -> 0
            InterpolationType.CARDINAL -> 1
            InterpolationType.MONOTONE -> 2
            InterpolationType.STEP -> 3
        }
        val result = nativeGetChannelFrequencies(
            timePoints, carrierFreqs, beatFreqs, 
            targetTimeSeconds, typeInt, tension
        )
        return result?.let { Pair(it[0], it[1]) }
    }
    
    // === Callback'и из C++ ===
    
    override fun onFrequencyChanged(beatFreq: Double, carrierFreq: Double) {
        _currentBeatFrequency.value = beatFreq
        _currentCarrierFrequency.value = carrierFreq
    }
    
    override fun onChannelsSwapped(swapped: Boolean) {
        _isChannelsSwapped.value = swapped
        Log.d(TAG, "Channels swapped: $swapped")
    }
    
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