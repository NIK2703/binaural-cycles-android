#pragma once

#include <cmath>
#include <cstdint>
#include <cstdlib>

#ifdef __ANDROID__
#include <malloc.h>
#endif

#ifdef USE_NEON
#include <arm_neon.h>
#endif

#ifdef USE_SSE
#include <immintrin.h>
#endif

namespace binaural {

/**
 * Wavetable для быстрой генерации синусоид с линейной интерполяцией
 * Линейная интерполяция обеспечивает высокую точность при малом размере таблицы
 * Эквивалентная точность: размер таблицы × 32
 * 
 * ОПТИМИЗАЦИИ:
 * - NEON SIMD для генерации 4 сэмплов одновременно
 * - Выравнивание таблицы 32 байта для оптимального SIMD доступа (AVX/NEON)
 * - Inline функции для критического пути
 * - Branchless операции для минимизации pipeline stalls
 * - Быстрый путь без интерполяции для целочисленных фаз
 * - FMA-friendly формулировка интерполяции (ARMv8 only)
 * 
 * АРХИТЕКТУРЫ:
 * - ARMv7 (armeabi-v7a): базовый NEON, без vrndmq_f32, без FMA intrinsics
 * - ARMv8 (arm64-v8a): полный NEON Advanced SIMD с FMA и rounding intrinsics
 */
class Wavetable {
public:
    // Размер таблицы кратный 16 для оптимального SIMD выравнивания
    // 2048 = 2^11, обеспечивает хороший баланс между точностью и cache locality
    static constexpr int DEFAULT_TABLE_SIZE = 2048;
    static constexpr float TWO_PI = 2.0f * M_PI;
    static constexpr float ONE_OVER_TWO_PI = 1.0f / TWO_PI;
    
    /**
     * Инициализировать таблицу заданного размера
     */
    static void initialize(int size = DEFAULT_TABLE_SIZE);
    
    /**
     * Освободить память таблицы
     */
    static void release();
    
    /**
     * Получить размер таблицы
     */
    static int getTableSize() { return s_tableSize; }
    
    /**
     * Получить указатель на таблицу (для NEON)
     */
    static const float* getTablePtr() { return s_sineTable; }
    
    /**
     * Получить масштабный коэффициент
     */
    static float getScaleFactor() { return s_scaleFactor; }
    
    /**
     * Быстрый синус с линейной интерполяцией
     * @param phase фаза в радианах [0, 2π)
     * @return значение синуса [-1, 1]
     * 
     * ОПТИМИЗАЦИИ:
     * - Предвычисленный scaleFactor вместо деления
     * - Bitwise mask вместо modulo
     * - FMA (fused multiply-add)-friendly формулировка
     */
    static inline float fastSin(float phase) {
        // Масштабируем фазу в индекс таблицы
        const float phaseScaled = phase * s_scaleFactor;
        const int scaledInt = static_cast<int>(phaseScaled);
        float fraction = phaseScaled - static_cast<float>(scaledInt);
        int index = scaledInt & s_tableSizeMask;
        if (fraction < 0.0f) {
            // Фаза вне [0, 2π): индекс и fraction берём от нижней границы ячейки,
            // иначе интерполяция уходит за пределы пары значений таблицы
            fraction += 1.0f;
            index = (index - 1) & s_tableSizeMask;
        }
        
        // Линейная интерполяция: y = y0 + (y1 - y0) * fraction
        // FMA-friendly форма: y = y0 + fraction * (y1 - y0)
        // Таблица имеет запас в 4 элемента, поэтому index + 1 безопасен без mask
        const float y0 = s_sineTable[index];
        const float y1 = s_sineTable[index + 1];
        return y0 + fraction * (y1 - y0);
    }
    
    /**
     * Быстрый синус без интерполяции (для случаев когда точность не критична)
     * @param phase фаза в радианах [0, 2π)
     * @return значение синуса [-1, 1]
     */
    static inline float fastSinNoInterp(float phase) {
        const float phaseScaled = phase * s_scaleFactor;
        const int index = static_cast<int>(phaseScaled) & s_tableSizeMask;
        return s_sineTable[index];
    }

#ifdef USE_NEON
    /**
     * NEON-оптимизированная генерация 4 синусов одновременно
     * Полностью векторизованная версия
     * 
     * Единая реализация для ARMv7/ARMv8 (без vrndmq_f32); FMA используется на ARMv8
     *
     * @param phasesScaled масштабированные фазы (phase * scaleFactor)
     * @return 4 значения синуса в NEON регистре
     */
    static inline float32x4_t fastSinNeon(float32x4_t phasesScaled) {
        // Индекс и дробная часть от одной целой части (усечение);
        // для отрицательных фаз сдвигаемся к нижней границе ячейки (branchless)
        const int32x4_t ints = vcvtq_s32_f32(phasesScaled);
        float32x4_t fractions = vsubq_f32(phasesScaled, vcvtq_f32_s32(ints));
        const uint32x4_t negMask = vcltq_f32(fractions, vdupq_n_f32(0.0f));
        const int32x4_t adj = vreinterpretq_s32_u32(vandq_u32(negMask, vdupq_n_u32(1)));
        const int32x4_t indices = vandq_s32(vsubq_s32(ints, adj), vdupq_n_s32(s_tableSizeMask));
        fractions = vaddq_f32(fractions, vcvtq_f32_s32(adj));
        
        // Извлекаем индексы для загрузки
        int idx[4] __attribute__((aligned(16)));
        vst1q_s32(idx, indices);
        
        // Загружаем y0 и y1 для каждого индекса
        float y0[4] __attribute__((aligned(16)));
        float y1[4] __attribute__((aligned(16)));
        
        for (int i = 0; i < 4; ++i) {
            y0[i] = s_sineTable[idx[i]];
            y1[i] = s_sineTable[idx[i] + 1]; // +1 безопасен благодаря запасу в таблице
        }
        
        float32x4_t vy0 = vld1q_f32(y0);
        float32x4_t vy1 = vld1q_f32(y1);
        
        // Интерполяция: result = y0 + fraction * (y1 - y0)
#ifdef USE_NEON_ARMV8
        // ARMv8: используем FMLA (fused multiply-add)
        float32x4_t diff = vsubq_f32(vy1, vy0);
        return vfmaq_f32(vy0, fractions, diff);
#else
        // ARMv7: обычные multiply-add
        float32x4_t oneMinusFrac = vsubq_f32(vdupq_n_f32(1.0f), fractions);
        return vaddq_f32(vmulq_f32(vy0, oneMinusFrac), vmulq_f32(vy1, fractions));
#endif
    }
    
    /**
     * NEON-оптимизированная генерация 8 сэмплов (2 регистра)
     * Максимальная пропускная способность для основного цикла
     */
    static inline void fastSinNeon8(
        float32x4_t phasesScaled1,
        float32x4_t phasesScaled2,
        float* results
    ) {
        float32x4_t res1 = fastSinNeon(phasesScaled1);
        float32x4_t res2 = fastSinNeon(phasesScaled2);
        
        vst1q_f32(results, res1);
        vst1q_f32(results + 4, res2);
    }
#endif

#ifdef USE_SSE
    /**
     * SSE-оптимизированная генерация 4 синусов одновременно
     * Использует SSSE3 для векторизованной интерполяции
     * 
     * @param phasesScaled масштабированные фазы (phase * scaleFactor)
     * @return 4 значения синуса в SSE регистре
     */
    static inline __m128 fastSinSse(__m128 phasesScaled) {
        // Индекс и дробная часть от одной целой части (усечение);
        // для отрицательных фаз сдвигаемся к нижней границе ячейки (branchless)
        const __m128i ints = _mm_cvttps_epi32(phasesScaled);
        __m128 fractions = _mm_sub_ps(phasesScaled, _mm_cvtepi32_ps(ints));
        const __m128i adj = _mm_and_si128(
            _mm_castps_si128(_mm_cmplt_ps(fractions, _mm_setzero_ps())),
            _mm_set1_epi32(1));
        const __m128i indices = _mm_and_si128(
            _mm_sub_epi32(ints, adj),
            _mm_set1_epi32(s_tableSizeMask));
        fractions = _mm_add_ps(fractions, _mm_cvtepi32_ps(adj));
        
        // Извлекаем индексы для загрузки
        int idx[4] __attribute__((aligned(16)));
        _mm_store_si128((__m128i*)idx, indices);
        
        // Загружаем y0 и y1 для каждого индекса
        float y0[4] __attribute__((aligned(16)));
        float y1[4] __attribute__((aligned(16)));
        
        for (int i = 0; i < 4; ++i) {
            y0[i] = s_sineTable[idx[i]];
            y1[i] = s_sineTable[idx[i] + 1];
        }
        
        __m128 vy0 = _mm_load_ps(y0);
        __m128 vy1 = _mm_load_ps(y1);
        
        // Интерполяция: result = y0 + fraction * (y1 - y0)
        __m128 diff = _mm_sub_ps(vy1, vy0);
        return _mm_add_ps(vy0, _mm_mul_ps(fractions, diff));
    }

    /**
     * SSE-вариант БЕЗ ветки на отрицательную дробь.
     *
     * Вызывающая сторона гарантирует phase >= 0 — иначе индекс и дробь берутся
     * не от той ячейки (усечение к нулю, а не вниз) и на 4 сэмплах появляется
     * ошибка ~0.6 шага таблицы. В генераторе это выполняется по построению:
     * фаза обматывается в [0, 2π), частота тона >= 20 Гц, а прирост за 4 сэмпла
     * (< 4π при SR 8000 и тоне 2000 Гц) перекрывается двумя условными
     * вычитаниями. Один max_ps страхует младшую грань на крутом спаде частоты
     * внутри короткого сегмента (3·omegaStep > 3·omega).
     *
     * Экономия против fastSinSse — 7 векторных операций на 4 сэмпла.
     */
    static inline __m128 fastSinSseNonNeg(__m128 phasesScaled) {
        phasesScaled = _mm_max_ps(phasesScaled, _mm_setzero_ps());
        const __m128i ints = _mm_cvttps_epi32(phasesScaled);
        const __m128 fractions = _mm_sub_ps(phasesScaled, _mm_cvtepi32_ps(ints));
        const __m128i indices = _mm_and_si128(ints, _mm_set1_epi32(s_tableSizeMask));

        int idx[4] __attribute__((aligned(16)));
        _mm_store_si128((__m128i*)idx, indices);

        float y0[4] __attribute__((aligned(16)));
        float y1[4] __attribute__((aligned(16)));
        for (int i = 0; i < 4; ++i) {
            y0[i] = s_sineTable[idx[i]];
            y1[i] = s_sineTable[idx[i] + 1];
        }

        const __m128 vy0 = _mm_load_ps(y0);
        const __m128 vy1 = _mm_load_ps(y1);
        const __m128 diff = _mm_sub_ps(vy1, vy0);
        return _mm_add_ps(vy0, _mm_mul_ps(fractions, diff));
    }
#endif

private:
    // Выровненный указатель на таблицу (32 байта для AVX/NEON)
    static float* s_sineTable;
    static int s_tableSize;
    static int s_tableSizeMask;
    static float s_scaleFactor;
    
    // Размер выделенной памяти (включая запас для интерполяции)
    static size_t s_allocatedSize;
};

} // namespace binaural