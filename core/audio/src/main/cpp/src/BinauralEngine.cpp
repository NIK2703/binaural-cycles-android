#include "BinauralEngine.h"
#include "BufferPackagePlanner.h"
#include <chrono>
#include <ctime>
#include <algorithm>
#include <cmath>
#include <atomic>
#include <shared_mutex>
#include <mutex>

#ifdef AUDIO_TEST_BUILD
#include "../tests/android_stub.h"
#elif defined(ANDROID)
#include <android/log.h>
#else
#include "../tests/android_stub.h"
#endif

#ifdef USE_NEON
#include <arm_neon.h>
#endif

#ifdef USE_SSE
#include <immintrin.h>
#endif

// Логирование только в DEBUG сборках. PKG_BOUNDARY — ВСЕГДА (диагностика стыков).
#ifdef AUDIO_DEBUG
#define LOG_TAG "BinauralEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif

// Лог стыков пакетов (PKG_BOUNDARY). ОТКЛЮЧЕН по умолчанию — включается
// только при debug.binaural.segment_log=1 (как SEGMENT_DEBUG), иначе в
// release/debug сборке засоряет logcat. Для включения:
//   adb shell setprop debug.binaural.segment_log 1  (и перезапуск движка).
#if defined(ANDROID) && !defined(AUDIO_TEST_BUILD)
#include <sys/system_properties.h>
inline bool pkgBoundaryLogEnabled() {
    static const bool enabled = []() {
        char v[PROP_VALUE_MAX] = {0};
        return __system_property_get("debug.binaural.segment_log", v) > 0 && v[0] == '1';
    }();
    return enabled;
}
#define PKG_LOG(...) do { if (pkgBoundaryLogEnabled()) __android_log_print(ANDROID_LOG_INFO, "PKG_BOUNDARY", __VA_ARGS__); } while (0)
#else
#define PKG_LOG(...) ((void)0)
#endif

namespace binaural {

// Нормализация времени суток в [0, 86400). Только float — единая ось времени.
namespace {
constexpr float kSecondsPerDayF = 86400.0f;
inline float normalizeTimeOfDay(float t) {
    float r = std::fmod(t, kSecondsPerDayF);
    if (r < 0.0f) r += kSecondsPerDayF;
    return r;
}

inline int64_t nowWallClockMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

// ===== Диагностика стыков пакетов (видна в debug-сборке через LOGD) =====
// Конец предыдущего пакета (сек суток) и версия конфига (инкремент на setConfig).
std::atomic<float>    s_lastPkgEnd{-1.0f};
std::atomic<uint32_t> s_cfgVersion{0};

// PKG_SEAM: контентный контроль стыка — последние сэмплы пакета N vs первые N+1
std::atomic<float> s_seamPrevLastL{0.0f};
std::atomic<float> s_seamPrevLastR{0.0f};
std::atomic<float> s_seamPrevEndLower{0.0f};
std::atomic<float> s_seamPrevEndUpper{0.0f};
std::atomic<bool>  s_seamHasPrev{false};
} // namespace

BinauralEngine::BinauralEngine() {
    // Инициализация конфигурации по умолчанию
    m_config.curve.updateCache();

    // Отпечаток сборки: если дата в логе старая — на устройстве СТАРЫЙ APK.
    PKG_LOG("ENGINE_BUILD: compiled %s %s (seam-diag v1)", __DATE__, __TIME__);
    
#ifdef USE_NEON
    LOGD("BinauralEngine initialized with NEON SIMD + FMA optimization");
#elif defined(USE_SSE)
    LOGD("BinauralEngine initialized with SSE SIMD optimization");
#else
    LOGD("BinauralEngine initialized (scalar mode)");
#endif
}

BinauralEngine::~BinauralEngine() = default;

void BinauralEngine::setCallbacks(EngineCallbacks callbacks) {
    m_callbacks = std::move(callbacks);
}

void BinauralEngine::setConfig(const BinauralConfig& config) {
    // Быстрый путь: обновляем конфигурацию с минимальной блокировкой
    // Копируем и строим кэш ВНЕ мьютекса.
    // P1: именно updateCache (а не голый buildLookupTable): TEMPORAL-нормализация
    // читает min/max-кэш кривой — без него амплитуды обнуляются (тишина).
    BinauralConfig newConfig = config;
    newConfig.curve.updateCache();
    
    // Эксклюзивная блокировка для записи
    std::unique_lock<std::shared_mutex> lock(m_configMutex);
    m_config = std::move(newConfig);
    
    // Диагностика: версия конфига растёт при каждой подмене — по логам
    // PKG_BOUNDARY видно, если кривая меняется между пакетами.
    const uint32_t cfgVer = s_cfgVersion.fetch_add(1, std::memory_order_relaxed) + 1;
    PKG_LOG("CONFIG_CHANGED: cfgVer=%u", cfgVer);
}

void BinauralEngine::setSampleRate(int sampleRate) {
    m_generator.setSampleRate(sampleRate);
}

void BinauralEngine::setBatchDurationMinutes(int durationMinutes) {
    m_batchDurationMinutes = durationMinutes;
    LOGD("Batch duration set to %d minutes", durationMinutes);
}

int BinauralEngine::generateBatch(float* buffer, int maxSamplesPerChannel) {
    if (!m_isPlaying.load(std::memory_order_acquire) || m_batchDurationMinutes <= 0) {
        return 0;
    }
    
    const int sampleRate = m_generator.getSampleRate();
    // План строим под ФАКТИЧЕСКИЙ лимит буфера, а не под полный батч:
    // генератор пишет ровно plan-длительность — расхождение = переполнение буфера
    const int maxSamples = m_batchDurationMinutes * 60 * sampleRate;
    const int samplesToGenerate = std::min(maxSamples, maxSamplesPerChannel);
    if (samplesToGenerate <= 0) {
        return 0;
    }
    const int64_t packageDurationMs =
        static_cast<int64_t>(samplesToGenerate) * 1000 / std::max(1, sampleRate);
    
    BinauralConfig config;
    {
        std::shared_lock<std::shared_mutex> lock(m_configMutex);
        config = m_config;
    }
    
    // Точное время для начала буфера (float для сохранения дробной части)
    // КРИТИЧНО: используем float вместо int32_t для бесшовных переходов между пакетами
    // Вычисляется ДО планирования: нужно планировщику для TREND-режима перестановки
    float timeSeconds = computePlaybackTimeSeconds();

    // Множитель скорости виртуального времени. В debug-режиме (VirtualClock
    // включён) частота-кривая должна обходиться быстрее реального времени.
#ifdef ENABLE_DEBUG_TIME_CONTROL
    const float timeScale = m_virtualClock.isEnabled() ? m_virtualClock.getTimeScale() : 1.0f;
#else
    const float timeScale = 1.0f;
#endif

    // Планируем пакет буферов
    BufferPackagePlanner planner;
    PackagePlan plan = planner.planPackage(packageDurationMs, config, m_state,
                                           timeSeconds, timeScale);

    const int64_t elapsedMs = static_cast<int64_t>(
        m_elapsedSeconds.load(std::memory_order_relaxed)
    ) * 1000;

    // Генерируем пакет буферов по плану
#if defined(USE_NEON)
    GenerateResult result = m_generator.generatePackageNeon(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#elif defined(USE_SSE)
    GenerateResult result = m_generator.generatePackageSse(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#else
    GenerateResult result = m_generator.generatePackage(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#endif
    
    // Обновляем время используя РЕАЛЬНОЕ количество сгенерированных сэмплов.
    // Только float — единая ось времени с генератором.
    const float batchDurationSeconds =
        static_cast<float>(result.samplesGenerated) / static_cast<float>(sampleRate);
    m_totalBufferTimeSeconds.store(
        m_totalBufferTimeSeconds.load(std::memory_order_relaxed) + batchDurationSeconds,
        std::memory_order_relaxed
    );

    // Фикс (Qwen, P1): продвигаем единый носитель времени кривой — как в
    // generateAudioBuffer. Без этого в real-time режиме computePlaybackTimeSeconds()
    // возвращал бы одно и то же время при повторных вызовах generateBatch
    // (замороженная кривая частот между батчами).
    m_curveTimeSeconds.store(
        normalizeTimeOfDay(m_curveTimeSeconds.load(std::memory_order_relaxed) +
                           batchDurationSeconds * timeScale),
        std::memory_order_relaxed);

    // Якорь UI-таймлайна (аналогично generateAudioBuffer).
    anchorUiTimeline(timeSeconds, timeSeconds + batchDurationSeconds * timeScale);

    // E5: коррекция дрейфа swap-цикла (как в generateAudioBuffer): планировщик
    // планировал packageDurationMs, реально сгенерировано samplesGenerated —
    // вычитаем разницу из остатка фазы свопа, иначе ms-ось планировщика
    // убегает от сэмпловой после серий коротких/нецелых пакетов.
    {
        const float plannedMs   = static_cast<float>(packageDurationMs);
        const float generatedMs = 1000.0f * batchDurationSeconds;
        const float deltaMs     = plannedMs - generatedMs;
        if (deltaMs > 0.0f && m_state.phaseRemainingMs > 0) {
            m_state.phaseRemainingMs -= static_cast<int64_t>(deltaMs + 0.5f);
            if (m_state.phaseRemainingMs < 0) m_state.phaseRemainingMs = 0;
        }
    }

    // E5: обновление s_lastPkgEnd для seam-диагностики — как в buffer-пути.
    s_lastPkgEnd.store(
        normalizeTimeOfDay(timeSeconds + batchDurationSeconds * timeScale),
        std::memory_order_relaxed);

    // Обновляем атомарные значения для Java
    const float prevBeatFreq = m_currentBeatFreq.exchange(result.currentBeatFreq, std::memory_order_relaxed);
    m_currentCarrierFreq.store(result.currentCarrierFreq, std::memory_order_relaxed);
    
    // Callback при значительном изменении частоты (> 0.1 Hz)
    if (std::abs(result.currentBeatFreq - prevBeatFreq) > 0.1f) {
        if (m_callbacks.onFrequencyChanged) {
            m_callbacks.onFrequencyChanged(result.currentBeatFreq, result.currentCarrierFreq);
        }
    }
    
    // Уведомляем о перестановке каналов
    if (result.channelsSwapped && m_callbacks.onChannelsSwapped) {
        LOGD("ChannelSwap: elapsedMs=%lld, channelsSwapped=%d",
             (long long)elapsedMs, m_state.channelsSwapped ? 1 : 0);
        m_callbacks.onChannelsSwapped(m_state.channelsSwapped);
    }
    
    // Контракт как у generateAudioBuffer: возвращаем РЕАЛЬНО сгенерированное
    // количество — вызывающая сторона обязана записать в AudioTrack ровно его
    return result.samplesGenerated;
}

void BinauralEngine::setPlaying(bool playing) {
    setPlaying(playing, /*preserveTimeline=*/false);
}

void BinauralEngine::setPlaying(bool playing, bool preserveTimeline) {
    m_isPlaying.store(playing, std::memory_order_release);
    
    if (playing) {
        if (preserveTimeline) {
            // RESUME: продолжаем с того же места кривой и той же фазой.
            // НЕ трогаем m_baseTimeSeconds / m_totalBufferTimeSeconds / фазы —
            // иначе кривая прыгает к wall-clock и слышен щелчок на resume.
            LOGD("setPlaying(true, resume): timeline preserved base=%d total=%.3f",
                 m_baseTimeSeconds.load(std::memory_order_relaxed),
                 m_totalBufferTimeSeconds.load(std::memory_order_relaxed));
            // Переякоряем UI-таймлайн на текущую позицию кривой, чтобы wall-время,
            // прошедшее за паузу, не экстраполировалось в будущее. Span=0 — до
            // следующего пакета UI стоит на текущей позиции (пакет сгенерируется сразу).
            const float resumeTime = computePlaybackTimeSeconds();

            // Коррекция расположения каналов при возобновлении:
            // 1) выключенный swap не должен «залипать» в переставленном состоянии
            //    (настройка менялась в паузе без рестарта воспроизведения);
            // 2) TREND-режим выравнивает расположение по текущему тренду
            //    (идемпотентно; TIMER не трогаем — его состоянием владеет фазовая машина).
            {
                BinauralConfig resumeCfg;
                // unique_lock: защищаем и чтение m_config, и запись
                // m_state.channelsSwapped (синхронно с isChannelsSwapped(),
                // читающим поле под shared_lock из UI-потока).
                std::unique_lock<std::shared_mutex> lock(m_configMutex);
                resumeCfg = m_config;
                if (!resumeCfg.channelSwapEnabled) {
                    if (m_state.channelsSwapped) {
                        LOGD("setPlaying(resume): swap disabled while paused -> force normal");
                        m_state.channelsSwapped = false;
                    }
                } else if (resumeCfg.channelSwapMode == ChannelSwapMode::TREND &&
                           !resumeCfg.curve.lowerFreqTable.empty() &&
                           !resumeCfg.curve.upperFreqTable.empty()) {
                    // BOTH: реалайн по знаку тренда (прежнее поведение). Для
                    // PEAKS/TROUGHS абсолютного эталона нет — фаза зависит от числа
                    // пройденных суток (теряется при wrap позиции), поэтому держим
                    // сохранённое состояние: тогглы на выбранных экстремумах иначе
                    // сломаются.
                    if (resumeCfg.channelSwapTrendPoints == ChannelSwapTrendPoints::BOTH) {
                        const bool aligned = trendDesiredSwapped(
                            m_state.channelsSwapped,
                            trendBeatDeltaAt(resumeCfg.curve, resumeTime));
                        if (aligned != m_state.channelsSwapped) {
                            LOGD("setPlaying(resume): TREND realign swapped %d -> %d",
                                 m_state.channelsSwapped ? 1 : 0, aligned ? 1 : 0);
                            m_state.channelsSwapped = aligned;
                        }
                    }
                }
            }

            anchorUiTimeline(resumeTime, resumeTime);
            return;
        }
        // ЖЁСТКАЯ гарантия первого сэмпла = 0: свежий старт всегда обнуляет фазы
        // генератора, независимо от порядка вызовов на стороне Kotlin.
        // (resetState() в Kotlin делает то же самое — операция идемпотентна.)
        m_generator.resetState(m_state);
        BufferPackagePlanner planner;
        m_state.lastSwapElapsedMs = 0;
        m_elapsedSeconds.store(0, std::memory_order_relaxed);

#ifdef ENABLE_DEBUG_TIME_CONTROL
        const float timeScale = m_virtualClock.isEnabled() ? m_virtualClock.getTimeScale() : 1.0f;
#else
        const float timeScale = 1.0f;
#endif

#ifdef ENABLE_DEBUG_TIME_CONTROL
        if (m_virtualClock.isEnabled()) {
            // ВАЖНО: НЕ сбрасываем m_totalBufferTimeSeconds.
            // И fresh-play, и resume в Kotlin идут через setPlaying(true);
            // сброс total откатил бы виртуальное время и вернул стык.
            // Таймлайн устанавливается в enable/scrub/setScale/reset.
            m_curveTimeSeconds.store(
                normalizeTimeOfDay(m_virtualBaseTimeSeconds.load(std::memory_order_relaxed)),
                std::memory_order_relaxed);
            LOGD("setPlaying(true): virtual timeline preserved (base=%.2f, total=%.3f)",
                 m_virtualBaseTimeSeconds.load(std::memory_order_relaxed),
                 m_totalBufferTimeSeconds.load(std::memory_order_relaxed));
        } else {
            // E1: дробный якорь свежего play (int32 терял долю секунды).
            const float baseF = realTimeOfDaySeconds();
            m_baseTimeSeconds.store(static_cast<int32_t>(baseF), std::memory_order_relaxed); // legacy/диагностика
            m_totalBufferTimeSeconds.store(0.0f, std::memory_order_relaxed);
            m_curveTimeSeconds.store(normalizeTimeOfDay(baseF),
                                     std::memory_order_relaxed);
            LOGD("setPlaying(true): baseTime=%.3f", baseF);
        }
#else
        // E1: дробный якорь свежего play (int32 терял долю секунды).
        const float baseF = realTimeOfDaySeconds();
        m_baseTimeSeconds.store(static_cast<int32_t>(baseF), std::memory_order_relaxed); // legacy/диагностика
        m_totalBufferTimeSeconds.store(0.0f, std::memory_order_relaxed);
        m_curveTimeSeconds.store(normalizeTimeOfDay(baseF),
                                 std::memory_order_relaxed);
        LOGD("setPlaying(true): baseTime=%.3f", baseF);
#endif

        // Свежий старт: якорим UI-таймлайн на базовую точку кривой.
        // Span=0 — до первого пакета UI стоит на базе; первый пакет
        // сгенерируется немедленно и расширит диапазон.
        const float freshStart = computePlaybackTimeSeconds();

        // Старт воспроизведения: положение каналов и фаза цикла инициализируются
        // по расписанию для текущего момента суток (initStateForStart): играем
        // сразу в правильном расположении, свап — только в расписанное время.
        {
            BinauralConfig startCfg;
            // unique_lock: чтение m_config + запись m_state (синхронно с
            // isChannelsSwapped() из UI-потока под shared_lock).
            std::unique_lock<std::shared_mutex> lock(m_configMutex);
            startCfg = m_config;
            planner.initStateForStart(startCfg, m_state, freshStart, timeScale);
            LOGD("setPlaying(true): schedule init swap=%d (mode=%d, points=%d, pos=%.3f, solidMs=%lld)",
                 m_state.channelsSwapped ? 1 : 0,
                 static_cast<int>(startCfg.channelSwapMode),
                 static_cast<int>(startCfg.channelSwapTrendPoints),
                 freshStart, (long long)m_state.phaseRemainingMs);
        }

        anchorUiTimeline(freshStart, freshStart);
    }
}

void BinauralEngine::setCurveTimeSeconds(float timeSeconds) {
    const float t = normalizeTimeOfDay(timeSeconds);
    // Единый носитель времени кривой + совместимость с legacy-диагностикой
    m_curveTimeSeconds.store(t, std::memory_order_relaxed);
    m_baseTimeSeconds.store(static_cast<int32_t>(t), std::memory_order_relaxed);
    m_totalBufferTimeSeconds.store(0.0f, std::memory_order_relaxed);
    // UI-указатель на позицию продолжения (иначе экстраполяция от старого якоря)
    anchorUiTimeline(t, t);
}

void BinauralEngine::resetState() {
    m_generator.resetState(m_state);
    
    // Сброс состояния планировщика
    BufferPackagePlanner planner;
    planner.resetState(m_state);
    
    m_elapsedSeconds.store(0, std::memory_order_relaxed);
    m_currentBeatFreq.store(0.0f, std::memory_order_relaxed);
    m_currentCarrierFreq.store(0.0f, std::memory_order_relaxed);

    // UI-таймлайн больше не валиден: после stop показываем реальное время суток
    m_uiAnchorWallMs.store(0, std::memory_order_relaxed);
    m_uiLastUiTimeSec.store(0.0f, std::memory_order_relaxed);
}

bool BinauralEngine::isChannelsSwapped() const {
    // Безвредный, но формальный data race: поле пишется аудио-потоком,
    // читается UI-потоком. Берём под общий мьютекс конфига — дёшево и
    // исключает предупреждения TSan / тиринг флага.
    std::shared_lock<std::shared_mutex> lock(m_configMutex);
    return m_state.channelsSwapped;
}
std::pair<float, float> BinauralEngine::getCurrentPhases() const {
    // best-effort чтение живой фазы OLD (см. комментарий в заголовке):
    // на ARM выровненное 32-битное чтение атомарно, отклонение — доли мкс.
    return { m_state.leftPhase, m_state.rightPhase };
}

void BinauralEngine::setPhases(float leftPhase, float rightPhase) {
    // Зовётся до старта писателя (prepare) — гонки с аудио-потоком нет.
    m_state.leftPhase = leftPhase;
    m_state.rightPhase = rightPhase;
}

bool BinauralEngine::isCurveConfigured() const {
    std::shared_lock<std::shared_mutex> lock(m_configMutex);
    return !m_config.curve.lowerFreqTable.empty() && !m_config.curve.upperFreqTable.empty();
}

int32_t BinauralEngine::getCurrentTimeSeconds() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    if (m_virtualClock.isEnabled()) {
        // Та же sample-driven ось времени, что и у генерации,
        // чтобы UI-индикатор и отображаемые частоты совпадали со звуком.
        return static_cast<int32_t>(computePlaybackTimeSeconds());
    }
#endif
    // Thread-safe получение текущего времени суток
    auto now = std::chrono::system_clock::now();
    
#ifdef __ANDROID__
    // На Android используем localtime_r (thread-safe версия)
    time_t time = std::chrono::system_clock::to_time_t(now);
    struct tm tm_info;
    localtime_r(&time, &tm_info);
    return tm_info.tm_hour * 3600 + tm_info.tm_min * 60 + tm_info.tm_sec;
#else
    // Fallback: UTC
    auto duration = now.time_since_epoch();
    auto totalSeconds = std::chrono::duration_cast<std::chrono::seconds>(duration).count();
    constexpr int32_t SECONDS_PER_DAY = 86400;
    return static_cast<int32_t>(totalSeconds % SECONDS_PER_DAY);
#endif
}

// E1: дробное ЛОКАЛЬНОЕ время суток. Целочисленный геттер терял долю секунды
// (смещение старта кривой до 1с). Мс — одним снимком system_clock; локальная
// ось через localtime_r (как в getCurrentTimeSeconds/VirtualClock), т.к.
// сырой %86400000 от эпохи дал бы UTC-сутки, а не локальные.
float BinauralEngine::realTimeOfDaySeconds() {
    const int64_t nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    const std::time_t sec = static_cast<std::time_t>(nowMs / 1000);
    struct tm tmInfo;
    localtime_r(&sec, &tmInfo);
    const int32_t whole = tmInfo.tm_hour * 3600 + tmInfo.tm_min * 60 + tmInfo.tm_sec;
    return static_cast<float>(whole) +
           static_cast<float>(nowMs % 1000) / 1000.0f;
}

std::pair<float, float> BinauralEngine::getFrequenciesAtCurrentTime() {
    // Получаем ТЕКУЩЕЕ время кривой для UI: плавная экстраполяция по wall-clock
    // в пределах сгенерированного диапазона. computePlaybackTimeSeconds() здесь
    // НЕ годится — он продвигается только при генерации пакета, из-за чего
    // частоты на экране «стояли» раз в интервал генерации.
    const float currentSeconds = computeUiTimeSeconds();
    
    // Читаем конфигурацию с shared_lock
    std::shared_lock<std::shared_mutex> lock(m_configMutex);
    
    const auto& curve = m_config.curve;
    
    // Проверяем что lookup table построена
    if (curve.lowerFreqTable.empty() || curve.upperFreqTable.empty()) {
        return {0.0f, 0.0f};
    }
    
    // O(1) доступ к предвычисленной таблице
    // Индекс: время в мс / шаг таблицы (100 мс)
    const float timeMs = currentSeconds * 1000.0f;
    const float indexFloat = timeMs / FREQUENCY_TABLE_INTERVAL_MS;
    const int index = static_cast<int>(indexFloat);
    
    // Безопасное получение значений с проверкой границ
    const int clampedIndex = std::clamp(index, 0, static_cast<int>(curve.lowerFreqTable.size()) - 1);
    
    const float lowerFreq = curve.lowerFreqTable[clampedIndex];
    const float upperFreq = curve.upperFreqTable[clampedIndex];
    
    // Вычисляем beat и carrier частоты
    const float beatFreq = upperFreq - lowerFreq;
    const float carrierFreq = (lowerFreq + upperFreq) / 2.0f;
    
    return {beatFreq, carrierFreq};
}

void BinauralEngine::updateElapsedTime() {
    const int64_t startTime = m_playbackStartTimeMs.load(std::memory_order_relaxed);
    if (startTime > 0) {
        auto now = std::chrono::system_clock::now();
        auto nowMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            now.time_since_epoch()
        ).count();
        int elapsed = static_cast<int>((nowMs - startTime) / 1000);
        m_elapsedSeconds.store(elapsed, std::memory_order_relaxed);
        
        if (m_callbacks.onElapsedChanged) {
            m_callbacks.onElapsedChanged(elapsed);
        }
    }
}

int BinauralEngine::generateAudioBuffer(float* buffer, int samplesPerChannel) {
    // Быстрая проверка без блокировки
    if (!m_isPlaying.load(std::memory_order_acquire)) {
        return 0;
    }
    
    // Вычисляем точное время для интерполяции
    const int sampleRate = m_generator.getSampleRate();
    const float bufferDurationSeconds = static_cast<float>(samplesPerChannel) / sampleRate;
    const int64_t bufferDurationMs = static_cast<int64_t>(samplesPerChannel) * 1000 / sampleRate;
    
    // Точное время для начала буфера (float для сохранения дробной части)
    // КРИТИЧНО: используем float вместо int32_t для бесшовных переходов между пакетами
    float timeSeconds = computePlaybackTimeSeconds();

    // Множитель скорости виртуального времени (см. generateBatch).
#ifdef ENABLE_DEBUG_TIME_CONTROL
    const float timeScale = m_virtualClock.isEnabled() ? m_virtualClock.getTimeScale() : 1.0f;
#else
    const float timeScale = 1.0f;
#endif

    const int64_t elapsedMs = static_cast<int64_t>(m_elapsedSeconds.load(std::memory_order_relaxed)) * 1000;

    // Обновляем прошедшее время асинхронно
    updateElapsedTime();
    
    // ОПТИМИЗАЦИЯ: Используем shared_lock для чтения (множественное чтение)
    // Это позволяет нескольким потокам читать конфигурацию одновременно
    BinauralConfig config;
    {
        std::shared_lock<std::shared_mutex> lock(m_configMutex);
        config = m_config;
    }
    
    // НОВАЯ АРХИТЕКТУРА: Используем планировщик пакетов
    // Планируем пакет буферов на основе текущего состояния
    // TREND-режиму перестановки нужны позиция кривой и масштаб времени
    BufferPackagePlanner planner;
    PackagePlan plan = planner.planPackage(bufferDurationMs, config, m_state,
                                           timeSeconds, timeScale);
    
    // Диагностика стыка: сравниваем частоты кривой в конце прошлого пакета
    // и в начале текущего. Если различаются при dt≈0 — скачок времени/конфига.
    // Фикс (Qwen, P2): сохраняем prevEnd ДО генерации и обновления s_lastPkgEnd,
    // чтобы PKG_SEAM ниже считал dt относительно реального конца прошлого пакета
    // (иначе dt всегда равен -длительности пакета и вводит в заблуждение).
    const float prevEndForSeam = s_lastPkgEnd.load(std::memory_order_relaxed);
    {
        const float prevEnd = prevEndForSeam;
        FrequencyTableResult fS = config.curve.getChannelFrequenciesAt(timeSeconds);
        FrequencyTableResult fP;
        fP.lowerFreq = 0.0f; fP.upperFreq = 0.0f;
        if (prevEnd >= 0.0f) {
            fP = config.curve.getChannelFrequenciesAt(prevEnd);
        }
        PKG_LOG("PKG_BOUNDARY: prevEnd=%.4f start=%.4f dt=%.6f | "
             "f@prevEnd=[%.3f,%.3f] f@start=[%.3f,%.3f] | cfgVer=%u swapped=%d "
             "swapPhase=%d phaseRemMs=%lld totalBuf=%.4f baseTime=%d",
             prevEnd, timeSeconds, timeSeconds - prevEnd,
             fP.lowerFreq, fP.upperFreq, fS.lowerFreq, fS.upperFreq,
             s_cfgVersion.load(std::memory_order_relaxed),
             m_state.channelsSwapped ? 1 : 0,
             static_cast<int>(m_state.swapPhase),
             (long long)m_state.phaseRemainingMs,
             m_totalBufferTimeSeconds.load(std::memory_order_relaxed),
             m_baseTimeSeconds.load(std::memory_order_relaxed));
    }
    
    // Используем SIMD-оптимизированную версию если доступна
#if defined(USE_NEON)
    GenerateResult result = m_generator.generatePackageNeon(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#elif defined(USE_SSE)
    GenerateResult result = m_generator.generatePackageSse(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#else
    GenerateResult result = m_generator.generatePackage(
        buffer,
        plan,
        config,
        m_state,
        timeSeconds,
        elapsedMs,
        timeScale
    );
#endif
    
    // Обновляем атомарные значения для Java (relaxed для производительности)
    const float prevBeatFreq = m_currentBeatFreq.exchange(result.currentBeatFreq, std::memory_order_relaxed);
    m_currentCarrierFreq.store(result.currentCarrierFreq, std::memory_order_relaxed);
    
    // Callback только при значительном изменении частоты (> 0.1 Hz)
    if (std::abs(result.currentBeatFreq - prevBeatFreq) > 0.1f) {
        if (m_callbacks.onFrequencyChanged) {
            m_callbacks.onFrequencyChanged(result.currentBeatFreq, result.currentCarrierFreq);
        }
    }
    
    // Уведомляем о перестановке каналов (редкое событие)
    if (result.channelsSwapped && m_callbacks.onChannelsSwapped) {
        LOGD("ChannelSwap: elapsedMs=%lld, channelsSwapped=%d",
             (long long)elapsedMs, m_state.channelsSwapped ? 1 : 0);
        m_callbacks.onChannelsSwapped(m_state.channelsSwapped);
    }
    
    // Обновляем время используя РЕАЛЬНОЕ количество сгенерированных сэмплов.
    // Только float — единая ось времени с генератором.
    const float actualDurationSeconds =
        static_cast<float>(result.samplesGenerated) / static_cast<float>(sampleRate);
    m_totalBufferTimeSeconds.store(
        m_totalBufferTimeSeconds.load(std::memory_order_relaxed) + actualDurationSeconds,
        std::memory_order_relaxed
    );
    // E4: кривая реально продвигается на dur*timeScale — учитываем масштаб и тут,
    // иначе dt на стыке в virtual-режиме лжёт (dt = D*(scale-1)).
    s_lastPkgEnd.store(
        normalizeTimeOfDay(timeSeconds + actualDurationSeconds * timeScale),
        std::memory_order_relaxed);

    // === PKG_SEAM: сравнение последних сэмплов пакета N с первыми пакета N+1 ===
    // |dL|,|dR| < 0.02 → данные непрерывны (щелчок вносит AudioTrack/HAL);
    // > 0.05 → разрыв В ДАННЫХ: смотрим dF/dt/cfgVer.
    if (result.samplesGenerated > 1) {
        const float firstL = buffer[0];
        const float firstR = buffer[1];
        const float lastL  = buffer[(result.samplesGenerated - 1) * 2];
        const float lastR  = buffer[(result.samplesGenerated - 1) * 2 + 1];

        const bool  hasPrev = s_seamHasPrev.load(std::memory_order_relaxed);
        const float dL = hasPrev ? firstL - s_seamPrevLastL.load(std::memory_order_relaxed) : 0.0f;
        const float dR = hasPrev ? firstR - s_seamPrevLastR.load(std::memory_order_relaxed) : 0.0f;

        const FrequencyTableResult curStart = config.curve.getChannelFrequenciesAt(timeSeconds);
        const float peL = s_seamPrevEndLower.load(std::memory_order_relaxed);
        const float peU = s_seamPrevEndUpper.load(std::memory_order_relaxed);

        PKG_LOG("PKG_SEAM: dL=%+.4f dR=%+.4f | prevEnd=[%.3f,%.3f] curStart=[%.3f,%.3f] "
                "dF=[%+.3f,%+.3f] | cfgVer=%u dt=%.6f swapped=%d gen=%d phase=[%.3f,%.3f]",
                dL, dR,
                peL, peU, curStart.lowerFreq, curStart.upperFreq,
                curStart.lowerFreq - peL, curStart.upperFreq - peU,
                s_cfgVersion.load(std::memory_order_relaxed),
                timeSeconds - prevEndForSeam,
                m_state.channelsSwapped ? 1 : 0,
                result.samplesGenerated,
                m_state.leftPhase, m_state.rightPhase);

        s_seamPrevLastL.store(lastL, std::memory_order_relaxed);
        s_seamPrevLastR.store(lastR, std::memory_order_relaxed);
        s_seamPrevEndLower.store(result.currentCarrierFreq - result.currentBeatFreq * 0.5f,
                                 std::memory_order_relaxed);
        s_seamPrevEndUpper.store(result.currentCarrierFreq + result.currentBeatFreq * 0.5f,
                                 std::memory_order_relaxed);
        s_seamHasPrev.store(true, std::memory_order_relaxed);
    }

    // Фикс 2 (Qwen): единый носитель времени кривой — продвигаем на столько,
    // на сколько реально продвинулся генератор (в virtual-режиме — с масштабом).
    // Старт следующего пакета станет ТОЙ ЖЕ float-величиной, что и этот конец.
    m_curveTimeSeconds.store(
        normalizeTimeOfDay(m_curveTimeSeconds.load(std::memory_order_relaxed) +
                           actualDurationSeconds * timeScale),
        std::memory_order_relaxed);

    // Якорь UI-таймлайна: частоты для UI экстраполируются от старта этого пакета
    // по wall-clock в пределах [start, start+duration] — плавное следование
    // графику между пакетами вместо ступеньки раз в интервал генерации.
    anchorUiTimeline(timeSeconds, timeSeconds + actualDurationSeconds * timeScale);

    // Фикс 3 (Qwen): коррекция дрейфа swap-цикла. Планировщик двигал фазу на
    // ЗАПЛАНИРОВАННЫЕ bufferDurationMs, а аудио продвинулось на ФАКТИЧЕСКИЕ
    // samplesGenerated. При нецелосекундных сегментах цикл убегает вперёд.
    {
        const float plannedMs   = static_cast<float>(bufferDurationMs);
        const float generatedMs = 1000.0f * static_cast<float>(result.samplesGenerated) /
                                  static_cast<float>(sampleRate);
        const float deltaMs     = plannedMs - generatedMs;
        if (deltaMs > 0.0f && m_state.phaseRemainingMs > 0) {
            m_state.phaseRemainingMs -= static_cast<int64_t>(deltaMs + 0.5f);
            if (m_state.phaseRemainingMs < 0) m_state.phaseRemainingMs = 0;
        }
    }
    
    // Возвращаем РЕАЛЬНОЕ число сэмплов: вызывающая сторона обязана записать в
    // AudioTrack ровно его, иначе на стыке пакетов звучит мусорный "хвост"
    // (щелчок + резкая смена частот из прошлого пакета).
    return result.samplesGenerated;
}

// ============ Debug virtual time ============

int32_t BinauralEngine::getCurrentTimeOfDaySeconds() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    if (m_virtualClock.isEnabled()) {
        return static_cast<int32_t>(computePlaybackTimeSeconds());
    }
#endif
    // J4: ЕДИНЫЙ источник с частотами UI (getFrequenciesAtCurrentTime ->
    // computeUiTimeSeconds -> m_uiLastUiTimeSec): пока играем — та же плавная
    // экстраполяция по якорю пакета, при паузе/остановке записи — то же
    // замороженное значение конца сгенерированного диапазона. Оси X и Y
    // индикатора рассинхронизироваться не могут по построению.
    // До первого якоря — реальное время суток.
    if (m_uiAnchorWallMs.load(std::memory_order_relaxed) != 0) {
        return static_cast<int32_t>(m_uiLastUiTimeSec.load(std::memory_order_relaxed));
    }
    return getCurrentTimeSeconds();
}

float BinauralEngine::computePlaybackTimeSeconds() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    if (m_virtualClock.isEnabled()) {
        // SAMPLE-DRIVEN таймлайн (как в реальном режиме), но с масштабом:
        //   audioTime = normalize(base + totalSamples * scale)
        // Это устраняет скачок при включении/выключении virtual clock и при
        // смене scale/scrub — время всегда монотонно и непрерывно.
        const float total = m_totalBufferTimeSeconds.load(std::memory_order_relaxed);
        const float scale = m_virtualClock.getTimeScale();
        const float base = m_virtualBaseTimeSeconds.load(std::memory_order_relaxed);
        return normalizeTimeOfDay(base + total * scale);
    }
#endif
    // Реал-тайм: ЕДИНЫЙ НОСИТЕЛЬ времени кривой (как curTime в харнессе).
    // Старт пакета N+1 = та же float-величина, что и конец пакета N.
    return normalizeTimeOfDay(m_curveTimeSeconds.load(std::memory_order_relaxed));
}

// ============ UI-таймлайн кривой ============

void BinauralEngine::anchorUiTimeline(float startSec, float endSec) {
    // Храним «сырые» значения (end может быть > 86400 при переходе через полночь):
    // span ниже считается через normalizeTimeOfDay(end - start), что корректно
    // обрабатывает переход через полночь.
    m_uiAnchorStartSec.store(startSec, std::memory_order_relaxed);
    m_uiAnchorEndSec.store(endSec, std::memory_order_relaxed);
    m_uiAnchorWallMs.store(nowWallClockMs(), std::memory_order_relaxed);
}

float BinauralEngine::computeUiTimeSeconds() {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    const float scale = m_virtualClock.isEnabled() ? m_virtualClock.getTimeScale() : 1.0f;
#else
    const float scale = 1.0f;
#endif

    // Не играем — UI замирает на последнем показанном времени кривой
    // (при soft-pause генерация продолжается и m_isPlaying остаётся true,
    // поэтому UI продолжает двигаться вместе с аудио-таймлайном).
    if (!m_isPlaying.load(std::memory_order_relaxed)) {
        return m_uiLastUiTimeSec.load(std::memory_order_relaxed);
    }

    const int64_t wallMs = m_uiAnchorWallMs.load(std::memory_order_relaxed);
    if (wallMs == 0) {
        // Ещё не якорили (нет ни одного пакета) — прежнее поведение.
        const float t = computePlaybackTimeSeconds();
        m_uiLastUiTimeSec.store(t, std::memory_order_relaxed);
        return t;
    }

    const float start = m_uiAnchorStartSec.load(std::memory_order_relaxed);
    const float end   = m_uiAnchorEndSec.load(std::memory_order_relaxed);
    const float span  = normalizeTimeOfDay(end - start);

    // Экстраполяция по wall-clock от старта пакета, с ограничением сверху
    // концом СГЕНЕРИРОВАННОГО диапазона (не выбегаем за уже сгенерированное аудио).
    float elapsed = static_cast<float>(nowWallClockMs() - wallMs) / 1000.0f * scale;
    if (elapsed < 0.0f) elapsed = 0.0f;
    if (elapsed > span) elapsed = span;

    const float t = normalizeTimeOfDay(start + elapsed);
    m_uiLastUiTimeSec.store(t, std::memory_order_relaxed);
    return t;
}

void BinauralEngine::setVirtualTimeEnabled(bool enabled) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    // E2: позиция кривой захвачена ДО включения (формула времени ещё старая).
    const float posBeforeEnable = computePlaybackTimeSeconds();
    const bool alreadyPlayed =
        m_uiAnchorWallMs.load(std::memory_order_relaxed) != 0 ||
        m_totalBufferTimeSeconds.load(std::memory_order_relaxed) > 0.0f;

    // Сначала заякорить VirtualClock на реальное время, затем прочитать его.
    m_virtualClock.setEnabled(enabled);
    if (enabled) {
        if (alreadyPlayed) {
            // E2: движок уже играл/якорён — сеем виртуальный базис ТЕКУЩЕЙ
            // позицией кривой вместо wall, иначе включение virtual посреди
            // игры сбрасывает позицию (после scrub на 23:00) на реальные часы.
            m_virtualBaseTimeSeconds.store(posBeforeEnable,
                                           std::memory_order_relaxed);
        } else {
            // Seed sample-driven таймлайна текущим реальным временем суток.
            // setEnabled() только что заякорил VirtualClock на реальное время,
            // поэтому getTimeOfDaySeconds() == реальное время суток.
            m_virtualBaseTimeSeconds.store(m_virtualClock.getTimeOfDaySeconds(),
                                           std::memory_order_relaxed);
        }
        m_totalBufferTimeSeconds.store(0.0f, std::memory_order_relaxed);
        m_curveTimeSeconds.store(
            normalizeTimeOfDay(m_virtualBaseTimeSeconds.load(std::memory_order_relaxed)),
            std::memory_order_relaxed);
    } else {
        // Перед выключением синхронизируем реальный базис с текущим виртуальным
        // временем генерации, чтобы реальный режим продолжился без скачка.
        const float total = m_totalBufferTimeSeconds.load(std::memory_order_relaxed);
        const float scale = m_virtualClock.getTimeScale();
        const float base = m_virtualBaseTimeSeconds.load(std::memory_order_relaxed);
        const float resumed = normalizeTimeOfDay(base + total * scale);
        m_baseTimeSeconds.store(static_cast<int32_t>(resumed), std::memory_order_relaxed);
        m_curveTimeSeconds.store(resumed, std::memory_order_relaxed);
        m_totalBufferTimeSeconds.store(0.0f, std::memory_order_relaxed);
        m_virtualClock.setEnabled(false);
    }
    LOGD("setVirtualTimeEnabled(%d)", enabled ? 1 : 0);
#else
    (void)enabled;
#endif
}

void BinauralEngine::scrubVirtualTime(float timeOfDaySeconds) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    // Гвардия: scrub двигает таймлайн из вызывающего потока и может гоняться
    // с in-flight генерацией. В реальном режиме (virtual clock выключен) скраб
    // перезаписал бы живой real-таймлайн — запрещаем; польза только в virtual.
    if (!m_virtualClock.isEnabled()) {
        return;
    }
    // Сдвигаем базис таймлайна, сбрасываем накопленное аудио → без скачка скорости.
    const float wrapped = normalizeTimeOfDay(timeOfDaySeconds);
    m_virtualBaseTimeSeconds.store(wrapped, std::memory_order_relaxed);
    m_curveTimeSeconds.store(wrapped, std::memory_order_relaxed);
    m_totalBufferTimeSeconds.store(0.0f, std::memory_order_relaxed);
    // E3: переякоряем UI-таймлайн на новую позицию, иначе до следующего пакета
    // указатель экстраполируется от СТАРОГО якоря (зависший указатель).
    const float newPos = computePlaybackTimeSeconds();
    anchorUiTimeline(newPos, newPos);
#else
    (void)timeOfDaySeconds;
#endif
}

void BinauralEngine::setVirtualTimeScale(float scale) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    const float clamped = std::clamp(scale, 1.0f, 60.0f);
    // Сохраняем текущее аудио-время при смене масштаба (переносим base,
    // сбрасываем накопленное) — в том числе на виртуальной паузе, иначе
    // позиция прыгнула бы по формуле base+total*новыйScale.
    if (m_virtualClock.isEnabled()) {
        const float total = m_totalBufferTimeSeconds.load(std::memory_order_relaxed);
        const float oldScale = m_virtualClock.getTimeScale();
        const float base = m_virtualBaseTimeSeconds.load(std::memory_order_relaxed);
        const float current = normalizeTimeOfDay(base + total * oldScale);
        m_virtualBaseTimeSeconds.store(current,
                                       std::memory_order_relaxed);
        m_totalBufferTimeSeconds.store(0.0f, std::memory_order_relaxed);
    }
    m_virtualClock.setTimeScale(clamped);
    // E3: переякоряем UI-таймлайн под новый масштаб (см. scrubVirtualTime).
    const float newPos = computePlaybackTimeSeconds();
    anchorUiTimeline(newPos, newPos);
    LOGD("setVirtualTimeScale(%.2f)", clamped);
#else
    (void)scale;
#endif
}

void BinauralEngine::setVirtualTimeRunning(bool running) {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    m_virtualClock.setRunning(running);
    // Ничего не меняем в таймлайне: при паузе генерация останавливается
    // (write блокируется о паузу AudioTrack) и m_totalBufferTimeSeconds замирает сам.
#else
    (void)running;
#endif
}

void BinauralEngine::resetVirtualTimeToReal() {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    m_virtualClock.resetToRealTime();
    // Пересадка на реальное время суток (после resetToRealTime getTimeOfDaySeconds()
    // возвращает реальное время, а не sample-driven ось UI).
    const float realTime = normalizeTimeOfDay(m_virtualClock.getTimeOfDaySeconds());
    m_virtualBaseTimeSeconds.store(realTime, std::memory_order_relaxed);
    m_curveTimeSeconds.store(realTime, std::memory_order_relaxed);
    m_totalBufferTimeSeconds.store(0.0f, std::memory_order_relaxed);
#endif
}

float BinauralEngine::getVirtualTimeOfDaySeconds() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    // Отдаём то же самое время, что генерируется (sample-driven), для консистентности UI.
    if (m_virtualClock.isEnabled()) {
        return computePlaybackTimeSeconds();
    }
    return static_cast<float>(getCurrentTimeSeconds());
#else
    return static_cast<float>(getCurrentTimeSeconds());
#endif
}

bool BinauralEngine::isVirtualTimeEnabled() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    return m_virtualClock.isEnabled();
#else
    return false;
#endif
}

float BinauralEngine::getVirtualTimeScale() const {
#ifdef ENABLE_DEBUG_TIME_CONTROL
    return m_virtualClock.getTimeScale();
#else
    return 1.0f;
#endif
}

} // namespace binaural
