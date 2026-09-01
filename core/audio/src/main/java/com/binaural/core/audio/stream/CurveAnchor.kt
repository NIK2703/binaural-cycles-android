package com.binaural.core.audio.stream

/**
 * Происхождение якоря кривой: ОТКУДА взялась позиция, на которую встанет поток.
 *
 * Якорь без происхождения — это голый `Float`, в котором «нет якоря», «полночь»
 * и «мусор» неотличимы: ровно то, из чего вырос баг «возобновление с 0:00»
 * (docs/handoff_anchor_zero_analysis_plan.md, дефект 2). Происхождение делает
 * каждое решение о якоре объяснимым и логируемым.
 */
enum class AnchorSource {
    /** Якоря нет. `prepare()` ОБЯЗАН сам взять «сейчас». */
    NONE,

    /** Якорь вычислен как текущий момент суток в последний ответственный момент. */
    NOW,

    /** Якорь пришёл извне (живой поток, восстановление сессии, debug-команда). */
    CAPTURED,

    /** Внешний якорь ОТВЕРГНУТ валидацией (далёк от «сейчас») — взято «сейчас». */
    FALLBACK
}

/**
 * Якорь кривой: значение с происхождением.
 *
 * Сентинел «нет якоря» — `-1` ([valueSec]) в паре с [AnchorSource.NONE],
 * и ТОЛЬКО он. `0` — легальная полночь и ничем не отличается от любого другого
 * времени суток: проверки вида `resumeCurveTimeSeconds >= 0` запрещены, потому
 * что они путают полночь с отсутствием якоря.
 *
 * @param valueSec    позиция кривой в секундах суток; `-1` — «нет якоря»
 * @param source      происхождение значения (обязательно, без «unknown»)
 * @param capturedAtMs wall-clock момент захвата (0 = не захвачен, а вычислен)
 */
data class CurveAnchor(
    val valueSec: Int,
    val source: AnchorSource,
    val capturedAtMs: Long = 0L
) {
    /** Есть ли якорь, который можно применить (иначе `prepare()` берёт «сейчас»). */
    val isPresent: Boolean get() = source != AnchorSource.NONE && valueSec >= 0

    /** Возраст захваченного якоря в мс (0 для NOW/NONE — он не устаревает). */
    fun ageMs(nowMs: Long): Long =
        if (source == AnchorSource.CAPTURED && capturedAtMs > 0L) nowMs - capturedAtMs else 0L

    override fun toString(): String = when (source) {
        AnchorSource.NONE -> "нет(-1)"
        else -> "$valueSec(source=$source)"
    }

    companion object {
        /** Якоря нет: `prepare()` якорит кривую на «сейчас». */
        val NONE: CurveAnchor = CurveAnchor(-1, AnchorSource.NONE)

        fun now(valueSec: Int): CurveAnchor = CurveAnchor(valueSec, AnchorSource.NOW)

        fun captured(valueSec: Float, atMs: Long = System.currentTimeMillis()): CurveAnchor =
            CurveAnchor(normalizeTimeOfDay(valueSec).toInt(), AnchorSource.CAPTURED, atMs)

        fun fallback(valueSec: Int): CurveAnchor = CurveAnchor(valueSec, AnchorSource.FALLBACK)
    }
}

/**
 * Правила валидации якоря — чистая математика, пригодная для юнит-тестов без
 * Android (Context/AudioTrack/NDK здесь не нужны).
 *
 * Единственная проверка: «якорь достаточно близко к сейчас». Всё остальное
 * (полночь, переход через сутки) решается нормализацией, а не особыми случаями.
 */
object CurveAnchorRules {
    /**
     * Допустимое расхождение якоря с «сейчас», секунды.
     *
     * 5 с — это запас на длительность хэндоффа (фейд-аут + стража шейпера +
     * релиз трека + prepare(), итого ≲ 1 с) плюс погрешность целых секунд.
     * Меньше нельзя: легальный якорь, снятый в момент начала хэндоффа, будет
     * ошибочно отвергнут. Больше нельзя: теряется смысл проверки — якорь
     * 00:00 при now = 12:00 обязан отбрасываться.
     */
    const val MAX_SKEW_SEC = 5f

    /**
     * Круговое расстояние между двумя моментами суток, секунды, [0, 43200].
     *
     * Прямая разность не годится: `86399 − 0 = 86399`, хотя между полночью и
     * последней секундой суток одна секунда.
     */
    fun circularDistance(aSec: Float, bSec: Float): Float {
        val d = normalizeTimeOfDay(aSec - bSec)
        return if (d > SECONDS_PER_DAY * 0.5f) SECONDS_PER_DAY - d else d
    }

    /**
     * Годен ли внешний якорь: выполняет ли он инвариант «звук == сейчас».
     *
     * Легальная полночь НЕ отбрасывается: при `now = 86399.9` якорь `0`
     * отстоит на 0.1 с и проходит проверку. Отбрасывается только по-настоящему
     * далёкий (протухший, мусорный, «замороженный на 0») якорь.
     */
    fun isPlausible(anchorSec: Float, nowSec: Float, maxSkewSec: Float = MAX_SKEW_SEC): Boolean =
        circularDistance(anchorSec, nowSec) <= maxSkewSec
}

/**
 * РЕШИТЕЛЬ ЯКОРЯ: единственная точка, где якорь превращается в конкретную
 * позицию кривой.
 *
 * Вызывается из `prepare()` — то есть в ПОСЛЕДНИЙ ответственный момент, когда
 * «сейчас» уже не может устареть. Раньше якорь захватывался в `beginHandoff`
 * и применялся после полного релиза старого потока; за это время он успевал
 * отстать от настенных часов на длительность хэндоффа, и лаг копится от
 * правки к правке.
 *
 * @param anchor       якорь из спеки (с происхождением)
 * @param nowSec       «сейчас» по единственным часам ([realTimeOfDaySeconds])
 * @param engineNowSec позиция, которую предлагает САМ движок. НЕ равна
 *                     [nowSec]: в debug-сборке это ось виртуального времени
 *                     или результат `debugScrub`, и её надо уважать — иначе
 *                     перемотка оператора молча откатывалась бы к настенным
 *                     часам.
 * @return якорь с происхождением; `valueSec` всегда валидная позиция кривой
 */
internal fun resolveCurveAnchor(
    anchor: CurveAnchor,
    nowSec: Float,
    engineNowSec: Float,
    maxSkewSec: Float = CurveAnchorRules.MAX_SKEW_SEC
): CurveAnchor {
    if (!anchor.isPresent) {
        return CurveAnchor.now(engineNowSec.toInt().coerceIn(0, 86_399))
    }
    if (CurveAnchorRules.isPlausible(anchor.valueSec.toFloat(), nowSec, maxSkewSec)) {
        return anchor
    }
    // Внешний якорь далёк от «сейчас» — это мусор, протухание или защёлкнутый
    // ноль. Инвариант приложения важнее непрерывности: берём «сейчас».
    return CurveAnchor.fallback(engineNowSec.toInt().coerceIn(0, 86_399))
}
