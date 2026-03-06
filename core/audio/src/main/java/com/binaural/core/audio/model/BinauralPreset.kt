package com.binaural.core.audio.model

import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Сериализатор для LocalTime
 */
object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.INT)
    
    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeInt(value.toSecondOfDay())
    }
    
    override fun deserialize(decoder: Decoder): LocalTime {
        return LocalTime.fromSecondOfDay(decoder.decodeInt())
    }
}

/**
 * Диапазон частот
 */
@Serializable
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
        val DEFAULT_BEAT = FrequencyRange(0.0, 1000.0)
    }
}

/**
 * Тип интерполяции между точками
 */
@Serializable
enum class InterpolationType {
    LINEAR,         // Линейная интерполяция
    CUBIC_SPLINE    // Кубический сплайн Catmull-Rom (плавная кривая)
}

/**
 * Точка на графике зависимости частот от времени суток
 * Содержит время, несущую частоту и частоту биений
 */
@Serializable
data class FrequencyPoint(
    @Serializable(with = LocalTimeSerializer::class)
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
@Serializable
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
     * Не ограничиваем результат - кубический сплайн может давать значения за пределами точек
     */
    fun getCarrierFrequencyAt(time: LocalTime): Double {
        return interpolate(time) { it.carrierFrequency }.coerceAtLeast(0.0)
    }

    /**
     * Получить частоту биений для заданного времени путём интерполяции
     * Не ограничиваем результат - кубический сплайн может давать значения за пределами точек
     */
    fun getBeatFrequencyAt(time: LocalTime): Double {
        return interpolate(time) { it.beatFrequency }.coerceAtLeast(0.0)
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
                    // Передаём isWrapping для корректной навигации при переходе через полночь
                    val p0 = getNeighborPoint(sortedPoints, leftIndex, -1, frequencySelector, isWrapping)
                    val p1 = frequencySelector(leftPoint)
                    val p2 = frequencySelector(rightPoint)
                    val p3 = getNeighborPoint(sortedPoints, rightIndex, +1, frequencySelector, isWrapping)
                    
                    val result = catmullRomInterpolate(p0, p1, p2, p3, ratio)
                    
                    // ВАЖНО: Catmull-Rom сплайн может давать значения за пределами [p1, p2]
                    // Ограничиваем результат минимумом 0, т.к. частота не может быть отрицательной
                    result.coerceAtLeast(0.0)
                }
            }
    }
    
    /**
     * Получить соседнюю точку для сплайна Catmull-Rom
     * Использует циклический переход через границы для получения 4 соседних точек.
     *
     * @param points отсортированный список точек
     * @param currentIndex текущий индекс
     * @param offset смещение (-1 для предыдущей, +1 для следующей)
     * @param frequencySelector селектор частоты
     * @param isWrapping true, если текущий интервал переходит через полночь
     */
    private fun getNeighborPoint(
        points: List<FrequencyPoint>,
        currentIndex: Int,
        offset: Int,
        frequencySelector: (FrequencyPoint) -> Double,
        isWrapping: Boolean = false
    ): Double {
        val neighborIndex = currentIndex + offset
        
        // Обработка границ массива
        return when {
            neighborIndex < 0 -> {
                // Если идём влево от первой точки
                if (isWrapping) {
                    // При переходе через полночь берём последнюю точку
                    frequencySelector(points.last())
                } else {
                    // Иначе берём первую точку (clamp)
                    frequencySelector(points.first())
                }
            }
            neighborIndex >= points.size -> {
                // Если идём вправо от последней точки
                if (isWrapping) {
                    // При переходе через полночь берём первую точку
                    frequencySelector(points.first())
                } else {
                    // Иначе берём последнюю точку (clamp)
                    frequencySelector(points.last())
                }
            }
            else -> {
                frequencySelector(points[neighborIndex])
            }
        }
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
         * Точки каждые 3 часа (0:00, 3:00, ..., 21:00)
         * Основано на циркадных ритмах: ночь - дельта/тета (сон), день - бета (активность)
         */
        fun defaultCurve(): FrequencyCurve {
            return FrequencyCurve(
                points = listOf(
                    FrequencyPoint.fromHours(0, 0, carrierFrequency = 174.0, beatFrequency = 3.0),    // Глубокий сон - дельта
                    FrequencyPoint.fromHours(3, 0, carrierFrequency = 210.0, beatFrequency = 6.0),    // Лёгкий сон - тета
                    FrequencyPoint.fromHours(6, 0, carrierFrequency = 220.0, beatFrequency = 8.0),    // Пробуждение - альфа/тета
                    FrequencyPoint.fromHours(9, 0, carrierFrequency = 440.0, beatFrequency = 20.0),   // Пик активности - бета
                    FrequencyPoint.fromHours(12, 0, carrierFrequency = 440.0, beatFrequency = 25.0),  // Продуктивность - высокий бета
                    FrequencyPoint.fromHours(15, 0, carrierFrequency = 440.0, beatFrequency = 18.0),  // Вторая половина дня - бета
                    FrequencyPoint.fromHours(18, 0, carrierFrequency = 250.0, beatFrequency = 12.0),  // Вечерний спад - альфа
                    FrequencyPoint.fromHours(21, 0, carrierFrequency = 240.0, beatFrequency = 10.0),  // Подготовка ко сну - альфа
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
    val volumeNormalizationEnabled: Boolean = true,  // Включено по умолчанию
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
 * Настройки перестановки каналов для пресета
 */
@Serializable
data class ChannelSwapSettings(
    val enabled: Boolean = false,
    val intervalSeconds: Int = 300,        // 5 минут по умолчанию
    val fadeEnabled: Boolean = true,       // затухание при смене каналов
    val fadeDurationMs: Long = 1000L       // длительность затухания/нарастания в мс
)

/**
 * Настройки нормализации громкости для пресета
 */
@Serializable
data class VolumeNormalizationSettings(
    val enabled: Boolean = true,           // включено по умолчанию
    val strength: Float = 0.5f             // от 0 до 1.0
)

/**
 * Пресет бинаурального ритма - сохранённая конфигурация с названием
 */
@Serializable
data class BinauralPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val frequencyCurve: FrequencyCurve,
    // Настройки перестановки каналов (для каждого пресета отдельно)
    val channelSwapSettings: ChannelSwapSettings = ChannelSwapSettings(),
    // Настройки нормализации громкости (для каждого пресета отдельно)
    val volumeNormalizationSettings: VolumeNormalizationSettings = VolumeNormalizationSettings(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Фиксированные ID для стандартных пресетов (важно для сохранения изменений)
        const val DEFAULT_PRESET_ID = "preset-circadian-rhythm"
        const val GAMMA_PRESET_ID = "preset-gamma-productivity"
        const val DAILY_CYCLE_PRESET_ID = "preset-daily-cycle"
        
        /**
         * Создаёт пресет по умолчанию "Циркадный ритм"
         * Основано на циркадных ритмах человека
         */
        fun defaultPreset(): BinauralPreset {
            return BinauralPreset(
                id = DEFAULT_PRESET_ID,
                name = "Циркадный ритм",
                frequencyCurve = FrequencyCurve.defaultCurve()
            )
        }
        
        /**
         * Создаёт пресет "Гамма-продуктивность"
         * Включает гамма-ритм во второй половине дня для улучшения памяти и когнитивной гибкости
         */
        fun gammaPreset(): BinauralPreset {
            return BinauralPreset(
                id = GAMMA_PRESET_ID,
                name = "Гамма-продуктивность",
                frequencyCurve = FrequencyCurve(
                    points = listOf(
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 220.0, beatFrequency = 1.5),   // Глубокий сон - дельта
                        FrequencyPoint.fromHours(3, 0, carrierFrequency = 250.0, beatFrequency = 5.0),   // Лёгкий сон - тета
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 340.0, beatFrequency = 9.0),   // Пробуждение - альфа
                        FrequencyPoint.fromHours(9, 0, carrierFrequency = 400.0, beatFrequency = 18.0),  // Пик активности - бета
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 380.0, beatFrequency = 14.0), // Поддержание внимания - бета/альфа
                        FrequencyPoint.fromHours(15, 0, carrierFrequency = 440.0, beatFrequency = 40.0), // Второй пик - гамма
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 300.0, beatFrequency = 7.5),  // Расслабление - альфа/тета
                        FrequencyPoint.fromHours(21, 0, carrierFrequency = 240.0, beatFrequency = 4.0),  // Подготовка ко сну - тета
                    )
                )
            )
        }
        
        /**
         * Создаёт пресет "Суточный цикл"
         * Полный цикл с глубокой регенерацией ночью до максимальной продуктивности днём
         */
        fun dailyCyclePreset(): BinauralPreset {
            return BinauralPreset(
                id = DAILY_CYCLE_PRESET_ID,
                name = "Суточный цикл",
                frequencyCurve = FrequencyCurve(
                    points = listOf(
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 200.0, beatFrequency = 2.0),   // Глубокий сон - дельта
                        FrequencyPoint.fromHours(3, 0, carrierFrequency = 200.0, beatFrequency = 3.0),   // Подготовка к пробуждению - дельта-тета
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 300.0, beatFrequency = 10.0),  // Спокойное пробуждение - альфа
                        FrequencyPoint.fromHours(9, 0, carrierFrequency = 400.0, beatFrequency = 18.0),  // Пик концентрации - бета
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 300.0, beatFrequency = 6.0),  // Креативная перезагрузка - тета
                        FrequencyPoint.fromHours(15, 0, carrierFrequency = 400.0, beatFrequency = 25.0), // Максимальная продуктивность - верхний бета
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 300.0, beatFrequency = 9.0),  // Вечернее расслабление - нижняя альфа
                        FrequencyPoint.fromHours(21, 0, carrierFrequency = 250.0, beatFrequency = 5.0),  // Подготовка ко сну - тета
                    )
                )
            )
        }
        
        /**
         * Возвращает список предустановленных пресетов
         */
        fun defaultPresets(): List<BinauralPreset> {
            return listOf(defaultPreset(), gammaPreset(), dailyCyclePreset())
        }
    }
}
