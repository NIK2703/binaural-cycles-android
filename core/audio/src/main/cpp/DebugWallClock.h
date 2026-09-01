#pragma once

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <ctime>

namespace binaural {
namespace debug {

/**
 * ВИРТУАЛЬНЫЕ НАСТЕННЫЕ ЧАСЫ (общий сдвиг для всего процесса).
 *
 * Зачем они, когда уже есть VirtualClock (см. VirtualClock.h).
 * VirtualClock ускоряет время (scale) и потому пригоден для «посмотреть, как
 * кривая бежит», но ОН НЕ ЭТО ТО, ЧТО НУЖНО ДЛЯ ПРОВЕРКИ ПАУЗЫ: в виртуальном
 * режиме носитель времени движка — sample-driven ось
 * (base + сгенерированные_секунды * scale), то есть время останавливается
 * вместе с генерацией. На паузе генерация стоит, значит «now» по VirtualClock
 * тоже стоит: сколько ни жди, Δ паузы останется нулевой, и решатель возобновления
 * вообще не будет испытан.
 *
 * Этот же сдвиг — ровно то, чем отличается реальная пауза: настенные часы идут,
 * а фронтир генерации стоит. Поэтому для верификации нужен сдвиг именно
 * НАСТЕННЫХ часов: он один воспроизводит «прошло Δ секунд» мгновенно и без
 * ожидания, оставляя пакет, голову трека и все кадровые оси замороженными.
 *
 * Модель: единый на процесс сдвиг в миллисекундах, прибавляемый и в Kotlin
 * (DebugClock), и здесь. Обе стороны обязаны читать ОДНО и то же смещённое
 * время, иначе «нынешний момент суток» у решателя и якорь свежего потока
 * (`prepare()` → `engine.getCurrentTimeOfDay()`) разойдутся, и проверка
 * начнёт врать сама себе.
 *
 * В release сдвиг всегда 0: единственный путь его установить — JNI-метод,
 * вызываемый из-под `BuildConfig.DEBUG`.
 */
inline std::atomic<int64_t>& wallOffsetMs() {
    static std::atomic<int64_t> offsetMs{0};
    return offsetMs;
}

inline int64_t nowWallMs() {
    const int64_t real = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    return real + wallOffsetMs().load(std::memory_order_relaxed);
}

/** Деление с округлением ВНИЗ — сдвиг часов может быть отрицательным. */
inline int64_t floorDiv(int64_t a, int64_t b) {
    const int64_t q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}

/**
 * Локальное время суток со сдвигом, с дробной долей секунды.
 *
 * Локальная ось берётся через localtime_r от СДВИНУТОГО снимка часов: сырой
 * `%86400000` от эпохи дал бы UTC-сутки.
 */
inline float realTimeOfDaySeconds() {
    const int64_t nowMs = nowWallMs();
    const std::time_t sec = static_cast<std::time_t>(floorDiv(nowMs, 1000));
    std::tm tmInfo{};
#if defined(_WIN32)
    localtime_s(&tmInfo, &sec);
#else
    localtime_r(&sec, &tmInfo);
#endif
    const int64_t whole = static_cast<int64_t>(tmInfo.tm_hour) * 3600 +
                          static_cast<int64_t>(tmInfo.tm_min) * 60 +
                          static_cast<int64_t>(tmInfo.tm_sec);
    const int64_t frac = nowMs - floorDiv(nowMs, 1000) * 1000;
    return static_cast<float>(whole) + static_cast<float>(frac) / 1000.0f;
}

}  // namespace debug
}  // namespace binaural
