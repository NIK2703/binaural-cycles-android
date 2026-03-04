package com.binaural.core.audio.model

import kotlinx.datetime.LocalTime
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

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
 * Тип интерполяции между точками
 */
enum class InterpolationType {
    LINEAR,         // Линейная интерполяция
    CUBIC_SPLINE    // Кубический сплайн Catmull-Rom (плавная кривая)
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
    val beatRange: FrequencyRange = FrequencyRange.DEFAULT_BEAT,
    val interpolationType: InterpolationType = InterpolationType.LINEAR
) {
    init {
        require(points.size >= 2) { "Кривая должна содержать минимум 2 точки" }
    }

    /**
     * Получить несущую частоту для заданного времени путём интерполяции
     */
    fun getCarrierFrequencyAt(time: LocalTime): Double {
        return carrierRange.clamp(interpolate(time) { it.carrierFrequency })
    }

    /**
     * Получить частоту биений для заданного времени путём интерполяции
     */
    fun getBeatFrequencyAt(time: LocalTime): Double {
        return beatRange.clamp(interpolate(time) { it.beatFrequency })
    }

    private fun interpolate(time: LocalTime, frequencySelector: (FrequencyPoint) -> Double): Double {
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
        val targetSeconds = time.toSecondOfDay()
        
        // Находим интервал, в который попадает время
        var intervalIndex = -1
        for (i in 0 until sortedPoints.size - 1) {
            val current = sortedPoints[i].time.toSecondOfDay()
            val next = sortedPoints[i + 1].time.toSecondOfDay()
            if (targetSeconds in current..next) {
                intervalIndex = i
                break
            }
        }
        
        // Если не нашли в обычных интервалах - это переход через полночь
        if (intervalIndex == -1) {
            // Время между последней точкой и первой (переход через полночь)
            val lastPoint = sortedPoints.last()
            val firstPoint = sortedPoints.first()
            return interpolateBetweenPoints(
                sortedPoints, 
                sortedPoints.size - 1, 
                0, // первая точка (с переходом через полночь)
                time, 
                frequencySelector,
                isWrapping = true
            )
        }
        
        return interpolateBetweenPoints(
            sortedPoints,
            intervalIndex,
            intervalIndex + 1,
            time,
            frequencySelector,
            isWrapping = false
        )
    }
    
    /**
     * Интерполяция между двумя точками с учётом соседних для кубического сплайна
     */
    private fun interpolateBetweenPoints(
        sortedPoints: List<FrequencyPoint>,
        leftIndex: Int,
        rightIndex: Int,
        time: LocalTime,
        frequencySelector: (FrequencyPoint) -> Double,
        isWrapping: Boolean
    ): Double {
        val leftPoint = sortedPoints[leftIndex]
        val rightPoint = sortedPoints[rightIndex]
        
        // Вычисляем нормализованную позицию t в интервале [0, 1]
        val t1 = leftPoint.time.toSecondOfDay()
        val t2 = if (isWrapping) {
            rightPoint.time.toSecondOfDay() + 24 * 3600 // переход через полночь
        } else {
            rightPoint.time.toSecondOfDay()
        }
        val t = if (time.toSecondOfDay() < t1 && isWrapping) {
            time.toSecondOfDay() + 24 * 3600
        } else {
            time.toSecondOfDay()
        }
        
        if (t2 == t1) return frequencySelector(leftPoint)
        
        val ratio = (t - t1).toDouble() / (t2 - t1)
        
        return when (interpolationType) {
            InterpolationType.LINEAR -> {
                linearInterpolate(frequencySelector(leftPoint), frequencySelector(rightPoint), ratio)
            }
            InterpolationType.CUBIC_SPLINE -> {
                // Для Catmull-Rom нужны 4 точки: P0 (до левой), P1 (левая), P2 (правая), P3 (после правой)
                val p0 = getNeighborPoint(sortedPoints, leftIndex, -1, frequencySelector)
                val p1 = frequencySelector(leftPoint)
                val p2 = frequencySelector(rightPoint)
                val p3 = getNeighborPoint(sortedPoints, rightIndex, +1, frequencySelector)
                
                catmullRomInterpolate(p0, p1, p2, p3, ratio)
            }
        }
    }
    
    /**
     * Получить соседнюю точку с учётом цикличности графика
     * @param points отсортированный список точек
     * @param currentIndex текущий индекс
     * @param offset смещение (-1 для предыдущей, +1 для следующей)
     * @param frequencySelector селектор частоты
     */
    private fun getNeighborPoint(
        points: List<FrequencyPoint>,
        currentIndex: Int,
        offset: Int,
        frequencySelector: (FrequencyPoint) -> Double
    ): Double {
        var neighborIndex = currentIndex + offset
        
        // Циклическая навигация
        when {
            neighborIndex < 0 -> neighborIndex = points.size + neighborIndex // переход к концу
            neighborIndex >= points.size -> neighborIndex = neighborIndex - points.size // переход к началу
        }
        
        return frequencySelector(points[neighborIndex])
    }
    
    /**
     * Линейная интерполяция
     */
    private fun linearInterpolate(y1: Double, y2: Double, t: Double): Double {
        return y1 + t * (y2 - y1)
    }
    
    /**
     * Интерполяция Catmull-Rom
     * Использует 4 точки для создания плавной кривой
     * P0 - точка до левой границы
     * P1 - левая граница
     * P2 - правая граница  
     * P3 - точка после правой границы
     */
    private fun catmullRomInterpolate(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        
        // Формула Catmull-Rom сплайна
        return 0.5 * (
            (2.0 * p1) +
            (-p0 + p2) * t +
            (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
            (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
        )
    }

    
    /**
     * Получить все интерполированные значения для отображения на графике
     * Возвращает список пар (время в секундах, значение) для указанного селектора частоты
     */
    fun getInterpolatedValues(
        numSamples: Int = 100,
        frequencySelector: (FrequencyPoint) -> Double
    ): List<Pair<Int, Double>> {
        return (0..numSamples).map { i ->
            val t = i.toDouble() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            time.toSecondOfDay() to interpolate(time, frequencySelector)
        }
    }

    companion object {
        /**
         * Создаёт кривую по умолчанию
         * Точки каждые 3 часа (последняя - 23:59)
         * Ночью - дельта/тета волны (сон), днём - бета/альфа (активность)
         */
        fun defaultCurve(): FrequencyCurve {
            return FrequencyCurve(
                points = listOf(
                    FrequencyPoint.fromHours(0, 0, carrierFrequency = 150.0, beatFrequency = 2.0),    // Полночь - глубокий сон (дельта)
                    FrequencyPoint.fromHours(3, 0, carrierFrequency = 150.0, beatFrequency = 2.0),    // Ночь - глубокий сон (дельта)
                    FrequencyPoint.fromHours(6, 0, carrierFrequency = 200.0, beatFrequency = 10.0),   // Утро - альфа (пробуждение)
                    FrequencyPoint.fromHours(9, 0, carrierFrequency = 220.0, beatFrequency = 15.0),   // Утро - бета (активность)
                    FrequencyPoint.fromHours(12, 0, carrierFrequency = 250.0, beatFrequency = 12.0),  // Обед - альфа/бета
                    FrequencyPoint.fromHours(15, 0, carrierFrequency = 250.0, beatFrequency = 18.0),  // День - бета (фокус)
                    FrequencyPoint.fromHours(18, 0, carrierFrequency = 200.0, beatFrequency = 10.0),  // Вечер - альфа (расслабление)
                    FrequencyPoint.fromHours(21, 0, carrierFrequency = 170.0, beatFrequency = 6.0),   // Поздний вечер - тета
                    FrequencyPoint(LocalTime(23, 59), carrierFrequency = 150.0, beatFrequency = 3.0), // Ночь - дельта/тета
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
    val channelSwapFadeEnabled: Boolean = true, // затухание при смене каналов
    val channelSwapFadeDurationMs: Long = 1000L, // длительность затухания/нарастания в миллисекундах
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

/**
 * Пресет бинаурального ритма - сохранённая конфигурация с названием
 */
data class BinauralPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val frequencyCurve: FrequencyCurve,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Создаёт пресет по умолчанию "Циркадный ритм"
         */
        fun defaultPreset(): BinauralPreset {
            return BinauralPreset(
                name = "Циркадный ритм",
                frequencyCurve = FrequencyCurve.defaultCurve()
            )
        }
        
        /**
         * Создаёт пресет для расслабления
         */
        fun relaxationPreset(): BinauralPreset {
            return BinauralPreset(
                name = "Расслабление",
                frequencyCurve = FrequencyCurve(
                    points = listOf(
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 200.0, beatFrequency = 6.0),
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 200.0, beatFrequency = 4.0),
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 200.0, beatFrequency = 8.0),
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 200.0, beatFrequency = 6.0),
                        FrequencyPoint.fromHours(23, 0, carrierFrequency = 200.0, beatFrequency = 4.0),
                    )
                )
            )
        }
        
        /**
         * Создаёт пресет для фокуса
         */
        fun focusPreset(): BinauralPreset {
            return BinauralPreset(
                name = "Фокус",
                frequencyCurve = FrequencyCurve(
                    points = listOf(
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 250.0, beatFrequency = 14.0),
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 250.0, beatFrequency = 12.0),
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 250.0, beatFrequency = 18.0),
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 250.0, beatFrequency = 15.0),
                        FrequencyPoint.fromHours(23, 0, carrierFrequency = 250.0, beatFrequency = 10.0),
                    )
                )
            )
        }
        
        /**
         * Создаёт пресет для сна
         */
        fun sleepPreset(): BinauralPreset {
            return BinauralPreset(
                name = "Сон",
                frequencyCurve = FrequencyCurve(
                    points = listOf(
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 150.0, beatFrequency = 2.0),
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 150.0, beatFrequency = 1.0),
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 150.0, beatFrequency = 2.0),
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 150.0, beatFrequency = 1.5),
                        FrequencyPoint.fromHours(23, 0, carrierFrequency = 150.0, beatFrequency = 0.5),
                    )
                )
            )
        }
        
        /**
         * Возвращает список предустановленных пресетов
         */
        fun defaultPresets(): List<BinauralPreset> {
            return listOf(
                defaultPreset(),
                relaxationPreset(),
                focusPreset(),
                sleepPreset()
            )
        }
    }
}
