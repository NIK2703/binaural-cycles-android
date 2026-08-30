#pragma once

#include <vector>
#include <memory>
#include <cstdint>

namespace binaural {

/**
 * Количество секунд в сутках
 */
constexpr int SECONDS_PER_DAY = 86400;

/**
 * Границы шага дискретизации таблицы частот (в миллисекундах).
 *
 * Шаг АДАПТИВНЫЙ и выбирается для каждой кривой отдельно (см.
 * FrequencyCurve::tableIntervalMs и buildLookupTableInternal):
 *
 *   step = clamp(minGapBetweenPoints * 1000 / FREQUENCY_TABLE_CELLS_PER_GAP,
 *                FREQUENCY_TABLE_MIN_INTERVAL_MS,
 *                FREQUENCY_TABLE_MAX_INTERVAL_MS)
 *
 * ПОЛ шага = 100 мс — это историческое разрешение, с которым таблица жила
 * всегда. Поэтому адаптивный шаг НИКОГДА не даёт ошибки больше, чем раньше:
 * самая «частая» кривая просто упирается в пол и ведёт себя по-старому.
 * Потолок 1000 мс выбран по замеру (tools-бенч: ошибка против точного сплайна
 * для типовых пресетов 1e-4 Гц при шаге 1 с против 2e-4 Гц при 100 мс).
 *
 * Зачем это нужно: при фиксированных 100 мс таблица занимала
 * 864000 значений × 2 канала × 4 байта = 6.6 МБ, а её пересборка
 * (updateCache, вызывается на КАЖДОЕ изменение настроек) стоила
 * 19 мс (LINEAR) … 37 мс (MONOTONE). У типового пресета точки разнесены на
 * часы, и 100 мс избыточны на два-три порядка.
 */
constexpr int FREQUENCY_TABLE_MIN_INTERVAL_MS = 100;
constexpr int FREQUENCY_TABLE_MAX_INTERVAL_MS = 1000;

/**
 * Сколько ячеек таблицы приходится на минимальный зазор между соседними
 * точками кривой. Определяет качество аппроксимации: ошибка линейной
 * интерполяции внутри ячейки ~ (1/8)·f''·Δt², т.е. убывает как квадрат
 * этого числа. 1000 даёт запас с большим запасом (см. замер выше).
 */
constexpr int FREQUENCY_TABLE_CELLS_PER_GAP = 1000;

/**
 * Максимальный размер таблицы частот (количество значений на канал) —
 * достигается при самом мелком шаге FREQUENCY_TABLE_MIN_INTERVAL_MS.
 * Это верхняя граница памяти, а не рабочий размер.
 */
constexpr int FREQUENCY_TABLE_SIZE = SECONDS_PER_DAY * 1000 / FREQUENCY_TABLE_MIN_INTERVAL_MS;

/**
 * Минимальный размер таблицы — при самом крупном шаге.
 */
constexpr int FREQUENCY_TABLE_MIN_SIZE = SECONDS_PER_DAY * 1000 / FREQUENCY_TABLE_MAX_INTERVAL_MS;

/**
 * Полуокно оценки трендовой производной: Δbeat = beat(t+h) − beat(t−h),
 * beat = upper − lower (частота биений). Живёт здесь, т.к. используется и
 * планировщиком (BufferPackagePlanner.h), и предвычислением нулей в
 * FrequencyCurve (Interpolation.h).
 */
constexpr float TREND_HALF_WINDOW_SEC = 60.0f;

/**
 * Ноль трендовой производной (локальный экстремум ЧАСТОТЫ БИЕНИЙ).
 * Предвычисляется ОДИН РАЗ при построении кривой (FrequencyCurve::updateCache)
 * и дальше только переиспользуется планировщиком.
 */
struct TrendCrossing {
    float timeSec;    // Время суток [0, SECONDS_PER_DAY), уточнено бисекцией
    bool toSwapped;   // true: после T тренд частоты биений убывает (пик) → swapped;
                      // false: после T тренд растёт (впадина) → swapped=false
};

/**
 * Тип интерполяции между точками
 */
enum class InterpolationType : int8_t {
    LINEAR = 0,     // Линейная интерполяция
    CARDINAL = 1,   // Кардинальный сплайн
    MONOTONE = 2,   // Монотонный сплайн
    STEP = 3        // Ступенчатая интерполяция
};

/**
 * Тип нормализации громкости
 */
enum class NormalizationType : int8_t {
    NONE = 0,       // Без нормализации
    CHANNEL = 1,    // Канальная нормализация
    TEMPORAL = 2    // Временная нормализация
};

/**
 * Результат запроса частот из lookup table
 * Содержит интерполированные частоты для конкретного момента времени
 */
struct FrequencyTableResult {
    float lowerFreq;   // Нижняя частота (левый канал)
    float upperFreq;   // Верхняя частота (правый канал)
};

/**
 * Точка на графике частот
 */
struct FrequencyPoint {
    int32_t timeSeconds;      // Секунды с начала суток (0-86399)
    float carrierFrequency;   // Несущая частота (Гц)
    float beatFrequency;      // Частота биений (Гц)
};

/**
 * Lookup-таблица частот одного канала.
 *
 * Таблица НЕИЗМЕНЯЕМА после постройки и разделяется через shared_ptr.
 *
 * Почему именно shared_ptr: движок копирует конфиг на КАЖДЫЙ генерируемый
 * пакет (`config = m_config;` под shared_lock в generateBatch/generateAudioBuffer).
 * При владении таблицами по значению это означает memcpy 6.6 МБ (0.651 мс по
 * замеру) плюс две аллокации по 3.3 МБ на каждый пакет, хотя за пакет
 * читается 0.2–0.4 % таблицы. Через shared_ptr копирование конфига становится
 * O(1): инкремент двух счётчиков ссылок. Пересборка (setConfig) создаёт НОВЫЕ
 * таблицы, старые при этом остаются валидны у тех, кто их держит.
 */
using FreqTablePtr = std::shared_ptr<const std::vector<float>>;

/**
 * Кривая зависимости частот от времени
 */
struct FrequencyCurve {
    std::vector<FrequencyPoint> points;
    InterpolationType interpolationType = InterpolationType::LINEAR;
    float splineTension = 0.0f;  // 0.0 = Catmull-Rom, 1.0 = почти линейный

    // Веса касательных кардинального сплайна — регулировка overshoot.
    //
    // Вес w[i] ∈ [0;1] домножает касательную в узле i: 1 — номинальная
    // касательная, 0 — нулевая. Подобраны так, чтобы кривая не перескакивала
    // вертикальные границы графика, а касалась их.
    //
    // Вес ОБЩИЙ для обоих каналов (и, как следствие линейности сплайна, для
    // несущей и частоты биений). Каналы от этого НЕ схлопываются: при общем
    // весе beat(t) = right(t) − left(t) остаётся ТОЧНОЙ интерполяцией узлов
    // частоты биений, а carrier(t) = (right+left)/2 — узлов несущей.
    //
    // Веса считает Kotlin (CardinalTension.forPoints) на той же кривой, что
    // рисует график, и присылает вместе с точками: второй реализации алгоритма
    // в нативе НЕТ, поэтому звук и график совпадают по построению. Пустой
    // массив (или размер != числу узлов) — регулировка выключена.
    //
    // Порядок: по времени суток, после сортировки и схлопывания дублей —
    // ровно как точки, к которым применяется (см. buildLookupTableInternal).
    std::vector<float> tensionWeights;

    // Кэш для оптимизации
    float minLowerFreq = 0.0;
    float maxLowerFreq = 0.0;
    float minUpperFreq = 0.0;
    float maxUpperFreq = 0.0;
    float minChannelFreq = 0.0;  // Минимальная частота среди обоих каналов (min(lower, upper) в каждой точке)
    int32_t cachedHash = -1;
    
    // Lookup table для O(1) доступа к частотам.
    // Размер таблицы адаптивный: SECONDS_PER_DAY * 1000 / tableIntervalMs,
    // от FREQUENCY_TABLE_MIN_SIZE до FREQUENCY_TABLE_SIZE записей.
    FreqTablePtr lowerFreqTable;  // Нижняя частота канала (carrier - beat/2)
    FreqTablePtr upperFreqTable;  // Верхняя частота канала (carrier + beat/2)

    /**
     * Рабочий шаг таблицы в миллисекундах (адаптивный, см.
     * FREQUENCY_TABLE_MIN_INTERVAL_MS). Вычисляется в buildLookupTable().
     * Значение по умолчанию = самому мелкому шагу, т.е. историческому
     * разрешению: пока таблица не построена, безопаснее assumes худшее.
     */
    int32_t tableIntervalMs = FREQUENCY_TABLE_MIN_INTERVAL_MS;
    // Кэш нулей трендовой производной beat(t+h) − beat(t−h), beat = upper − lower,
    // h = TREND_HALF_WINDOW_SEC. Строится один раз в updateCache() вместе с
    // lookup-таблицей (профиль сохранён → экстремумы частоты биений известны);
    // планировщик только ищет по нему. Отсортирован по времени суток.
    std::vector<TrendCrossing> trendCrossings;
    bool trendCrossingsValid = false;
    
    /**
     * Таблицы построены и готовы к чтению (обе непустые).
     */
    bool hasFreqTables() const {
        return lowerFreqTable && upperFreqTable &&
               !lowerFreqTable->empty() && !upperFreqTable->empty();
    }

    /**
     * Число записей в таблице (0, если не построена).
     */
    int freqTableSize() const {
        return lowerFreqTable ? static_cast<int>(lowerFreqTable->size()) : 0;
    }

    /**
     * Получить частоты каналов для заданного времени через lookup table
     * Возвращает интерполированные частоты для конкретного момента времени
     * @param timeSeconds секунды с начала суток (0-86399.999...), поддерживает дробные значения
     * @return структура с частотами для обоих каналов
     */
    FrequencyTableResult getChannelFrequenciesAt(float timeSeconds) const;
    
    /**
     * Обновить кэш min/max частот и перестроить lookup table
     * Вызывается при изменении точек графика
     */
    void updateCache();
    
    /**
     * Построить lookup table с адаптивным шагом tableIntervalMs
     */
    void buildLookupTable();

    /**
     * Предвычислить все нули трендовой производной за сутки.
     * Вызывается из updateCache(); результат — trendCrossings.
     */
    void buildTrendCrossings();

private:
    /**
     * Внутренняя реализация построения таблицы (адаптивный шаг)
     */
    void buildLookupTableInternal();

    /**
     * Залить обе таблицы константой заданного размера.
     * Случаи «нет точек» и «одна точка»: кривая вырождена, шаг не важен.
     */
    void fillTablesConstant(float lowerFreq, float upperFreq, int entries);
};

/**
 * Режим автоматической перестановки каналов
 */
/**
 * Точки графика, в которых происходит перестановка каналов в TREND-режиме.
 * TREND-режим переключает (toggle) channelsSwapped при прохождении выбранного
 * типа локального экстремума ЧАСТОТЫ БИЕНИЙ (предвычисленные нули трендовой
 * производной beat). BOTH — текущее поведение (на каждом экстремуме); PEAKS — только
 * на пиках; TROUGHS — только на впадинах.
 */
enum class ChannelSwapTrendPoints : int8_t {
    BOTH = 0,     // На пиках и впадинах (текущее поведение)
    PEAKS = 1,    // Только на пиках
    TROUGHS = 2   // Только на впадинах
};

enum class ChannelSwapMode : int8_t {
    TIMER = 0,   // По таймеру: swap каждые channelSwapIntervalSec секунд
    TREND = 1    // По тенденции графика: рост частоты биений — прямое расположение,
                 // убывание — обратное; интервал не участвует, смены происходят
                 // в локальных экстремумах частоты биений (предвычисленные нули
                 // трендовой производной beat), процедура центрирована на них
};

/**
 * Конфигурация бинаурального ритма
 */
struct BinauralConfig {
    FrequencyCurve curve;
    float volume = 0.7f;
    
    // Настройки перестановки каналов
    bool channelSwapEnabled = false;
    int32_t channelSwapIntervalSec = 300;  // 5 минут (только TIMER; в TREND не участвует)
    ChannelSwapMode channelSwapMode = ChannelSwapMode::TIMER;
    ChannelSwapTrendPoints channelSwapTrendPoints = ChannelSwapTrendPoints::BOTH;
    bool channelSwapFadeEnabled = true;
    int64_t channelSwapFadeDurationMs = 1000;
    int64_t channelSwapPauseDurationMs = 0;  // Пауза между fade-out и fade-in (0 = без паузы)
    
    // Настройки нормализации
    NormalizationType normalizationType = NormalizationType::TEMPORAL;
    float volumeNormalizationStrength = 0.5f;  // 0.0 - 2.0
};

/**
 * Фаза цикла перестановки каналов
 *
 * Цикл: [SOLID N сек] → [FADE_OUT M сек] → [PAUSE K сек] → [FADE_IN M сек] → [SOLID N сек] → ...
 *                                           ↑
 *                               swap каналов здесь (в начале PAUSE)
 */
enum class SwapPhase : int8_t {
    SOLID = 0,      // Сплошной буфер без fade (до swap)
    FADE_OUT = 1,   // Затухание (перед swap)
    PAUSE = 2,      // Пауза между fade-out и fade-in (swap происходит здесь)
    FADE_IN = 3     // Возрастание (после swap)
};

/**
 * Тип сегмента буфера для генерации пакета
 */
enum class BufferType : int8_t {
    SOLID = 0,     // Сплошной буфер без fade
    FADE_OUT = 1,  // Затухание
    PAUSE = 2,     // Пауза (тишина, фазы обновляются)
    FADE_IN = 3    // Возрастание
};

/**
 * Описание сегмента буфера для генерации пакета
 */
struct BufferSegment {
    BufferType type;           // Тип буфера
    int64_t durationMs;        // Длительность в мс
    bool swapAfterSegment;     // Выполнить swap после этого сегмента
    // Для FADE_OUT/FADE_IN — позиция внутри ПОЛНОГО фейда.
    // Позволяет фейду, разрезанному границей пакета, продолжиться с правильной
    // амплитудой вместо рестарта с полной громкости (щелчок + скачок частот).
    int64_t fadeOffsetMs = 0;  // Сколько мс фейда УЖЕ сгенерировано до этого сегмента
    int64_t fadeTotalMs  = 0;  // Полная длительность фейда (из phaseDuration)
};

/**
 * Результат планирования пакета
 */
struct PackagePlan {
    std::vector<BufferSegment> segments;  // Последовательность сегментов
    int64_t totalDurationMs = 0;          // Общая длительность пакета в мс
    bool endsMidCycle = false;            // Пакет заканчивается в середине цикла
};

/**
 * Состояние генератора
 * Используем float для фаз - совместимость с NEON SIMD и достаточная точность
 */
struct GeneratorState {
    float leftPhase = 0.0f;
    float rightPhase = 0.0f;
    bool channelsSwapped = false;
    int64_t lastSwapElapsedMs = 0;
    int64_t totalSamplesGenerated = 0;
    
    // ================================================================
    // НОВАЯ STATE MACHINE ДЛЯ SWAP-ЦИКЛА
    // ================================================================
    
    // Текущая фаза swap-цикла
    SwapPhase swapPhase = SwapPhase::SOLID;
    
    // Оставшееся время в текущей фазе (в мс)
    // Когда достигает 0, переходим к следующей фазе
    int64_t phaseRemainingMs = 0;
    // True only for the first planPackage after reset/init: one-shot parity fix.
    bool justStarted = true;
    // Drift-free TREND curve position (seconds within the day), maintained across
    // planPackage calls. The caller passes a float curveStartSeconds each call
    // (engine m_curveTimeSeconds / test accumulator) that accumulates float
    // rounding drift over long playbacks; that drift makes the planner re-target
    // an already-served crossing and emit a spurious second swap. We keep an
    // internal double here so SOLID targets stay anchored to the true crossings.
    double trendCurvePosSec = 0.0;
    // Last caller-supplied normalized curve position, used to distinguish a real
    // seek (caller jumps far beyond the audio advanced this package) from the
    // gradual float drift of the caller's own accumulator (which we must ignore).
    double lastNormInput = 0.0;
    
    // Позиция внутри цикла для переноса между пакетами
    int64_t cyclePositionMs = 0;

    // ================================================================
    // LEGACY ПОЛЯ (для обратной совместимости)
    // Будут удалены после полного перехода на новую архитектуру
    // ================================================================
    
    // Позиция внутри текущей фазы (в сэмплах)
    int64_t phaseSamplePosition = 0;
    
    // Время начала текущего SOLID периода (для вычисления времени до swap)
    int64_t solidStartMs = 0;
    
    // Legacy fade состояние
    enum class FadeOperation : int8_t {
        NONE = 0,
        CHANNEL_SWAP = 1,
        PRESET_SWITCH = 2,
        PAUSE = 3
    };
    
    FadeOperation currentFadeOperation = FadeOperation::NONE;
    bool isFadingOut = true;  // Для CHANNEL_SWAP: true = fade-out, false = fade-in
    int64_t fadeStartSample = 0;
    int64_t pauseStartSample = 0;
};

} // namespace binaural