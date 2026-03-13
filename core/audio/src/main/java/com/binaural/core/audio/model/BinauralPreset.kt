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
        val DEFAULT_CARRIER = FrequencyRange(50.0, 500.0)
        val DEFAULT_BEAT = FrequencyRange(0.0, 1000.0)
    }
}

/**
 * Тип интерполяции между точками
 */
@Serializable
enum class InterpolationType {
    LINEAR,             // Линейная интерполяция
    CARDINAL,           // Кардинальный сплайн (с параметром tension: 0=Catmull-Rom, 1=линейная)
    MONOTONE,           // Монотонный сплайн (без overshoot, сохраняет форму данных)
    STEP                // Ступенчатая интерполяция (без интерполяции, значение до следующей точки)
}

/**
 * Тип нормализации громкости
 */
@Serializable
enum class NormalizationType {
    NONE,               // Без нормализации
    CHANNEL,            // Канальная нормализация (уравнивание между левым и правым каналом)
    TEMPORAL            // Временная нормализация (уравнивание между точками графика, поверх канальной)
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
    val interpolationType: InterpolationType = InterpolationType.LINEAR,
    val splineTension: Float = 0.0f  // 0.0 = Catmull-Rom (плавный), 1.0 = почти линейный
) {
    // Предварительно отсортированные точки для оптимизации интерполяции
    private val sortedPoints: List<FrequencyPoint> = points.sortedBy { it.time.toSecondOfDay() }
    
    // Массив секунд для быстрого бинарного поиска
    private val pointSeconds: IntArray = sortedPoints.map { it.time.toSecondOfDay() }.toIntArray()
    
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
    
    /**
     * Получить частоту ВЕРХНЕГО канала для заданного времени путём интерполяции
     * Интерполяция применяется НАПРЯМУЮ к кривой канала (carrier + beat/2)
     * Каждая точка кривой канала: carrier + beat/2
     */
    fun getUpperChannelFrequencyAt(time: LocalTime): Double {
        return interpolate(time) { point ->
            point.carrierFrequency + point.beatFrequency / 2.0
        }.coerceAtLeast(0.0)
    }
    
    /**
     * Получить частоту НИЖНЕГО канала для заданного времени путём интерполяции
     * Интерполяция применяется НАПРЯМУЮ к кривой канала (carrier - beat/2)
     * Каждая точка кривой канала: carrier - beat/2
     */
    fun getLowerChannelFrequencyAt(time: LocalTime): Double {
        return interpolate(time) { point ->
            point.carrierFrequency - point.beatFrequency / 2.0
        }.coerceAtLeast(0.0)
    }
    
    private fun interpolate(time: LocalTime, frequencySelector: (FrequencyPoint) -> Double): Double {
        val targetSeconds = time.toSecondOfDay()
        
        // Бинарный поиск для быстрого нахождения интервала
        val intervalIndex = findIntervalIndex(targetSeconds)

        // Если не нашли в обычных интервалах - это переход через полночь
        if (intervalIndex == -1) {
            // Время между последней точкой и первой (переход через полночь)
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
     * Бинарный поиск интервала для заданного времени
     * @return индекс левой границы интервала или -1 если переход через полночь
     */
    private fun findIntervalIndex(targetSeconds: Int): Int {
        // Быстрая проверка границ
        if (targetSeconds < pointSeconds[0] || targetSeconds >= pointSeconds[pointSeconds.size - 1]) {
            return -1 // Переход через полночь
        }
        
        // Бинарный поиск
        var left = 0
        var right = pointSeconds.size - 1
        
        while (left < right - 1) {
            val mid = (left + right) ushr 1
            if (pointSeconds[mid] <= targetSeconds) {
                left = mid
            } else {
                right = mid
            }
        }
        
        return left
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
        
        // Получаем 4 точки для интерполяции
        val p0 = getNeighborPoint(leftIndex, -1, frequencySelector, isWrapping)
        val p1 = frequencySelector(leftPoint)
        val p2 = frequencySelector(rightPoint)
        val p3 = getNeighborPoint(rightIndex, +1, frequencySelector, isWrapping)
        
        // Используем общий объект интерполяции с параметром tension для CARDINAL
        return Interpolation.interpolate(interpolationType, p0, p1, p2, p3, ratio, splineTension)
    }
    
    /**
     * Получить соседнюю точку для сплайна Catmull-Rom
     * Использует циклический переход через границы для получения 4 соседних точек.
     */
    private fun getNeighborPoint(
        currentIndex: Int,
        offset: Int,
        frequencySelector: (FrequencyPoint) -> Double,
        isWrapping: Boolean = false
    ): Double {
        val neighborIndex = currentIndex + offset
        val size = sortedPoints.size

        // Обработка границ массива
        return when {
            neighborIndex < 0 -> {
                if (isWrapping) frequencySelector(sortedPoints.last())
                else frequencySelector(sortedPoints.first())
            }
            neighborIndex >= size -> {
                if (isWrapping) frequencySelector(sortedPoints.first())
                else frequencySelector(sortedPoints.last())
            }
            else -> frequencySelector(sortedPoints[neighborIndex])
        }
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
                ),
                carrierRange = FrequencyRange(100.0, 500.0),
                interpolationType = InterpolationType.MONOTONE
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
    val normalizationType: NormalizationType = NormalizationType.TEMPORAL,  // тип нормализации (временная по умолчанию)
    val volumeNormalizationStrength: Float = 0.5f, // от 0 до 2.0
    // Поля для обратной совместимости
    @kotlinx.serialization.Transient
    val volumeNormalizationEnabled: Boolean = true,  // DEPRECATED: используйте normalizationType
    @kotlinx.serialization.Transient
    val temporalNormalizationEnabled: Boolean = false  // DEPRECATED: используйте normalizationType
) {
    /**
     * Получить текущие частоты для заданного времени
     * Возвращает (частота_биений, несущая_частота)
     */
    fun getFrequenciesAt(time: LocalTime): Pair<Double, Double> {
        val beatFreq = frequencyCurve.getBeatFrequencyAt(time)
        val carrierFreq = frequencyCurve.getCarrierFrequencyAt(time)
        return Pair(beatFreq, carrierFreq)
    }
    
    /**
     * Получить частоты каналов для заданного времени
     * Интерполяция применяется НАПРЯМУЮ к кривым каналов
     * Возвращает (нижняя_частота, верхняя_частота) = (carrier - beat/2, carrier + beat/2)
     * 
     * ВАЖНО: Каждая кривая канала интерполируется отдельно через свои точки!
     */
    fun getChannelFrequenciesAt(time: LocalTime): Pair<Double, Double> {
        val lowerFreq = frequencyCurve.getLowerChannelFrequencyAt(time)
        val upperFreq = frequencyCurve.getUpperChannelFrequencyAt(time)
        return Pair(lowerFreq, upperFreq)
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
 * Настройки режима расслабления для пресета
 */
@Serializable
data class RelaxationModeSettings(
    val enabled: Boolean = false,
    val carrierReductionPercent: Int = 20,  // 5-50%
    val beatReductionPercent: Int = 20      // 5-50%
) {
    init {
        require(carrierReductionPercent in 5..50) { "Снижение несущей частоты должно быть от 5% до 50%" }
        require(beatReductionPercent in 5..50) { "Снижение частоты биений должно быть от 5% до 50%" }
    }
    
    /**
     * Генерирует виртуальные точки режима расслабления между реальными точками.
     * Виртуальные точки создаются посередине между каждой парой соседних точек.
     */
    fun generateVirtualPoints(points: List<FrequencyPoint>): List<FrequencyPoint> {
        if (!enabled || points.size < 2) return emptyList()
        
        val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
        val virtualPoints = mutableListOf<FrequencyPoint>()
        
        val carrierReduction = carrierReductionPercent / 100.0
        val beatReduction = beatReductionPercent / 100.0
        
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
}

/**
 * Настройки нормализации громкости для пресета
 */
@Serializable
data class VolumeNormalizationSettings(
    val type: NormalizationType = NormalizationType.TEMPORAL,  // тип нормализации (временная по умолчанию)
    val strength: Float = 1.0f,            // от 0 до 2.0 (0% - 200%)
    // Поля для обратной совместимости со старыми пресетами
    @kotlinx.serialization.Transient
    val enabled: Boolean = true,           // DEPRECATED: используйте type
    @kotlinx.serialization.Transient  
    val temporalNormalizationEnabled: Boolean = false  // DEPRECATED: используйте type
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
    // Настройки режима расслабления (для каждого пресета отдельно)
    val relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Кэшированная кривая с виртуальными точками расслабления
     * Вычисляется лениво при первом обращении
     */
    @kotlinx.serialization.Transient
    val curveWithRelaxation: FrequencyCurve by lazy {
        if (relaxationModeSettings.enabled) {
            val virtualPoints = relaxationModeSettings.generateVirtualPoints(frequencyCurve.points)
            val allPoints = (frequencyCurve.points + virtualPoints).sortedBy { it.time.toSecondOfDay() }
            FrequencyCurve(
                points = allPoints,
                carrierRange = frequencyCurve.carrierRange,
                beatRange = frequencyCurve.beatRange,
                interpolationType = frequencyCurve.interpolationType,
                splineTension = frequencyCurve.splineTension
            )
        } else {
            frequencyCurve
        }
    }
    
    /**
     * Получить несущую частоту для заданного времени с учётом режима расслабления
     */
    fun getCarrierFrequencyAt(time: LocalTime): Double {
        return curveWithRelaxation.getCarrierFrequencyAt(time)
    }
    
    /**
     * Получить частоту биений для заданного времени с учётом режима расслабления
     */
    fun getBeatFrequencyAt(time: LocalTime): Double {
        return curveWithRelaxation.getBeatFrequencyAt(time)
    }
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
                    ),
                    carrierRange = FrequencyRange(100.0, 500.0),
                    interpolationType = InterpolationType.CARDINAL
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
                    ),
                    carrierRange = FrequencyRange(100.0, 500.0),
                    interpolationType = InterpolationType.CARDINAL
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
