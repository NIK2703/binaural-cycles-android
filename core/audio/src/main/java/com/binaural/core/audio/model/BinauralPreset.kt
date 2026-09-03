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
import kotlin.math.abs
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
        /**
         * Частота биений — величина ЗНАКОВАЯ (см. [FrequencyPoint.beatFrequency]),
         * поэтому диапазон симметричен относительно нуля.
         */
        val DEFAULT_BEAT = FrequencyRange(
            -FrequencyMath.MAX_BEAT_MAGNITUDE,
            FrequencyMath.MAX_BEAT_MAGNITUDE
        )
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
 *
 * ЧАСТОТА БИЕНИЙ — ЗНАКОВАЯ: beat = частота_правого − частота_левого.
 * - beat > 0 — правый канал выше левого (обычная раскладка);
 * - beat < 0 — каналы поменяны местами, правый ниже левого;
 * - слышимая пульсация — |beat|, знак задаёт только раскладку каналов.
 *
 * Канальные частоты: левый = carrier − beat/2, правый = carrier + beat/2
 * (см. [FrequencyMath]). При смене знака beat они меняются местами, поэтому
 * обе формулы корректны для любого знака, а ограничение накладывается только
 * на модуль: |beat| <= 2 * (carrier - 20 Гц), чтобы ни один канал не ушёл
 * ниже слышимого диапазона.
 */
@Serializable
data class FrequencyPoint(
    @Serializable(with = LocalTimeSerializer::class)
    val time: LocalTime,           // Время суток
    val carrierFrequency: Float,  // Несущая частота (Гц)
    val beatFrequency: Float      // Частота биений (Гц, может быть отрицательной)
) {
    /**
     * Частота ЛЕВОГО канала: carrier − beat/2.
     * При beat < 0 это более высокий тон (каналы поменяны местами).
     */
    val leftChannelFrequency: Float
        get() = FrequencyMath.leftChannelFrequency(carrierFrequency, beatFrequency)

    /**
     * Частота ПРАВОГО канала: carrier + beat/2.
     * При beat < 0 это более низкий тон (каналы поменяны местами).
     */
    val rightChannelFrequency: Float
        get() = FrequencyMath.rightChannelFrequency(carrierFrequency, beatFrequency)

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

    /**
     * Веса касательных кардинального сплайна — регулировка overshoot.
     *
     * Считаются ОДИН РАЗ на объект кривой: [sortedPoints], тип интерполяции,
     * натяжение и вертикальные границы [carrierRange] — всё это неизменяемые
     * поля конструктора, так что кэш всегда согласован с кривой.
     *
     * null означает «регулировка не нужна»: тип не CARDINAL либо номинальный
     * сплайн никуда не вылетает. Это же значение уезжает в нативный движок
     * (пустой массив ⇒ движок строит таблицу с номинальными касательными),
     * поэтому звук и график получают РОВНО ОДИН И ТОТ ЖЕ результат по
     * построению: вторая реализация алгоритма просто не существует.
     *
     * Веса ОБЩИЕ для обоих каналов — см. [CardinalTension]. Благодаря этому
     * каналы не схлопываются в точке касания с границей: beat(t) остаётся
     * точной интерполяцией узлов частоты биений, а carrier(t) — узлов несущей.
     *
     * Индексируются по [sortedPoints] (порядок по времени суток).
     */
    val tensionWeights: FloatArray? = CardinalTension.forPoints(
        points = sortedPoints,
        type = interpolationType,
        tension = splineTension,
        carrierRange = carrierRange,
        presorted = true
    )

    init {
        require(points.size >= 1) { "Кривая должна содержать минимум 1 точку" }
    }

    /**
     * Диапазон частот биений, симметричный относительно нуля.
     *
     * Пресеты, сохранённые до появления отрицательных частот биений, держат
     * beatRange = (0; 1000). Зеркальное расширение делает знак доступным
     * независимо от версии пресета.
     *
     * ВАЖНО: это МАСШТАБ ОТРИСОВКИ (размер маркера точки на графике), а НЕ
     * разрешённый предел частоты биений. Предел модуля всегда выводится из
     * геометрии — удвоенное расстояние от несущей до ближайшей границы
     * (см. FrequencyMath.maxBeatMagnitude / FrequencyMath.UNBOUNDED_BEAT_RANGE).
     * Иначе старый потолок 1000 Гц продолжал бы резать выбор пользователя.
     */
    val effectiveBeatRange: FrequencyRange
        get() = FrequencyMath.symmetricBeatRange(beatRange)

    /**
     * Получить несущую частоту для заданного времени путём интерполяции
     * Не ограничиваем результат - кубический сплайн может давать значения за пределами точек
     *
     * Несущая — физическая частота тона, отрицательной быть не может.
     */
    fun getCarrierFrequencyAt(time: LocalTime): Float {
        return interpolate(time) { it.carrierFrequency }.coerceAtLeast(0.0f)
    }

    /**
     * Получить частоту биений для заданного времени путём интерполяции.
     * Результат ЗНАКОВЫЙ: отрицательная частота биений означает, что каналы
     * поменяны местами (правый тон ниже левого). Кламп к >= 0 здесь
     * недопустим — он уничтожил бы знак и раскладку каналов.
     *
     * Не ограничиваем результат - кубический сплайн может давать значения за пределами точек
     */
    fun getBeatFrequencyAt(time: LocalTime): Float {
        return interpolate(time, allowNegative = true) { it.beatFrequency }
    }

    /**
     * Частоты каналов (левый, правый) для заданного времени.
     * Каждая канальная кривая интерполируется отдельно, как в движке:
     * левый = carrier − beat/2, правый = carrier + beat/2.
     *
     * При отрицательной частоте биений каналы меняются местами автоматически —
     * отдельной обработки знака не требуется.
     *
     * @return Pair(левый канал, правый канал)
     */
    fun getChannelFrequenciesAt(time: LocalTime): Pair<Float, Float> =
        Interpolation.interpolateChannels(
            sortedPoints, time, interpolationType, splineTension,
            presorted = true, weights = tensionWeights
        )

    /**
     * Получить частоту ПРАВОГО канала для заданного времени путём интерполяции
     * Интерполяция применяется НАПРЯМУЮ к кривой канала (carrier + beat/2)
     * Каждая точка кривой канала: carrier + beat/2
     *
     * ВАЖНО: при beat < 0 это БОЛЕЕ НИЗКИЙ тон — имя сохраняется за формулой,
     * а не за взаимным расположением каналов.
     */
    fun getUpperChannelFrequencyAt(time: LocalTime): Float {
        return getChannelFrequenciesAt(time).second
    }

    /**
     * Получить частоту ЛЕВОГО канала для заданного времени путём интерполяции
     * Интерполяция применяется НАПРЯМУЮ к кривой канала (carrier - beat/2)
     * Каждая точка кривой канала: carrier - beat/2
     *
     * ВАЖНО: при beat < 0 это БОЛЕЕ ВЫСОКИЙ тон — имя сохраняется за формулой,
     * а не за взаимным расположением каналов.
     */
    fun getLowerChannelFrequencyAt(time: LocalTime): Float {
        return getChannelFrequenciesAt(time).first
    }

    /**
     * Допустимый интервал частоты биений для точки с заданной несущей.
     *
     * Предел — ГЕОМЕТРИЧЕСКИЙ: физика (каналы в 20…2000 Гц) ∩ вертикальные
     * границы графика. Хранимый beatRange в расчёт не входит: он задаёт только
     * масштаб маркеров и не должен резать выбор пользователя.
     * Интервал симметричен по модулю.
     */
    fun beatBoundsForCarrier(carrierFrequency: Float): ClosedFloatingPointRange<Float> =
        FrequencyMath.beatBounds(carrierFrequency, carrierRange = carrierRange)

    /**
     * Привести частоту биений к допустимому интервалу для заданной несущей.
     * Знак сохраняется, клампится только модуль; предел берётся из геометрии.
     */
    fun clampBeatForCarrier(carrierFrequency: Float, beatFrequency: Float): Float =
        FrequencyMath.clampBeat(carrierFrequency, beatFrequency, carrierRange = carrierRange)

    /**
     * @param allowNegative false (по умолчанию) — результат клампится к >= 0
     *        (физические частоты: несущая и каналы). true — знак сохраняется
     *        (частота биений).
     */
    private fun interpolate(
        time: LocalTime,
        allowNegative: Boolean = false,
        frequencySelector: (FrequencyPoint) -> Float
    ): Float {
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
                allowNegative,
                isWrapping = true,
                weights = tensionWeights
            )
        }

        return interpolateBetweenPoints(
            sortedPoints,
            intervalIndex,
            intervalIndex + 1,
            time,
            frequencySelector,
            allowNegative,
            isWrapping = false,
            weights = tensionWeights
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
        allowNegative: Boolean = false,
        isWrapping: Boolean,
        weights: FloatArray? = null
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

        // Кламп ratio [0,1] — согласовано с C++ (buildLookupTableInternal)
        val ratio = ((t - t1).toFloat() / (t2 - t1)).coerceIn(0.0f, 1.0f)
        
        // Получаем 4 точки для интерполяции
        val p0 = getNeighborPoint(leftIndex, -1, frequencySelector, isWrapping)
        val p1 = frequencySelector(leftPoint)
        val p2 = frequencySelector(rightPoint)
        val p3 = getNeighborPoint(rightIndex, +1, frequencySelector, isWrapping)

        // Веса те же, что у канальных кривых: сплайн линеен, поэтому
        // carrier = (left+right)/2 и beat = right−left интерполируются
        // РОВНО теми же весами, что и каналы. Иначе несущая и частота биений
        // разошлись бы с нарисованными каналами.
        val w = if (weights != null && weights.size == sortedPoints.size) weights else null
        val w1 = w?.get(leftIndex) ?: 1.0f
        val w2 = w?.get(rightIndex) ?: 1.0f

        // Используем общий объект интерполяции с параметром tension для CARDINAL.
        // allowNegative=false клампит результат к >= 0 (несущая и каналы —
        // физические частоты); для частоты биений знак сохраняется.
        return Interpolation.interpolate(
            interpolationType, p0, p1, p2, p3, ratio, splineTension,
            allowNegative = allowNegative, w1 = w1, w2 = w2
        )
    }
    
    /**
     * Получить соседнюю точку для сплайна Catmull-Rom
     * Использует циклический переход через границы (mod size) для получения 4 соседних точек.
     * Согласовано с C++ реализацией (buildLookupTableInternal), которая всегда берёт
     * соседей циклически: (leftIndex - 1 + n) % n и (rightIndex + 1) % n.
     */
    private fun getNeighborPoint(
        currentIndex: Int,
        offset: Int,
        frequencySelector: (FrequencyPoint) -> Float,
        isWrapping: Boolean = false
    ): Float {
        val size = sortedPoints.size
        // Циклический доступ — согласован с C++ (wrap-соседи всегда по модулю size)
        val neighborIndex = ((currentIndex + offset) % size + size) % size
        return frequencySelector(sortedPoints[neighborIndex])
    }
    
    
    /**
     * Получить все интерполированные значения для отображения на графике
     * Возвращает список пар (время в секундах, значение) для указанного селектора частоты
     */
    fun getInterpolatedValues(
        numSamples: Int = 100,
        allowNegative: Boolean = false,
        frequencySelector: (FrequencyPoint) -> Float
    ): List<Pair<Int, Float>> {
        return (0..numSamples).map { i ->
            val t = i.toDouble() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            time.toSecondOfDay() to interpolate(time, allowNegative, frequencySelector)
        }
    }

    companion object {
        /**
         * Кривая для НОВОГО пресета (пустой шаблон).
         *
         * Одна точка в полдень (12:00) с несущей 200 Гц и частотой биений 16 Гц.
         * Редактор позволяет удалить все точки кроме одной, поэтому одноточечная
         * кривая — допустимое состояние (см. require(points.size >= 1) выше).
         * Диапазон несущей по умолчанию: 100…600 Гц.
         */
        fun newPresetCurve(): FrequencyCurve {
            return FrequencyCurve(
                points = listOf(
                    FrequencyPoint.fromHours(12, 0, carrierFrequency = 200.0f, beatFrequency = 16.0f)
                ),
                carrierRange = FrequencyRange(100.0f, 600.0f),
                beatRange = FrequencyRange(
                    -FrequencyMath.MAX_BEAT_MAGNITUDE,
                    FrequencyMath.MAX_BEAT_MAGNITUDE
                ),
                interpolationType = InterpolationType.MONOTONE
            )
        }

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
 * Конфигурация бинаурального ритма
 */
data class BinauralConfig(
    val frequencyCurve: FrequencyCurve = FrequencyCurve.defaultCurve(),
    val volume: Float = 1.0f,
    // Настройки перестановки каналов
    val channelSwapEnabled: Boolean = false,
    val channelSwapIntervalSeconds: Int = 300, // 5 минут по умолчанию (только TIMER)
    val channelSwapMode: ChannelSwapMode = ChannelSwapMode.TIMER,
    val channelSwapTrendPoints: ChannelSwapTrendPoints = ChannelSwapTrendPoints.BOTH,
    val channelSwapFadeEnabled: Boolean = true, // затухание при смене каналов
    val channelSwapFadeDurationMs: Long = 1000L, // длительность затухания/нарастания в миллисекундах
    val channelSwapPauseDurationMs: Long = 0L, // длительность паузы между fade-out и fade-in (0 = без паузы)
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
    fun getFrequenciesAt(time: LocalTime): Pair<Float, Float> {
        val beatFreq = frequencyCurve.getBeatFrequencyAt(time)
        val carrierFreq = frequencyCurve.getCarrierFrequencyAt(time)
        return Pair(beatFreq, carrierFreq)
    }
    
    /**
     * Получить частоты каналов для заданного времени
     * Интерполяция применяется НАПРЯМУЮ к кривым каналов
     * Возвращает (левый_канал, правый_канал) = (carrier - beat/2, carrier + beat/2)
     *
     * ВАЖНО: Каждая кривая канала интерполируется отдельно через свои точки!
     * При beat < 0 каналы меняются местами — это и есть отрицательная частота
     * биений, поэтому знак здесь терять нельзя.
     */
    fun getChannelFrequenciesAt(time: LocalTime): Pair<Float, Float> {
        return frequencyCurve.getChannelFrequenciesAt(time)
    }
}

/**
 * Состояние плеера
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val config: BinauralConfig = BinauralConfig(),
    val elapsedSeconds: Int = 0,
    val volume: Float = 1.0f
)

/**
 * Режим автоматической перестановки каналов
 */
@Serializable
enum class ChannelSwapMode {
    TIMER,  // По таймеру: перестановка каждые intervalSeconds секунд
    TREND   // По тенденции графика: несущая растёт - обычное расположение каналов,
            // убывает - обратное; интервал не участвует, защита от дребезга -
            // только мёртвая зона производной
}
/**
 * Точки графика, в которых происходит перестановка каналов в TREND-режиме.
 * BOTH — на каждом локальном экстремуме (пиках и впадинах);
 * PEAKS — только на пиках; TROUGHS — только на впадинах.
 */
@Serializable
enum class ChannelSwapTrendPoints {
    BOTH,    // На пиках и впадинах (текущее поведение)
    PEAKS,   // Только на пиках
    TROUGHS  // Только на впадинах
}

/**
 * Настройки перестановки каналов для пресета
 */
@Serializable
data class ChannelSwapSettings(
    val enabled: Boolean = false,
    val mode: ChannelSwapMode = ChannelSwapMode.TIMER,
    val trendPoints: ChannelSwapTrendPoints = ChannelSwapTrendPoints.BOTH,
    val intervalSeconds: Int = 300,        // 5 минут по умолчанию (только TIMER)
    val fadeEnabled: Boolean = true,       // затухание при смене каналов
    val fadeDurationMs: Long = 1000L,      // длительность затухания/нарастания в мс
    val pauseDurationMs: Long = 0L         // длительность паузы между fade-out и fade-in в мс (0 = без паузы)
)

/**
 * Режим периодов расслабления
 */
@Serializable
enum class RelaxationMode {
    STEP,      // Ступенчатый режим - трапецеидальные впадины по расписанию
    SMOOTH     // Плавный режим - чередующиеся точки с регулируемым интервалом
}

/**
 * Настройки режима расслабления для пресета
 */
@Serializable
data class RelaxationModeSettings(
    val enabled: Boolean = false,
    val mode: RelaxationMode = RelaxationMode.STEP,
    /**
     * Снижение несущей частоты в периодах расслабления: 0-100%.
     *
     * ПОЛ — не минимум несущей, а минимум частоты КАНАЛА: сниженная точка
     * обязана остаться внутри нарисованного графика, поэтому нижняя боковая
     * (carrier − |beat|/2) не уходит ниже минимума диапазона пресета
     * ([FrequencyCurve.carrierRange].min). Абсолютный пол 20 Гц действует
     * всегда, даже если минимум графика задан ниже.
     *
     * На 100% несущая опускается НАСТОЛЬКО НИЗКО, НАСКОЛЬКО ПОЗВОЛЯЕТ
     * частота биений: carrier = min + |beat'|/2, то есть нижняя боковая
     * садится ровно на минимум. Полнее нельзя — иначе биения пришлось бы
     * погасить, а период расслабления выродился бы в чистый тон без
     * бинаурального эффекта. При полностью погашенных биениях
     * (beatReductionPercent = 100%) несущая на 100% доходит до минимума.
     */
    val carrierReductionPercent: Int = 25,  // 0-100%
    /**
     * Снижение частоты биений в периодах расслабления: 0-200%.
     *
     * Свыше 100% величина уходит за ноль: биения сначала гаснут (ровно 100%),
     * затем нарастают снова, но с ОБРАТНЫМ знаком — каналы меняются местами.
     * На 200% модуль равен исходному, раскладка каналов инвертирована.
     * Знак имеет смысл только благодаря знаковой семантике beat = right − left.
     */
    val beatReductionPercent: Int = 50,      // 0-200%
    // Параметры для расширенного режима
    val gapBetweenRelaxationMinutes: Int = 45,  // Интервал МЕЖДУ периодами расслабления: 0-120 минут
    val transitionPeriodMinutes: Int = 3,       // Период перехода (вход/выход): 1-10 минут
    val relaxationDurationMinutes: Int = 15,    // Длительность периода расслабления: 10-60 минут
    // Параметры для плавного режима
    val smoothIntervalMinutes: Int = 30         // Интервал между точками: 5-60 минут
) {
    companion object {
        /**
         * Верхний предел снижения несущей частоты (%).
         *
         * 100% означает «опустить несущую к минимуму диапазона пресета
         * настолько, насколько позволяет частота биений» — ниже частота
         * канала не уйдёт благодаря полу в [reduceFrequencies].
         */
        const val MAX_CARRIER_REDUCTION_PERCENT = 100

        /**
         * Верхний предел снижения частоты биений (%).
         *
         * 100% гасит биения полностью; всё, что выше, — инверсия каналов
         * (модуль нарастает снова с обратным знаком). Предел живёт здесь,
         * чтобы модель, ViewModel и слайдер в UI не расходились.
         */
        const val MAX_BEAT_REDUCTION_PERCENT = 200
    }

    init {
        require(carrierReductionPercent in 0..MAX_CARRIER_REDUCTION_PERCENT) {
            "Снижение несущей частоты должно быть от 0% до $MAX_CARRIER_REDUCTION_PERCENT%"
        }
        require(beatReductionPercent in 0..MAX_BEAT_REDUCTION_PERCENT) {
            "Снижение частоты биений должно быть от 0% до $MAX_BEAT_REDUCTION_PERCENT%"
        }
        require(gapBetweenRelaxationMinutes in 0..120) { "Интервал между периодами расслабления должен быть от 0 до 120 минут" }
        require(transitionPeriodMinutes in 1..10) { "Период перехода должен быть от 1 до 10 минут" }
        require(relaxationDurationMinutes in 5..60) { "Длительность периода расслабления должна быть от 5 до 60 минут" }
        require(smoothIntervalMinutes in 5..120) { "Интервал между точками должен быть от 5 до 120 минут" }
    }
    
    /**
     * Генерирует виртуальные точки режима расслабления по кривой.
     * Для ADVANCED режима: 4 точки на каждый период расслабления, образующие трапецию.
     * Для SMOOTH режима: чередующиеся точки (базовая → снижающая → базовая → снижающая).
     *
     * @param curve Базовая кривая частот (из основных точек)
     */
    fun generateVirtualPoints(curve: FrequencyCurve): List<FrequencyPoint> =
        generateVirtualPoints(curve.points, curve.interpolationType, curve.splineTension, curve.carrierRange)

    /**
     * Снижает частоты на период расслабления.
     *
     * Знак частоты биений при снижении ДО 100% СОХРАНЯЕТСЯ: гаснет модуль, а
     * раскладка каналов (какое ухо слышит более высокий тон) не меняется — иначе
     * режим расслабления молча переставлял бы каналы местами на отрицательных
     * участках кривой.
     *
     * Формула `beat * (1 - beatReduction)` продолжается и СВЫШЕ 100%:
     * - 100% — биения полностью погашены (beat = 0, чистый тон без пульсации);
     * - 150% — beat = −0.5 * beat₀, каналы поменяны местами, модуль вдвое меньше;
     * - 200% — beat = −beat₀, модуль исходный, раскладка каналов инвертирована.
     * Это осознанное следствие знаковой семантики beat = right − left, а не
     * ошибка: физически инверсия валидна (боковые частоты просто меняются
     * ролями), поэтому знаковый результат НЕ клампится к нулю.
     *
     * Несущая не уходит ниже слышимого минимума; если из-за этого клампа
     * соотношение carrier/beat нарушается, модуль beat приводится к физически
     * допустимому (боковая частота не должна уйти ниже 0 Гц). При снижении выше
     * 100% это ограничение может дополнительно урезать модуль инвертированных
     * биений — сниженная несущая физически не держит прежний разнос каналов.
     *
     * ПОЛ ЧАСТОТЫ ([minCarrier]) — ограничение накладывается на ФАКТИЧЕСКУЮ
     * частоту канала, а не только на несущую: нижняя боковая
     * (carrier − |beat|/2) обязана остаться не ниже минимума диапазона
     * пресета, иначе кривая уезжает за нижнюю границу графика. Абсолютный пол
     * 20 Гц при этом сохраняется (минимум графика можно задать и ниже
     * слышимого диапазона).
     *
     * Пол выполняется ПОДЪЁМОМ несущей, а не гашением биений: сниженная
     * несущая опускается лишь до `floor + |beat|/2`, при котором нижняя
     * боковая садится ровно на пол. Иначе максимальное снижение несущей
     * обнуляло бы биения (при carrier = floor разнос каналов физически
     * невозможен) и период расслабления вырождался бы в чистый тон — для
     * бинаурального ритма это потеря смысла режима.
     *
     * ПОРЯДОК ШАГОВ (важен для граничных случаев):
     * 1. снижаются обе частоты (несущая и биения) — знак beat сохраняется;
     * 2. несущая поднимается до `floor + |beat'|/2`, если ушла ниже;
     * 3. несущая НЕ может стать выше исходной — снижение не повышает частоту
     *    (случай «минимум графика подняли выше самих точек»);
     * 4. если после этого пол всё равно нарушен (базовая точка сама лежит
     *    ниже минимума), клампится модуль биений, знак сохраняется.
     */
    private fun reduceFrequencies(
        carrier: Float,
        beat: Float,
        carrierReduction: Float,
        beatReduction: Float,
        minCarrier: Float
    ): Pair<Float, Float> {
        // Пол частоты КАНАЛА: минимум графика, но не ниже слышимого абсолютного minima.
        val floor = maxOf(minCarrier, FrequencyMath.MIN_TONE_FREQUENCY)

        val rawCarrier = carrier * (1.0f - carrierReduction)
        val rawBeat = beat * (1.0f - beatReduction)

        // Несущая, при которой нижняя боковая (carrier − |beat|/2) садится на пол.
        val carrierForFloor = floor + abs(rawBeat) / 2.0f

        val reducedCarrier = maxOf(rawCarrier, carrierForFloor, floor)
            // Снижение не должно ПОДНИМАТЬ частоту: если базовая точка сама
            // лежит ниже пола (минимум графика подняли выше точек), оставляем её.
            .coerceAtMost(carrier)

        // Страховка: базовая точка вне диапазона (ниже пола) — пол физически
        // недостижим, иначе предел выродился бы в ноль и погасил биения.
        val maxBeatMagnitude = if (reducedCarrier >= floor) {
            (2.0f * (reducedCarrier - floor)).coerceAtLeast(0.0f)
        } else {
            Float.POSITIVE_INFINITY
        }
        // Предел модуля — геометрический (хранимый beatRange здесь не у дел:
        // он лишь масштаб маркеров и не должен срезать биения, которые
        // пользователь имел право задать вплоть до границы графика).
        val reducedBeat = FrequencyMath.clampBeat(
            reducedCarrier,
            rawBeat,
            FrequencyMath.UNBOUNDED_BEAT_RANGE
        ).coerceIn(-maxBeatMagnitude, maxBeatMagnitude)
        return reducedCarrier to reducedBeat
    }

    /**
     * Генерирует виртуальные точки режима расслабления (единая реализация для UI и движка).
     * Каноническое правило: базовые кривые carrier/beat оцениваются сплайном
     * interpolationType+splineTension по исходным точкам. Обе кривые знаковые:
     * beat интерполируется без клампа к >= 0.
     *
     * ВАЖНО: [carrierRange] обязан передаваться из редактируемой кривой — его
     * минимум задаёт пол частоты КАНАЛА виртуальных точек. Если вызвать
     * перегрузку без диапазона, точки посчитаются по умолчанию (100 Гц) и
     * разойдутся с графиком пресета, у которого минимум свой.
     */
    fun generateVirtualPoints(
        points: List<FrequencyPoint>,
        interpolationType: InterpolationType,
        splineTension: Float,
        carrierRange: FrequencyRange = FrequencyRange.DEFAULT_CARRIER
    ): List<FrequencyPoint> {
        if (!enabled || points.isEmpty()) return emptyList()

        val baseCurve = FrequencyCurve(
            points = points,
            carrierRange = carrierRange,
            interpolationType = interpolationType,
            splineTension = splineTension
        )

        return when (mode) {
            RelaxationMode.STEP -> generateStepVirtualPoints(baseCurve)
            RelaxationMode.SMOOTH -> generateSmoothVirtualPoints(baseCurve)
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
     */
    private fun generateStepVirtualPoints(curve: FrequencyCurve): List<FrequencyPoint> {
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

        // Guard от бесконечного цикла при неположительном шаге
        val periodStepSeconds = fullPeriodSeconds + gapSeconds
        if (periodStepSeconds <= 0L) return emptyList()

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
                val (carrier2, beat2) = reduceFrequencies(
                    curve.getCarrierFrequencyAt(time2),
                    curve.getBeatFrequencyAt(time2),
                    carrierReduction,
                    beatReduction,
                    curve.carrierRange.min
                )
                virtualPoints.add(FrequencyPoint(time2, carrier2, beat2))
            }
            
            // Точка 3: конец расслабления (сниженные частоты)
            val t3 = periodStartSeconds + transitionSeconds + durationSeconds
            if (t3 < daySeconds) {
                val time3 = LocalTime.fromSecondOfDay((t3 % daySeconds).toInt())
                val (carrier3, beat3) = reduceFrequencies(
                    curve.getCarrierFrequencyAt(time3),
                    curve.getBeatFrequencyAt(time3),
                    carrierReduction,
                    beatReduction,
                    curve.carrierRange.min
                )
                virtualPoints.add(FrequencyPoint(time3, carrier3, beat3))
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
            periodStartSeconds += periodStepSeconds
        }
        
        // Сортируем по времени
        return virtualPoints.sortedBy { it.time.toSecondOfDay() }
    }
    
    /**
     * Плавный режим: чередующиеся точки (базовая → снижающая → базовая → снижающая).
     * Интервал между точками регулируется параметром smoothIntervalMinutes.
     * Итоговая кривая строится ТОЛЬКО по этим виртуальным точкам.
     */
    private fun generateSmoothVirtualPoints(curve: FrequencyCurve): List<FrequencyPoint> {
        val virtualPoints = mutableListOf<FrequencyPoint>()
        
        val carrierReduction = carrierReductionPercent / 100.0f
        val beatReduction = beatReductionPercent / 100.0f
        val intervalSeconds = smoothIntervalMinutes * 60L
        // Guard от бесконечного цикла: неположительный интервал → дефолт 5 минут
        val safeIntervalSeconds = if (intervalSeconds > 0L) intervalSeconds else 5 * 60L
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
                // Нечётный индекс - снижающая точка (знак beat сохраняется)
                val (carrier, beat) = reduceFrequencies(
                    curve.getCarrierFrequencyAt(time),
                    curve.getBeatFrequencyAt(time),
                    carrierReduction,
                    beatReduction,
                    curve.carrierRange.min
                )
                virtualPoints.add(FrequencyPoint(time, carrier, beat))
            }

            currentSeconds += safeIntervalSeconds
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

    /**
     * Частоты каналов как в движке: каждая канальная кривая (carrier∓beat/2)
     * интерполируется отдельно по точкам кривой с учётом расслабления.
     * Отображаемые carrier=(l+r)/2, beat=r−l (знак beat сохраняется).
     *
     * @return Pair(левый канал, правый канал)
     */
    fun getChannelFrequenciesAt(time: LocalTime): Pair<Float, Float> {
        return curveWithRelaxation.getChannelFrequenciesAt(time)
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
