package com.binaural.core.audio.model

import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Память желаемых значений точки ([PointIntentMemory]).
 *
 * Два главных сценария, ради которых всё затевалось:
 *
 * 1. ТОЧКА ДВИЖЕТСЯ К ГРАНИЦЕ. Точка с частотой биений 100 Гц придвигается
 *    к нижней границе диапазона, биения гаснут до геометрически возможных,
 *    а при отодвигании точки — возвращаются к тем же 100 Гц.
 *
 * 2. ГРАНИЦА ДВИЖЕТСЯ К ТОЧКЕ. Диапазон сузили — точка прижалась к новой
 *    границе и потеряла биения. Диапазон вернули — точка должна оказаться
 *    там, где её оставил пользователь, с его частотой биений. Раньше
 *    прижатое значение перезаписывало точку, и возврата не было.
 *
 * Обозначения в тестах:
 * - carrierRange = 100…600 Гц;
 * - предел модуля у несущей C: 2 * min(C − 100, 600 − C).
 */
class PointIntentMemoryTest {

    private val range = FrequencyRange(100.0f, 600.0f)

    private fun point(secondOfDay: Int, carrier: Float, beat: Float) = FrequencyPoint(
        time = LocalTime.fromSecondOfDay(secondOfDay),
        carrierFrequency = carrier,
        beatFrequency = beat
    )

    /** Предел модуля частоты биений для несущей внутри [range]. */
    private fun limitAt(carrier: Float): Float =
        FrequencyMath.maxBeatMagnitude(carrier, range)

    @Test
    fun seededFromPreset_desiredEqualsSavedValues() {
        val memory = PointIntentMemory()
        val point = point(12 * 3600, 350.0f, 42.0f)

        memory.seedFrom(listOf(point))

        assertEquals(350.0f, memory.desiredCarrierFor(point), EPS)
        assertEquals(42.0f, memory.desiredBeatFor(point), EPS)
        assertTrue(memory.remembers(point))
    }

    @Test
    fun shrinkAtBoundary_thenBackAway_restoresDesiredBeat() {
        val memory = PointIntentMemory()
        // Несущая 300 Гц — середина диапазона, предел модуля 400 Гц.
        val point = point(0, 300.0f, 100.0f)
        memory.seedFrom(listOf(point))

        // Придвигаем к нижней границе: 105 Гц → предел 2 * 5 = 10 Гц.
        val nearFloor = memory.resolveBeat(point, 105.0f, range)
        assertEquals(10.0f, nearFloor, EPS)

        // Совсем вплотную: 100 Гц → биений не остаётся.
        assertEquals(0.0f, memory.resolveBeat(point, 100.0f, range), EPS)

        // Отодвигаем назад — желаемые 100 Гц возвращаются целиком.
        assertEquals(100.0f, memory.resolveBeat(point, 300.0f, range), EPS)

        // И на любом удалении, где они помещаются: 200 Гц → предел 200 Гц.
        assertEquals(100.0f, memory.resolveBeat(point, 200.0f, range), EPS)

        // А где не помещаются — ровно по пределу, без «западания».
        assertEquals(60.0f, memory.resolveBeat(point, 130.0f, range), EPS)
    }

    @Test
    fun repeatedMoves_doNotDegradeDesiredBeat() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 120.0f)
        memory.seedFrom(listOf(point))

        // Много ходов к границе и обратно: желаемое значение не должно
        // «сползать» — раньше каждый ход перезаписывал точку обрезанным
        // значением, и после первого же касания границы 120 Гц исчезали.
        repeat(20) {
            memory.resolveBeat(point, 100.0f, range)
            memory.resolveBeat(point, 110.0f, range)
            memory.resolveBeat(point, 560.0f, range)
        }

        assertEquals(120.0f, memory.desiredBeatFor(point), EPS)
        assertEquals(120.0f, memory.resolveBeat(point, 300.0f, range), EPS)
    }

    @Test
    fun manualValue_becomesNewDesired() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 40.0f)
        memory.seedFrom(listOf(point))

        memory.rememberBeat(point.time, 250.0f)

        assertEquals(250.0f, memory.desiredBeatFor(point), EPS)
        // У границы новое желаемое гасится, но при отодвигании возвращается
        // именно к 250 Гц, а не к сохранённым в пресете 40 Гц.
        assertEquals(20.0f, memory.resolveBeat(point, 110.0f, range), EPS)
        assertEquals(250.0f, memory.resolveBeat(point, 300.0f, range), EPS)
    }

    @Test
    fun negativeBeat_keepsSign() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, -100.0f)
        memory.seedFrom(listOf(point))

        // Клампится только модуль: знак (раскладка каналов) сохраняется.
        assertEquals(-10.0f, memory.resolveBeat(point, 105.0f, range), EPS)
        assertEquals(-100.0f, memory.resolveBeat(point, 300.0f, range), EPS)
        assertEquals(0.0f, memory.resolveBeat(point, 100.0f, range), EPS)
    }

    @Test
    fun desiredBeyondCurrentLimit_waitsUntilItFits() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 30.0f)
        memory.seedFrom(listOf(point))

        // Пользователь задал 300 Гц, но точка стоит в месте, где помещается
        // только 60 Гц. Значение запоминается целиком.
        memory.rememberBeat(point.time, 300.0f)
        assertEquals(60.0f, memory.resolveBeat(point, 130.0f, range), EPS)

        // Как только место появилось — получаем все 300 Гц.
        assertEquals(300.0f, memory.resolveBeat(point, 300.0f, range), EPS)
    }

    @Test
    fun timeKeySurvivesReorderAndRemoval() {
        val memory = PointIntentMemory()
        val a = point(0, 300.0f, 10.0f)
        val b = point(3600, 300.0f, 20.0f)
        val c = point(7200, 300.0f, 30.0f)
        memory.seedFrom(listOf(a, b, c))

        // Удаление соседа и пересортировка списка (индексы при этом уезжают)
        // на память не влияют: точка опознаётся по времени.
        memory.forget(b.time)

        assertEquals(10.0f, memory.desiredBeatFor(a), EPS)
        assertEquals(30.0f, memory.desiredBeatFor(c), EPS)
        assertFalse(memory.remembers(b))
    }

    @Test
    fun rekeyMovesIntentWithPoint() {
        val memory = PointIntentMemory()
        val point = point(3600, 300.0f, 77.0f)
        memory.seedFrom(listOf(point))

        val moved = point.copy(time = LocalTime.fromSecondOfDay(4 * 3600))
        memory.rekey(point.time, moved.time)

        assertEquals(300.0f, memory.desiredCarrierFor(moved), EPS)
        assertEquals(77.0f, memory.desiredBeatFor(moved), EPS)
        assertFalse(memory.remembers(point))
    }

    @Test
    fun unknownPoint_fallsBackToItsOwnValues() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 55.0f)

        // Точку память не знает (например, кривая подменена извне) —
        // поведение не хуже старого: работаем с её сохранёнными значениями.
        assertEquals(300.0f, memory.desiredCarrierFor(point), EPS)
        assertEquals(55.0f, memory.desiredBeatFor(point), EPS)
        assertEquals(55.0f, memory.resolveBeat(point, 300.0f, range), EPS)
    }

    @Test
    fun clearDropsEverything() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 55.0f)
        memory.seedFrom(listOf(point))
        memory.clear()

        assertFalse(memory.remembers(point))
        // После очистки точка всё ещё разрешается — по своим значениям.
        assertEquals(300.0f, memory.desiredCarrierFor(point), EPS)
        assertEquals(55.0f, memory.desiredBeatFor(point), EPS)
    }

    @Test
    fun withoutCarrierRange_onlyPhysicsLimits() {
        val memory = PointIntentMemory()
        // Несущая 500 Гц вне всяких графиков: предел только по 20/2000 Гц.
        val point = point(0, 500.0f, 800.0f)
        memory.seedFrom(listOf(point))

        assertEquals(800.0f, memory.resolveBeat(point, 500.0f, null), EPS)
        // У 520 Гц предел 1000 Гц — желаемые 800 Гц помещаются целиком.
        assertEquals(800.0f, memory.resolveBeat(point, 520.0f, null), EPS)
        // А у 420 Гц предел 800 Гц — ровно касание.
        assertEquals(800.0f, memory.resolveBeat(point, 420.0f, null), EPS)
        // Ниже — уже обрезка: у 400 Гц предел 760 Гц.
        assertEquals(760.0f, memory.resolveBeat(point, 400.0f, null), EPS)
        assertEquals(800.0f, memory.resolveBeat(point, 500.0f, null), EPS)
    }

    @Test
    fun narrowingThenRestoringCarrierRange_restoresBeat() {
        val memory = PointIntentMemory()
        // Точка в середине диапазона 100…600, биения 200 Гц.
        val point = point(0, 300.0f, 200.0f)
        memory.seedFrom(listOf(point))

        // Диапазон сузили до 250…350: у несущей 300 Гц предел 2*50 = 100 Гц.
        val narrowed = FrequencyRange(250.0f, 350.0f)
        val damped = memory.resolveBeat(point, 300.0f, narrowed)
        assertEquals(100.0f, damped, EPS)

        // Точка перезаписана обрезанным значением — именно так это и
        // происходило в редакторе до появления памяти желаемых значений.
        val stored = point.copy(beatFrequency = damped)
        assertEquals(100.0f, stored.beatFrequency, EPS)

        // Вернули прежний диапазон — биения вернулись, а не остались 100 Гц.
        assertEquals(200.0f, memory.resolveBeat(stored, 300.0f, range), EPS)
    }

    @Test
    fun carrierPushedByNarrowedRange_returnsWhenRangeWidened() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 200.0f)
        memory.seedFrom(listOf(point))

        // Сузили сверху до 250 Гц: точка прижата к новой границе, а у самой
        // границы биений по геометрии не остаётся вовсе.
        val narrowed = FrequencyRange(100.0f, 250.0f)
        val pushed = memory.resolveCarrier(point, narrowed)
        assertEquals(250.0f, pushed, EPS)
        assertEquals(0.0f, memory.resolveBeat(point, pushed, narrowed), EPS)

        // Точка перезаписана прижатыми значениями (старое поведение редактора).
        val stored = point.copy(
            carrierFrequency = pushed,
            beatFrequency = memory.resolveBeat(point, pushed, narrowed)
        )

        // Вернули прежний диапазон — и несущая, и биения на своих местах.
        val restored = memory.resolveCarrier(stored, range)
        assertEquals(300.0f, restored, EPS)
        assertEquals(200.0f, memory.resolveBeat(stored, restored, range), EPS)
    }

    @Test
    fun manualCarrierMove_becomesNewDesiredAndSurvivesRangeChange() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 40.0f)
        memory.seedFrom(listOf(point))

        // Пользователь оттянул точку вверх, но диапазон её не вмещает:
        // она встала у границы 600 Гц.
        memory.rememberCarrier(point.time, 800.0f)
        assertEquals(600.0f, memory.resolveCarrier(point, range), EPS)

        // Расширили диапазон — точка уходит туда, куда её оттянули, а не
        // остаётся у старой границы.
        assertEquals(800.0f, memory.resolveCarrier(point, FrequencyRange(100.0f, 900.0f)), EPS)

        // Частота биений при этом не пострадала.
        assertEquals(40.0f, memory.desiredBeatFor(point), EPS)
    }

    @Test
    fun carrierAndBeatIntent_areIndependent() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 200.0f)
        memory.seedFrom(listOf(point))

        // Точку придвинули к границе — биения погасли до 10 Гц.
        assertEquals(10.0f, memory.resolveBeat(point, 105.0f, range), EPS)

        // Правка несущей НЕ должна затирать память о желаемых биениях
        // (иначе 200 Гц исчезли бы вместе с придвинутой точкой).
        memory.rememberCarrier(point.time, 500.0f)
        assertEquals(200.0f, memory.desiredBeatFor(point), EPS)

        // И наоборот: правка биений не затирает желаемую несущую.
        memory.rememberBeat(point.time, 42.0f)
        assertEquals(500.0f, memory.desiredCarrierFor(point), EPS)
        assertEquals(42.0f, memory.desiredBeatFor(point), EPS)
    }

    @Test
    fun resolveCarrier_clampsToAudibleRange() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 0.0f)
        memory.seedFrom(listOf(point))

        // Без диапазона графика предел только по физике тона (20…2000 Гц).
        memory.rememberCarrier(point.time, 5000.0f)
        assertEquals(FrequencyMath.MAX_TONE_FREQUENCY, memory.resolveCarrier(point, null), EPS)

        memory.rememberCarrier(point.time, 5.0f)
        assertEquals(FrequencyMath.MIN_TONE_FREQUENCY, memory.resolveCarrier(point, null), EPS)
    }

    @Test
    fun limitAtMatchesMaxBeatMagnitude() {
        // Опорная проверка самой формулы предела: она должна «касаться»
        // границы диапазона, а не срезать с запасом.
        assertEquals(0.0f, limitAt(100.0f), EPS)
        assertEquals(0.0f, limitAt(600.0f), EPS)
        assertEquals(400.0f, limitAt(300.0f), EPS)
        assertEquals(200.0f, limitAt(200.0f), EPS)
    }

    /**
     * Несущую задали ЗА границу диапазона: она не отбрасывается, а встаёт на
     * границу, биения в этой точке гаснут в ноль — и возвращаются, как только
     * несущая (или сама граница) отодвигается.
     *
     * Здесь проверяется ровно то, что делает редактор: память желаемой несущей
     * получает ЗАПРОШЕННОЕ значение, а частота биений выводится из желаемой
     * ([PointIntentMemory.resolveBeat]) и потому не сгорает.
     */
    @Test
    fun carrierSetBeyondBoundary_beatDropsToZeroAndReturns() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 200.0f)
        memory.seedFrom(listOf(point))

        // Пользователь задал 900 Гц при диапазоне 100…600: значение не
        // потеряно — точка встала на границу 600 Гц.
        memory.rememberCarrier(point.time, 900.0f)
        val atBoundary = memory.resolveCarrier(point, range)
        assertEquals(600.0f, atBoundary, EPS)

        // У самой границы каналам негде развернуться — биения гаснут в ноль.
        assertEquals(0.0f, memory.resolveBeat(point, atBoundary, range), EPS)
        // Желаемые 200 Гц при этом живы: правка несущей их не затирает.
        assertEquals(200.0f, memory.desiredBeatFor(point), EPS)

        // Точка перезаписана прижатыми значениями — как это делает редактор.
        val stored = point.copy(
            carrierFrequency = atBoundary,
            beatFrequency = memory.resolveBeat(point, atBoundary, range)
        )
        assertEquals(0.0f, stored.beatFrequency, EPS)

        // Отодвинули несущую от границы — биения вернулись целиком.
        assertEquals(200.0f, memory.resolveBeat(stored, 400.0f, range), EPS)

        // Отодвинули саму границу — вернулись и несущая, и биения.
        val widened = FrequencyRange(100.0f, 1200.0f)
        val restoredCarrier = memory.resolveCarrier(stored, widened)
        assertEquals(900.0f, restoredCarrier, EPS)
        assertEquals(200.0f, memory.resolveBeat(stored, restoredCarrier, widened), EPS)
    }

    /** Тот же случай у НИЖНЕЙ границы: несущую задали ниже минимума. */
    @Test
    fun carrierSetBelowBoundary_beatDropsToZeroAndReturns() {
        val memory = PointIntentMemory()
        val point = point(0, 300.0f, 120.0f)
        memory.seedFrom(listOf(point))

        memory.rememberCarrier(point.time, 40.0f)
        val atBoundary = memory.resolveCarrier(point, range)
        assertEquals(100.0f, atBoundary, EPS)
        assertEquals(0.0f, memory.resolveBeat(point, atBoundary, range), EPS)

        // Чуть отодвинули — биения вернулись (предел 2*30 = 60 Гц, 120 не влезли).
        assertEquals(60.0f, memory.resolveBeat(point, 130.0f, range), EPS)
        // Отодвинули достаточно — вернулись полностью.
        assertEquals(120.0f, memory.resolveBeat(point, 300.0f, range), EPS)
    }

    /**
     * Правка частоты биений двигает несущую (см. FrequencyMathFitTest), и
     * сдвинутая несущая обязана стать желаемой: иначе при следующей правке
     * диапазона точка отпрыгнула бы на прежнее место — под биения, которые
     * там уже не помещаются.
     */
    @Test
    fun beatChange_movesCarrierAndNewCarrierBecomesDesired() {
        val memory = PointIntentMemory()
        val point = point(0, 150.0f, 100.0f)
        memory.seedFrom(listOf(point))

        // Просим 200 Гц при несущей 150: нижний канал уходил бы на 50 Гц,
        // поэтому несущая отодвигается на 200 Гц, а пульсация остаётся 200 Гц.
        val fit = FrequencyMath.fitBeatWithCarrierShift(
            point.carrierFrequency, 200.0f, range)
        assertEquals(200.0f, fit.beatFrequency, EPS)
        assertEquals(200.0f, fit.carrierFrequency, EPS)

        memory.rememberBeat(point.time, fit.beatFrequency)
        memory.rememberCarrier(point.time, fit.carrierFrequency)

        // Сдвинутая несущая — теперь желаемая: диапазон расширили, а точка
        // осталась там, куда её отодвинули под увеличенную пульсацию.
        val widened = FrequencyRange(50.0f, 600.0f)
        assertEquals(200.0f, memory.resolveCarrier(point, widened), EPS)
        assertEquals(200.0f, memory.resolveBeat(point, 200.0f, widened), EPS)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
