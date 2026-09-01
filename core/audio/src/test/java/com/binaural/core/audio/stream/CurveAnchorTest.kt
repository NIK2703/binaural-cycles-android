package com.binaural.core.audio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты якорения кривой (план K1–K5,
 * docs/handoff_anchor_zero_analysis_plan.md, раздел 5.2).
 *
 * Тестируется РЕШАЮЩАЯ логика, а не менеджер целиком: [BinauralStreamManager]
 * требует Context/HandlerThread, а [BinauralStreamImpl] — AudioTrack, то есть
 * настоящий Android. Всё, что решает судьбу якоря, вынесено в чистые функции
 * ([resolveCurveAnchor], [CurveAnchorRules]) и проверяется здесь; поведение
 * менеджера в окнах FADE_IN и «движок разрушен» проверяется на устройстве
 * штормами S1/S2 (tools/dbgxfade.sh) — там спека всегда несёт
 * [CurveAnchor.NONE], поэтому этим окнам просто нечем сломаться.
 */
class CurveAnchorTest {

    // ------------------------------------------------------------------helpers

    private fun tod(h: Int, m: Int, s: Int = 0): Float = (h * 3600 + m * 60 + s).toFloat()

    // ------------------------------------------------------------------ K1

    /**
     * K1. Изменение во время FADE_IN (≤ 100 мс после start).
     *
     * Раньше в этом окне захват непрерывности читал протухший нативный кэш и
     * получал 0; следующий поток якорился на 0 и защёлкивался. Теперь спека
     * хэндоффа якоря не несёт вовсе.
     */
    @Test
    fun k1_changeDuringFadeIn_anchorsOnNow_neverZero() {
        val now = tod(12, 37, 33)   // 12:37:33 — из лога шторма
        val spec = PlaybackSpec(
            serial = 9,
            config = config(),
            relaxation = relaxation(),
            sampleRate = sampleRate(),
            volume = 1f,
            reason = SpecReason.SETTINGS
        )

        // Инвариант P1.4: хэндофф НЕ несёт якоря.
        assertEquals(CurveAnchor.NONE, spec.resumeAnchor)
        assertFalse("хэндофф не имеет права наследовать позицию кривой", spec.resumeAnchor.isPresent)

        val resolved = resolveCurveAnchor(spec.resumeAnchor, now, engineNowSec = now)

        assertEquals(AnchorSource.NOW, resolved.source)
        assertEquals("никогда не 0 при now ≠ 0", 45453, resolved.valueSec)
    }

    // ------------------------------------------------------------------ K2

    /**
     * K2. Окно «движок разрушен». Раньше `getCurrentCurveTimeSeconds()`
     * отвечала на него литеральным `0f` (второй независимый источник
     * защёлкивания). Теперь ответа нет вовсе — null, а решение одно: «сейчас».
     */
    @Test
    fun k2_destroyedEngine_fallsBackToNow_neverZero() {
        val now = tod(18, 15, 0)
        val resolved = resolveCurveAnchor(CurveAnchor.NONE, now, engineNowSec = now)

        assertEquals(AnchorSource.NOW, resolved.source)
        assertEquals(65700, resolved.valueSec)
        assertTrue("фолбэк не имеет права быть полночью при now ≠ 0", resolved.valueSec > 0)
    }

    // ------------------------------------------------------------------ K3

    /**
     * K3. Липкий-0: принудительно поданный плохой захват (ровно тот, который
     * ловил баг) ОТБРАСЫВАЕТСЯ валидацией, и цепочка 0 → 0 → 0 рвётся: у
     * результата происхождение FALLBACK, а не CAPTURED, то есть следующий шаг
     * вообще не видит ноль.
     */
    @Test
    fun k3_stickyZero_isRejectedAndCannotLatch() {
        val now = tod(12, 37, 33)
        val bogus = CurveAnchor.captured(0f)

        val resolved = resolveCurveAnchor(bogus, now, engineNowSec = now)
        assertEquals(AnchorSource.FALLBACK, resolved.source)
        assertEquals(45453, resolved.valueSec)

        // Цепочка: результат прошлого шага подаётся как якорь следующего.
        // FALLBACK-значение == now, поэтому следующий шаг видит корректный
        // якорь и защёлкивание невозможно по построению.
        val next = resolveCurveAnchor(
            CurveAnchor.captured(resolved.valueSec.toFloat()),
            now + 1f,
            engineNowSec = now + 1f
        )
        // Якорь 45453 (= прошлый now) всё ещё правдоподобен (±1 с от now=45454)
        // → принят как CAPTURED без изменения значения. Цепочка несёт РЕАЛЬНОЕ
        // время, а не 0, поэтому защёлкивание 0→0→0 невозможно по построению.
        assertEquals(AnchorSource.CAPTURED, next.source)
        assertEquals(45453, next.valueSec)
    }

    @Test
    fun k3b_garbageAnchorFarFromNow_isRejected() {
        val now = tod(12, 0, 0)
        val garbage = CurveAnchor.captured(tod(3, 0, 0))

        assertFalse(CurveAnchorRules.isPlausible(garbage.valueSec.toFloat(), now))
        assertEquals(
            AnchorSource.FALLBACK,
            resolveCurveAnchor(garbage, now, engineNowSec = now).source
        )
    }

    @Test
    fun k3c_plausibleAnchor_isAccepted() {
        val now = tod(12, 0, 0)
        val fresh = CurveAnchor.captured(now - 0.8f)   // длительность хэндоффа < 1 с

        assertTrue(CurveAnchorRules.isPlausible(fresh.valueSec.toFloat(), now))
        assertEquals(
            AnchorSource.CAPTURED,
            resolveCurveAnchor(fresh, now, engineNowSec = now).source
        )
    }

    // ------------------------------------------------------------------ K4

    /**
     * K4. Серия из 20 правок не копит лаг.
     *
     * Старая модель: якорь = замороженная позиция уходящего потока, снятая в
     * beginHandoff. Каждая правка добавляла длительность хэндоффа к отставанию
     * (в логе шторма — −1 с за 7 хендоверов). Новая: якорь всегда «сейчас»,
     * расхождение тождественно нулю на каждом шаге.
     */
    @Test
    fun k4_twentyEdits_doNotAccumulateLag() {
        var now = tod(9, 0, 0)
        val deltas = mutableListOf<Float>()

        repeat(20) {
            // Правка пресета: спека без якоря, решение принимается в prepare().
            val resolved = resolveCurveAnchor(CurveAnchor.NONE, now, engineNowSec = now)
            deltas += CurveAnchorRules.circularDistance(resolved.valueSec.toFloat(), now)
            // Хэндофф длится ~0.7 с; старая модель отдала бы их накапливающимся лагом.
            now += 0.7f
        }

        val maxLag = deltas.max()
        assertTrue(
            "лаг не имеет права копиться: max=${maxLag}с (было бы ≈14с на 20 правках)",
            maxLag <= 1f
        )
    }

    /**
     * K4b. Контрольный расчёт старой модели: тот же сценарий, но с наследованием
     * замороженной позиции. Демонстрирует, что тест выше что-то реально ловит.
     */
    @Test
    fun k4b_frozenAnchorModel_wouldAccumulateLag() {
        var now = tod(9, 0, 0)
        var audible = now               // слышимая позиция текущего потока
        val deltas = mutableListOf<Float>()

        repeat(20) {
            // Захват непрерывности читает позицию уходящего потока...
            val captured = audible
            deltas += CurveAnchorRules.circularDistance(captured, now)
            // ...пока идёт хэндофф, настенные часы ушли вперёд, а новый поток
            // стартует ровно с захваченной (уже отставшей) позиции.
            now += 0.7f
            audible = captured
        }

        // Лаг растёт линейно: ≈0.7 с за правку. В ε = 5 с старая модель
        // перестаёт вписываться на 8-й правке, а к 20-й отстаёт на 13.3 с.
        assertTrue(
            "старая модель обязана ломаться: Δ[8]=${deltas[8]}с, Δ[19]=${deltas[19]}с",
            deltas[8] > CurveAnchorRules.MAX_SKEW_SEC
        )
        assertTrue("лаг обязан РАСТИ, а не стоять", deltas[19] > deltas[8])
    }

    // ------------------------------------------------------------------ K5

    /**
     * K5. Полуночный рубеж. Якорь 0 при now = 86399.9 — ЛЕГАЛЬНАЯ полночь, и
     * валидация обязана его принять. Отбрасывается только по-настоящему далёкий
     * якорь. Критично: иначе правка около полуночи молча откатывала бы кривую.
     */
    @Test
    fun k5_midnightAnchorZero_isAccepted() {
        val now = 86399.9f
        val midnight = CurveAnchor.captured(0f)

        assertTrue(
            "полночь — легальное время суток, а не мусор",
            CurveAnchorRules.isPlausible(midnight.valueSec.toFloat(), now)
        )
        val resolved = resolveCurveAnchor(midnight, now, engineNowSec = now)
        assertEquals(AnchorSource.CAPTURED, resolved.source)
        assertEquals(0, resolved.valueSec)
    }

    @Test
    fun k5b_justPastMidnight_previousDayAnchorIsAccepted() {
        val now = 2.5f
        val beforeMidnight = CurveAnchor.captured(86399.0f)

        assertTrue(CurveAnchorRules.isPlausible(beforeMidnight.valueSec.toFloat(), now))
        assertEquals(
            AnchorSource.CAPTURED,
            resolveCurveAnchor(beforeMidnight, now, engineNowSec = now).source
        )
    }

    // ------------------------------------------------------- математика правил

    @Test
    fun circularDistance_isCircular() {
        assertEquals(1f, CurveAnchorRules.circularDistance(0f, 86399f), 0.01f)
        assertEquals(1f, CurveAnchorRules.circularDistance(86399f, 0f), 0.01f)
        assertEquals(0f, CurveAnchorRules.circularDistance(45452f, 45452f), 0.01f)
        // Самая далёкая пара — ровно половина суток.
        assertEquals(43200f, CurveAnchorRules.circularDistance(0f, 43200f), 0.01f)
        assertEquals(43200f, CurveAnchorRules.circularDistance(43200f, 0f), 0.01f)
    }

    @Test
    fun anchorSource_noneIsNotPresent_zeroIsPresent() {
        assertFalse(CurveAnchor.NONE.isPresent)

        val midnightCaptured = CurveAnchor.captured(0f)
        assertTrue("0 — легальная полночь и полноценный якорь", midnightCaptured.isPresent)
        assertEquals(0, midnightCaptured.valueSec)
    }

    // ------------------------------------------------------------------ helpers

    private fun config() = com.binaural.core.audio.model.BinauralConfig()
    private fun relaxation() = com.binaural.core.audio.model.RelaxationModeSettings()
    private fun sampleRate() = com.binaural.core.audio.model.SampleRate.MEDIUM
}
