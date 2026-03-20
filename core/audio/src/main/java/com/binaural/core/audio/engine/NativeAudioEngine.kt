package com.binaural.core.audio.engine

import android.util.Log
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.NormalizationType
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime

/**
 * JNI обёртка для C++ аудиодвижка.
 * 
 * PULL MODEL ARCHITECTURE:
 * - C++ обновляет атомарные переменные после каждой генерации буфера
 * - Kotlin polling читает значения через JNI getters без callbacks
 * - Это устраняет overhead JNI callbacks и context switching
 * 
 * ОПТИМИЗАЦИЯ ЭНЕРГОПОТРЕБЛЕНИЯ:
 * - Убраны JNI callbacks из C++ в Java (push model)
 * - Kotlin polling читает данные только когда нужно (pull model)
 * - Нет лишних JNI calls и context switching
 */
class NativeAudioEngine {
    
    companion object {
        private const val TAG = "NativeAudioEngine"
        
        init {
            try {
                System.loadLibrary("binaural-engine")
                Log.d(TAG, "Native library loaded successfully (pull-model)")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }
    }
    
    // Текущая конфигурация
    private var currentConfig: BinauralConfig? = null
    private var isInitialized = false
    
    // Настройки режима расслабления
    private var relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
    
    // Нативные методы (PULL MODEL - без callback параметра)
    private external fun nativeInitialize()
    private external fun nativeRelease()
    private external fun nativeSetConfig(
        timePoints: IntArray,
        carrierFreqs: FloatArray,
        beatFreqs: FloatArray,
        interpolationType: Int,
        splineTension: Float,
        volume: Float,
        channelSwapEnabled: Boolean,
        channelSwapIntervalSec: Int,
        channelSwapFadeEnabled: Boolean,
        channelSwapFadeDurationMs: Long,
        channelSwapPauseDurationMs: Long,
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
    
    // PULL MODEL: Геттеры читают из атомарных переменных в C++
    private external fun nativeGetCurrentBeatFrequency(): Float
    private external fun nativeGetCurrentCarrierFrequency(): Float
    private external fun nativeGetElapsedSeconds(): Int
    private external fun nativeIsChannelsSwapped(): Boolean
    private external fun nativeUpdateElapsedTime()
    
    // === Нативные методы для интерполяции (используются в UI для графика) ===
    
    private external fun nativeInterpolate(
        p0: Float, p1: Float, p2: Float, p3: Float,
        t: Float,
        interpolationType: Int,
        tension: Float
    ): Float
    
    private external fun nativeGenerateInterpolatedCurve(
        timePoints: IntArray,
        values: FloatArray,
        numOutputPoints: Int,
        interpolationType: Int,
        tension: Float
    ): FloatArray?
    
    private external fun nativeGetChannelFrequencies(
        timePoints: IntArray,
        carrierFreqs: FloatArray,
        beatFreqs: FloatArray,
        targetTimeSeconds: Int,
        interpolationType: Int,
        tension: Float
    ): FloatArray?
    
    /**
     * Инициализация движка
     */
    fun initialize() {
        if (!isInitialized) {
            nativeInitialize()
            isInitialized = true
            Log.d(TAG, "Native engine initialized (pull-model)")
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
        
        // Генерируем точки для воспроизведения в зависимости от режима расслабления
        val playbackPoints = if (relaxationSettings.enabled && curve.points.size >= 2) {
            when (relaxationSettings.mode) {
                RelaxationMode.STEP -> {
                    // В STEP режиме используем ТОЛЬКО виртуальные точки
                    generateStepVirtualPoints(curve.points, relaxationSettings)
                }
                RelaxationMode.SMOOTH -> {
                    // В SMOOTH режиме используем ТОЛЬКО виртуальные точки (чередование базовых и сниженных)
                    generateSmoothVirtualPoints(curve.points, relaxationSettings)
                }
            }
        } else {
            curve.points
        }
        
        val numPoints = playbackPoints.size
        
        val timePoints = IntArray(numPoints) { playbackPoints[it].time.toSecondOfDay() }
        val carrierFreqs = FloatArray(numPoints) { playbackPoints[it].carrierFrequency }
        val beatFreqs = FloatArray(numPoints) { playbackPoints[it].beatFrequency }
        
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
            volume = 1.0f,  // Фиксированная громкость в нативном движке; мастер-громкость управляется через AudioTrack
            channelSwapEnabled = config.channelSwapEnabled,
            channelSwapIntervalSec = config.channelSwapIntervalSeconds,
            channelSwapFadeEnabled = config.channelSwapFadeEnabled,
            channelSwapFadeDurationMs = config.channelSwapFadeDurationMs,
            channelSwapPauseDurationMs = config.channelSwapPauseDurationMs,
            normalizationType = normalizationType,
            volumeNormalizationStrength = config.volumeNormalizationStrength
        )
        
        Log.d(TAG, "Config updated with ${curve.points.size} real points, " +
            "${if (relaxationSettings.enabled) "relaxation mode enabled" else "relaxation mode disabled"}")
    }
    
    /**
     * Генерирует виртуальные точки для SMOOTH режима расслабления.
     * Чередует точки на графике и снижающие точки с заданным интервалом.
     * Интерполяция производится ТОЛЬКО по виртуальным точкам.
     */
    private fun generateSmoothVirtualPoints(
        points: List<FrequencyPoint>,
        settings: RelaxationModeSettings
    ): List<FrequencyPoint> {
        if (!settings.enabled || points.size < 2) return emptyList()
        
        val virtualPoints = mutableListOf<FrequencyPoint>()
        
        val carrierReduction = settings.carrierReductionPercent / 100.0f
        val beatReduction = settings.beatReductionPercent / 100.0f
        val intervalSeconds = settings.smoothIntervalMinutes * 60L
        
        val daySeconds = 24 * 3600L
        
        // Генерируем точки от 00:00 с заданным интервалом
        // Чередуем: первая на графике, вторая снижающая, третья на графике, четвёртая снижающая и т.д.
        var currentTimeSeconds = 0L
        var isRelaxationPoint = false
        
        while (currentTimeSeconds < daySeconds) {
            val time = LocalTime.fromSecondOfDay((currentTimeSeconds % daySeconds).toInt())
            val baseCarrier = interpolateCarrierAtTime(points, time)
            val baseBeat = interpolateBeatAtTime(points, time)
            
            if (isRelaxationPoint) {
                // Снижающая точка
                virtualPoints.add(FrequencyPoint(
                    time,
                    baseCarrier * (1.0f - carrierReduction),
                    baseBeat * (1.0f - beatReduction)
                ))
            } else {
                // Точка на графике (базовая)
                virtualPoints.add(FrequencyPoint(time, baseCarrier, baseBeat))
            }
            
            isRelaxationPoint = !isRelaxationPoint
            currentTimeSeconds += intervalSeconds
        }
        
        // Сортируем по времени
        return virtualPoints.sortedBy { it.time.toSecondOfDay() }
    }
    
    /**
     * Генерирует виртуальные точки для STEP режима расслабления.
     * Создаёт группы из 4 точек для каждого периода расслабления, образующие трапецию.
     * Итоговая кривая проходит ТОЛЬКО через эти виртуальные точки.
     */
    private fun generateStepVirtualPoints(
        points: List<FrequencyPoint>,
        settings: RelaxationModeSettings
    ): List<FrequencyPoint> {
        if (!settings.enabled || points.size < 2) return emptyList()
        
        val virtualPoints = mutableListOf<FrequencyPoint>()
        
        val carrierReduction = settings.carrierReductionPercent / 100.0f
        val beatReduction = settings.beatReductionPercent / 100.0f
        
        val gapSeconds = settings.gapBetweenRelaxationMinutes * 60L
        val transitionSeconds = settings.transitionPeriodMinutes * 60L
        val durationSeconds = settings.relaxationDurationMinutes * 60L
        
        // Полный период расслабления = 2 * переход + длительность
        val fullPeriodSeconds = 2 * transitionSeconds + durationSeconds
        
        // Генерируем периоды расслабления от 00:00
        val daySeconds = 24 * 3600L
        
        var periodStartSeconds = 0L
        
        while (periodStartSeconds < daySeconds) {
            // Точка 1: начало периода (на базовой кривой)
            val t1 = periodStartSeconds
            val time1 = LocalTime.fromSecondOfDay((t1 % daySeconds).toInt())
            val carrier1 = interpolateCarrierAtTime(points, time1)
            val beat1 = interpolateBeatAtTime(points, time1)
            virtualPoints.add(FrequencyPoint(time1, carrier1, beat1))
            
            // Точка 2: после перехода (сниженные частоты)
            val t2 = periodStartSeconds + transitionSeconds
            if (t2 < daySeconds) {
                val time2 = LocalTime.fromSecondOfDay((t2 % daySeconds).toInt())
                val baseCarrier2 = interpolateCarrierAtTime(points, time2)
                val baseBeat2 = interpolateBeatAtTime(points, time2)
                virtualPoints.add(FrequencyPoint(
                    time2,
                    baseCarrier2 * (1.0f - carrierReduction),
                    baseBeat2 * (1.0f - beatReduction)
                ))
            }
            
            // Точка 3: конец расслабления (сниженные частоты)
            val t3 = periodStartSeconds + transitionSeconds + durationSeconds
            if (t3 < daySeconds) {
                val time3 = LocalTime.fromSecondOfDay((t3 % daySeconds).toInt())
                val baseCarrier3 = interpolateCarrierAtTime(points, time3)
                val baseBeat3 = interpolateBeatAtTime(points, time3)
                virtualPoints.add(FrequencyPoint(
                    time3,
                    baseCarrier3 * (1.0f - carrierReduction),
                    baseBeat3 * (1.0f - beatReduction)
                ))
            }
            
            // Точка 4: после выхода (на базовой кривой)
            val t4 = periodStartSeconds + fullPeriodSeconds
            if (t4 < daySeconds) {
                val time4 = LocalTime.fromSecondOfDay((t4 % daySeconds).toInt())
                val carrier4 = interpolateCarrierAtTime(points, time4)
                val beat4 = interpolateBeatAtTime(points, time4)
                virtualPoints.add(FrequencyPoint(time4, carrier4, beat4))
            }
            
            // Переходим к следующему периоду: полный период + пауза между периодами
            periodStartSeconds += fullPeriodSeconds + gapSeconds
        }
        
        // Сортируем по времени
        return virtualPoints.sortedBy { it.time.toSecondOfDay() }
    }
    
    /**
     * Интерполирует несущую частоту для заданного времени.
     */
    private fun interpolateCarrierAtTime(points: List<FrequencyPoint>, time: LocalTime): Float {
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
        val targetSeconds = time.toSecondOfDay()
        
        // Находим интервал
        for (i in 0 until sortedPoints.size) {
            val current = sortedPoints[i]
            val next = sortedPoints[(i + 1) % sortedPoints.size]
            
            val currentSeconds = current.time.toSecondOfDay()
            var nextSeconds = next.time.toSecondOfDay()
            
            // Обработка перехода через полночь
            if (nextSeconds <= currentSeconds) {
                nextSeconds += 24 * 3600
            }
            
            val adjustedTarget = if (targetSeconds < currentSeconds && i == sortedPoints.size - 1) {
                targetSeconds + 24 * 3600
            } else {
                targetSeconds
            }
            
            if (adjustedTarget in currentSeconds..nextSeconds) {
                val ratio = if (nextSeconds == currentSeconds) 0f else {
                    (adjustedTarget - currentSeconds).toFloat() / (nextSeconds - currentSeconds)
                }
                return current.carrierFrequency + (next.carrierFrequency - current.carrierFrequency) * ratio
            }
        }
        
        return sortedPoints.first().carrierFrequency
    }
    
    /**
     * Интерполирует частоту биения для заданного времени.
     */
    private fun interpolateBeatAtTime(points: List<FrequencyPoint>, time: LocalTime): Float {
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
        val targetSeconds = time.toSecondOfDay()
        
        // Находим интервал
        for (i in 0 until sortedPoints.size) {
            val current = sortedPoints[i]
            val next = sortedPoints[(i + 1) % sortedPoints.size]
            
            val currentSeconds = current.time.toSecondOfDay()
            var nextSeconds = next.time.toSecondOfDay()
            
            // Обработка перехода через полночь
            if (nextSeconds <= currentSeconds) {
                nextSeconds += 24 * 3600
            }
            
            val adjustedTarget = if (targetSeconds < currentSeconds && i == sortedPoints.size - 1) {
                targetSeconds + 24 * 3600
            } else {
                targetSeconds
            }
            
            if (adjustedTarget in currentSeconds..nextSeconds) {
                val ratio = if (nextSeconds == currentSeconds) 0f else {
                    (adjustedTarget - currentSeconds).toFloat() / (nextSeconds - currentSeconds)
                }
                return current.beatFrequency + (next.beatFrequency - current.beatFrequency) * ratio
            }
        }
        
        return sortedPoints.first().beatFrequency
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
    
    // === PULL MODEL: Геттеры читают из атомарных переменных в C++ ===
    // Эти методы вызываются из Kotlin после каждой генерации буфера
    // вместо callbacks из C++
    
    fun getCurrentBeatFrequency(): Float = nativeGetCurrentBeatFrequency()
    fun getCurrentCarrierFrequency(): Float = nativeGetCurrentCarrierFrequency()
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
        p0: Float, p1: Float, p2: Float, p3: Float,
        t: Float,
        interpolationType: InterpolationType,
        tension: Float = 0.0f
    ): Float {
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
        values: FloatArray,
        numOutputPoints: Int,
        interpolationType: InterpolationType,
        tension: Float = 0.0f
    ): FloatArray? {
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
        carrierFreqs: FloatArray,
        beatFreqs: FloatArray,
        targetTimeSeconds: Int,
        interpolationType: InterpolationType,
        tension: Float = 0.0f
    ): Pair<Float, Float>? {
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
}