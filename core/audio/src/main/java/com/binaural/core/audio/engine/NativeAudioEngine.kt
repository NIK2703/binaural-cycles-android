package com.binaural.core.audio.engine

import android.util.Log
import com.binaural.core.audio.BuildConfig
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.ChannelSwapMode
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.Interpolation
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.NormalizationType
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime
import java.util.concurrent.atomic.AtomicLong

/**
 * JNI обёртка для C++ аудиодвижка.
 *
 * PER-INSTANCE ARCHITECTURE (фикс краша "destroyed mutex" / SIGABRT):
 * - Каждый Kotlin-объект владеет СОБСТВЕННЫМ C++-движком через непрозрачный
 *   jlong-хэндл (nativeHandle).
 * - release() делает атомарный getAndSet(0): движок удаляется ровно один раз,
 *   даже если релиз одновременно зовут писатель (выход из лупа) и менеджер.
 * - Все нативные вызовы читают snapshot хэндла и тихо возвращают дефолт, если
 *   движок уже освобождён — поэтому повторный тап по пресету больше не делит
 *   один разрушаемый движок между старым (ещё пишущим) и новым стримом.
 */
class NativeAudioEngine {

    companion object {
        private const val TAG = "NativeAudioEngine"

        @Volatile
        private var libraryLoadFailed = false

        @Volatile
        private var failureLogged = false

        init {
            try {
                System.loadLibrary("binaural-engine")
                Log.d(TAG, "Native library loaded successfully (per-instance)")
            } catch (e: UnsatisfiedLinkError) {
                libraryLoadFailed = true
                Log.e(TAG, "Failed to load native library", e)
            }
        }

        // C9: graceful-режим без нативной библиотеки
        private fun nativeUnavailable(): Boolean {
            if (!libraryLoadFailed) return false
            if (!failureLogged) {
                failureLogged = true
                Log.w(TAG, "Native library unavailable, native calls are no-op")
            }
            return true
        }
    }

    // Текущая конфигурация
    private var currentConfig: BinauralConfig? = null

    @Volatile
    private var isInitialized = false

    // Непрозрачный хэндл per-instance C++-движка. getAndSet(0) в release()
    // гарантирует ровно одну нативную деструкцию.
    private val nativeHandle = AtomicLong(0L)

    // C11: сериализация play/stop/resetState
    private val playbackLock = Any()

    // Настройки режима расслабления
    private var relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()

    // Нативные методы: ВСЕ принимают хэндл движка первым аргументом.
    // nativeInitialize возвращает хэндл свежего per-instance движка.
    private external fun nativeInitialize(): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeSetConfig(
        handle: Long,
        timePoints: IntArray,
        carrierFreqs: FloatArray,
        beatFreqs: FloatArray,
        interpolationType: Int,
        splineTension: Float,
        volume: Float,
        channelSwapEnabled: Boolean,
        channelSwapIntervalSec: Int,
        channelSwapMode: Int,
        channelSwapFadeEnabled: Boolean,
        channelSwapFadeDurationMs: Long,
        channelSwapPauseDurationMs: Long,
        channelSwapTrendPoints: Int,
        normalizationType: Int,
        volumeNormalizationStrength: Float
    )
    private external fun nativeSetSampleRate(handle: Long, sampleRate: Int)
    private external fun nativeResetState(handle: Long)
    private external fun nativeSetPlaying(handle: Long, playing: Boolean, preserveTimeline: Boolean)
    private external fun nativeSetPlaybackStartTime(handle: Long, startTimeMs: Long)
    private external fun nativeSetCurveTime(handle: Long, timeSeconds: Int)

    // FloatArray версия (с копированием) - для обратной совместимости
    private external fun nativeGenerateBuffer(handle: Long, buffer: FloatArray, samplesPerChannel: Int): Boolean

    // Zero-copy версия через DirectByteBuffer - ОПТИМИЗИРОВАНО
    private external fun nativeGenerateBufferDirect(handle: Long, buffer: java.nio.ByteBuffer, samplesPerChannel: Int): Int

    // Геттеры читают атомарные поля СВОЕГО движка (устраняет рассинхрон
    // частот между параллельными стримами при кроссфейде).
    private external fun nativeGetCurrentBeatFrequency(handle: Long): Float
    private external fun nativeGetCurrentCarrierFrequency(handle: Long): Float
    private external fun nativeGetElapsedSeconds(handle: Long): Int
    private external fun nativeIsChannelsSwapped(handle: Long): Boolean
    private external fun nativeUpdateElapsedTime(handle: Long)
    private external fun nativeGetFrequenciesAtCurrentTime(handle: Long): FloatArray?

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

    /** Снимок хэндла для нативного вызова. 0 => движок освобождён. */
    private inline fun h(): Long = nativeHandle.get()

    /**
     * Инициализация движка (идемпотентна, потокобезопасна).
     */
    fun initialize() {
        if (nativeUnavailable()) return
        synchronized(playbackLock) {
            if (nativeHandle.get() != 0L) return
            val handle = nativeInitialize()
            if (handle == 0L) {
                Log.e(TAG, "nativeInitialize returned null handle")
                return
            }
            nativeHandle.set(handle)
            isInitialized = true
            Log.d(TAG, "Native engine initialized (per-instance, handle=$handle)")
        }
    }

    /**
     * Освобождение ресурсов. Идемпотентно и потокобезопасно: атомарный
     * getAndSet(0) гарантирует, что движок будет удалён ровно один раз,
     * даже если релиз зовут одновременно писатель (выход из лупа) и менеджер.
     */
    fun release() {
        if (nativeUnavailable()) return
        val handle = nativeHandle.getAndSet(0L)
        if (handle != 0L) {
            try { nativeRelease(handle) } catch (e: Exception) {
                Log.e(TAG, "nativeRelease failed: ${e.message}")
            }
            isInitialized = false
            Log.d(TAG, "Native engine released (handle=$handle)")
        }
    }

    /**
     * Установить конфигурацию с настройками режима расслабления
     */
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        currentConfig = config
        relaxationModeSettings = relaxationSettings

        val hh = h()
        if (hh == 0L || nativeUnavailable()) return

        val curve = config.frequencyCurve

        // Генерируем точки для воспроизведения в зависимости от режима расслабления
        // C1: оценка базовых кривых тем же сплайном, что и график
        val playbackPoints = if (relaxationSettings.enabled && curve.points.size >= 2) {
            when (relaxationSettings.mode) {
                RelaxationMode.STEP -> {
                    // В STEP режиме используем ТОЛЬКО виртуальные точки
                    generateStepVirtualPoints(curve.points, relaxationSettings, curve.interpolationType, curve.splineTension)
                }
                RelaxationMode.SMOOTH -> {
                    // В SMOOTH режиме используем ТОЛЬКО виртуальные точки (чередование базовых и сниженных)
                    generateSmoothVirtualPoints(curve.points, relaxationSettings, curve.interpolationType, curve.splineTension)
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
            handle = hh,
            timePoints = timePoints,
            carrierFreqs = carrierFreqs,
            beatFreqs = beatFreqs,
            interpolationType = interpolationType,
            splineTension = curve.splineTension,
            volume = 1.0f,  // Фиксированная громкость в нативном движке; мастер-громкость управляется через AudioTrack
            channelSwapEnabled = config.channelSwapEnabled,
            channelSwapIntervalSec = config.channelSwapIntervalSeconds,
            channelSwapMode = if (config.channelSwapMode == ChannelSwapMode.TREND) 1 else 0,
            channelSwapFadeEnabled = config.channelSwapFadeEnabled,
            channelSwapFadeDurationMs = config.channelSwapFadeDurationMs,
            channelSwapPauseDurationMs = config.channelSwapPauseDurationMs,
            channelSwapTrendPoints = config.channelSwapTrendPoints.ordinal,
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
        settings: RelaxationModeSettings,
        interpType: InterpolationType,
        splineTension: Float
    ): List<FrequencyPoint> {
        if (!settings.enabled || points.size < 2) return emptyList()

        val virtualPoints = mutableListOf<FrequencyPoint>()

        val carrierReduction = settings.carrierReductionPercent / 100.0f
        val beatReduction = settings.beatReductionPercent / 100.0f
        // C1: guard от бесконечного цикла при нецелом интервале
        val intervalSeconds = (settings.smoothIntervalMinutes.takeIf { it > 0 } ?: 5) * 60L

        // K9: сортируем один раз на всю развёртку (см. interpolateValueAtTime)
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }

        val daySeconds = 24 * 3600L

        // Генерируем точки от 00:00 с заданным интервалом
        // Чередуем: первая на графике, вторая снижающая, третья на графике, четвёртая снижающая и т.д.
        var currentTimeSeconds = 0L
        var isRelaxationPoint = false

        while (currentTimeSeconds < daySeconds) {
            val time = LocalTime.fromSecondOfDay((currentTimeSeconds % daySeconds).toInt())
            val baseCarrier = interpolateCarrierAtTime(sortedPoints, time, interpType, splineTension)
            val baseBeat = interpolateBeatAtTime(sortedPoints, time, interpType, splineTension)

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
        settings: RelaxationModeSettings,
        interpType: InterpolationType,
        splineTension: Float
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

        // K8: defense-in-depth от бесконечного цикла при вырожденных настройках
        val stepSeconds = fullPeriodSeconds + settings.gapBetweenRelaxationMinutes * 60L
        if (stepSeconds <= 0L) return emptyList()

        // K9: сортируем один раз на всю развёртку (см. interpolateValueAtTime)
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }

        // Генерируем периоды расслабления от 00:00
        val daySeconds = 24 * 3600L

        var periodStartSeconds = 0L

        while (periodStartSeconds < daySeconds) {
            // Точка 1: начало периода (на базовой кривой)
            val t1 = periodStartSeconds
            val time1 = LocalTime.fromSecondOfDay((t1 % daySeconds).toInt())
            val carrier1 = interpolateCarrierAtTime(sortedPoints, time1, interpType, splineTension)
            val beat1 = interpolateBeatAtTime(sortedPoints, time1, interpType, splineTension)
            virtualPoints.add(FrequencyPoint(time1, carrier1, beat1))

            // Точка 2: после перехода (сниженные частоты)
            val t2 = periodStartSeconds + transitionSeconds
            if (t2 < daySeconds) {
                val time2 = LocalTime.fromSecondOfDay((t2 % daySeconds).toInt())
                val baseCarrier2 = interpolateCarrierAtTime(sortedPoints, time2, interpType, splineTension)
                val baseBeat2 = interpolateBeatAtTime(sortedPoints, time2, interpType, splineTension)
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
                val baseCarrier3 = interpolateCarrierAtTime(sortedPoints, time3, interpType, splineTension)
                val baseBeat3 = interpolateBeatAtTime(sortedPoints, time3, interpType, splineTension)
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
                val carrier4 = interpolateCarrierAtTime(sortedPoints, time4, interpType, splineTension)
                val beat4 = interpolateBeatAtTime(sortedPoints, time4, interpType, splineTension)
                virtualPoints.add(FrequencyPoint(time4, carrier4, beat4))
            }

            // Переходим к следующему периоду: полный период + пауза между периодами
            periodStartSeconds += stepSeconds
        }

        // Сортируем по времени
        return virtualPoints.sortedBy { it.time.toSecondOfDay() }
    }

    /**
     * Интерполирует несущую частоту для заданного времени.
     */
    private fun interpolateCarrierAtTime(
        points: List<FrequencyPoint>,
        time: LocalTime,
        interpType: InterpolationType,
        splineTension: Float
    ): Float {
        return interpolateValueAtTime(points, time, interpType, splineTension) { it.carrierFrequency }
    }

    /**
     * Интерполирует частоту биения для заданного времени.
     */
    private fun interpolateBeatAtTime(
        points: List<FrequencyPoint>,
        time: LocalTime,
        interpType: InterpolationType,
        splineTension: Float
    ): Float {
        return interpolateValueAtTime(points, time, interpType, splineTension) { it.beatFrequency }
    }

    /**
     * C1: оценка базовой кривой сплайном выбранного типа (как на графике UI).
     * p0..p3 — соседние точки пресета вокруг интервала [current, next].
     */
    private fun interpolateValueAtTime(
        points: List<FrequencyPoint>,
        time: LocalTime,
        interpType: InterpolationType,
        splineTension: Float,
        valueOf: (FrequencyPoint) -> Float
    ): Float {
        // K9: points уже отсортированы вызывающей стороной (генераторами)
        val sortedPoints = points
        val size = sortedPoints.size
        val targetSeconds = time.toSecondOfDay()

        // Находим интервал
        for (i in 0 until size) {
            val current = sortedPoints[i]
            val next = sortedPoints[(i + 1) % size]

            val currentSeconds = current.time.toSecondOfDay()
            var nextSeconds = next.time.toSecondOfDay()

            // Обработка перехода через полночь
            if (nextSeconds <= currentSeconds) {
                nextSeconds += 24 * 3600
            }

            val adjustedTarget = if (targetSeconds < currentSeconds && i == size - 1) {
                targetSeconds + 24 * 3600
            } else {
                targetSeconds
            }

            if (adjustedTarget in currentSeconds..nextSeconds) {
                if (nextSeconds == currentSeconds) return valueOf(current)
                val t = (adjustedTarget - currentSeconds).toFloat() / (nextSeconds - currentSeconds)
                val prev = sortedPoints[(i - 1 + size) % size]
                val after = sortedPoints[(i + 2) % size]
                return Interpolation.interpolate(
                    interpType,
                    valueOf(prev), valueOf(current), valueOf(next), valueOf(after),
                    t,
                    splineTension
                )
            }
        }

        return valueOf(sortedPoints.first())
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
        if (nativeUnavailable()) return
        val hh = h()
        if (hh == 0L) return
        nativeSetSampleRate(hh, sampleRate)
    }

    /**
     * Сбросить состояние
     */
    fun resetState() {
        if (nativeUnavailable()) return
        synchronized(playbackLock) {
            val hh = h()
            if (hh == 0L) return
            nativeResetState(hh)
        }
    }

    /**
     * Начать воспроизведение
     * @param preserveTimeline true = RESUME: продолжить с того же места кривой
     *        без сброса таймлайна и фаз (иначе pause→resume даёт скачок частот)
     */
    fun play(preserveTimeline: Boolean = false) {
        if (nativeUnavailable()) return
        // C11: атомарность пары setPlaybackStartTime + setPlaying
        synchronized(playbackLock) {
            val hh = h()
            if (hh == 0L) return
            if (!preserveTimeline) {
                nativeSetPlaybackStartTime(hh, System.currentTimeMillis())
            }
            nativeSetPlaying(hh, true, preserveTimeline)
        }
    }

    /**
     * Остановить воспроизведение
     */
    fun stop() {
        if (nativeUnavailable()) return
        synchronized(playbackLock) {
            val hh = h()
            if (hh == 0L) return
            nativeSetPlaying(hh, false, false)
        }
    }

    /**
     * Переякорить опорную точку elapsed-таймера (мс с эпохи).
     * F9: используется при soft-resume для исключения скачка elapsed на длину паузы.
     */
    fun setPlaybackStartTime(startTimeMs: Long) {
        if (nativeUnavailable()) return
        synchronized(playbackLock) {
            val hh = h()
            if (hh == 0L) return
            nativeSetPlaybackStartTime(hh, startTimeMs)
        }
    }

    /**
     * Явно задать позицию кривой (секунды суток) для продолжения воспроизведения
     * в НОВОМ нативном движке (resume из паузы / handoff). Вызывать ДО
     * play(preserveTimeline = true), иначе свежий движок сгенерирует с 00:00.
     */
    fun setCurveTime(timeSeconds: Int) {
        if (nativeUnavailable()) return
        synchronized(playbackLock) {
            val hh = h()
            if (hh == 0L) return
            nativeSetCurveTime(hh, timeSeconds)
        }
    }

    /**
     * Сгенерировать буфер аудио (FloatArray версия - с копированием)
     */
    fun generateBuffer(buffer: FloatArray, samplesPerChannel: Int): Boolean {
        if (nativeUnavailable()) return false
        val hh = h()
        if (hh == 0L) return false
        return nativeGenerateBuffer(hh, buffer, samplesPerChannel)
    }

    /**
     * Сгенерировать буфер аудио (Zero-copy через DirectByteBuffer)
     * ОПТИМИЗАЦИЯ: Избегает копирования данных между Java и C++
     * @return РЕАЛЬНО сгенерированное количество сэмплов на канал (0 = не активно).
     *         Вызывающая сторона ОБЯЗАНА записать в AudioTrack ровно это значение.
     */
    fun generateBufferDirect(
        directBuffer: java.nio.ByteBuffer,
        samplesPerChannel: Int
    ): Int {
        if (nativeUnavailable()) return 0
        val hh = h()
        if (hh == 0L) return 0
        return nativeGenerateBufferDirect(hh, directBuffer, samplesPerChannel)
    }

    // === Геттеры читают атомарные поля СВОЕГО движка (per-instance) ===

    fun getCurrentBeatFrequency(): Float {
        if (nativeUnavailable()) return 0f
        val hh = h()
        return if (hh == 0L) 0f else nativeGetCurrentBeatFrequency(hh)
    }
    fun getCurrentCarrierFrequency(): Float {
        if (nativeUnavailable()) return 0f
        val hh = h()
        return if (hh == 0L) 0f else nativeGetCurrentCarrierFrequency(hh)
    }
    fun getElapsedSeconds(): Int {
        if (nativeUnavailable()) return 0
        val hh = h()
        return if (hh == 0L) 0 else nativeGetElapsedSeconds(hh)
    }
    fun isChannelsSwapped(): Boolean {
        if (nativeUnavailable()) return false
        val hh = h()
        return hh != 0L && nativeIsChannelsSwapped(hh)
    }

    /**
     * Получить частоты для текущего времени из lookup table.
     * O(1) операция - использует предвычисленную таблицу в C++.
     * @return Pair(beatFrequency, carrierFrequency) или null если конфиг не установлен
     */
    fun getFrequenciesAtCurrentTime(): Pair<Float, Float>? {
        if (nativeUnavailable()) return null
        val hh = h()
        if (hh == 0L) return null
        val result = nativeGetFrequenciesAtCurrentTime(hh)
        return result?.let { Pair(it[0], it[1]) }
    }

    // === Нативные методы для батчевой генерации (оптимизация энергопотребления) ===

    private external fun nativeSetBatchDurationMinutes(handle: Long, durationMinutes: Int)
    private external fun nativeGetBatchDurationMinutes(handle: Long): Int
    private external fun nativeGenerateBatch(handle: Long, buffer: java.nio.ByteBuffer, maxSamplesPerChannel: Int): Int

    // === НОВОЕ: текущее время суток (учитывает virtual-режим) ===
    private external fun nativeGetCurrentTimeOfDay(handle: Long): Int

    // === НОВОЕ: Debug virtual time (только debug-сборка) ===
    private external fun nativeDebugSetVirtualTimeEnabled(handle: Long, enabled: Boolean)
    private external fun nativeDebugScrub(handle: Long, timeSeconds: Int)
    private external fun nativeDebugSetTimeScale(handle: Long, scale: Float)
    private external fun nativeDebugSetRunning(handle: Long, running: Boolean)
    private external fun nativeDebugReset(handle: Long)
    private external fun nativeDebugGetVirtualTime(handle: Long): Int
    private external fun nativeDebugIsEnabled(handle: Long): Boolean
    private external fun nativeDebugGetTimeScale(handle: Long): Float

    /**
     * Установить длительность батча для оптимизации энергопотребления
     * @param durationMinutes длительность в минутах (0 = отключено)
     */
    fun setBatchDurationMinutes(durationMinutes: Int) {
        if (nativeUnavailable()) return
        val hh = h()
        if (hh == 0L) return
        nativeSetBatchDurationMinutes(hh, durationMinutes.coerceIn(0, 60))
        Log.d(TAG, "Batch duration set to $durationMinutes minutes")
    }

    /**
     * Получить длительность батча в минутах
     */
    fun getBatchDurationMinutes(): Int {
        if (nativeUnavailable()) return 0
        val hh = h()
        return if (hh == 0L) 0 else nativeGetBatchDurationMinutes(hh)
    }

    /**
     * Сгенерировать батч аудио (оптимизация энергопотребления)
     * Генерирует один большой буфер на заданное время за один вызов
     * @param directBuffer DirectByteBuffer достаточного размера
     * @param maxSamplesPerChannel максимальное количество сэмплов на канал
     * @return количество сгенерированных сэмплов на канал
     */
    fun generateBatch(directBuffer: java.nio.ByteBuffer, maxSamplesPerChannel: Int): Int {
        if (nativeUnavailable()) return 0
        val hh = h()
        if (hh == 0L) return 0
        return nativeGenerateBatch(hh, directBuffer, maxSamplesPerChannel)
    }

    /**
     * Текущее время суток в секундах (реальное или виртуальное).
     */
    fun getCurrentTimeOfDay(): Int {
        if (nativeUnavailable()) return 0
        val hh = h()
        return if (hh == 0L) 0 else nativeGetCurrentTimeOfDay(hh)
    }

    // === Публичные обёртки для Debug virtual time (no-op в release) ===

    fun debugSetVirtualTimeEnabled(enabled: Boolean) {
        if (BuildConfig.DEBUG && !nativeUnavailable()) {
            val hh = h()
            if (hh != 0L) nativeDebugSetVirtualTimeEnabled(hh, enabled)
        }
    }

    fun debugScrub(timeSeconds: Int) {
        if (BuildConfig.DEBUG && !nativeUnavailable()) {
            val hh = h()
            if (hh != 0L) nativeDebugScrub(hh, timeSeconds)
        }
    }

    fun debugSetTimeScale(scale: Float) {
        if (BuildConfig.DEBUG && !nativeUnavailable()) {
            val hh = h()
            if (hh != 0L) nativeDebugSetTimeScale(hh, scale)
        }
    }

    fun debugSetRunning(running: Boolean) {
        if (BuildConfig.DEBUG && !nativeUnavailable()) {
            val hh = h()
            if (hh != 0L) nativeDebugSetRunning(hh, running)
        }
    }

    fun debugResetToRealTime() {
        if (BuildConfig.DEBUG && !nativeUnavailable()) {
            val hh = h()
            if (hh != 0L) nativeDebugReset(hh)
        }
    }

    fun debugGetVirtualTime(): Int =
        if (BuildConfig.DEBUG && !nativeUnavailable()) {
            val hh = h()
            if (hh != 0L) nativeDebugGetVirtualTime(hh) else 0
        } else 0

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
        if (nativeUnavailable()) return Interpolation.interpolate(interpolationType, p0, p1, p2, p3, t, tension)
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
        if (nativeUnavailable()) return null
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
        if (nativeUnavailable()) return null
        val result = nativeGetChannelFrequencies(
            timePoints, carrierFreqs, beatFreqs,
            targetTimeSeconds, typeInt, tension
        )
        return result?.let { Pair(it[0], it[1]) }
    }
}
