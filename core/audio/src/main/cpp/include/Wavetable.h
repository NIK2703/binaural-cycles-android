#pragma once

#include <cmath>
#include <vector>
#include <cstdint>

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
 * - Выравнивание таблицы для эффективного доступа
 * - Inline функции для критического пути
 */
class Wavetable {
public:
    // Размер таблицы кратный 4 для NEON выравнивания
    static constexpr int DEFAULT_TABLE_SIZE = 2048;
    static constexpr double TWO_PI = 2.0 * M_PI;
    
    /**
     * Инициализировать таблицу заданного размера
     */
    static void initialize(int size = DEFAULT_TABLE_SIZE);
    
    /**
     * Получить размер таблицы
     */
    static int getTableSize() { return s_tableSize; }
    
    /**
     * Получить указатель на таблицу (для NEON)
     */
    static const float* getTablePtr() { return s_sineTable.data(); }
    
    /**
     * Получить масштабный коэффициент
     */
    static double getScaleFactor() { return s_scaleFactor; }
    
    /**
     * Быстрый синус с линейной интерполяцией
     * @param phase фаза в радианах [0, 2π)
     * @return значение синуса [-1, 1]
     */
    static inline float fastSin(double phase) {
        // Масштабируем фазу в индекс таблицы
        const double phaseScaled = phase * s_scaleFactor;
        const int index = static_cast<int>(phaseScaled) & s_tableSizeMask;
        const float fraction = static_cast<float>(phaseScaled - static_cast<int>(phaseScaled));
        const int indexNext = (index + 1) & s_tableSizeMask;
        
        // Линейная интерполяция
        return s_sineTable[index] * (1.0f - fraction) + s_sineTable[indexNext] * fraction;
    }
    
    /**
     * Быстрый синус с float фазой (быстрее для SIMD)
     */
    static inline float fastSinFloat(float phaseScaled) {
        const int index = static_cast<int>(phaseScaled) & s_tableSizeMask;
        const float fraction = phaseScaled - static_cast<float>(index);
        const int indexNext = (index + 1) & s_tableSizeMask;
        return s_sineTable[index] * (1.0f - fraction) + s_sineTable[indexNext] * fraction;
    }

#ifdef USE_NEON
    /**
     * NEON-оптимизированная генерация 4 синусов одновременно
     * @param phasesScaled масштабированные фазы (phase * scaleFactor)
     * @param results выходной массив из 4 float значений
     */
    static inline void fastSinNeon4(float32x4_t phasesScaled, float* results) {
        // Получаем целые части как индексы
        int32x4_t indices = vcvtq_s32_f32(phasesScaled);
        
        // Применяем маску для wraparound
        indices = vandq_s32(indices, vdupq_n_s32(s_tableSizeMask));
        
        // Дробные части
        float32x4_t fractions = vsubq_f32(phasesScaled, vcvtq_f32_s32(indices));
        
        // Извлекаем индексы для скалярной загрузки
        int idx[4];
        vst1q_s32(idx, indices);
        
        // Загружаем значения из таблицы и интерполируем
        // Это быстрее чем gather на ARM
        for (int i = 0; i < 4; ++i) {
            int index = idx[i];
            int indexNext = (index + 1) & s_tableSizeMask;
            float frac = results[i] = ((float*)&fractions)[i]; // hack to extract fraction
            float y0 = s_sineTable[index];
            float y1 = s_sineTable[indexNext];
            results[i] = y0 + (y1 - y0) * frac;
        }
    }
    
    /**
     * NEON-оптимизированная генерация 4 синусов (альтернативная версия)
     * Возвращает значения напрямую в регистре NEON
     */
    static inline float32x4_t fastSinNeon(float32x4_t phasesScaled) {
        // Получаем целые части как индексы
        int32x4_t indices = vcvtq_s32_f32(phasesScaled);
        indices = vandq_s32(indices, vdupq_n_s32(s_tableSizeMask));
        
        // Дробные части: frac = scaled - floor(scaled)
        float32x4_t fractions = vsubq_f32(phasesScaled, vcvtq_f32_s32(vcvtq_s32_f32(phasesScaled)));
        
        // Дробные части как 1 - frac для интерполяции
        float32x4_t oneMinusFrac = vsubq_f32(vdupq_n_f32(1.0f), fractions);
        
        // Скалярная загрузка и векторная интерполяция
        int idx[4];
        vst1q_s32(idx, indices);
        
        // Загружаем y0 и y1 для каждого индекса
        float y0[4], y1[4];
        for (int i = 0; i < 4; ++i) {
            y0[i] = s_sineTable[idx[i]];
            y1[i] = s_sineTable[(idx[i] + 1) & s_tableSizeMask];
        }
        
        float32x4_t vy0 = vld1q_f32(y0);
        float32x4_t vy1 = vld1q_f32(y1);
        
        // Интерполяция: result = y0 * (1 - frac) + y1 * frac
        float32x4_t result = vaddq_f32(
            vmulq_f32(vy0, oneMinusFrac),
            vmulq_f32(vy1, fractions)
        );
        
        return result;
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
    static std::vector<float> s_sineTable;
    static int s_tableSize;
    static int s_tableSizeMask;
    static double s_scaleFactor;
    static float s_scaleFactorFloat;
};

} // namespace binaural
