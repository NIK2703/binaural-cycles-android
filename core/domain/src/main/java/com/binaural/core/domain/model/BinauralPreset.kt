package com.binaural.core.domain.model

import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

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
    val min: Float,
    val max: Float
) {
    init {
        require(max > min) { "Максимальная частота должна быть больше минимальной" }
    }
    
    fun contains(value: Float): Boolean = value in min..max
    
    fun clamp(value: Float): Float = value.coerceIn(min, max)
    
    companion object {
        val DEFAULT_CARRIER = FrequencyRange(100.0f, 600.0f)
        val DEFAULT_BEAT = FrequencyRange(0.0f, 1000.0f)
    }
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
    val carrierFrequency: Float,  // Несущая частота (Гц)
    val beatFrequency: Float      // Частота биений (Гц)
) {
    companion object {
        /**
         * Создаёт точку из часов и минут
         */
        fun fromHours(hours: Int, minutes: Int = 0, carrierFrequency: Float, beatFrequency: Float): FrequencyPoint {
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
    fun getCarrierFrequencyAt(time: LocalTime): Float {
        return interpolate(time) { it.carrierFrequency }.coerceAtLeast(0.0f)
    }

    /**
     * Получить частоту биений для заданного времени путём интерполяции
     * Не ограничиваем результат - кубический сплайн может давать значения за пределами точек
     */
    fun getBeatFrequencyAt(time: LocalTime): Float {
        return interpolate(time) { it.beatFrequency }.coerceAtLeast(0.0f)
    }
    
    /**
     * Получить частоту ВЕРХНЕГО канала для заданного времени путём интерполяции
     * Интерполяция применяется НАПРЯМУЮ к кривой канала (carrier + beat/2)
     * Каждая точка кривой канала: carrier + beat/2
     */
    fun getUpperChannelFrequencyAt(time: LocalTime): Float {
        return interpolate(time) { point ->
            point.carrierFrequency + point.beatFrequency / 2.0f
        }.coerceAtLeast(0.0f)
    }
    
    /**
     * Получить частоту НИЖНЕГО канала для заданного времени путём интерполяции
     * Интерполяция применяется НАПРЯМУЮ к кривой канала (carrier - beat/2)
     * Каждая точка кривой канала: carrier - beat/2
     */
    fun getLowerChannelFrequencyAt(time: LocalTime): Float {
        return interpolate(time) { point ->
            point.carrierFrequency - point.beatFrequency / 2.0f
        }.coerceAtLeast(0.0f)
    }
    
    private fun interpolate(time: LocalTime, frequencySelector: (FrequencyPoint) -> Float): Float {
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
        frequencySelector: (FrequencyPoint) -> Float,
        isWrapping: Boolean
    ): Float {
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
        
        val ratio = ((t - t1).toFloat() / (t2 - t1))
        
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
        frequencySelector: (FrequencyPoint) -> Float,
        isWrapping: Boolean = false
    ): Float {
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
        frequencySelector: (FrequencyPoint) -> Float
    ): List<Pair<Int, Float>> {
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
                    FrequencyPoint.fromHours(0, 0, carrierFrequency = 174.0f, beatFrequency = 3.0f),    // Глубокий сон - дельта
                    FrequencyPoint.fromHours(3, 0, carrierFrequency = 210.0f, beatFrequency = 6.0f),    // Лёгкий сон - тета
                    FrequencyPoint.fromHours(6, 0, carrierFrequency = 220.0f, beatFrequency = 8.0f),    // Пробуждение - альфа/тета
                    FrequencyPoint.fromHours(9, 0, carrierFrequency = 440.0f, beatFrequency = 20.0f),   // Пик активности - бета
                    FrequencyPoint.fromHours(12, 0, carrierFrequency = 440.0f, beatFrequency = 25.0f),  // Продуктивность - высокий бета
                    FrequencyPoint.fromHours(15, 0, carrierFrequency = 440.0f, beatFrequency = 18.0f),  // Вторая половина дня - бета
                    FrequencyPoint.fromHours(18, 0, carrierFrequency = 250.0f, beatFrequency = 12.0f),  // Вечерний спад - альфа
                    FrequencyPoint.fromHours(21, 0, carrierFrequency = 240.0f, beatFrequency = 10.0f),  // Подготовка ко сну - альфа
                ),
                carrierRange = FrequencyRange(100.0f, 600.0f),
                interpolationType = InterpolationType.MONOTONE
            )
        }
    }
}

/**
 * Режим перестановки каналов
 */
@Serializable
enum class SwapMode {
    INTERVAL,    // По фиксированному интервалу (существующая логика)
    TENDENCY     // По тенденции изменения частоты (на экстремумах)
}

/**
 * Режим периодов расслабления
 */
@Serializable
enum class RelaxationMode {
    STEP,      // Ступенчатый режим - трапецеидальные впадины по расписанию
    SMOOTH     // Плавный режим - чередующиеся точки с регулируемым интервалом
}

/**
 * Настройки перестановки каналов для пресета
 */
@Serializable
data class ChannelSwapSettings(
    val enabled: Boolean = false,
    val intervalSeconds: Int = 300,        // 5 минут по умолчанию (для режима INTERVAL)
    val fadeEnabled: Boolean = true,       // затухание при смене каналов
    val fadeDurationMs: Long = 1000L,      // длительность затухания/нарастания в мс
    val pauseDurationMs: Long = 0L,        // длительность паузы между fade-out и fade-in в мс (0 = без паузы)
    // Новые параметры для режима TENDENCY
    val swapMode: SwapMode = SwapMode.INTERVAL,  // режим перестановки
    val invertTendencyBehavior: Boolean = false  // инвертировать поведение
)

/**
 * Настройки режима расслабления для пресета
 */
@Serializable
data class RelaxationModeSettings(
    val enabled: Boolean = false,
    val mode: RelaxationMode = RelaxationMode.STEP,
    val carrierReductionPercent: Int = 25,  // 0-50%
    val beatReductionPercent: Int = 50,      // 0-100%
    // Параметры для расширенного режима
    val gapBetweenRelaxationMinutes: Int = 45,  // Интервал МЕЖДУ периодами расслабления: 0-120 минут
    val transitionPeriodMinutes: Int = 3,       // Период перехода (вход/выход): 1-10 минут
    val relaxationDurationMinutes: Int = 15,    // Длительность периода расслабления: 10-60 минут
    // Параметры для плавного режима
    val smoothIntervalMinutes: Int = 30         // Интервал между точками: 5-60 минут
) {
    init {
        require(carrierReductionPercent in 0..50) { "Снижение несущей частоты должно быть от 0% до 50%" }
        require(beatReductionPercent in 0..100) { "Снижение частоты биений должно быть от 0% до 100%" }
        require(gapBetweenRelaxationMinutes in 0..120) { "Интервал между периодами расслабления должен быть от 0 до 120 минут" }
        require(transitionPeriodMinutes in 1..10) { "Период перехода должен быть от 1 до 10 минут" }
        require(relaxationDurationMinutes in 5..60) { "Длительность периода расслабления должна быть от 5 до 60 минут" }
        require(smoothIntervalMinutes in 5..120) { "Интервал между точками должен быть от 5 до 120 минут" }
    }
    
    companion object {
        private const val MIN_AUDIBLE_FREQUENCY = 20.0f
    }
    
    /**
     * Генерирует виртуальные точки режима расслабления.
     * Для ADVANCED режима: 4 точки на каждый период расслабления, образующие трапецию.
     * Для SMOOTH режима: чередующиеся точки (базовая → снижающая → базовая → снижающая).
     * 
     * @param curve Базовая кривая частот (из основных точек)
     */
    fun generateVirtualPoints(curve: FrequencyCurve): List<FrequencyPoint> {
        if (!enabled || curve.points.size < 2) return emptyList()
        
        return when (mode) {
            RelaxationMode.STEP -> generateStepVirtualPoints(curve, curve.carrierRange)
            RelaxationMode.SMOOTH -> generateSmoothVirtualPoints(curve, curve.carrierRange)
        }
    }
    
    
    /**
     * Ступенчатый режим: генерация виртуальных точек по расписанию.
     * Создаётся группа из 4 точек для каждого периода расслабления:
     * - Точка 1: на базовой кривой (начало периода)
     * - Точка 2: сниженные частоты (после перехода)
     * - Точка 3: сниженные частоты (конец расслабления)
     * - Точка 4: на базовой кривой (после выхода)
     * 
     * Между периодами расслабления есть пауза gapBetweenRelaxationMinutes.
     * Итоговая кривая строится ТОЛЬКО по этим виртуальным точкам.
     * 
     * @param carrierRange Диапазон несущей частоты графика (сниженная частота не опускается ниже min)
     */
    private fun generateStepVirtualPoints(curve: FrequencyCurve, carrierRange: FrequencyRange): List<FrequencyPoint> {
        val virtualPoints = mutableListOf<FrequencyPoint>()
        
        val carrierReduction = carrierReductionPercent / 100.0f
        val beatReduction = beatReductionPercent / 100.0f
        
        val gapSeconds = gapBetweenRelaxationMinutes * 60L
        val transitionSeconds = transitionPeriodMinutes * 60L
        val durationSeconds = relaxationDurationMinutes * 60L
        
        // Полный период расслабления = 2 * переход + длительность
        val fullPeriodSeconds = 2 * transitionSeconds + durationSeconds
        
        // Генерируем периоды расслабления от 00:00
        val daySeconds = 24 * 3600L
        
        var periodStartSeconds = 0L
        
        while (periodStartSeconds < daySeconds) {
            // Точка 1: начало периода (на базовой кривой)
            val t1 = periodStartSeconds
            val time1 = LocalTime.fromSecondOfDay((t1 % daySeconds).toInt())
            val carrier1 = curve.getCarrierFrequencyAt(time1)
            val beat1 = curve.getBeatFrequencyAt(time1)
            virtualPoints.add(FrequencyPoint(time1, carrier1, beat1))
            
            // Точка 2: после перехода (сниженные частоты)
            val t2 = periodStartSeconds + transitionSeconds
            if (t2 < daySeconds) {
                val time2 = LocalTime.fromSecondOfDay((t2 % daySeconds).toInt())
                val baseCarrier2 = curve.getCarrierFrequencyAt(time2)
                val baseBeat2 = curve.getBeatFrequencyAt(time2)
                // Ограничиваем сниженную частоту снизу границей графика и физическим минимумом
                val reducedCarrier2 = (baseCarrier2 * (1.0f - carrierReduction))
                    .coerceAtLeast(carrierRange.min)
                    .coerceAtLeast(MIN_AUDIBLE_FREQUENCY)
                virtualPoints.add(FrequencyPoint(
                    time2,
                    reducedCarrier2,
                    baseBeat2 * (1.0f - beatReduction)
                ))
            }
            
            // Точка 3: конец расслабления (сниженные частоты)
            val t3 = periodStartSeconds + transitionSeconds + durationSeconds
            if (t3 < daySeconds) {
                val time3 = LocalTime.fromSecondOfDay((t3 % daySeconds).toInt())
                val baseCarrier3 = curve.getCarrierFrequencyAt(time3)
                val baseBeat3 = curve.getBeatFrequencyAt(time3)
                // Ограничиваем сниженную частоту снизу границей графика и физическим минимумом
                val reducedCarrier3 = (baseCarrier3 * (1.0f - carrierReduction))
                    .coerceAtLeast(carrierRange.min)
                    .coerceAtLeast(MIN_AUDIBLE_FREQUENCY)
                virtualPoints.add(FrequencyPoint(
                    time3,
                    reducedCarrier3,
                    baseBeat3 * (1.0f - beatReduction)
                ))
            }
            
            // Точка 4: после выхода (на базовой кривой)
            val t4 = periodStartSeconds + fullPeriodSeconds
            if (t4 < daySeconds) {
                val time4 = LocalTime.fromSecondOfDay((t4 % daySeconds).toInt())
                val carrier4 = curve.getCarrierFrequencyAt(time4)
                val beat4 = curve.getBeatFrequencyAt(time4)
                virtualPoints.add(FrequencyPoint(time4, carrier4, beat4))
            }
            
            // Переходим к следующему периоду: полный период + пауза между периодами
            periodStartSeconds += fullPeriodSeconds + gapSeconds
        }
        
        // Сортируем по времени
        return virtualPoints.sortedBy { it.time.toSecondOfDay() }
    }
    
    /**
     * Плавный режим: чередующиеся точки (базовая → снижающая → базовая → снижающая).
     * Интервал между точками регулируется параметром smoothIntervalMinutes.
     * Итоговая кривая строится ТОЛЬКО по этим виртуальным точкам.
     * 
     * @param carrierRange Диапазон несущей частоты графика (сниженная частота не опускается ниже min)
     */
    private fun generateSmoothVirtualPoints(curve: FrequencyCurve, carrierRange: FrequencyRange): List<FrequencyPoint> {
        val virtualPoints = mutableListOf<FrequencyPoint>()
        
        val carrierReduction = carrierReductionPercent / 100.0f
        val beatReduction = beatReductionPercent / 100.0f
        val intervalSeconds = smoothIntervalMinutes * 60L
        val daySeconds = 24 * 3600L
        
        // Генерируем точки от 00:00 до 23:59 с заданным интервалом
        // Чётные индексы (0, 2, 4...) - точки на базовой кривой
        // Нечётные индексы (1, 3, 5...) - снижающие точки
        
        var currentSeconds = 0L
        var index = 0
        
        while (currentSeconds < daySeconds) {
            val time = LocalTime.fromSecondOfDay((currentSeconds % daySeconds).toInt())
            
            if (index % 2 == 0) {
                // Чётный индекс - точка на базовой кривой
                val carrier = curve.getCarrierFrequencyAt(time)
                val beat = curve.getBeatFrequencyAt(time)
                virtualPoints.add(FrequencyPoint(time, carrier, beat))
            } else {
                // Нечётный индекс - снижающая точка
                val baseCarrier = curve.getCarrierFrequencyAt(time)
                val baseBeat = curve.getBeatFrequencyAt(time)
                // Ограничиваем сниженную частоту снизу границей графика и физическим минимумом
                val reducedCarrier = (baseCarrier * (1.0f - carrierReduction))
                    .coerceAtLeast(carrierRange.min)
                    .coerceAtLeast(MIN_AUDIBLE_FREQUENCY)
                virtualPoints.add(FrequencyPoint(
                    time,
                    reducedCarrier,
                    baseBeat * (1.0f - beatReduction)
                ))
            }
            
            currentSeconds += intervalSeconds
            index++
        }
        
        return virtualPoints.sortedBy { it.time.toSecondOfDay() }
    }
}

/**
 * Настройки нормализации громкости для пресета
 */
@Serializable
data class VolumeNormalizationSettings(
    val type: NormalizationType = NormalizationType.TEMPORAL,  // тип нормализации (временная по умолчанию)
    val strength: Float = 1.0f            // от 0 до 2.0 (0% - 200%)
)

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
    val channelSwapPauseDurationMs: Long = 0L, // длительность паузы между fade-out и fade-in (0 = без паузы)
    // Настройки нормализации громкости
    val normalizationType: NormalizationType = NormalizationType.TEMPORAL,  // тип нормализации (временная по умолчанию)
    val volumeNormalizationStrength: Float = 0.5f, // от 0 до 2.0
    // Новые параметры для режима TENDENCY
    val channelSwapMode: SwapMode = SwapMode.INTERVAL,
    val invertTendencyBehavior: Boolean = false
) {
    /**
     * Получить текущие частоты для заданного времени
     * Возвращает (частота_биений, несущая_частота)
     */
    fun getFrequenciesAt(time: LocalTime): Pair<Float, Float> {
        val beatFreq = frequencyCurve.getBeatFrequencyAt(time)
        val carrierFreq = frequencyCurve.getCarrierFrequencyAt(time)
        return Pair(beatFreq, carrierFreq)
    }
    
    /**
     * Получить частоты каналов для заданного времени
     */
    fun getChannelFrequenciesAt(time: LocalTime): Pair<Float, Float> {
        val lowerFreq = frequencyCurve.getLowerChannelFrequencyAt(time)
        val upperFreq = frequencyCurve.getUpperChannelFrequencyAt(time)
        return Pair(lowerFreq, upperFreq)
    }
}

/**
 * Состояние воспроизведения.
 * Используется как единый источник правды для UI и сервиса.
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentBeatFrequency: Float = 0.0f,
    val currentCarrierFrequency: Float = 0.0f,
    val isChannelsSwapped: Boolean = false,
    val elapsedSeconds: Int = 0,
    val currentPresetName: String? = null,
    val currentPresetId: String? = null,
    val config: BinauralConfig = BinauralConfig(),
    val volume: Float = 0.7f
)

/**
 * Пресет бинаурального ритма - сохранённая конфигурация с названием
 */
@Serializable
data class BinauralPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val frequencyCurve: FrequencyCurve,
    // Настройки режима расслабления (для каждого пресета отдельно)
    val relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * Кэшированная кривая с виртуальными точками расслабления
     * Вычисляется лениво при первом обращении.
     * 
     * Для SIMPLE режима: объединяются реальные и виртуальные точки.
     * Для ADVANCED режима: используются ТОЛЬКО виртуальные точки
     * (реальные точки нужны только для расчёта базовой кривой).
     */
    @kotlinx.serialization.Transient
    val curveWithRelaxation: FrequencyCurve by lazy {
        if (relaxationModeSettings.enabled) {
            val virtualPoints = relaxationModeSettings.generateVirtualPoints(frequencyCurve)
            
            when (relaxationModeSettings.mode) {
                RelaxationMode.STEP, RelaxationMode.SMOOTH -> {
                    // Ступенчатый и плавный режимы: ТОЛЬКО виртуальные точки
                    // Если виртуальных точек меньше 2, используем базовую кривую
                    if (virtualPoints.size >= 2) {
                        FrequencyCurve(
                            points = virtualPoints,
                            carrierRange = frequencyCurve.carrierRange,
                            beatRange = frequencyCurve.beatRange,
                            interpolationType = frequencyCurve.interpolationType,
                            splineTension = frequencyCurve.splineTension
                        )
                    } else {
                        frequencyCurve
                    }
                }
            }
        } else {
            frequencyCurve
        }
    }
    
    /**
     * Получить несущую частоту для заданного времени с учётом режима расслабления
     */
    fun getCarrierFrequencyAt(time: LocalTime): Float {
        return curveWithRelaxation.getCarrierFrequencyAt(time)
    }
    
    /**
     * Получить частоту биений для заданного времени с учётом режима расслабления
     */
    fun getBeatFrequencyAt(time: LocalTime): Float {
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
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 220.0f, beatFrequency = 1.5f),   // Глубокий сон - дельта
                        FrequencyPoint.fromHours(3, 0, carrierFrequency = 250.0f, beatFrequency = 5.0f),   // Лёгкий сон - тета
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 340.0f, beatFrequency = 9.0f),   // Пробуждение - альфа
                        FrequencyPoint.fromHours(9, 0, carrierFrequency = 400.0f, beatFrequency = 18.0f),  // Пик активности - бета
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 380.0f, beatFrequency = 14.0f), // Поддержание внимания - бета/альфа
                        FrequencyPoint.fromHours(15, 0, carrierFrequency = 440.0f, beatFrequency = 40.0f), // Второй пик - гамма
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 300.0f, beatFrequency = 7.5f),  // Расслабление - альфа/тета
                        FrequencyPoint.fromHours(21, 0, carrierFrequency = 240.0f, beatFrequency = 4.0f),  // Подготовка ко сну - тета
                    ),
                    carrierRange = FrequencyRange(100.0f, 600.0f),
                    interpolationType = InterpolationType.MONOTONE
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
                        FrequencyPoint.fromHours(0, 0, carrierFrequency = 200.0f, beatFrequency = 2.0f),   // Глубокий сон - дельта
                        FrequencyPoint.fromHours(3, 0, carrierFrequency = 200.0f, beatFrequency = 3.0f),   // Подготовка к пробуждению - дельта-тета
                        FrequencyPoint.fromHours(6, 0, carrierFrequency = 300.0f, beatFrequency = 10.0f),  // Спокойное пробуждение - альфа
                        FrequencyPoint.fromHours(9, 0, carrierFrequency = 400.0f, beatFrequency = 18.0f),  // Пик концентрации - бета
                        FrequencyPoint.fromHours(12, 0, carrierFrequency = 300.0f, beatFrequency = 6.0f),  // Креативная перезагрузка - тета
                        FrequencyPoint.fromHours(15, 0, carrierFrequency = 400.0f, beatFrequency = 25.0f), // Максимальная продуктивность - верхний бета
                        FrequencyPoint.fromHours(18, 0, carrierFrequency = 300.0f, beatFrequency = 9.0f),  // Вечернее расслабление - нижняя альфа
                        FrequencyPoint.fromHours(21, 0, carrierFrequency = 250.0f, beatFrequency = 5.0f),  // Подготовка ко сну - тета
                    ),
                    carrierRange = FrequencyRange(100.0f, 600.0f),
                    interpolationType = InterpolationType.MONOTONE
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