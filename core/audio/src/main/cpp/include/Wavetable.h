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
        const int index = static_cast<int>(phaseScaled) & s_tableSizeMask;
        const float fraction = phaseScaled - static_cast<float>(index);
        
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
     * ARMv8: использует vrndmq_f32 и vfmaq_f32 (FMA)
     * ARMv7: использует совместимую реализацию без расширенных intrinsics
     * 
     * @param phasesScaled масштабированные фазы (phase * scaleFactor)
     * @return 4 значения синуса в NEON регистре
     */
    static inline float32x4_t fastSinNeon(float32x4_t phasesScaled) {
        // Получаем целые части как индексы
        int32x4_t indices = vcvtq_s32_f32(phasesScaled);
        
        // Применяем маску для wraparound (branchless)
        indices = vandq_s32(indices, vdupq_n_s32(s_tableSizeMask));
        
        // Дробные части: frac = scaled - floor(scaled)
#ifdef USE_NEON_ARMV8
        // ARMv8: используем vrndmq_f32 для floor
        float32x4_t floored = vrndmq_f32(phasesScaled);
#else
        // ARMv7: floor через truncate (для положительных чисел truncate = floor)
        // phasesScaled всегда положительный, так что vcvt работает как floor
        float32x4_t floored = vcvtq_f32_s32(vcvtq_s32_f32(phasesScaled));
#endif
        float32x4_t fractions = vsubq_f32(phasesScaled, floored);
        
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