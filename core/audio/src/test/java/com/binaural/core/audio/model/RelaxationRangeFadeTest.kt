package com.binaural.core.audio.model

import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Угасание периодов расслабления по положению частот в диапазоне
 * ([RelaxationModeSettings.fadeByRangePosition]).
 *
 * Правила R1…R8 и нумерация тестов — docs/design_relaxation_range_fade.md §4, §7.
 * Ключевое, что здесь проверяется:
 *
 * 1. ВЫКЛЮЧЕНО ⇒ ПОБИТОВО КАК РАНЬШЕ (R8). Точки сравниваются не с самими
 *    собой, а с независимой эталонной реализацией [referencePoints] — копией
 *    трапеции и `reduceFrequencies`, написанной по формулам документа. Любое
 *    расхождение прода с эталоном видно сразу.
 * 2. ГОМОТОПИЯ (R5) — главное structural-свойство: при ЛЮБОМ весе
 *    `w ∈ [0; 1]` результат лежит между базовой точкой и полностью сниженной.
 *    Отсюда следует, что ни пол частоты канала, ни кламп модуля биений не
 *    срабатывают ни при каком `w`, и `reduceFrequencies` правок не требует.
 * 3. ТОЖДЕСТВЕННОСТЬ ПРИ w = 0 (R6) — виртуальная точка РАВНА базовой, а не
 *    «примерно равна». Это единственное, что гарантирует отсутствие ступеньки
 *    на стыке периодов у нижней границы диапазона.
 * 4. ВЫРОЖДЕННАЯ ОПОРА (R4) даёт w = 1 — угасание инертно, а НЕ «молча
 *    выключает режим». Обратное потом не диагностируется никак.
 *
 * Эталонная реализация намеренно НЕ переиспользует прод-код: иначе тест
 * вырождается в проверку «код равен сам себе».
 */
class RelaxationRangeFadeTest {

    private val range = FrequencyRange(100.0f, 600.0f)

    private val eps = 1e-4f

    // ------------------------------------------------------------------
    // Пресеты
    // ------------------------------------------------------------------

    /** «Циркадный ритм»: несущая 174…440 Гц, |beat| 3…25 Гц. */
    private val circadian = curve(
        0 to (174f to 3f),
        10800 to (210f to 6f),
        21600 to (220f to 8f),
        32400 to (440f to 20f),
        43200 to (440f to 25f),
        54000 to (440f to 18f),
        64800 to (250f to 12f),
        75600 to (240f to 10f)
    )

    /** Зигзаг с узлами у обеих границ; биения — константа (w ≡ 1 по построению). */
    private val zigzag = curve(
        0 to (560f to 30f),
        14400 to (120f to 30f),
        28800 to (570f to 30f),
        43200 to (115f to 30f),
        57600 to (565f to 30f),
        72000 to (125f to 30f),
        type = InterpolationType.CARDINAL
    )

    /** CARDINAL с размахом биений — сплайн вылетает за узлы (overshoot). */
    private val overshootingBeats = curve(
        0 to (300f to 5f),
        21600 to (500f to 40f),
        43200 to (300f to 5f),
        64800 to (500f to 40f),
        type = InterpolationType.CARDINAL
    )

    /** Отрицательные биения: знак задаёт раскладку каналов, а не глубину. */
    private val negativeBeats = curve(
        0 to (138.2f to 5.27f),
        21600 to (179.1f to 27.5f),
        43200 to (582.6f to -20.94f),
        64800 to (557.6f to -36.99f)
    )

    /** Несущая — константа на максимуме диапазона, биения меняются. */
    private val topCarrierVaryingBeat = curve(
        0 to (600f to 40f),
        43200 to (600f to 5f),
        86399 to (600f to 40f)
    )

    private val allPresets = mapOf(
        "циркадный" to circadian,
        "зигзаг CARDINAL" to zigzag,
        "overshoot биений" to overshootingBeats,
        "отрицательные биения" to negativeBeats,
        "новый пресет (1 точка)" to FrequencyCurve.newPresetCurve()
    )

    // ------------------------------------------------------------------
    // 1. Выключено ⇒ побитого как раньше
    // ------------------------------------------------------------------

    @Test
    fun fadeDisabledMatchesReferenceGenerator() {
        val settings = settings(fade = false)
        for ((name, curve) in allPresets) {
            val actual = settings.generateVirtualPoints(curve)
            val expected = referencePoints(curve, settings, wCarrier = 1.0f, wBeat = 1.0f)
            assertSamePoints("без угасания «$name»", expected, actual)
        }
    }

    // ------------------------------------------------------------------
    // 2. Дно диапазона ⇒ глубина 0
    // ------------------------------------------------------------------

    @Test
    fun carrierAtCurveMinimumIsNotReduced() {
        // Плато первого периода (t2 = 180 с, t3 = 1080 с) лежит РОВНО на
        // реальном минимуме несущей кривой (200 Гц); объявленная граница
        // `carrierRange` здесь ни при чём. ⇒ w_carrier = 0, снижение несущей
        // обязано выродиться в тождественное преобразование. Биения — константа
        // ⇒ w_beat = 1 (вырожденная опора).
        val minC = 200.0f
        val maxC = 500.0f
        val curve = curve(
            0 to (minC to 8.0f),
            180 to (minC to 8.0f),
            1080 to (minC to 8.0f),
            43200 to (maxC to 8.0f),
            86399 to (minC to 8.0f)
        )
        val settings = settings(fade = true, carrierPercent = 25, beatPercent = 50)

        val points = settings.generateVirtualPoints(curve)
        val minPlateau = points.filter { it.time.toSecondOfDay() in setOf(180, 1080) }
        assertTrue("есть плато первого периода", minPlateau.isNotEmpty())
        for (point in minPlateau) {
            assertClose(
                "несущая на минимуме кривой не должна снижаться",
                curve.getCarrierFrequencyAt(point.time), point.carrierFrequency
            )
        }
    }

    @Test
    fun zeroBeatIsNotReduced() {
        // |beat| ≡ 0 ⇒ снижать нечего. Побочно проверяется, что вырожденная
        // опора биений (beatTop = 0) не даёт NaN (R4, тест 8).
        val curve = curve(
            0 to (174f to 0f),
            21600 to (300f to 0f),
            43200 to (500f to 0f),
            64800 to (220f to 0f)
        )
        val settings = settings(fade = true, carrierPercent = 100, beatPercent = 200)

        val points = settings.generateVirtualPoints(curve)
        assertTrue("точки должны генерироваться", points.isNotEmpty())
        for (point in points) {
            assertFalse("частота биений не должна быть NaN", point.beatFrequency.isNaN())
            assertFalse("несущая не должна быть NaN", point.carrierFrequency.isNaN())
            assertClose("нулевые биения обязаны остаться нулевыми", 0.0f, point.beatFrequency)
        }
    }

    // ------------------------------------------------------------------
    // 3. Верх диапазона ⇒ полная глубина
    // ------------------------------------------------------------------

    @Test
    fun carrierAtCurveMaximumGetsFullDepth() {
        // Плато первого периода (t2 = 180 с, t3 = 1080 с) лежит РОВНО на
        // реальном максимуме несущей кривой (500 Гц) ⇒ w_carrier = 1 ⇒
        // снижение на полную глубину слайдера.
        val minC = 200.0f
        val maxC = 500.0f
        val curve = curve(
            0 to (minC to 25.0f),
            180 to (maxC to 25.0f),
            1080 to (maxC to 25.0f),
            43200 to (minC to 25.0f),
            86399 to (maxC to 25.0f)
        )
        val settings = settings(fade = true, carrierPercent = 25, beatPercent = 50)

        val points = settings.generateVirtualPoints(curve)
        val maxPlateau = points.filter { it.time.toSecondOfDay() in setOf(180, 1080) }
        assertTrue("есть плато первого периода", maxPlateau.isNotEmpty())
        for (point in maxPlateau) {
            assertClose(
                "несущая на максимуме кривой — полная глубина",
                maxC * (1.0f - settings.carrierReductionPercent / 100.0f),
                point.carrierFrequency,
                1e-3f
            )
        }
    }

    // ------------------------------------------------------------------
    // 4. Монотонность
    // ------------------------------------------------------------------

    @Test
    fun weightsGrowWithPositionInRange() {
        // Прямая проверка нормализации весов на РЕАЛЬНЫЙ размах кривой:
        // по обеим осям вес неубывает от минимума кривой к максимуму, и каждая
        // ось независима.
        val s = settings(fade = true)
        val cLo = 200.0f
        val cHi = 500.0f
        val cSpan = cHi - cLo
        val bLo = 5.0f
        val bHi = 25.0f

        var prevC = -1.0f
        for (carrier in 200..500 step 5) {
            val w = s.fadeWeights(carrier.toFloat(), 0.0f, cLo, cSpan, bLo, bHi).first
            assertTrue("wCarrier неубывает: carrier=$carrier", w >= prevC - 1e-4f)
            prevC = w
        }
        var prevB = -1.0f
        for (b in 5..25 step 1) {
            val w = s.fadeWeights(0.0f, b.toFloat(), cLo, cSpan, bLo, bHi).second
            assertTrue("wBeat неубывает: |beat|=$b", w >= prevB - 1e-4f)
            prevB = w
        }
    }

    @Test
    fun beatDepthGrowsWithBeatMagnitude() {
        // Несущая — константа на максимуме диапазона: w_carrier = 1 везде, пол
        // частоты канала заведомо не срабатывает, и глубина биений зависит
        // только от |beat|.
        val settings = settings(fade = true, carrierPercent = 25, beatPercent = 50)
        val samples = settings.generateVirtualPoints(topCarrierVaryingBeat)
            .filter { it.isFloor(settings) }
            .map { point ->
                val base = abs(topCarrierVaryingBeat.getBeatFrequencyAt(point.time))
                val depth = 1.0f - abs(point.beatFrequency) / base
                base to depth
            }
            .sortedBy { it.first }

        assertTrue("должно быть что сравнивать", samples.size > 1)
        for (i in 1 until samples.size) {
            val (base, depth) = samples[i]
            val (previousBase, previousDepth) = samples[i - 1]
            assertTrue(
                "глубина биений не должна убывать: |beat|=$base, " +
                    "depth=$depth < previous=$previousDepth при |beat|=$previousBase",
                depth >= previousDepth - eps
            )
        }
    }

    // ------------------------------------------------------------------
    // 5. Тождественность при w = 0 (R6)
    // ------------------------------------------------------------------

    @Test
    fun zeroWeightReproducesTheBaseCurveExactly() {
        // Плато первого периода (t2 = 180 с, t3 = 1080 с) лежит РОВНО на
        // реальном минимуме несущей (200 Гц) при beat ≡ 0: оба веса на этих
        // точках равны 0 ⇒ преобразование тождественно именно там (правило
        // R6), без «почти тождественно». Вырожденная ось (плоская кривая)
        // дала бы w = 1, поэтому минимум берётся из РЕАЛЬНОГО размаха, а не
        // из flat-кривой.
        val minC = 200.0f
        val curve = curve(
            0 to (minC to 0.0f),
            180 to (minC to 0.0f),
            1080 to (minC to 0.0f),
            43200 to (500.0f to 0.0f),
            86399 to (minC to 0.0f)
        )
        val settings = settings(fade = true, carrierPercent = 100, beatPercent = 200)

        val points = settings.generateVirtualPoints(curve)
        assertTrue("точки должны генерироваться", points.isNotEmpty())
        for (point in points) {
            assertFalse("несущая не должна быть NaN", point.carrierFrequency.isNaN())
            assertFalse("частота биений не должна быть NaN", point.beatFrequency.isNaN())
        }
        val minPlateau = points.filter { it.time.toSecondOfDay() in setOf(180, 1080) }
        assertTrue("есть плато первого периода", minPlateau.isNotEmpty())
        for (point in minPlateau) {
            assertClose(
                "при w = 0 несущая должна быть РАВНА базовой",
                curve.getCarrierFrequencyAt(point.time), point.carrierFrequency, 1e-6f
            )
            assertClose(
                "при w = 0 биения должны быть РАВНЫ базовым",
                curve.getBeatFrequencyAt(point.time), point.beatFrequency, 1e-6f
            )
        }
    }

    // ------------------------------------------------------------------
    // Прямые тесты нормализации весов (fadeWeights сделан internal)
    // ------------------------------------------------------------------

    @Test
    fun fadeWeightsNormalizesToRealExtent() {
        // Носитель каждой оси — РЕАЛЬНЫЙ размах кривой, а не carrierRange:
        // 0 на минимуме, 1 на максимуме, 0.5 посередине, с клампом за пределами.
        val s = settings(fade = true)
        val cLo = 200.0f
        val cHi = 500.0f
        val cSpan = cHi - cLo
        val bLo = 5.0f
        val bHi = 25.0f

        assertClose("wCarrier на минимуме", 0.0f, s.fadeWeights(200f, 0f, cLo, cSpan, bLo, bHi).first)
        assertClose("wCarrier на максимуме", 1.0f, s.fadeWeights(500f, 0f, cLo, cSpan, bLo, bHi).first)
        assertClose("wCarrier посередине", 0.5f, s.fadeWeights(350f, 0f, cLo, cSpan, bLo, bHi).first)
        assertClose("wCarrier кламп в 0", 0.0f, s.fadeWeights(100f, 0f, cLo, cSpan, bLo, bHi).first)
        assertClose("wCarrier кламп в 1", 1.0f, s.fadeWeights(900f, 0f, cLo, cSpan, bLo, bHi).first)

        assertClose("wBeat на минимуме", 0.0f, s.fadeWeights(0f, 5f, cLo, cSpan, bLo, bHi).second)
        assertClose("wBeat на максимуме", 1.0f, s.fadeWeights(0f, 25f, cLo, cSpan, bLo, bHi).second)
        assertClose("wBeat посередине", 0.5f, s.fadeWeights(0f, 15f, cLo, cSpan, bLo, bHi).second)
        assertClose("wBeat кламп в 0", 0.0f, s.fadeWeights(0f, 0f, cLo, cSpan, bLo, bHi).second)
        assertClose("wBeat кламп в 1", 1.0f, s.fadeWeights(0f, 40f, cLo, cSpan, bLo, bHi).second)
    }

    @Test
    fun fadeWeightsDegenerateExtentFallsBackToFull() {
        // Вырожденная ось (размах ≈ 0) ⇒ вес 1 (правило R4): угасание инертно,
        // а НЕ «молча выключает режим». Отличается от старого поведения, где
        // плоская кривая на carrierRange.min давала w = 0 (и тем самым глушила
        // режим).
        val s = settings(fade = true)
        val w = s.fadeWeights(200f, 8f, 200f, 0.0f, 8f, 8f)
        assertClose("вырожденная несущая ⇒ w = 1", 1.0f, w.first)
        assertClose("вырожденные биения ⇒ w = 1", 1.0f, w.second)
    }

    @Test
    fun fadeWeightsDisabledReturnsFull() {
        val s = settings(fade = false)
        val w = s.fadeWeights(200f, 8f, 200f, 300f, 5f, 25f)
        assertClose("выключено ⇒ w_carrier = 1", 1.0f, w.first)
        assertClose("выключено ⇒ w_beat = 1", 1.0f, w.second)
    }

    // ------------------------------------------------------------------
    // 6. Гомотопия (R5)
    // ------------------------------------------------------------------

    @Test
    fun fadedPointStaysBetweenBaseAndFullyReduced() {
        val combinations = listOf(
            settings(fade = true, carrierPercent = 0, beatPercent = 0),
            settings(fade = true, carrierPercent = 25, beatPercent = 50),
            settings(fade = true, carrierPercent = 100, beatPercent = 100),
            settings(fade = true, carrierPercent = 100, beatPercent = 150),
            settings(fade = true, carrierPercent = 100, beatPercent = 200)
        )

        for (settings in combinations) {
            for ((name, curve) in allPresets) {
                val floor = max(curve.carrierRange.min, FrequencyMath.MIN_TONE_FREQUENCY)
                val label = "«$name», ${settings.carrierReductionPercent}/" +
                    "${settings.beatReductionPercent}"
                for (point in settings.generateVirtualPoints(curve)) {
                    val baseCarrier = curve.getCarrierFrequencyAt(point.time)
                    val baseBeat = curve.getBeatFrequencyAt(point.time)

                    assertFalse("$label: несущая NaN", point.carrierFrequency.isNaN())
                    assertFalse("$label: биения NaN", point.beatFrequency.isNaN())
                    assertTrue(
                        "$label: несущая ушла ниже пола: ${point.carrierFrequency} < $floor",
                        point.carrierFrequency >= floor - eps
                    )
                    assertTrue(
                        "$label: снижение подняло несущую: ${point.carrierFrequency} > $baseCarrier",
                        point.carrierFrequency <= baseCarrier + eps
                    )
                    assertTrue(
                        "$label: модуль биений вырос: |${point.beatFrequency}| > |$baseBeat|",
                        abs(point.beatFrequency) <= abs(baseBeat) + eps
                    )
                    // Пол частоты КАНАЛА, а не только несущей.
                    val lower = point.carrierFrequency - abs(point.beatFrequency) / 2.0f
                    assertTrue(
                        "$label: нижний канал ушёл ниже слышимого: $lower",
                        lower >= FrequencyMath.MIN_TONE_FREQUENCY - eps
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 7. Плоская кривая биений ⇒ w_beat ≡ 1
    // ------------------------------------------------------------------

    @Test
    fun flatBeatCurveKeepsFullBeatDepth() {
        // Плоская кривая биений — не экзотика, а состояние НОВОГО пресета
        // (FrequencyCurve.newPresetCurve: одна точка). Шкала [0; beatTop]
        // вырождается сама: beatTop = |beat| ⇒ w ≡ 1 ⇒ глубина биений как при
        // выключенном угасании. Отдельного правила не требуется (R4).
        //
        // Сравниваются ТОЛЬКО биения: несущая при плоских биениях продолжает
        // угасать по своей оси — оси независимы.
        assertEquals(
            "у нового пресета ровно одна точка",
            1, FrequencyCurve.newPresetCurve().points.size
        )

        for ((name, curve) in allPresets) {
            val flatBeat = FrequencyCurve(
                points = curve.points.map { it.copy(beatFrequency = 12.0f) },
                carrierRange = curve.carrierRange,
                beatRange = curve.beatRange,
                interpolationType = curve.interpolationType,
                splineTension = curve.splineTension
            )
            val withFade = settings(fade = true).generateVirtualPoints(flatBeat)
            val withoutFade = settings(fade = false).generateVirtualPoints(flatBeat)
            assertSameBeats("плоские биения «$name»", withoutFade, withFade)
        }
    }

    // ------------------------------------------------------------------
    // 8. Биения тождественно 0 ⇒ без исключений (см. zeroBeatIsNotReduced)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 9. Вырожденная ширина диапазона ⇒ w_carrier = 1
    // ------------------------------------------------------------------

    @Test
    fun degenerateCarrierSpanFallsBackToFullDepth() {
        // FrequencyRange требует max > min, поэтому «ровно ноль» недостижим;
        // проверяется ширина НИЖЕ порога вырожденности (1e-3 Гц). Плоские
        // биения — чтобы w_beat = 1 и сравнение было чистым.
        val narrowRange = FrequencyRange(100.0f, 100.0005f)
        assertTrue(
            "контроль предусловия: ширина диапазона должна быть ниже порога",
            narrowRange.max - narrowRange.min < 1e-3f
        )
        val curve = flatCarrier(carrier = 174.0f, beat = 8.0f, carrierRange = narrowRange)

        val withFade = settings(fade = true).generateVirtualPoints(curve)
        val withoutFade = settings(fade = false).generateVirtualPoints(curve)
        assertSamePoints("вырожденная ширина диапазона", withoutFade, withFade)
    }

    // ------------------------------------------------------------------
    // 10. CARDINAL-overshoot выше сэмплированного потолка
    // ------------------------------------------------------------------

    @Test
    fun overshootNeverExceedsSliderDepth() {
        // Overshoot кардинального сплайна выше сэмплированного потолка обязан
        // дать ровно w = 1 (кламп), а не глубину больше слайдера. Проверяется
        // двумя вещами: глубина НИКОГДА не превышает слайдер, а на кривой с
        // константными биениями (w ≡ 1 по построению) она ему РАВНА.
        val settings = settings(fade = true, carrierPercent = 25, beatPercent = 50)
        val sliderDepth = settings.beatReductionPercent / 100.0f

        for ((name, curve) in listOf("overshoot" to overshootingBeats, "зигзаг" to zigzag)) {
            var maxBeatDepth = 0.0f
            for (point in settings.generateVirtualPoints(curve)) {
                val baseBeat = abs(curve.getBeatFrequencyAt(point.time))
                if (!point.isFloor(settings) || baseBeat <= 0.0f) continue
                maxBeatDepth = maxOf(maxBeatDepth, 1.0f - abs(point.beatFrequency) / baseBeat)
            }
            assertTrue(
                "$name: угасание не должно молчать (глубина $maxBeatDepth)",
                maxBeatDepth > 0.0f
            )
            assertTrue(
                "$name: глубина биений превысила слайдер: $maxBeatDepth > $sliderDepth",
                maxBeatDepth <= sliderDepth + 1e-3f
            )
        }

        // Константные биения: w ≡ 1 на каждом узле ⇒ глубина ровно по слайдеру.
        val zigzagDepth = settings.generateVirtualPoints(zigzag)
            .filter { it.isFloor(settings) }
            .maxOf { 1.0f - abs(it.beatFrequency) / abs(zigzag.getBeatFrequencyAt(it.time)) }
        assertClose(
            "при w ≡ 1 глубина биений равна слайдеру", sliderDepth, zigzagDepth, 1e-3f
        )
    }

    // ------------------------------------------------------------------
    // 11. Знак биений при снижении свыше 100 % (R7)
    // ------------------------------------------------------------------

    @Test
    fun signIsPreservedBelow100Percent() {
        for (percent in listOf(0, 25, 50, 100)) {
            val settings = settings(fade = true, carrierPercent = 25, beatPercent = percent)
            for (point in settings.generateVirtualPoints(topCarrierVaryingBeat)) {
                val base = topCarrierVaryingBeat.getBeatFrequencyAt(point.time)
                if (base == 0.0f) continue
                assertTrue(
                    "при $percent % знак биений должен сохраниться: " +
                        "$base → ${point.beatFrequency}",
                    point.beatFrequency * base >= -eps
                )
            }
        }
    }

    @Test
    fun signIsInvertedOnlyWhereWeightIsHigh() {
        // 150 % уводит результат за ноль, но угасание гасит эффект ЦЕЛИКОМ:
        // при малом весе знак исходный, при большом — инвертирован. Переход
        // непрерывен, отдельной обработки знака не требуется (R7).
        val settings = settings(fade = true, carrierPercent = 25, beatPercent = 150)

        var preserved = 0
        var inverted = 0
        for (point in settings.generateVirtualPoints(topCarrierVaryingBeat)) {
            val base = topCarrierVaryingBeat.getBeatFrequencyAt(point.time)
            if (base == 0.0f) continue
            // Непрерывность: модуль нигде не превышает исходного (R5).
            assertTrue(
                "модуль инвертированных биений превысил исходный",
                abs(point.beatFrequency) <= abs(base) + eps
            )
            when {
                point.beatFrequency * base > eps -> preserved++
                point.beatFrequency * base < -eps -> inverted++
            }
        }
        assertTrue("при малых весах знак обязан сохраняться", preserved > 0)
        assertTrue("при весе 1 знак обязан инвертироваться", inverted > 0)
    }

    // ------------------------------------------------------------------
    // 12. Детерминизм (R1)
    // ------------------------------------------------------------------

    @Test
    fun generationIsDeterministic() {
        val settings = settings(fade = true)
        for ((name, curve) in allPresets) {
            val first = settings.generateVirtualPoints(curve)
            assertSamePoints("повторный вызов «$name»", first, settings.generateVirtualPoints(curve))

            // Вес считается ТОЛЬКО по базовой кривой, поэтому предыдущий кадр
            // с угасанием на результат не влияет.
            settings(fade = false).generateVirtualPoints(curve)
            assertSamePoints(
                "после кадра без угасания «$name»",
                first, settings.generateVirtualPoints(curve)
            )
        }
    }

    // ------------------------------------------------------------------
    // 13. Геометрия не поехала
    // ------------------------------------------------------------------

    @Test
    fun geometryIsIdenticalWithAndWithoutFade() {
        for ((name, curve) in allPresets) {
            for ((carrierPercent, beatPercent) in listOf(0 to 0, 25 to 50, 100 to 200)) {
                val off = settings(
                    fade = false, carrierPercent = carrierPercent, beatPercent = beatPercent
                ).generateVirtualPoints(curve)
                val on = settings(
                    fade = true, carrierPercent = carrierPercent, beatPercent = beatPercent
                ).generateVirtualPoints(curve)

                assertEquals("число точек «$name» $carrierPercent/$beatPercent", off.size, on.size)
                for (i in off.indices) {
                    assertEquals(
                        "время точки «$name» $carrierPercent/$beatPercent, индекс $i",
                        off[i].time.toSecondOfDay(), on[i].time.toSecondOfDay()
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 14. Инвариант t1/t4
    // ------------------------------------------------------------------

    @Test
    fun periodEdgesStayOnTheBaseCurve() {
        // Именно неизменность t1/t4 закрывает период ровно на базовом уровне:
        // на стыке с соседними участками нет ни разрыва, ни ступеньки (R3).
        val settings = settings(fade = true, carrierPercent = 100, beatPercent = 200)
        val step = settings.periodStepSeconds
        val fullPeriod = settings.fullPeriodSeconds

        for ((name, curve) in allPresets) {
            for (point in settings.generateVirtualPoints(curve)) {
                val phase = point.time.toSecondOfDay() % step
                if (phase != 0 && phase != fullPeriod) continue
                assertClose(
                    "«$name»: точка $phase из $step обязана лежать на базовой кривой",
                    curve.getCarrierFrequencyAt(point.time), point.carrierFrequency
                )
                assertClose(
                    "«$name»: биения на краю периода не должны меняться",
                    curve.getBeatFrequencyAt(point.time), point.beatFrequency
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Эталон («старый» генератор) — независимая копия формул документа
    // ------------------------------------------------------------------

    /**
     * Трапеция периодов расслабления, посчитанная прямо по формулам документа
     * с ЯВНО ЗАДАННЫМИ весами. `w = 1` воспроизводит поведение до фичи.
     */
    private fun referencePoints(
        curve: FrequencyCurve,
        settings: RelaxationModeSettings,
        wCarrier: Float,
        wBeat: Float
    ): List<FrequencyPoint> {
        val carrierReduction = settings.carrierReductionPercent / 100.0f * wCarrier
        val beatReduction = settings.beatReductionPercent / 100.0f * wBeat

        val step = settings.periodStepSeconds
        val transition = settings.transitionPeriodMinutes * 60
        val duration = settings.relaxationDurationMinutes * 60
        if (step <= 0) return emptyList()

        val result = LinkedHashMap<Int, FrequencyPoint>()
        var start = 0
        while (start < 86400) {
            // Порядок t1→t4 важен: совпадающие по времени точки разрешаются
            // правилом «последняя побеждает», как в проде.
            val offsets = listOf(
                0L to false,
                transition.toLong() to true,
                (transition + duration).toLong() to true,
                settings.fullPeriodSeconds.toLong() to false
            )
            for ((offset, reduced) in offsets) {
                val second = start + offset
                if (second >= 86400) continue
                val time = LocalTime.fromSecondOfDay(second.toInt())
                val carrier = curve.getCarrierFrequencyAt(time)
                val beat = curve.getBeatFrequencyAt(time)
                result[second.toInt()] = if (reduced) {
                    val (c, b) = referenceReduce(
                        carrier, beat, carrierReduction, beatReduction, curve.carrierRange.min
                    )
                    FrequencyPoint(time, c, b)
                } else {
                    FrequencyPoint(time, carrier, beat)
                }
            }
            start += step
        }
        return result.values.sortedBy { it.time.toSecondOfDay() }
    }

    /** Копия `RelaxationModeSettings.reduceFrequencies` по формулам R5. */
    private fun referenceReduce(
        carrier: Float,
        beat: Float,
        carrierReduction: Float,
        beatReduction: Float,
        minCarrier: Float
    ): Pair<Float, Float> {
        val floor = max(minCarrier, FrequencyMath.MIN_TONE_FREQUENCY)
        val rawCarrier = carrier * (1.0f - carrierReduction)
        val rawBeat = beat * (1.0f - beatReduction)
        val carrierForFloor = floor + abs(rawBeat) / 2.0f
        val reducedCarrier = maxOf(rawCarrier, carrierForFloor, floor).coerceAtMost(carrier)
        val maxBeatMagnitude = if (reducedCarrier >= floor) {
            (2.0f * (reducedCarrier - floor)).coerceAtLeast(0.0f)
        } else {
            Float.POSITIVE_INFINITY
        }
        val reducedBeat = FrequencyMath.clampBeat(
            reducedCarrier, rawBeat, FrequencyMath.UNBOUNDED_BEAT_RANGE
        ).coerceIn(-maxBeatMagnitude, maxBeatMagnitude)
        return reducedCarrier to reducedBeat
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    private fun curve(
        vararg points: Pair<Int, Pair<Float, Float>>,
        carrierRange: FrequencyRange = range,
        type: InterpolationType = InterpolationType.LINEAR
    ): FrequencyCurve = FrequencyCurve(
        points = points.map { (second, frequencies) ->
            FrequencyPoint(
                time = LocalTime.fromSecondOfDay(second),
                carrierFrequency = frequencies.first,
                beatFrequency = frequencies.second
            )
        },
        carrierRange = carrierRange,
        interpolationType = type
    )

    /** Кривая с постоянными несущей и частотой биений (два совпадающих узла). */
    private fun flatCarrier(
        carrier: Float,
        beat: Float,
        carrierRange: FrequencyRange = range
    ): FrequencyCurve = curve(
        0 to (carrier to beat),
        86399 to (carrier to beat),
        carrierRange = carrierRange
    )

    private fun settings(
        fade: Boolean,
        carrierPercent: Int = 25,
        beatPercent: Int = 50
    ) = RelaxationModeSettings(
        enabled = true,
        carrierReductionPercent = carrierPercent,
        beatReductionPercent = beatPercent,
        gapBetweenRelaxationMinutes = 45,
        transitionPeriodMinutes = 3,
        relaxationDurationMinutes = 15,
        fadeByRangePosition = fade
    )

    private val RelaxationModeSettings.fullPeriodSeconds: Int
        get() = 2 * transitionPeriodMinutes * 60 + relaxationDurationMinutes * 60

    private val RelaxationModeSettings.periodStepSeconds: Int
        get() = fullPeriodSeconds + gapBetweenRelaxationMinutes * 60

    /** Точка плато (t2/t3) — единственная, на которой видна глубина. */
    private fun FrequencyPoint.isFloor(settings: RelaxationModeSettings): Boolean {
        val phase = time.toSecondOfDay() % settings.periodStepSeconds
        val transition = settings.transitionPeriodMinutes * 60
        val duration = settings.relaxationDurationMinutes * 60
        return phase == transition || phase == transition + duration
    }

    private fun assertClose(label: String, expected: Float, actual: Float, delta: Float = eps) {
        assertTrue(
            "$label: ожидалось $expected, получено $actual (допуск $delta)",
            abs(expected - actual) <= delta
        )
    }

    private fun assertSamePoints(
        label: String,
        expected: List<FrequencyPoint>,
        actual: List<FrequencyPoint>
    ) {
        assertEquals("$label: число точек", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals(
                "$label: время точки $i",
                expected[i].time.toSecondOfDay(), actual[i].time.toSecondOfDay()
            )
            assertClose(
                "$label: несущая точки $i", expected[i].carrierFrequency, actual[i].carrierFrequency
            )
            assertClose(
                "$label: биения точки $i", expected[i].beatFrequency, actual[i].beatFrequency
            )
        }
    }

    private fun assertSameBeats(
        label: String,
        expected: List<FrequencyPoint>,
        actual: List<FrequencyPoint>
    ) {
        assertEquals("$label: число точек", expected.size, actual.size)
        for (i in expected.indices) {
            assertClose(
                "$label: биения точки $i", expected[i].beatFrequency, actual[i].beatFrequency
            )
        }
    }
}
