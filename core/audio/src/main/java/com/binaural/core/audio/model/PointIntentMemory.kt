package com.binaural.core.audio.model

import kotlinx.datetime.LocalTime

/**
 * Память «желаемых» значений точки редактора пресета: несущей и частоты
 * биений.
 *
 * ЗАЧЕМ ОНА НУЖНА
 * ---------------
 * Оба значения ограничены геометрией, и раньше обрезанное значение
 * записывалось обратно в точку. Из-за этого пользователь БЕССЛЕДНО терял
 * свою настройку: пододвинул точку к границе — биения схлопнулись, отодвинул
 * назад — они не вернулись, потому что хранить было нечего. То же самое
 * происходило с несущей, когда к точке пододвигали ГРАНИЦУ диапазона: точка
 * прижималась к новой границе, а при возврате диапазона оставалась там, куда
 * её загнал суженный диапазон.
 *
 * Здесь хранится не то, что ПОЛУЧИЛОСЬ после обрезки, а то, что пользователь
 * ХОТЕЛ: последнее значение, заданное вручную, либо сохранённое в пресете,
 * если с момента открытия редактора оно не менялось. Эффективное значение
 * каждый раз выводится из желаемого ([resolveCarrier], [resolveBeat]) —
 * поэтому при удалении от границы настройка восстанавливается, а при
 * приближении плавно гасится.
 *
 * ЧТО СЧИТАЕТСЯ «ЗАДАННЫМ ВРУЧНУЮ»
 * ---------------------------------
 * - значение, сохранённое в пресете на момент открытия редактора ([seedFrom]);
 * - значение, введённое пользователем (поле/свайп в контекстном окне точки);
 * - значение новой точки, запрошенное при её создании.
 *
 * Автоматическая обрезка у границы и СМЕНА ГРАНИЦ ГРАФИКА желаемое значение
 * НЕ меняют: это не воля пользователя, а следствие геометрии. Отсюда главное
 * правило: границы двигаются — точки возвращаются к желаемому; точку двигает
 * сам пользователь — желаемым становится его значение.
 *
 * КЛЮЧ — ВРЕМЯ, А НЕ ИНДЕКС
 * -------------------------
 * Точка отождествляется по времени ([LocalTime.toSecondOfDay]), а не по
 * индексу в списке: при добавлении список пересортировывается, при удалении
 * сдвигается, и индексы «уезжают». Точность ключа — до секунды: именно так
 * время точки сериализуется в пресете ([LocalTimeSerializer]) и задаётся
 * в редакторе (шаг 5 минут, либо часы/минуты вручную).
 *
 * Несущая и частота биений запоминаются НЕЗАВИСИМО: правка одного поля не
 * должна затирать память о втором, которое в этот момент может хранить
 * погашенное у границы значение.
 */
class PointIntentMemory {

    private val desiredCarrierBySecond = HashMap<Int, Float>()
    private val desiredBeatBySecond = HashMap<Int, Float>()

    /** Очистить память (выход из редактора, отмена, сохранение). */
    fun clear() {
        desiredCarrierBySecond.clear()
        desiredBeatBySecond.clear()
    }

    /**
     * Перезаполнить память из точек пресета: желаемым становится сохранённое
     * значение. Вызывается при открытии редактора — дальше оно живёт, пока
     * пользователь не задаст значения сам.
     *
     * Сохранённое значение может быть уже обрезанным (точка стоит у границы),
     * но большего мы о ней не знаем: исходного намерения в пресете нет.
     */
    fun seedFrom(points: Iterable<FrequencyPoint>) {
        clear()
        points.forEach { point ->
            val key = key(point.time)
            desiredCarrierBySecond[key] = point.carrierFrequency
            desiredBeatBySecond[key] = point.beatFrequency
        }
    }

    /**
     * Запомнить желаемую несущую точки.
     *
     * Вырождение: если по этому времени уже есть запись (две точки в одной
     * секунде), новая её перекрывает — под одним ключом две записи всё равно
     * не различить. На практике точка добавляется с шагом 5 минут, так что
     * это возможно только если новая точка встала ровно на существующую.
     *
     * @param desiredCarrier значение, которое пользователь хочет получить. Оно
     *                       может лежать вне текущего диапазона — тогда точка
     *                       будет стоять у границы, а вернётся на своё место,
     *                       когда диапазон снова её вместит.
     */
    fun rememberCarrier(time: LocalTime, desiredCarrier: Float) {
        desiredCarrierBySecond[key(time)] = desiredCarrier
    }

    /**
     * Запомнить желаемую частоту биений точки. Смысл и вырождение — как у
     * [rememberCarrier]; хранится независимо от несущей.
     */
    fun rememberBeat(time: LocalTime, desiredBeat: Float) {
        desiredBeatBySecond[key(time)] = desiredBeat
    }

    /** Забыть точку (удаление). */
    fun forget(time: LocalTime) {
        val key = key(time)
        desiredCarrierBySecond.remove(key)
        desiredBeatBySecond.remove(key)
    }

    /**
     * Перенести память точки на новое время — точка переехала по оси времени.
     *
     * Если по новому времени память уже есть (две точки оказались в одной
     * секунде — вырожденный случай), побеждает переехавшая: две записи под
     * одним ключом всё равно не различить.
     */
    fun rekey(from: LocalTime, to: LocalTime) {
        val fromKey = key(from)
        val toKey = key(to)
        if (fromKey == toKey) return
        desiredCarrierBySecond.remove(fromKey)?.let { desiredCarrierBySecond[toKey] = it }
        desiredBeatBySecond.remove(fromKey)?.let { desiredBeatBySecond[toKey] = it }
    }

    /**
     * Желаемая несущая точки.
     *
     * Если точка неизвестна памяти (например, кривая подменена извне),
     * желаемой считается её сохранённая несущая — поведение не хуже старого.
     */
    fun desiredCarrierFor(point: FrequencyPoint): Float =
        desiredCarrierBySecond[key(point.time)] ?: point.carrierFrequency

    /**
     * Желаемая частота биений точки; для неизвестной точки — её сохранённое
     * значение (см. [desiredCarrierFor]).
     */
    fun desiredBeatFor(point: FrequencyPoint): Float =
        desiredBeatBySecond[key(point.time)] ?: point.beatFrequency

    /**
     * Эффективная несущая точки внутри [carrierRange]: желаемое значение,
     * приведённое к границам графика и к слышимому диапазону
     * ([FrequencyMath.MIN_TONE_FREQUENCY]…[FrequencyMath.MAX_TONE_FREQUENCY]).
     *
     * Ровно этот вызов возвращает точку на место, когда ГРАНИЦА отодвигается
     * от точки (а не точка от границы): сузили диапазон — точка прижалась,
     * вернули диапазон — она вернулась туда, где её оставил пользователь.
     *
     * @param carrierRange null — предел только по физике тона.
     */
    fun resolveCarrier(point: FrequencyPoint, carrierRange: FrequencyRange? = null): Float {
        val desired = carrierRange?.clamp(desiredCarrierFor(point)) ?: desiredCarrierFor(point)
        return desired.coerceIn(
            FrequencyMath.MIN_TONE_FREQUENCY, FrequencyMath.MAX_TONE_FREQUENCY)
    }

    /**
     * Эффективная частота биений точки при несущей [newCarrier]: желаемое
     * значение, обрезанное по МОДУЛЮ под геометрию новой несущей и (если
     * задан [carrierRange]) под границы графика.
     *
     * Ровно этот вызов даёт оба нужных свойства: у границы желаемое гасится
     * до максимально возможного, а при удалении от границы — восстанавливается
     * до желаемого, как только оно снова помещается.
     *
     * Знак сохраняется: клампится только модуль (beat = right − left —
     * величина знаковая; см. [FrequencyMath]).
     */
    fun resolveBeat(
        point: FrequencyPoint,
        newCarrier: Float,
        carrierRange: FrequencyRange? = null
    ): Float = FrequencyMath.clampBeat(
        newCarrier, desiredBeatFor(point), carrierRange = carrierRange)

    /**
     * Значение известно памяти? Истина, если remembering хоть одного из двух
     * полей: [seedFrom] и [rekey] пишут оба сразу, а одиночные правки
     * ([rememberCarrier], [rememberBeat]) — только своё.
     */
    fun remembers(point: FrequencyPoint): Boolean {
        val key = key(point.time)
        return desiredCarrierBySecond.containsKey(key) || desiredBeatBySecond.containsKey(key)
    }

    private fun key(time: LocalTime): Int = time.toSecondOfDay()
}
