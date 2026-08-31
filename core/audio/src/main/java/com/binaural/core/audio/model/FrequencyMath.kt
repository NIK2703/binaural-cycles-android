package com.binaural.core.audio.model

import kotlin.math.abs

/**
 * Математика частот бинаурального ритма. Единая точка истины для UI и движка.
 *
 * ЗНАК ЧАСТОТЫ БИЕНИЙ
 * -------------------
 * beat — величина ЗНАКОВАЯ: beat = right − left.
 * - beat > 0: правый канал звучит выше левого (обычное расположение);
 * - beat < 0: каналы поменяны местами — правый ниже левого;
 * - |beat| — то, что слышно как пульсация. Знак задаёт только раскладку каналов
 *   (какое ухо получает более высокий тон).
 *
 * Физические частоты тонов (carrier, левый/правый канал) знаковыми не бывают и
 * всегда клампятся к >= 0 Гц; частота биений клампится только по МОДУЛЮ.
 *
 * ОГРАНИЧЕНИЕ МОДУЛЯ
 * ------------------
 * Боковые частоты должны оставаться в слышимом диапазоне
 * [MIN_TONE_FREQUENCY; MAX_TONE_FREQUENCY]. Условие симметрично по знаку beat,
 * т.к. при смене знака боковые частоты просто меняются местами:
 *   min(carrier ∓ |beat|/2) >= MIN_TONE_FREQUENCY  =>  |beat| <= 2 * (carrier - MIN_TONE_FREQUENCY)
 *   max(carrier ± |beat|/2) <= MAX_TONE_FREQUENCY  =>  |beat| <= 2 * (MAX_TONE_FREQUENCY - carrier)
 * Соблюдение этого ограничения гарантирует, что ни один канал не уйдёт ниже 0 Гц
 * (иначе движок обрезал бы его, исказив и carrier, и beat).
 *
 * ДВА СПОСОБА УЛОЖИТЬСЯ В ГРАНИЦЫ
 * -------------------------------
 * Одно и то же условие выполняется двумя разными способами, и выбор между
 * ними — про то, ЧТО в этот момент правит пользователь:
 * - [clampBeat] — несущая стоит где стоит, режется пульсация. Так надо, когда
 *   несущую выбрал сам пользователь (перетаскивание точки по графику, смена
 *   границ графика): его выбор не имеет права уезжать из-под пальца.
 * - [fitBeatWithCarrierShift] — пульсация сохраняется, а несущая отодвигается
 *   от границы, на которую наехал канал. Так надо, когда пользователь правит
 *   именно частоту биений: он тянет пульсацию, он её и получает, а не молча
 *   обрезанное значение. Обрезка остаётся только на потолок — ширину
 *   диапазона, который не влезет ни при какой несущей.
 */
object FrequencyMath {

    /** Минимальная частота тона (Гц) — нижняя граница боковых частот. */
    const val MIN_TONE_FREQUENCY = 20.0f

    /** Максимальная частота тона (Гц) — верхняя граница боковых частот. */
    const val MAX_TONE_FREQUENCY = 2000.0f

    /** Максимальный модуль частоты биений по умолчанию (Гц). */
    const val MAX_BEAT_MAGNITUDE = 1000.0f

    /**
     * Потолок модуля частоты биений, который даёт сама ГЕОМЕТРИЯ (Гц).
     *
     * Оба ограничения (|beat| <= 2*(carrier − 20) и |beat| <= 2*(2000 − carrier))
     * пересекаются на несущей ровно посередине слышимого диапазона:
     * 2 * (1010 − 20) = 1980 Гц. Дальше увеличивать некуда ни при какой несущей.
     */
    const val MAX_GEOMETRIC_BEAT_MAGNITUDE = MAX_TONE_FREQUENCY - MIN_TONE_FREQUENCY

    /**
     * Диапазон частоты биений, в котором модуль ограничен ТОЛЬКО ГЕОМЕТРИЕЙ:
     * физикой каналов (20…2000 Гц) и, если передан [carrierRange], вертикальными
     * границами графика.
     *
     * Именно он используется везде, где пользователь ВЫБИРАЕТ частоту биений:
     * предел задаёт удвоенное расстояние от несущей выбранной точки до ближайшей
     * границы, а не число, сохранённое в пресете. Хранить «максимум биений»
     * в пресете незачем — он целиком выводится из несущей и границ, а
     * устаревшее значение (1000 Гц у старых пресетов) искусственно не давало
     * выйти за рамки, которых больше нет.
     *
     * Хранимое значение [FrequencyRange.DEFAULT_BEAT] остаётся лишь масштабом
     * для отрисовки маркеров на графике и в качестве ограничения НЕ применяется.
     */
    val UNBOUNDED_BEAT_RANGE: FrequencyRange = FrequencyRange(
        min = -MAX_GEOMETRIC_BEAT_MAGNITUDE,
        max = MAX_GEOMETRIC_BEAT_MAGNITUDE
    )

    /**
     * Частота ЛЕВОГО канала: carrier − beat/2.
     * При beat < 0 это БОЛЕЕ высокий тон (каналы поменяны местами).
     */
    fun leftChannelFrequency(carrierFrequency: Float, beatFrequency: Float): Float =
        carrierFrequency - beatFrequency / 2.0f

    /**
     * Частота ПРАВОГО канала: carrier + beat/2.
     * При beat < 0 это БОЛЕЕ низкий тон (каналы поменяны местами).
     */
    fun rightChannelFrequency(carrierFrequency: Float, beatFrequency: Float): Float =
        carrierFrequency + beatFrequency / 2.0f

    /** Модуль частоты биений — слышимая пульсация (знак задаёт раскладку каналов). */
    fun beatMagnitude(beatFrequency: Float): Float = abs(beatFrequency)

    /**
     * Несущая частота по частотам каналов: (left + right) / 2.
     */
    fun carrierFromChannels(leftFrequency: Float, rightFrequency: Float): Float =
        (leftFrequency + rightFrequency) / 2.0f

    /**
     * Частота биений по частотам каналов (как её считает движок): right − left.
     */
    fun beatFromChannels(leftFrequency: Float, rightFrequency: Float): Float =
        rightFrequency - leftFrequency

    /**
     * Максимальный МОДУЛЬ частоты биений для данной несущей.
     *
     * @param carrierRange если задан, боковые частоты дополнительно не должны
     *                     выходить за вертикальные границы графика.
     */
    fun maxBeatMagnitude(carrierFrequency: Float, carrierRange: FrequencyRange? = null): Float {
        val byFloor = 2.0f * (carrierFrequency - MIN_TONE_FREQUENCY)
        val byCeiling = 2.0f * (MAX_TONE_FREQUENCY - carrierFrequency)
        val global = minOf(byFloor, byCeiling)
        val byRange = carrierRange?.let {
            minOf(
                2.0f * (carrierFrequency - it.min),
                2.0f * (it.max - carrierFrequency)
            )
        } ?: Float.POSITIVE_INFINITY
        return minOf(global, byRange).coerceAtLeast(0.0f)
    }

    /**
     * Границы, внутри которых обязаны лежать частоты КАНАЛОВ: вертикальные
     * границы графика, пересечённые со слышимым диапазоном тонов.
     *
     * Границы графика могут быть шире слышимого диапазона (а могут и выходить
     * за него), поэтому физика всегда входит в пересечение: канал не имеет
     * права уйти ниже 20 Гц, даже если минимум графика задан ниже.
     */
    private fun channelBounds(carrierRange: FrequencyRange?): Pair<Float, Float> {
        val lower = maxOf(carrierRange?.min ?: MIN_TONE_FREQUENCY, MIN_TONE_FREQUENCY)
        val upper = minOf(carrierRange?.max ?: MAX_TONE_FREQUENCY, MAX_TONE_FREQUENCY)
        return lower to upper.coerceAtLeast(lower)
    }

    /**
     * ПОТОЛОК частоты биений: наибольший модуль, который вообще помещается в
     * [carrierRange] ПРИ ЛЮБОЙ несущей (Гц) — то есть ширина диапазона
     * (верхняя граница минус нижняя).
     *
     * Каналы занимают подряд весь отрезок длиной |beat| — от carrier − |beat|/2
     * до carrier + |beat|/2, — поэтому шире самого диапазона разнос быть не
     * может ни при какой несущей. Предел достижим ровно в одном положении:
     * несущая ПОСЕРЕДИНЕ диапазона, нижний канал сидит на нижней границе,
     * верхний — на верхней.
     *
     * Отсюда правило подгонки ([fitBeatWithCarrierShift]): пока желаемая
     * пульсация не выше потолка, её можно получить, отодвинув несущую от
     * границы; резать частоту биений приходится только когда несущая уже
     * стоит посередине и отодвигать её больше некуда.
     */
    fun maxFittableBeatMagnitude(carrierRange: FrequencyRange? = null): Float {
        val (lower, upper) = channelBounds(carrierRange)
        return (upper - lower).coerceAtLeast(0.0f)
    }

    /**
     * Пара «несущая + частота биений», подобранная под желаемую пульсацию
     * ([fitBeatWithCarrierShift]).
     *
     * @param carrierFrequency несущая, при которой оба канала лежат внутри
     *                         границ: желаемая, если она годилась, иначе
     *                         отодвинутая от границы, на которую наезжал канал.
     * @param beatFrequency    частота биений: желаемая, если она не выше
     *                         потолка ([maxFittableBeatMagnitude]), иначе сам
     *                         потолок. Знак (раскладка каналов) сохранён.
     */
    data class CarrierBeatFit(
        val carrierFrequency: Float,
        val beatFrequency: Float
    )

    /**
     * Подобрать пару «несущая + частота биений» под желаемую пульсацию,
     * НЕ обрезая её, пока это возможно.
     *
     * В отличие от [clampBeat] (который фиксирует несущую и режет биения),
     * здесь наоборот: частота биений сохраняется, а несущая ОТОДВИГАЕТСЯ от
     * той границы, за которую полез канал. Пользователь тянет пульсацию вверх
     * — он и получает пульсацию, а не молчаливо обрезанное значение.
     *
     * ШАГ 1. Потолок. Если желаемый модуль больше ширины диапазона, он
     *        обрезается по потолку: влезть в диапазон он не может ни при какой
     *        несущей (см. [maxFittableBeatMagnitude]).
     * ШАГ 2. Коридор несущих. Для модуля B годятся несущие из отрезка
     *        [lower + B/2; upper − B/2] — ровно те, у которых нижний канал не
     *        проваливается ниже lower, а верхний не вылезает выше upper.
     *        Берётся желаемая несущая, прижатая к этому коридору: сдвиг
     *        минимальный, ровно настолько, насколько канал вылез за границу.
     *        При B, равном потолку, коридор стягивается в точку — в середину
     *        диапазона, что и требовалось.
     *
     * Знак частоты биений сохраняется всегда: ограничения симметричны по
     * модулю, при смене знака каналы просто меняются местами.
     *
     * @param carrierRange границы, внутри которых обязаны лежать КАНАЛЫ.
     *                     null — только слышимый диапазон (20…2000 Гц).
     */
    fun fitBeatWithCarrierShift(
        carrierFrequency: Float,
        beatFrequency: Float,
        carrierRange: FrequencyRange? = null
    ): CarrierBeatFit {
        val (lower, upper) = channelBounds(carrierRange)
        val ceiling = (upper - lower).coerceAtLeast(0.0f)
        val sign = if (beatFrequency < 0.0f) -1.0f else 1.0f

        // Шаг 1: выше потолка не влезет ни при какой несущей.
        val magnitude = minOf(abs(beatFrequency), ceiling)

        // Шаг 2: минимальный сдвиг несущей внутрь коридора.
        val lowestCarrier = lower + magnitude / 2.0f
        val highestCarrier = upper - magnitude / 2.0f
        val fittedCarrier = if (lowestCarrier <= highestCarrier) {
            carrierFrequency.coerceIn(lowestCarrier, highestCarrier)
        } else {
            // Вырожденный диапазон (upper < lower после клампа) — середина.
            (lower + upper) / 2.0f
        }

        return CarrierBeatFit(
            carrierFrequency = fittedCarrier,
            beatFrequency = sign * magnitude
        )
    }

    /**
     * Допустимый интервал частоты биений для точки с данной несущей:
     * физика (боковые в слышимом диапазоне) ∩ границы графика ∩ диапазон кривой.
     *
     * Интервал всегда симметричен относительно нуля по модулю, поэтому
     * отрицательные частоты биений разрешены ровно на тех же условиях, что и
     * положительные той же величины.
     *
     * @param beatRange по умолчанию НЕ ограничивает модуль — предел выводится из
     *                  геометрии (см. [UNBOUNDED_BEAT_RANGE]).
     */
    fun beatBounds(
        carrierFrequency: Float,
        beatRange: FrequencyRange = UNBOUNDED_BEAT_RANGE,
        carrierRange: FrequencyRange? = null
    ): ClosedFloatingPointRange<Float> {
        val maxMagnitude = maxBeatMagnitude(carrierFrequency, carrierRange)
        val lower = maxOf(beatRange.min, -maxMagnitude)
        val upper = minOf(beatRange.max, maxMagnitude)
        // Защита от вырожденного интервала (несущая вне слышимого диапазона)
        return lower.coerceAtMost(upper)..upper
    }

    /**
     * Привести частоту биений к допустимому интервалу для данной несущей.
     * Знак сохраняется: клампится только модуль.
     *
     * @param beatRange по умолчанию НЕ ограничивает модуль — предел выводится из
     *                  геометрии (см. [UNBOUNDED_BEAT_RANGE]).
     */
    fun clampBeat(
        carrierFrequency: Float,
        beatFrequency: Float,
        beatRange: FrequencyRange = UNBOUNDED_BEAT_RANGE,
        carrierRange: FrequencyRange? = null
    ): Float = beatFrequency.coerceIn(beatBounds(carrierFrequency, beatRange, carrierRange))

    /**
     * Диапазон частот биений, симметричный относительно нуля.
     *
     * Старые пресеты сохраняли beatRange = (0; 1000) — отрицательные частоты
     * биений в них просто не использовались. Расширение «в зеркало» делает
     * знак доступным независимо от версии пресета.
     *
     * ВАЖНО: это МАСШТАБ для отрисовки (размер маркера точки на графике),
     * а не разрешённый предел. Предел модуля всегда выводится из геометрии —
     * см. [maxBeatMagnitude] и [UNBOUNDED_BEAT_RANGE].
     */
    fun symmetricBeatRange(range: FrequencyRange): FrequencyRange = FrequencyRange(
        min = minOf(range.min, -range.max),
        max = maxOf(range.max, -range.min)
    )
}
