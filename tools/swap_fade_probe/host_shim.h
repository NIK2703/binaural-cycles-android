#pragma once

// Заглушки для host-сборки зонда (MinGW, без Android/POSIX).
//
// posix_memalign в MinGW UCRT отсутствует. Реализация «через malloc + ручное
// выравнивание» здесь НЕ годится: Wavetable::release() освобождает таблицу
// обычным std::free(ptr), а со смещённым указателем это UB. Поэтому честно
// возвращаем ошибку — Wavetable уходит на свой fallback со std::malloc.
// Выравнивание 32 байта при этом не гарантировано, но это безопасно: SIMD-путь
// генератора делает только _mm_store_ps в стековые массивы, выровненных
// загрузок из таблицы нет.
#if !defined(__ANDROID__)
#include <cstdlib>
inline int posix_memalign(void** memptr, size_t alignment, size_t size) {
    (void)memptr; (void)alignment; (void)size;
    return 12; // ENOMEM — вызывающий обязан уйти на fallback
}
#endif
