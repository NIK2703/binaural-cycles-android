package com.binaural.core.audio.model

import kotlinx.datetime.LocalTime

/**
 * Диапазон частот
 */
data class FrequencyRange(
    val min: Double,
    val max: Double
) {
    init {
        require(max > min) { "Максимальная частота должна быть больше минимальной" }
    }
    
    fun contains(value: Double): Boolean = value in min..max
    
    fun clamp(value: Double): Double = value.coerceIn(min, max)
    
    companion object {
        val DEFAULT_CARRIER = FrequencyRange(20.0, 500.0)
        val DEFAULT_BEAT = FrequencyRange(0.125, 1000.0)
    }
}

/**
 * Точка на графике зависимости частот от времени суток
 * Содержит время, несущую частоту и частоту биений
 */
data class FrequencyPoint(
    val time: LocalTime,           // Время суток
    val carrierFrequency: Double,  // Несущая частота (Гц)
    val beatFrequency: Double      // Частота биений (Гц)
) {
    companion object {
        /**
         * Создаёт точку из часов и минут
         */
        fun fromHours(hours: Int, minutes: Int = 0, carrierFrequency: Double, beatFrequency: Double): FrequencyPoint {
            return FrequencyPoint(LocalTime(hours, minutes), carrierFrequency, beatFrequency)
        }
    }
}

/**
 * Кривая зависимости частот от времени суток
 * Содержит набор точек и интерполирует значения между ними
 */
data class FrequencyCurve(
    val points: List<FrequencyPoint>,
    val carrierRange: FrequencyRange = FrequencyRange.DEFAULT_CARRIER,
    val beatRange: FrequencyRange = FrequencyRange.DEFAULT_BEAT
) {
    init {
        require(points.size >= 2) { "Кривая должна содержать минимум 2 точки" }
    }

    /**
     * Получить несущую частоту для заданного времени путём линейной интерполяции
     */
    fun getCarrierFrequencyAt(time: LocalTime): Double {
        return carrierRange.clamp(interpolate(time) { it.carrierFrequency })
    }

    /**
     * Получить частоту биений для заданного времени путём линейной интерполяции
     */
    fun getBeatFrequencyAt(time: LocalTime): Double {
        return beatRange.clamp(interpolate(time) { it.beatFrequency })
    }

    private fun interpolate(time: LocalTime, frequencySelector: (FrequencyPoint) -> Double): Double {
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
        
        // Если время раньше первой точки - интерполируем от последней к первой
        if (time.toSecondOfDay() <= sortedPoints.first().time.toSecondOfDay()) {
            return interpolateValue(
                sortedPoints.last(),
                sortedPoints.first(),
                time,
                frequencySelector
            )
        }
        
        // Если время позже последней точки - интерполируем от последней к первой
        if (time.toSecondOfDay() >= sortedPoints.last().time.toSecondOfDay()) {
            return interpolateValue(
                sortedPoints.last(),
                sortedPoints.first(),
                time,
                frequencySelector
            )
        }
        
        // Находим две соседние точки
        for (i in 0 until sortedPoints.size - 1) {
            val current = sortedPoints[i]
            val next = sortedPoints[i + 1]
            
            if (time.toSecondOfDay() in current.time.toSecondOfDay()..next.time.toSecondOfDay()) {
                return interpolateValue(current, next, time, frequencySelector)
            }
        }
        
        return frequencySelector(sortedPoints.first())
    }

    private fun interpolateValue(
        p1: FrequencyPoint,
        p2: FrequencyPoint,
        time: LocalTime,
        frequencySelector: (FrequencyPoint) -> Double
    ): Double {
        val t1 = p1.time.toSecondOfDay()
        val t2 = if (p2.time.toSecondOfDay() < t1) {
            p2.time.toSecondOfDay() + 24 * 3600 // Следующий день
        } else {
            p2.time.toSecondOfDay()
        }
        val t = if (time.toSecondOfDay() < t1) {
            time.toSecondOfDay() + 24 * 3600
        } else {
            time.toSecondOfDay()
        }
        
        if (t2 == t1) return frequencySelector(p1)
        
        val ratio = (t - t1).toDouble() / (t2 - t1)
        return frequencySelector(p1) + ratio * (frequencySelector(p2) - frequencySelector(p1))
    }

    companion object {
        /**
         * Создаёт кривую по умолчанию
         * Ночью - дельта/тета волны (сон), днём - бета/альфа (активность)
         */
        fun defaultCurve(): FrequencyCurve {
            return FrequencyCurve(
                points = listOf(
                    FrequencyPoint.fromHours(0, 0, carrierFrequency = 150.0, beatFrequency = 2.0),    // Полночь - глубокий сон (дельта)
                    FrequencyPoint.fromHours(6, 0, carrierFrequency = 200.0, beatFrequency = 10.0),   // Утро - альфа (пробуждение)
                    FrequencyPoint.fromHours(9, 0, carrierFrequency = 220.0, beatFrequency = 15.0),   // Утро - бета (активность)
                    FrequencyPoint.fromHours(12, 0, carrierFrequency = 250.0, beatFrequency = 12.0),  // Обед - альфа/бета
                    FrequencyPoint.fromHours(15, 0, carrierFrequency = 250.0, beatFrequency = 18.0),  // День - бета (фокус)
                    FrequencyPoint.fromHours(18, 0, carrierFrequency = 200.0, beatFrequency = 10.0),  // Вечер - альфа (расслабление)
                    FrequencyPoint.fromHours(21, 0, carrierFrequency = 170.0, beatFrequency = 6.0),   // Поздний вечер - тета
                    FrequencyPoint.fromHours(23, 0, carrierFrequency = 150.0, beatFrequency = 3.0),   // Ночь - дельта/тета
                )
            )
        }
    }
}

/**
 * Конфигурация бинаурального ритма
 */
data class BinauralConfig(
    val frequencyCurve: FrequencyCurve = FrequencyCurve.defaultCurve(),
    val volume: Float = 0.7f,
    // Настройки перестановки каналов
    val channelSwapEnabled: Boolean = false,
    val channelSwapIntervalSeconds: Int = 300, // 5 минут по умолчанию
    // Настройки нормализации громкости
    val volumeNormalizationEnabled: Boolean = false,
    val volumeNormalizationStrength: Float = 0.5f // от 0 до 1.0
) {
    /**
     * Получить текущие частоты для заданного времени
     */
    fun getFrequenciesAt(time: LocalTime): Pair<Double, Double> {
        val beatFreq = frequencyCurve.getBeatFrequencyAt(time)
        val carrierFreq = frequencyCurve.getCarrierFrequencyAt(time)
        return Pair(beatFreq, carrierFreq)
    }
}

/**
 * Состояние плеера
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val config: BinauralConfig = BinauralConfig(),
    val elapsedSeconds: Int = 0,
    val volume: Float = 0.7f
)
