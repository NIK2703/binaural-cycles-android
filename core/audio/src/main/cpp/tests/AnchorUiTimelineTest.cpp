// Регрессионные тесты якорения UI-таймлайна.
//
// Покрывают корневую причину бага «возобновление с 0:00»
// (docs/handoff_anchor_zero_analysis_plan.md, раздел 5.1, тесты N1–N4):
// кэш UI-времени m_uiLastUiTimeSec писался ТОЛЬКО опросом частот, тогда как
// страж «якорь установлен» (m_uiAnchorWallMs) ставился и anchorUiTimeline().
// Между prepare() и первым опросом UI геттер позиции отдавал протухший ноль.

#include <gtest/gtest.h>

#include <cmath>
#include <thread>

#include "BinauralEngine.h"

namespace binaural {
namespace test {
namespace {

constexpr float kDay = 86400.0f;

inline float normalize(float t) {
    float r = std::fmod(t, kDay);
    if (r < 0.0f) r += kDay;
    return r;
}

/** Круговое расстояние по кольцу суток: |a - b| ∈ [0, 43200]. */
inline float circularDelta(float a, float b) {
    const float d = normalize(a - b);
    return d > kDay * 0.5f ? kDay - d : d;
}

}  // namespace

class AnchorUiTimelineTest : public ::testing::Test {
protected:
    BinauralEngine engine;
};

// N1. Главная регрессия: setCurveTime(T) и СРАЗУ чтение позиции, без опроса UI.
//
// Именно здесь старый код возвращал 0: resetState() обнулял кэш,
// setCurveTimeSeconds() ставил страж через anchorUiTimeline(), но кэш не
// трогал, а геттер читал именно кэш.
TEST_F(AnchorUiTimelineTest, FreshAnchorIsReadableImmediatelyWithoutUiPoll) {
    constexpr float kT = 45452.0f;   // 12:37:32 — из лога шторма

    engine.resetState();
    const int32_t beforeAnchor = engine.getCurrentTimeOfDaySeconds();
    EXPECT_GE(beforeAnchor, 0);
    EXPECT_LT(beforeAnchor, 86400);

    engine.setCurveTimeSeconds(kT);
    const int32_t got = engine.getCurrentTimeOfDaySeconds();

    EXPECT_NEAR(static_cast<float>(got), kT, 1.5f)
        << "позиция кривой обязана быть якорем, а не протухшим кэшём";
    EXPECT_GT(got, 0) << "РЕГРЕССИЯ: литеральный 0 вместо якоря (баг «возобновление с 0:00»)";
}

// N2. Консистентность записи: после anchorUiTimeline(S, E) показанное
// UI-время == S (кэш обновлён атомарно с якорем, а не «когда-нибудь потом»).
TEST_F(AnchorUiTimelineTest, AnchorWritesCacheAtomically) {
    constexpr float kS = 12345.0f;

    engine.resetState();
    engine.setPlaying(true);              // иначе UI-время заморожено по флагу
    engine.anchorUiTimeline(kS, kS);      // span = 0: экстраполяция обнулена

    const int32_t got = engine.getCurrentTimeOfDaySeconds();
    EXPECT_NEAR(static_cast<float>(got), kS, 1.5f)
        << "кэш обязан быть консистентен с якорем в момент его установки";

    // Частоты UI обязаны считаться по ТОЙ ЖЕ оси (одна формула на двоих) и не
    // сдвинуть её. Кривая здесь не сконфигурирована (частоты 0/0), но время
    // считается и обновляет кэш — проверяем, что это не ломает позицию.
    (void)engine.getFrequenciesAtCurrentTime();
    EXPECT_NEAR(static_cast<float>(engine.getCurrentTimeOfDaySeconds()), kS, 1.5f)
        << "опрос частот UI не имеет права сдвигать ось времени";
}

// N2b. Играющий пакет: позиция ползёт вперёд по wall-clock, но НЕ выбегает за
// конец сгенерированного диапазона. И никогда не уезжает назад.
TEST_F(AnchorUiTimelineTest, PlayingPositionAdvancesWithinGeneratedSpan) {
    constexpr float kStart = 40000.0f;
    constexpr float kSpan = 10.0f;

    engine.resetState();
    engine.setPlaying(true);
    engine.anchorUiTimeline(kStart, kStart + kSpan);

    const float first = static_cast<float>(engine.getCurrentTimeOfDaySeconds());
    EXPECT_LE(circularDelta(first, kStart), 1.5f);

    std::this_thread::sleep_for(std::chrono::milliseconds(250));

    const float later = static_cast<float>(engine.getCurrentTimeOfDaySeconds());
    const float elapsed = circularDelta(later, kStart);
    EXPECT_GE(elapsed, 0.0f);
    EXPECT_LE(elapsed, kSpan + 1.5f) << "указатель не имеет права обгонять сгенерированное аудио";
}

// N3. Переход через полночь: нормализация обязательна. T = 86399.9 и якорь,
// чей конец лежит ЗА полночью (86401) — ни отрицательных значений, ни 86400+.
TEST_F(AnchorUiTimelineTest, MidnightBoundaryIsNormalized) {
    constexpr float kT = 86399.9f;

    engine.resetState();
    engine.setCurveTimeSeconds(kT);

    const int32_t got = engine.getCurrentTimeOfDaySeconds();
    EXPECT_GE(got, 0);
    EXPECT_LT(got, 86400);
    EXPECT_LE(circularDelta(static_cast<float>(got), kT), 1.5f)
        << "86399.9 и 0 — соседи, а не «противоположные концы суток»";

    // Якорь, переходящий через полночь: start = 86399, end = 86401 (span = 2).
    engine.setPlaying(true);
    engine.anchorUiTimeline(86399.0f, 86401.0f);
    const int32_t cross = engine.getCurrentTimeOfDaySeconds();
    EXPECT_GE(cross, 0);
    EXPECT_LT(cross, 86400);
    const float delta = circularDelta(static_cast<float>(cross), 86399.0f);
    EXPECT_LE(delta, 2.0f + 1.5f) << "позиция обязана считаться по кругу, а не по модулю";
}

// N4. Пауза: UI-время ЗАМОРОЖЕНО, getCurrentTimeOfDaySeconds() не «убегает»
// ни по wall-clock, ни после снятия флага воспроизведения.
TEST_F(AnchorUiTimelineTest, PauseFreezesUiTime) {
    constexpr float kA = 45409.0f;   // слышимая позиция на момент заморозки

    engine.resetState();
    engine.setPlaying(true);
    engine.freezeUiTimelineAt(kA);   // span = 0, кэш = A

    const int32_t atFreeze = engine.getCurrentTimeOfDaySeconds();
    EXPECT_NEAR(static_cast<float>(atFreeze), kA, 1.5f);

    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    const int32_t afterWait = engine.getCurrentTimeOfDaySeconds();
    EXPECT_EQ(atFreeze, afterWait) << "на паузе время кривой стоять обязано";

    // Полная остановка (движок выключен) — заморозка тем более держится.
    engine.setPlaying(false);
    EXPECT_NEAR(static_cast<float>(engine.getCurrentTimeOfDaySeconds()), kA, 1.5f);
}

// N4b. Возобновление снимает заморозку: указатель продолжает с той же позиции
// и НЕ выбегает за фронтир уже сгенерированного аудио.
TEST_F(AnchorUiTimelineTest, ResumeUnfreezesWithinFrontier) {
    constexpr float kA = 45409.0f;
    constexpr float kFrontier = 45509.0f;   // 100 с сгенерированного пакета

    engine.resetState();
    engine.setPlaying(true);
    engine.setCurveTimeSeconds(kFrontier);   // фронтир генерации
    engine.resumeUiTimelineFrom(kA);

    const int32_t got = engine.getCurrentTimeOfDaySeconds();
    EXPECT_NEAR(static_cast<float>(got), kA, 1.5f);

    std::this_thread::sleep_for(std::chrono::milliseconds(250));

    const float later = static_cast<float>(engine.getCurrentTimeOfDaySeconds());
    const float fromA = circularDelta(later, kA);
    EXPECT_LE(fromA, 1.5f) << "после возобновления указатель идёт от A, а не от фронтира";
}

}  // namespace test
}  // namespace binaural
