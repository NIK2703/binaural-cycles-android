package com.binaural.core.audio.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Регуляция натяжения кардинального сплайна там, где кривая выходит за
 * вертикальные границы графика.
 *
 * ПРОБЛЕМА
 * --------
 * Кардинальный сплайн — кубический, и между контрольными точками он вовсе не
 * обязан лежать внутри отрезка [p1; p2]: при tension = 0 (Catmull-Rom) касательные
 * M_i = (y[i+1] − y[i−1]) / 2 разгоняют кривую, и она вылетает за границы
 * графика (overshoot). Монотонный сплайн такой выход режет клампом
 * (`Interpolation.monotone`), но у кардинального клампа нет — он «честный»
 * сплайн, и именно за его плавность его и выбирают.
 *
 * РЕШЕНИЕ: ОБЩИЙ ВЕС КАСАТЕЛЬНОЙ НА УЗЕЛ
 * --------------------------------------
 * Касательную в узле можно домножить на вес w ∈ [0; 1]: w = 1 — номинальная
 * касательная, w = 0 — нулевая (кривая входит в узел горизонтально). Нужно
 * подобрать веса так, чтобы на «плохих» участках кривая не перескакивала
 * границу, а КАСАЛАСЬ её.
 *
 * Почему вес ОБЩИЙ для левого и правого каналов, а не свой на канал:
 *
 *   left  = carrier − beat/2
 *   right = carrier + beat/2
 *
 * Сплайн — оператор ЛИНЕЙНЫЙ по ordinate и по касательным, поэтому при общем
 * весе w_i в узле i
 *
 *   right(t) − left(t) = spline(right; w·M^right) − spline(left; w·M^left)
 *                      = spline(right − left; w·(M^right − M^left))
 *                      = spline(beat; w·M^beat)
 *
 * То есть частота биений ОСТАЁТСЯ ТОЧНОЙ ИНТЕРПОЛЯЦИЕЙ своих же узлов — той же
 * самой сплайн-кривой beat, только с теми же весами. Схлопнуться к нулю она
 * может лишь если номинальная кривая beat сама проходит через ноль; веса тут
 * ни при чём. Каналы НЕ слипаются в точке касания — гасится только избыточная
 * кривизна, а не разность каналов.
 *
 * Если же дать каждому каналу СВОЙ вес, это тождество рушится: beat(t)
 * перестаёт быть интерполяцией своих узлов, каналы в точке касания почти
 * слипаются, а overshoot просто переезжает в другое место.
 *
 * ПОЧЕМУ ВЕС НА УЗЕЛ, А НЕ НА ИНТЕРВАЛ
 * ------------------------------------
 * Интервал [i; i+1] использует касательные M_i и M_{i+1}. Если масштабировать
 * их только для этого интервала, то в узле i слева и справа получатся РАЗНЫЕ
 * касательные — кривая потеряет C¹-непрерывность (излом). Берём
 *
 *      w[i] = min(k[i−1], k[i])
 *
 * где k[i] — допустимый масштаб для интервала i. Оба смежных интервала
 * домножают касательную узла на одно и то же число ⇒ C¹ сохранена.
 *
 * ПОЧЕМУ ЭФФЕКТИВНЫЕ ГРАНИЦЫ, А НЕ ЗАДАННЫЕ
 * -----------------------------------------
 * Узел, лежащий ВНЕ [min; max], нельзя «исправить» никаким весом: кривая
 * обязана через него пройти. Поэтому требуем не «значения внутри [min; max]»,
 * а «касательные не добавляют нарушения сверх того, что дают сами узлы»:
 *
 *      loEff = min(lo, p1, p2)      hiEff = max(hi, p1, p2)
 *
 * Побочный выигрыш: при k = 0 кривая монотонна между p1 и p2, т.е. лежит
 * внутри [loEff; hiEff] ВСЕГДА — значит k = 0 допустим, множество допустимых
 * k — отрезок, и бинарный поиск по нему корректен.
 *
 * ПОЧЕМУ НУЖЕН ДОПУСК TOLERANCE_HZ
 * --------------------------------
 * Узел, лежащий РОВНО на границе, даёт формальное нарушение, которое нельзя
 * убрать ни при каком конечном k (касательная в нуль — и кривая «прилипла»).
 * Без допуска алгоритм обнулил бы касательную в каждой точке на границе и
 * превратил сплайн в ломаную. Допуск 0.1 Гц — ниже слухового разрешения и ниже
 * пикселя на графике (0.1 Гц из 500 Гц диапазона ≈ 0.16 px).
 *
 * СЛОЖНОСТЬ И МЕСТО ВЫЗОВА
 * ------------------------
 * O(n) узлов × O(1) на интервал (24 итерации бинарного поиска + до 8 проходов
 * коррекции). Считается ОДИН РАЗ на кривую — там же, где строится lookup-таблица,
 * в горячий путь генерации звука не попадает вообще.
 */
object CardinalTension {

    /**
     * Допуск касания границы, Гц. Выход в пределах допуска — это и есть
     * «касание», а не заскок.
     */
    const val TOLERANCE_HZ = 0.1f

    /** Число итераций бинарного поиска масштаба (2^-24 ≈ 6e-8 — ниже float32). */
    private const val BISECTION_STEPS = 24

    /** Максимум проходов корректирующей «мётлки». */
    private const val MAX_SWEEPS = 8

    /** Порог вырождения квадратичного/линейного случая производной. */
    private const val EPS = 1e-12f

    /** Численный зазор поверх допуска (погрешность вычисления кубики в float32). */
    private const val SLACK = 1e-4f

    /**
     * Подобрать веса касательных для обоих каналов.
     *
     * @param lowerY ординаты ЛЕВОГО канала (carrier − beat/2) по узлам, по порядку времени
     * @param upperY ординаты ПРАВОГО канала (carrier + beat/2) по узлам, в том же порядке
     * @param tension натяжение сплайна (0 = Catmull-Rom, 1 = почти линейный)
     * @param minFreq нижняя вертикальная граница, Гц
     * @param maxFreq верхняя вертикальная граница, Гц
     * @return веса w[i] ∈ [0; 1], ОБЩИЕ для обоих каналов; 1 — номинальная касательная
     */
    fun computeSharedWeights(
        lowerY: FloatArray,
        upperY: FloatArray,
        tension: Float,
        minFreq: Float,
        maxFreq: Float
    ): FloatArray {
        val n = lowerY.size
        val weights = FloatArray(n) { 1f }
        if (n < 2 || upperY.size != n) return weights
        // Границы вырождены/перепутаны — регулировать нечего.
        if (!(maxFreq > minFreq)) return weights

        val scratch = FloatArray(2)
        val s = (1f - tension) / 2f

        // Номинальные касательные в узлах: M_i = s·(y[i+1] − y[i−1]), циклически.
        val mLower = FloatArray(n)
        val mUpper = FloatArray(n)
        for (i in 0 until n) {
            val prev = if (i == 0) n - 1 else i - 1
            val next = if (i == n - 1) 0 else i + 1
            mLower[i] = (lowerY[next] - lowerY[prev]) * s
            mUpper[i] = (upperY[next] - upperY[prev]) * s
        }

        // Проход 1: допустимый масштаб касательных КАЖДОГО интервала.
        val intervalScale = FloatArray(n) { 1f }
        for (i in 0 until n) {
            val j = if (i == n - 1) 0 else i + 1
            val kLower = maxScale(
                lowerY[i], lowerY[j], mLower[i], mLower[j], minFreq, maxFreq, scratch
            )
            val kUpper = maxScale(
                upperY[i], upperY[j], mUpper[i], mUpper[j], minFreq, maxFreq, scratch
            )
            intervalScale[i] = min(kLower, kUpper)
        }

        // Проход 2: вес УЗЛА — минимум по двум смежным интервалам (держит C¹).
        for (i in 0 until n) {
            val prev = if (i == 0) n - 1 else i - 1
            weights[i] = min(intervalScale[prev], intervalScale[i])
        }

        // Проход 3: проверка и коррекция.
        //
        // Проход 2 ЗАНИЖАЕТ веса относительно прохода 1, поэтому он не может
        // ничего испортить, но мог бы избыточно погасить касательную. Здесь
        // проверяется фактическая допустимость и, если интервал всё ещё
        // нарушен (такое возможно: min по интервалам не гарантирует
        // совместной допустимости, т.к. масштаб по модулю не линеен),
        // оба веса узлов интервала домножаются на ровно необходимый множитель.
        // Именованная лямбда, а не break: repeat — не цикл, break внутри
        // её лямбды недопустим (нет объемлющего цикла для выхода).
        repeat(MAX_SWEEPS) sweep@ {
            var changed = false
            for (i in 0 until n) {
                val j = if (i == n - 1) 0 else i + 1
                val w1 = weights[i]
                val w2 = weights[j]
                if (feasible(lowerY[i], lowerY[j], mLower[i] * w1, mLower[j] * w2,
                        minFreq, maxFreq, scratch) &&
                    feasible(upperY[i], upperY[j], mUpper[i] * w1, mUpper[j] * w2,
                        minFreq, maxFreq, scratch)
                ) continue

                // maxScale от ТЕКУЩИХ касательных даёт ровно тот множитель,
                // на который их осталось укоротить.
                val need = min(
                    maxScale(lowerY[i], lowerY[j], mLower[i] * w1, mLower[j] * w2,
                        minFreq, maxFreq, scratch),
                    maxScale(upperY[i], upperY[j], mUpper[i] * w1, mUpper[j] * w2,
                        minFreq, maxFreq, scratch)
                )
                weights[i] = w1 * need
                weights[j] = w2 * need
                changed = true
            }
            if (!changed) return@sweep
        }

        return weights
    }

    /**
     * Веса для кривой, заданной контрольными точками, либо null, если регулировка
     * не нужна и все веса равны 1 (тип не CARDINAL, точек меньше двух, либо
     * номинальный сплайн никуда не вылетает).
     *
     * null — не «ошибка», а норма: в этом случае вычислители работают с
     * номинальными касательными и не тратят на веса ни такта.
     *
     * ПОРЯДОК ВАЖЕН: веса возвращаются в том же порядке, в каком переданы точки,
     * и должны применяться к точкам, отсортированным по времени суток ровно так же
     * (в нативе — к точкам после сортировки и схлопывания дублей по времени).
     *
     * @param presorted true, если points уже отсортированы по времени суток
     */
    fun forPoints(
        points: List<FrequencyPoint>,
        type: InterpolationType,
        tension: Float,
        carrierRange: FrequencyRange,
        presorted: Boolean = false
    ): FloatArray? {
        if (type != InterpolationType.CARDINAL) return null
        val sorted = if (presorted) points else points.sortedBy { it.time.toSecondOfDay() }
        // Нативный движок схлопывает точки с одинаковым временем, оставляя
        // ПОСЛЕДНЮЮ (buildLookupTableInternal). Веса обязаны считаться по тем же
        // узлам: иначе их число разойдётся с числом узлов таблицы, движок
        // отбросит их защитой по размеру, и звук останется без регулировки ровно
        // в тех кривых, где узлы задвоены.
        val knots = collapseDuplicateTimes(sorted)
        val n = knots.size
        if (n < 2) return null

        val lower = FloatArray(n)
        val upper = FloatArray(n)
        var allOnes = true
        for (i in 0 until n) {
            lower[i] = knots[i].leftChannelFrequency
            upper[i] = knots[i].rightChannelFrequency
        }
        val weights = computeSharedWeights(
            lower, upper, tension, carrierRange.min, carrierRange.max
        )
        for (w in weights) {
            if (w < 1f) { allOnes = false; break }
        }
        return if (allOnes) null else weights
    }

    /**
     * Схлопнуть точки с одинаковым временем суток, оставив ПОСЛЕДНЮЮ из каждой
     * группы — ровно так, как делает нативный `buildLookupTableInternal`.
     *
     * Порядок сохраняется: [sorted] уже отсортирован по времени, а `sortedBy`
     * в Kotlin стабилен, поэтому «последняя» здесь — та же, что и в нативе.
     */
    private fun collapseDuplicateTimes(sorted: List<FrequencyPoint>): List<FrequencyPoint> {
        for (i in 1 until sorted.size) {
            if (sorted[i].time.toSecondOfDay() == sorted[i - 1].time.toSecondOfDay()) {
                // Дубли есть — строим новый список; частый путь (без дублей)
                // не аллоцирует ничего.
                val out = ArrayList<FrequencyPoint>(sorted.size)
                for (p in sorted) {
                    val lastIndex = out.size - 1
                    if (lastIndex >= 0 &&
                        out[lastIndex].time.toSecondOfDay() == p.time.toSecondOfDay()
                    ) {
                        out[lastIndex] = p
                    } else {
                        out.add(p)
                    }
                }
                return out
            }
        }
        return sorted
    }

    // ========================================================================
    // Геометрия кубического сегмента Эрмита
    // ========================================================================

    /**
     * Значение кубики Эрмита в t ∈ [0; 1].
     */
    private fun hermite(p1: Float, p2: Float, m1: Float, m2: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2
    }

    /**
     * ТОЧНЫЙ диапазон значений кубики на [0; 1] — через аналитические экстремумы,
     * а не через сэмплирование.
     *
     * C(t)  = p1 + m1·t + (3d − 2m1 − m2)·t² + (m1 + m2 − 2d)·t³,  d = p2 − p1
     * C'(t) = A·t² + B·t + C,
     *   A = 3(m1 + m2 − 2d),  B = 2(3d − 2m1 − m2),  C = m1
     *
     * Результат пишется в [out]: out[0] = min, out[1] = max.
     */
    private fun cubicRange(
        p1: Float, p2: Float, m1: Float, m2: Float, out: FloatArray
    ) {
        var lo = min(p1, p2)
        var hi = max(p1, p2)

        val d = p2 - p1
        val a = 3f * (m1 + m2 - 2f * d)
        val b = 2f * (3f * d - 2f * m1 - m2)
        val c = m1

        if (abs(a) < EPS) {
            // Производная линейна (или константа) — один корень.
            if (abs(b) > EPS) {
                val t = -c / b
                if (t > 0f && t < 1f) {
                    val v = hermite(p1, p2, m1, m2, t)
                    if (v < lo) lo = v else if (v > hi) hi = v
                }
            }
        } else {
            val disc = b * b - 4f * a * c
            if (disc > 0f) {
                val sq = sqrt(disc)
                val inv = 1f / (2f * a)
                val tA = (-b - sq) * inv
                val tB = (-b + sq) * inv
                if (tA > 0f && tA < 1f) {
                    val v = hermite(p1, p2, m1, m2, tA)
                    if (v < lo) lo = v else if (v > hi) hi = v
                }
                if (tB > 0f && tB < 1f) {
                    val v = hermite(p1, p2, m1, m2, tB)
                    if (v < lo) lo = v else if (v > hi) hi = v
                }
            }
        }

        out[0] = lo
        out[1] = hi
    }

    /**
     * Допустим ли сегмент: не выходит ли он за ЭФФЕКТИВНЫЕ границы с учётом допуска.
     *
     * Узел вне [lo; hi] уже «нарушает» — но это нарушение неустранимо, кривая
     * обязана через узел пройти. Поэтому границы расширяются до самих узлов, и
     * требуется лишь, чтобы КАСАТЕЛЬНЫЕ не добавили сверх этого.
     */
    private fun feasible(
        p1: Float, p2: Float, m1: Float, m2: Float,
        lo: Float, hi: Float, out: FloatArray
    ): Boolean {
        val loEff = min(lo, min(p1, p2))
        val hiEff = max(hi, max(p1, p2))
        cubicRange(p1, p2, m1, m2, out)
        return out[0] >= loEff - TOLERANCE_HZ - SLACK &&
            out[1] <= hiEff + TOLERANCE_HZ + SLACK
    }

    /**
     * Наибольший k ∈ [0; 1], при котором сегмент с касательными (k·m1, k·m2)
     * остаётся допустимым.
     *
     * k = 0 допустим ВСЕГДА (при нулевых касательных кубика монотонна между p1
     * и p2, т.е. лежит внутри эффективных границ), поэтому множество допустимых
     * k — отрезок, начинающийся в нуле, и бинарный поиск по нему корректен.
     */
    private fun maxScale(
        p1: Float, p2: Float, m1: Float, m2: Float,
        lo: Float, hi: Float, out: FloatArray
    ): Float {
        if (feasible(p1, p2, m1, m2, lo, hi, out)) return 1f
        if (!feasible(p1, p2, 0f, 0f, lo, hi, out)) return 0f
        var good = 0f
        var bad = 1f
        repeat(BISECTION_STEPS) {
            val mid = (good + bad) * 0.5f
            if (feasible(p1, p2, m1 * mid, m2 * mid, lo, hi, out)) good = mid else bad = mid
        }
        return good
    }
}
