#pragma once

#include "Config.h"
#include "Interpolation.h"
// Расписание смен раскладки и сам множитель раскладки живут здесь: раскладка
// ушей — ЧИСТАЯ ФУНКЦИЯ (конфиг, t), а не состояние планировщика.
#include "ChannelLayout.h"
#include <vector>

#include <algorithm>
#include <cmath>


namespace binaural {

// Расписание смен раскладки (channelSwapStateAt, trendDesiredSwapped,
// timerSolidDurationMs, trendSolidDurationMs) и множитель s(t) = layoutSignAt
// перенесены в ChannelLayout.h. Они по-прежнему доступны отсюда: этот
// заголовок включается везде, где включался BufferPackagePlanner.h.

/**
 * [SOLID N сек] → [FADE_OUT M сек] → [PAUSE K сек] → [FADE_IN M сек] → [SOLID N сек] → ...
 *
 * Ключевой принцип: неполный буфер в конце пакета переносится в начало следующего.
 *
 * ШАГ 3 МИГРАЦИИ: планировщик БОЛЬШЕ НЕ ВЕДЁТ фазовую машину swap-цикла.
 * Раскладка ушей — чистая функция (конфиг, t), реализованная в layoutSignAt()
 * через непрерывную рампу; «смена каналов» = beat проходит через ноль, без
 * ритуала затухания/тишины/нарастания. Поэтому planPackage вырождается в
 * НАРЕЗКУ SOLID на подсегменты ≤100 мс — ровно ту, что генератор и так
 * использует для кривой частот (частоты ушей берутся хордами между границами
 * подсегментов, см. generateSolidBuffer). Остальные методы класса
 * (nextPhase/phaseDuration/toBufferType/initStateForStart/resetState/
 * calculateCycleDuration) оставлены для совместимости вызовов, но planPackage
 * их больше не использует.
 */

// Логирование планировщика пакетов. ОТКЛЮЧЕНО по умолчанию — включается
// только при debug.binaural.segment_log=1 (как SEGMENT_DEBUG/PKG_BOUNDARY),
// чтобы не засорять logcat в обычной сборке.
#ifdef AUDIO_TEST_BUILD
// При тестировании отключаем логирование
#define LOGD_PLANNER(...) ((void)0)
#elif defined(ANDROID)
#include <android/log.h>
#include <sys/system_properties.h>
inline bool plannerDebugLogEnabled() {
    static const bool enabled = []() {
        char v[PROP_VALUE_MAX] = {0};
        return __system_property_get("debug.binaural.segment_log", v) > 0 && v[0] == '1';
    }();
    return enabled;
}
#define LOG_TAG "BufferPackagePlanner"
#define LOGD_PLANNER(...) do { if (plannerDebugLogEnabled()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#else
#define LOGD_PLANNER(...) ((void)0)
#endif
class BufferPackagePlanner {
public:
    /**
     * Спланировать пакет буферов.
     *
     * ШАГ 3 МИГРАЦИИ: планировщик режет пакет на SOLID-подсегменты ≤100 мс
     * безусловно для всех режимов. Рампа раскладки (layoutSignAt) учитывается
     * генератором внутри каждого подсегмента через channelsAt(); фазовая
     * машина swap-цикла (SOLID→FADE_OUT→PAUSE→FADE_IN) удалена.
     *
     * @param packageDurationMs Длительность пакета в мс
     * @param config Конфигурация с параметрами swap
     * @param state Текущее состояние (больше не используется планировщиком;
     *              оставлено для совместимости сигнатуры вызова)
     * @param curveStartSeconds Позиция кривой (сек суток) — больше не нужна
     * @param timeScale Множитель скорости кривой относительно аудио-времени
     *        (debug virtual time; в release всегда 1.0)
     * @return План пакета с последовательностью SOLID-подсегментов
     */
    PackagePlan planPackage(
        int64_t packageDurationMs,
        const BinauralConfig& config,
        GeneratorState& state,
        float curveStartSeconds = -1.0f,
        float timeScale = 1.0f
    );

    /**
     * Длительность полного swap-цикла (историческая; cycle больше нет).
     * Оставлено для совместимости — возвращает 0.
     */
    int64_t calculateCycleDuration(const BinauralConfig& config) const;

    /**
     * Сбросить состояние планировщика в начальное (историческое; состояние
     * раскладки больше не ведётся). Оставлено для совместимости вызовов.
     */
    void resetState(GeneratorState& state);
    /**
     * Инициализировать состояние по расписанию для момента свежего старта
     * (историческое; раскладка теперь — чистая функция t). Оставлено для
     * совместимости вызовов.
     */
    void initStateForStart(
        const BinauralConfig& config,
        GeneratorState& state,
        float curveStartSeconds,
        float timeScale = 1.0f
    );


private:
    /**
     * Определить следующую фазу после текущей (историческое)
     */
    SwapPhase nextPhase(SwapPhase current) const;

    /**
     * Вычислить длительность фазы в мс (историческое)
     */
    int64_t phaseDuration(SwapPhase phase, const BinauralConfig& config) const;

    /**
     * Преобразовать SwapPhase в BufferType (историческое)
     */
    BufferType toBufferType(SwapPhase phase) const;
};
inline void BufferPackagePlanner::initStateForStart(
    const BinauralConfig& config,
    GeneratorState& state,
    float curveStartSeconds,
    float timeScale) {
    (void)config; (void)curveStartSeconds; (void)timeScale;
    // ШАГ 3: раскладка — чистая функция t. Поле channelsSwapped больше не
    // задаёт раскладку (см. layoutSignAt). Оставляем состояние нейтральным.
    state.swapPhase = SwapPhase::SOLID;
    state.phaseRemainingMs = 0;
}


// ============================================================================
// INLINE РЕАЛИЗАЦИЯ ДЛЯ ПРОИЗВОДИТЕЛЬНОСТИ
// ============================================================================

inline PackagePlan BufferPackagePlanner::planPackage(
    int64_t packageDurationMs,
    const BinauralConfig& config,
    GeneratorState& state,
    float curveStartSeconds,
    float timeScale
) {
    (void)config; (void)state; (void)curveStartSeconds; (void)timeScale;
    PackagePlan plan;
    plan.totalDurationMs = 0;
    plan.endsMidCycle = false;

    // ШАГ 3: нарезка на SOLID-подсегменты ≤100 мс безусловно для всех режимов.
    // Рампа раскладки учитывается генератором внутри подсегментов (channelsAt),
    // поэтому никакой фазовой машины здесь не нужно.
    constexpr int64_t SOLID_SUBSEGMENT_MS = 100;
    int64_t remaining = packageDurationMs;
    plan.segments.reserve(static_cast<size_t>(
        (remaining + SOLID_SUBSEGMENT_MS - 1) / SOLID_SUBSEGMENT_MS));
    while (remaining > 0) {
        const int64_t segDur = std::min(remaining, SOLID_SUBSEGMENT_MS);
        BufferSegment segment;
        segment.type = BufferType::SOLID;
        segment.durationMs = segDur;
        segment.swapAfterSegment = false;
        plan.segments.push_back(segment);
        plan.totalDurationMs += segDur;
        remaining -= segDur;
    }
    return plan;
}

inline int64_t BufferPackagePlanner::calculateCycleDuration(const BinauralConfig& config) const {
    (void)config;
    return 0;  // Цикла больше нет
}

inline void BufferPackagePlanner::resetState(GeneratorState& state) {
    // ШАГ 3: состояние раскладки не ведётся. Держим фазу нейтральной.
    state.swapPhase = SwapPhase::SOLID;
    state.phaseRemainingMs = 0;
    state.cyclePositionMs = 0;
    state.channelsSwapped = false;
    state.justStarted = true;
}

inline SwapPhase BufferPackagePlanner::nextPhase(SwapPhase current) const {
    (void)current;
    return SwapPhase::SOLID;
}

inline int64_t BufferPackagePlanner::phaseDuration(SwapPhase phase, const BinauralConfig& config) const {
    (void)phase; (void)config;
    return 0;
}

inline BufferType BufferPackagePlanner::toBufferType(SwapPhase phase) const {
    (void)phase;
    return BufferType::SOLID;
}

} // namespace binaural
