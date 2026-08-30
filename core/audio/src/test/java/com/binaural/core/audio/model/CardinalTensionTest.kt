package com.binaural.core.audio.model

import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Инварианты регулировки натяжения кардинального сплайна ([CardinalTension]).
 *
 * Проверяется ровно то, что обещано алгоритмом и что ломается у наивных
 * альтернатив:
 *
 * 1. ГРАНИЦЫ — после регулировки канальные кривые не выходят за
 *    [carrierRange] (кроме допуска на касание [CardinalTension.TOLERANCE_HZ]).
 * 2. КАСАНИЕ, А НЕ ПЕРЕСКОК — там, где номинальный сплайн вылетал за границу,
 *    отрегулированный подходит к ней вплотную, а не зависает далеко внутри.
 * 3. БИЕНИЯ НЕ СХЛОПЫВАЮТСЯ — beat(t) = right(t) − left(t) остаётся ТОЧНОЙ
 *    интерполяцией узлов частоты биений с теми же весами. Это прямое следствие
 *    ОБЩЕГО веса на узел; при независимых весах на канал тождество рушится и
 *    каналы в точке касания слипаются.
 * 4. НЕСУЩАЯ НЕ РАСХОДИТСЯ — carrier(t) = (left+right)/2 остаётся точной
 *    интерполяцией узлов несущей. Иначе базовая кривая поехала бы
 *    относительно нарисованных каналов.
 * 5. БЕЗ ИЗЛОМА — регуляция домножает касательную в узле на ОДИН и тот же
 *    вес слева и справа, поэтому кривая остаётся C¹-гладкой там, где была
 *    гладкой номинальная.
 * 6. БИЕНИЯ НЕ СХЛОПЫВАЮТСЯ — кривая биений пересекает ноль РОВНО столько раз,
 *    сколько предписано её узлами, и на интервале с однозначными узлами не
 *    подходит к нулю ближе их меньшего модуля. Регулировка гасит избыточную
 *    кривизну, а не разность каналов.
 *
 *    ВАЖНО: пересечение нуля, предписанное самими узлами (соседние узлы биений
 *    разного знака), — это не «схлоп», а содержание данных. Сравнивать
 *    знак/модуль биений поточково между номинальной и отрегулированной кривой
 *    вблизи такого перехода бессмысленно: обе кривые там проходят через ноль, и
 *    положение перехода неизбежно сдвигается на единицы процентов интервала.
 *
 * ЗАМЕР ВРЕМЕНИ: все сэмплы берутся по ЦЕЛЫМ секундам суток, а ratio
 * считается той же формулой, что и в проде. Иначе `LocalTime.fromSecondOfDay`
 * усекает дробную секунду, ожидаемое и фактическое значения оказываются в
 * разных точках кривой, и тест ловит разницу в единицы мГц, которой нет.
 */
class CardinalTensionTest {

    // ------------------------------------------------------------------
    // Пресеты (carrierRange = 100…600 Гц, как в графике по умолчанию)
    // ------------------------------------------------------------------

    private val range = FrequencyRange(100.0f, 600.0f)

    /** (секунды суток, несущая, частота биений) — до клампа по геометрии. */
    private val presets: Map<String, List<Triple<Int, Float, Float>>> = mapOf(
        "пик у верхней границы" to listOf(
            Triple(0, 174f, 3f), Triple(10800, 210f, 6f), Triple(21600, 220f, 8f),
            Triple(32400, 560f, 20f), Triple(43200, 575f, 25f), Triple(54000, 560f, 18f),
            Triple(64800, 250f, 12f), Triple(75600, 240f, 10f)
        ),
        "провал у нижней границы" to listOf(
            Triple(0, 500f, 4f), Triple(21600, 105f, 6f),
            Triple(43200, 110f, 8f), Triple(64800, 520f, 10f)
        ),
        "зигзаг (узел ровно на границе)" to listOf(
            Triple(0, 560f, 30f), Triple(14400, 120f, 30f), Triple(28800, 570f, 30f),
            Triple(43200, 115f, 30f), Triple(57600, 565f, 30f), Triple(72000, 125f, 30f)
        ),
        "резкие броски к обеим границам" to listOf(
            Triple(0, 590f, 18f), Triple(21600, 101f, 4f),
            Triple(43200, 595f, 8f), Triple(64800, 300f, 14f)
        ),
        "отрицательные биения, заскок вверх" to listOf(
            Triple(0, 138.2f, 5.27f), Triple(21600, 179.1f, 27.5f),
            Triple(43200, 582.6f, -20.94f), Triple(64800, 557.6f, -36.99f)
        ),
        // Неравномерная сетка: здесь номинальный Catmull-Rom НЕ C¹ по времени
        // (касательная s·(y[i+1]−y[i−1]) не равна производной при разной длине
        // интервалов). Регуляция обязана сохранить СУЩЕСТВУЮЩИЙ излом, а не
        // добавить свой.
        "неравномерная сетка" to listOf(
            Triple(0, 520f, 12f), Triple(3600, 130f, 6f), Triple(28800, 560f, 22f),
            Triple(36000, 140f, 9f), Triple(82800, 480f, 16f)
        )
    )

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private fun pointsOf(raw: List<Triple<Int, Float, Float>>): List<FrequencyPoint> =
        raw.map { (t, c, b) ->
            FrequencyPoint(
                time = LocalTime.fromSecondOfDay(t),
                carrierFrequency = c,
                beatFrequency = FrequencyMath.clampBeat(c, b, carrierRange = range)
            )
        }.sortedBy { it.time.toSecondOfDay() }

    private fun timesOf(points: List<FrequencyPoint>): IntArray =
        IntArray(points.size) { points[it].time.toSecondOfDay() }

    /** Длительность интервала [i → i+1] в секундах; для последнего — через полночь. */
    private fun durationOf(times: IntArray, i: Int): Int {
        val n = times.size
        return if (i == n - 1) times[0] + 86400 - times[i] else times[i + 1] - times[i]
    }

    /**
     * ratio интервала [i → i+1] для момента [seconds] — ТОЙ ЖЕ формулой, что и в
     * `Interpolation.interpolateChannels`. Иначе ожидаемое и фактическое
     * значения считаются в разных точках кривой.
     */
    private fun ratioAt(times: IntArray, i: Int, seconds: Int): Float {
        val n = times.size
        val wrapping = (i == n - 1)
        val t1 = times[i].toFloat()
        var t2 = times[(i + 1) % n].toFloat()
        if (wrapping) t2 += 24f * 3600f
        var t = seconds.toFloat()
        if (wrapping && t < t1) t += 24f * 3600f
        return if (t2 != t1) ((t - t1) / (t2 - t1)).coerceIn(0f, 1f) else 0f
    }

    /**
     * Целые секунды суток, принадлежащие интервалу [i → i+1]
     * (для последнего — включая переход через полночь).
     */
    private fun sampleSeconds(times: IntArray, i: Int, perInterval: Int): IntArray {
        val dur = durationOf(times, i)
        if (dur <= 0) return IntArray(0)
        val count = max(2, min(perInterval, dur))
        return IntArray(count) { s ->
            val offset = (s.toLong() * dur / count).toInt()
            (times[i] + offset) % 86400
        }
    }

    /**
     * Значение сплайна на интервале [i → j] с весами — тот же выбор циклических
     * соседей, что в проде (prevIndex / nextNextIndex по модулю n).
     */
    private fun splineAt(
        ys: FloatArray, i: Int, j: Int, ratio: Float, tension: Float, w: FloatArray?
    ): Float {
        val n = ys.size
        val p0 = ys[(i - 1 + n) % n]
        val p1 = ys[i]
        val p2 = ys[j]
        val p3 = ys[(j + 1) % n]
        return Interpolation.cardinal(
            p0, p1, p2, p3, ratio, tension, w?.get(i) ?: 1f, w?.get(j) ?: 1f
        )
    }

    private fun channelAt(
        points: List<FrequencyPoint>, seconds: Int, tension: Float, w: FloatArray?
    ): Pair<Float, Float> =
        Interpolation.interpolateChannels(
            points, LocalTime.fromSecondOfDay(seconds),
            InterpolationType.CARDINAL, tension, presorted = true, weights = w
        )

    /**
     * Веса кривой. null от [CardinalTension.forPoints] означает «коррекция не
     * нужна», что равносильно единичным весам — для проверок инвариантов это
     * одно и то же, поэтому наружу отдаём массив единиц.
     */
    private fun effectiveWeights(points: List<FrequencyPoint>, tension: Float = 0.0f): FloatArray =
        CardinalTension.forPoints(
            points, InterpolationType.CARDINAL, tension, range, presorted = true
        ) ?: FloatArray(points.size) { 1f }

    /** Обход всех сэмплов кривой: (интервал, ratio, секунды суток). */
    private inline fun forEachSample(
        points: List<FrequencyPoint>,
        perInterval: Int = 120,
        body: (interval: Int, ratio: Float, seconds: Int) -> Unit
    ) {
        val times = timesOf(points)
        for (i in times.indices) {
            for (sec in sampleSeconds(times, i, perInterval)) {
                body(i, ratioAt(times, i, sec), sec)
            }
        }
    }

    /**
     * Односторонняя производная по ВРЕМЕНИ (Гц/с) в узле [k]: [side] = −1 — в
     * конце интервала слева, +1 — в начале интервала справа.
     *
     * Считается аналитически по сплайну, а не конечной разностью по выходу
     * `interpolateChannels`: на пологих участках производная ~1e-3 Гц/с, и
     * конечная разность шумит сравнимо с ней.
     */
    private fun derivativePerSecond(
        points: List<FrequencyPoint>,
        k: Int,
        side: Int,
        tension: Float,
        w: FloatArray?
    ): Pair<Float, Float> {      // (левый канал, правый канал)
        val n = points.size
        val times = timesOf(points)
        val leftY = FloatArray(n) { points[it].leftChannelFrequency }
        val rightY = FloatArray(n) { points[it].rightChannelFrequency }
        val d = 1e-3f

        fun slope(ys: FloatArray, i: Int, j: Int, from: Float, to: Float): Float {
            val dur = durationOf(times, i).toFloat()
            val a = splineAt(ys, i, j, from, tension, w)
            val b = splineAt(ys, i, j, to, tension, w)
            return (b - a) / (to - from) / dur
        }

        return if (side < 0) {
            val i = (k - 1 + n) % n
            slope(leftY, i, k, 1f - d, 1f) to slope(rightY, i, k, 1f - d, 1f)
        } else {
            val j = (k + 1) % n
            slope(leftY, k, j, 0f, d) to slope(rightY, k, j, 0f, d)
        }
    }

    private fun isUniformGrid(points: List<FrequencyPoint>): Boolean {
        val times = timesOf(points)
        val n = times.size
        val step = times[1] - times[0]
        return (1 until n).all { times[it] - times[it - 1] == step } &&
            (times[0] + 86400 - times[n - 1]) == step
    }

    private fun randomPoints(rnd: Random, n: Int): List<FrequencyPoint> {
        val times = LinkedHashSet<Int>()
        while (times.size < n) times.add(rnd.nextInt(0, 86400))
        return times.sorted().map { t ->
            // Каждая четвёртая точка кладётся РОВНО на границу — самый злой
            // случай для алгоритма (узел на границе даёт формальное нарушение,
            // которое нельзя устранить никаким конечным весом, см. TOLERANCE_HZ).
            val carrier = if (rnd.nextInt(4) == 0) {
                if (rnd.nextBoolean()) range.min else range.max
            } else {
                range.min + rnd.nextFloat() * (range.max - range.min)
            }
            val beat = rnd.nextFloat() * 80f - 40f
            FrequencyPoint(
                time = LocalTime.fromSecondOfDay(t),
                carrierFrequency = carrier,
                beatFrequency = FrequencyMath.clampBeat(carrier, beat, carrierRange = range)
            )
        }
    }

    // ------------------------------------------------------------------
    // 1. Границы
    // ------------------------------------------------------------------

    @Test
    fun regulatedChannelsStayWithinBounds() {
        val allowance = CardinalTension.TOLERANCE_HZ + 0.02f
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val w = effectiveWeights(points)
            forEachSample(points) { i, ratio, seconds ->
                val (left, right) = channelAt(points, seconds, 0.0f, w)
                for (v in floatArrayOf(left, right)) {
                    assertTrue(
                        "$name: канал $v Гц вышел за [${range.min}; ${range.max}] " +
                            "(интервал $i, ratio=$ratio)",
                        v >= range.min - allowance && v <= range.max + allowance
                    )
                }
            }
        }
    }

    @Test
    fun nominalChannelsDoOvershoot() {
        // Контрольный тест: без регулировки те же пресеты ГРАНИЦЫ нарушают.
        // Иначе тест выше проходил бы просто потому, что overshoot нет вообще.
        var worst = 0f
        for ((_, raw) in presets) {
            val points = pointsOf(raw)
            forEachSample(points) { _, _, seconds ->
                val (left, right) = channelAt(points, seconds, 0.0f, null)
                worst = maxOf(
                    worst,
                    range.max - right, range.max - left,
                    left - range.min, right - range.min
                )
            }
        }
        assertTrue(
            "номинальный Catmull-Rom должен вылетать за границы (максимум $worst Гц)",
            worst > 1.0f
        )
    }

    @Test
    fun randomCurvesStayWithinBounds() {
        // Стресс: случайные кривые (в т.ч. с узлами ровно на границах и с
        // почти слипшимися по времени точками) обязаны остаться в границах.
        // Это главная гарантия регуляции — не «на этих шести пресетах», а вообще.
        val rnd = Random(20260830)
        val allowance = CardinalTension.TOLERANCE_HZ + 0.05f
        var regulated = 0
        repeat(300) {
            val points = randomPoints(rnd, 2 + rnd.nextInt(11))
            val w = CardinalTension.forPoints(
                points, InterpolationType.CARDINAL, 0.0f, range, presorted = true
            )
            if (w != null) regulated++
            val effective = w ?: FloatArray(points.size) { 1f }
            forEachSample(points, perInterval = 24) { i, ratio, seconds ->
                val (left, right) = channelAt(points, seconds, 0.0f, effective)
                for (v in floatArrayOf(left, right)) {
                    assertTrue(
                        "случайная кривая: канал $v Гц вышел за границы " +
                            "(интервал $i, ratio=$ratio)",
                        v >= range.min - allowance && v <= range.max + allowance
                    )
                }
            }
        }
        // Стресс обязан быть содержательным: часть кривых реально регулируется.
        assertTrue(
            "стресс не воспроизвёл ни одного overshoot (регулировано $regulated)",
            regulated > 30
        )
    }

    // ------------------------------------------------------------------
    // 2. Касание, а не перескок
    // ------------------------------------------------------------------

    @Test
    fun regulatedCurveTouchesTheBoundaryItUsedToJumpOver() {
        // Там, где номинальный сплайн вылетал за границу, отрегулированный
        // обязан подойти к ней вплотную (иначе касательные погашены сильнее
        // нужного и форма кривой испорчена зря), но не перейти её.
        val allowance = CardinalTension.TOLERANCE_HZ + 0.02f
        var checked = 0
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val w = effectiveWeights(points)

            var nomMin = Float.MAX_VALUE
            var nomMax = -Float.MAX_VALUE
            var regMin = Float.MAX_VALUE
            var regMax = -Float.MAX_VALUE
            forEachSample(points, perInterval = 400) { _, _, seconds ->
                val nom = channelAt(points, seconds, 0.0f, null)
                val reg = channelAt(points, seconds, 0.0f, w)
                nomMin = min(nomMin, min(nom.first, nom.second))
                nomMax = max(nomMax, max(nom.first, nom.second))
                regMin = min(regMin, min(reg.first, reg.second))
                regMax = max(regMax, max(reg.first, reg.second))
            }

            if (nomMax > range.max + 0.5f) {
                checked++
                assertTrue(
                    "$name: выброс вверх $nomMax Гц — отрегулированная кривая не " +
                        "подошла к верхней границе ${range.max} (максимум $regMax Гц)",
                    regMax >= range.max - 2.0f
                )
                assertTrue(
                    "$name: отрегулированная кривая перескочила верхнюю границу ($regMax Гц)",
                    regMax <= range.max + allowance
                )
            }
            if (nomMin < range.min - 0.5f) {
                checked++
                assertTrue(
                    "$name: провал вниз $nomMin Гц — отрегулированная кривая не " +
                        "подошла к нижней границе ${range.min} (минимум $regMin Гц)",
                    regMin <= range.min + 2.0f
                )
                assertTrue(
                    "$name: отрегулированная кривая перескочила нижнюю границу ($regMin Гц)",
                    regMin >= range.min - allowance
                )
            }
        }
        assertTrue("ни один пресет не дал overshoot — тест касания бессмысленен", checked > 0)
    }

    @Test
    fun nullWeightsMeanNoMeaningfulOvershoot() {
        // null — это не «забыли посчитать», а «считать нечего». Проверяем
        // обратное: если алгоритм сказал «коррекция не нужна», номинальная
        // кривая действительно не выходит за границы сверх допуска. Без этого
        // теста null мог бы означать «алгоритм сломался и сдался».
        val allowance = CardinalTension.TOLERANCE_HZ + 0.05f
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val w = CardinalTension.forPoints(
                points, InterpolationType.CARDINAL, 0.0f, range, presorted = true
            )
            if (w != null) continue
            forEachSample(points, perInterval = 400) { i, ratio, seconds ->
                val (left, right) = channelAt(points, seconds, 0.0f, null)
                for (v in floatArrayOf(left, right)) {
                    assertTrue(
                        "$name: веса null, но номинальная кривая выходит за границы " +
                            "($v Гц, интервал $i, ratio=$ratio)",
                        v >= range.min - allowance && v <= range.max + allowance
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3–4. Биения и несущая остаются точными сплайнами своих узлов
    // ------------------------------------------------------------------

    @Test
    fun beatRemainsExactSplineOfBeatKnots() {
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val w = effectiveWeights(points)
            val n = points.size
            val beatY = FloatArray(n) { points[it].beatFrequency }
            forEachSample(points) { i, ratio, seconds ->
                val (left, right) = channelAt(points, seconds, 0.0f, w)
                val expected = splineAt(beatY, i, (i + 1) % n, ratio, 0.0f, w)
                assertEquals(
                    "$name: beat ≠ spline(beat) на интервале $i, ratio=$ratio",
                    expected, right - left, 2e-3f
                )
            }
        }
    }

    @Test
    fun carrierRemainsExactSplineOfCarrierKnots() {
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val w = effectiveWeights(points)
            val n = points.size
            val carrierY = FloatArray(n) { points[it].carrierFrequency }
            forEachSample(points) { i, ratio, seconds ->
                val (left, right) = channelAt(points, seconds, 0.0f, w)
                val expected = splineAt(carrierY, i, (i + 1) % n, ratio, 0.0f, w)
                assertEquals(
                    "$name: carrier ≠ spline(carrier) на интервале $i, ratio=$ratio",
                    expected, (left + right) / 2f, 2e-3f
                )
            }
        }
    }

    @Test
    fun beatNeverCollapsesWhenKnotsShareSign() {
        // Главный страх из постановки задачи: «кривые каналов схлопываются в
        // точке касания с границей, сводя частоту биений к нулю». При ОБЩЕМ
        // весе beat(t) — тот же сплайн узлов beat, только с укороченными
        // касательными: если узлы одного знака и номинальная кривая не проходит
        // через ноль, то и отрегулированная не пройдёт.
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            if (points.any { it.beatFrequency <= 0f }) continue   // знак не един
            val w = effectiveWeights(points)
            forEachSample(points, perInterval = 400) { i, ratio, seconds ->
                val (left, right) = channelAt(points, seconds, 0.0f, w)
                val beat = right - left
                assertTrue(
                    "$name: частота биений схлопнулась до $beat Гц " +
                        "(интервал $i, ratio=$ratio) — каналы слиплись",
                    beat > 0.5f
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 5. Без излома
    // ------------------------------------------------------------------

    @Test
    fun regulationDoesNotAddAKinkOnUniformGrid() {
        // Равномерная сетка: номинальный Catmull-Rom C¹-гладок, и после
        // регуляции он обязан остаться гладким — касательная узла домножается
        // на один и тот же вес с обеих сторон.
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            if (!isUniformGrid(points)) continue

            val w = effectiveWeights(points)
            for (k in points.indices) {
                val (dlL, dlR) = derivativePerSecond(points, k, -1, 0.0f, w)
                val (drL, drR) = derivativePerSecond(points, k, +1, 0.0f, w)
                for ((dl, dr) in arrayOf(dlL to drL, dlR to drR)) {
                    val scale = max(max(abs(dl), abs(dr)), 0.05f)
                    assertEquals(
                        "$name: излом в узле $k (${timeLabel(timesOf(points)[k])}): " +
                            "слева $dl Гц/с, справа $dr Гц/с",
                        dl, dr, scale * 0.01f
                    )
                }
            }
        }
    }

    @Test
    fun regulationPreservesExistingKinkOnNonUniformGrid() {
        // Неравномерная сетка. Номинальный сплайн здесь НЕ C¹ по времени, и
        // регуляция не обязана это исправлять — но и не имеет права менять
        // СООТНОШЕНИЕ односторонних производных: обе они домножаются на w[k].
        val points = pointsOf(presets.getValue("неравномерная сетка"))
        val w = effectiveWeights(points)

        for (k in points.indices) {
            val nomLeft = derivativePerSecond(points, k, -1, 0.0f, null)
            val nomRight = derivativePerSecond(points, k, +1, 0.0f, null)
            val regLeft = derivativePerSecond(points, k, -1, 0.0f, w)
            val regRight = derivativePerSecond(points, k, +1, 0.0f, w)
            val wk = w[k]
            for (channel in 0..1) {
                val nl = if (channel == 0) nomLeft.first else nomLeft.second
                val nr = if (channel == 0) nomRight.first else nomRight.second
                val rl = if (channel == 0) regLeft.first else regLeft.second
                val rr = if (channel == 0) regRight.first else regRight.second
                val tol = max(max(abs(nl), abs(nr)), 0.05f) * 0.01f
                assertEquals(
                    "узел $k, канал $channel: касательная слева не домножена на вес $wk",
                    wk * nl, rl, tol
                )
                assertEquals(
                    "узел $k, канал $channel: касательная справа не домножена на вес $wk",
                    wk * nr, rr, tol
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 6. Модуль и знак биений не разрушаются
    // ------------------------------------------------------------------

    /** Знак с мёртвой зоной: в окне ±0.01 Гц знак не определён, и это не «схлоп». */
    private fun signum(v: Float): Int = when {
        v > 0.01f -> 1
        v < -0.01f -> -1
        else -> 0
    }

    @Test
    fun beatChangesSignOnlyWhereKnotsDemandIt() {
        // Прямая формулировка «каналы не схлопываются»: кривая биений обязана
        // пересечь ноль РОВНО столько раз, сколько предписано её узлами — по
        // одному разу на каждый интервал, у которого концевые узлы разного
        // знака, и ни разу сверх того.
        //
        // Лишняя пара нулей означала бы, что каналы сошлись и разошлись посреди
        // интервала: ровно тот «схлоп» частоты биений, которого постановка
        // задачи требует избежать. При ОБЩЕМ весе beat(t) — точный сплайн своих
        // же узлов, поэтому лишний ноль может взяться только из данных, но не
        // из регуляции. При независимых весах на канал тождество рушится, и нули
        // появляются на ровном месте.
        //
        // Почему нельзя сравнивать знак поточково с номиналом: если соседние
        // узлы биений разного знака, переход через ноль предписан данными, и его
        // положение при изменении весов сдвигается на единицы процентов
        // интервала. Сравнение «номинал vs регуляция» в этой точке давало бы
        // ложный отказ при любом корректном алгоритме.
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val w = effectiveWeights(points)
            val n = points.size
            val beats = FloatArray(n) { points[it].beatFrequency }

            val mandated = (0 until n).count { i ->
                val a = signum(beats[i])
                val b = signum(beats[(i + 1) % n])
                a != 0 && b != 0 && a != b
            }

            var crossings = 0
            var firstSign = 0
            var prevSign = 0
            forEachSample(points, perInterval = 400) { _, _, seconds ->
                val (left, right) = channelAt(points, seconds, 0.0f, w)
                val s = signum(right - left)
                if (s == 0) return@forEachSample
                if (firstSign == 0) firstSign = s
                if (prevSign != 0 && s != prevSign) crossings++
                prevSign = s
            }
            // Цикл замкнут: от последнего сэмпла к первому — через полночь.
            if (firstSign != 0 && prevSign != 0 && prevSign != firstSign) crossings++

            assertEquals(
                "$name: биения пересекают ноль $crossings раз(а), а узлы предписывают " +
                    "$mandated — регуляция добавила лишний «схлоп» каналов",
                mandated, crossings
            )
        }
    }

    @Test
    fun beatNeverDipsBelowItsOwnKnotsOnSameSignedIntervals() {
        // На интервале, у которого оба концевых узла биений одного знака, кривая
        // биений обязана держаться не ближе к нулю, чем меньший из них: каналы
        // не сходятся теснее, чем их расставил пользователь.
        //
        // Базисная часть кубики h00·y1 + h01·y2 лежит внутри [min; max] своих
        // узлов, поэтому уйти внутрь можно только за счёт касательных — и ровно
        // настолько, насколько их оставила регуляция. Допуск берётся не «на
        // глаз», а из точной оценки вклада касательных:
        //
        //     max|h10| = max|h11| = 4/27   (в t = 1/3 и t = 2/3 соответственно)
        //
        // то есть суммарный прогиб не превышает (4/27)·(|m1| + |m2|). Это верхняя
        // оценка, и она честная: она сжимается там, где регуляция укоротила
        // касательные, то есть ровно на «плохих» участках.
        val maxHermiteSlope = 4f / 27f
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val w = effectiveWeights(points)
            val n = points.size
            val beats = FloatArray(n) { points[it].beatFrequency }

            forEachSample(points, perInterval = 200) { i, ratio, seconds ->
                val j = (i + 1) % n
                val b1 = beats[i]
                val b2 = beats[j]
                val sign = signum(b1)
                if (sign == 0 || sign != signum(b2)) return@forEachSample

                val (left, right) = channelAt(points, seconds, 0.0f, w)
                val beat = right - left
                val m1 = w[i] * 0.5f * (beats[j] - beats[(i - 1 + n) % n])
                val m2 = w[j] * 0.5f * (beats[(j + 1) % n] - beats[i])
                val slack = CardinalTension.TOLERANCE_HZ +
                    maxHermiteSlope * (abs(m1) + abs(m2))
                val floor = min(abs(b1), abs(b2))
                assertTrue(
                    "$name: на интервале $i (ratio=$ratio) биения $beat Гц ниже своего " +
                        "узлового минимума $floor Гц (допуск $slack) — каналы схлопываются",
                    abs(beat) >= floor - slack
                )
                assertEquals(
                    "$name: на интервале $i (ratio=$ratio) знак биений перевернулся " +
                        "($beat Гц при узлах $b1 и $b2)",
                    sign, signum(beat)
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Веса: диапазон, отключение, порядок, дубли времени
    // ------------------------------------------------------------------

    @Test
    fun weightsStayWithinUnitRange() {
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val w = effectiveWeights(points)
            w.forEachIndexed { i, v ->
                assertTrue("$name: вес[$i] = $v вне [0; 1]", v >= 0f && v <= 1f)
            }
        }
    }

    @Test
    fun noRegulationNeededReturnsNull() {
        // Плоская кривая внутри границ — overshoot нет, веса не нужны.
        val flat = listOf(
            FrequencyPoint(LocalTime.fromSecondOfDay(0), 300f, 10f),
            FrequencyPoint(LocalTime.fromSecondOfDay(21600), 300f, 10f),
            FrequencyPoint(LocalTime.fromSecondOfDay(43200), 300f, 10f),
            FrequencyPoint(LocalTime.fromSecondOfDay(64800), 300f, 10f)
        )
        assertNull(CardinalTension.forPoints(flat, InterpolationType.CARDINAL, 0.0f, range))

        // tension = 1 ⇒ s = 0 ⇒ нулевые касательные ⇒ вылетать некуда.
        val spiky = pointsOf(presets.getValue("резкие броски к обеим границам"))
        assertNull(CardinalTension.forPoints(spiky, InterpolationType.CARDINAL, 1.0f, range))

        // Не CARDINAL — регулировка не имеет смысла (MONOTONE и так без overshoot).
        assertNull(CardinalTension.forPoints(spiky, InterpolationType.MONOTONE, 0.0f, range))
        assertNull(CardinalTension.forPoints(spiky, InterpolationType.LINEAR, 0.0f, range))
        assertNull(CardinalTension.forPoints(spiky, InterpolationType.STEP, 0.0f, range))

        // Одна точка — сплайн не определён.
        assertNull(
            CardinalTension.forPoints(
                listOf(FrequencyPoint(LocalTime.fromSecondOfDay(0), 300f, 10f)),
                InterpolationType.CARDINAL, 0.0f, range
            )
        )
    }

    @Test
    fun curveWeightsAreIndexedBySortedPoints() {
        // Точки в UI могут прийти в любом порядке (пользователь таскает их по
        // времени). Веса обязаны соответствовать ОТСОРТИРОВАННОЙ кривой —
        // именно в этом порядке их применит и график, и нативный движок.
        val sorted = pointsOf(presets.getValue("отрицательные биения, заскок вверх"))
        val shuffled = listOf(sorted[2], sorted[0], sorted[3], sorted[1])
        val curve = FrequencyCurve(
            points = shuffled,
            carrierRange = range,
            interpolationType = InterpolationType.CARDINAL,
            splineTension = 0.0f
        )
        val expected = effectiveWeights(sorted)
        assertNotNull(curve.tensionWeights)
        curve.tensionWeights!!.forEachIndexed { i, v ->
            assertEquals(
                "вес[$i] не совпадает с весом отсортированной кривой",
                expected[i], v, 0f
            )
        }
    }

    @Test
    fun curveExposesWeightsForOvershootingPreset() {
        val curve = FrequencyCurve(
            points = pointsOf(presets.getValue("провал у нижней границы")),
            carrierRange = range,
            interpolationType = InterpolationType.CARDINAL,
            splineTension = 0.0f
        )
        // Провал к нижней границе — самый тяжёлый случай: Catmull-Rom уходит
        // к 53 Гц при разрешённых 100 Гц, и веса обязаны это убрать.
        assertNotNull("кривая с overshoot обязана отдать веса", curve.tensionWeights)
        assertTrue(
            "хотя бы один вес должен быть < 1",
            curve.tensionWeights!!.any { it < 1f }
        )
    }

    @Test
    fun duplicateTimePointsCollapseLikeNative() {
        // Нативный buildLookupTableInternal схлопывает точки с одинаковым
        // временем, оставляя ПОСЛЕДНЮЮ, и сравнивает размер весов с числом
        // узлов ПОСЛЕ схлопывания. Если бы Kotlin считал веса по исходному
        // списку, размер разошёлся бы и движок отбросил регулировку целиком.
        val base = pointsOf(presets.getValue("провал у нижней границы"))

        // Дубль времени base[0] с ДРУГОЙ несущей: именно он «выживает» при
        // схлопывании (натив оставляет последнюю точку группы). Частоты
        // подобраны так, чтобы кривая по-прежнему вылетала за нижнюю границу —
        // иначе алгоритм законно вернёт null и тест станет бессмысленным.
        val survivor = FrequencyPoint(base[0].time, 480f, 4f)
        val collapsed = listOf(survivor, base[1], base[2], base[3])
        val collapsedWeights = CardinalTension.forPoints(
            collapsed, InterpolationType.CARDINAL, 0.0f, range, presorted = true
        )
        assertNotNull("схлопнутая кривая обязана вылетать за границу", collapsedWeights)

        // Порядок внутри группы важен: sortedBy стабилен, поэтому исходная
        // точка идёт ПЕРВОЙ, а дубль — вторым, и выживает именно дубль.
        val doubled = listOf(base[0], survivor, base[1], base[2], base[3])
            .sortedBy { it.time.toSecondOfDay() }

        val weights = CardinalTension.forPoints(
            doubled, InterpolationType.CARDINAL, 0.0f, range, presorted = true
        )
        assertNotNull("регулировка обязана считаться и при задвоенных узлах", weights)
        assertEquals(
            "число весов обязано равняться числу узлов ПОСЛЕ схлопывания дублей",
            base.size, weights!!.size
        )
        // И не только числом: веса задвоенного списка обязаны совпасть с
        // весами схлопнутого — это и есть «считаем по тем же узлам, что натив».
        collapsedWeights!!.forEachIndexed { i, v ->
            assertEquals("вес[$i] не совпадает с весом схлопнутой кривой", v, weights[i], 0f)
        }
    }

    // ------------------------------------------------------------------
    // Паритет вычислителей: график и звук обязаны совпадать
    // ------------------------------------------------------------------

    @Test
    fun frequencyCurveAndInterpolationAgree() {
        // FrequencyCurve.getChannelFrequenciesAt (несущая/каналы в UI) и
        // Interpolation.interpolateChannels (каналы в графике) — два разных
        // обхода одних и тех же узлов. Без передачи весов в ОБА они разошлись
        // бы ровно на участках overshoot, то есть базовая кривая «поехала» бы
        // относительно нарисованных каналов.
        for ((name, raw) in presets) {
            val points = pointsOf(raw)
            val curve = FrequencyCurve(
                points = points,
                carrierRange = range,
                interpolationType = InterpolationType.CARDINAL,
                splineTension = 0.0f
            )
            val w = effectiveWeights(points)
            forEachSample(points) { i, ratio, seconds ->
                val fromCurve = curve.getChannelFrequenciesAt(LocalTime.fromSecondOfDay(seconds))
                val direct = channelAt(points, seconds, 0.0f, w)
                assertEquals(
                    "$name: левый канал разошёлся (интервал $i, ratio=$ratio)",
                    direct.first, fromCurve.first, 2e-3f
                )
                assertEquals(
                    "$name: правый канал разошёлся (интервал $i, ratio=$ratio)",
                    direct.second, fromCurve.second, 2e-3f
                )
            }
        }
    }

    private fun timeLabel(seconds: Int): String =
        "%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60)
}
