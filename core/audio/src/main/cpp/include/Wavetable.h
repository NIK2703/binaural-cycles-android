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
 */
class Wavetable {
public:
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
    
#ifdef USE_NEON
    /**
     * NEON-оптимизированная генерация 4 синусов одновременно
     * @param phases массив из 4 фаз
     * @return массив из 4 значений синуса
     */
    static inline float32x4_t fastSinNeon(float32x4_t phases) {
        // Масштабируем фазы
        float32x4_t scaled = vmulq_f32(phases, vdupq_n_f32(static_cast<float>(s_scaleFactor)));
        
        // Получаем целые части как индексы
        int32x4_t indices = vcvtq_s32_f32(scaled);
        indices = vandq_s32(indices, vdupq_n_s32(s_tableSizeMask));
        
        // Дробные части
        float32x4_t fractions = vsubq_f32(scaled, vcvtq_f32_s32(indices));
        
        // Загружаем значения из таблицы
        // Для NEON нужно использовать gather, но на ARM его нет, 
        // поэтому используем скалярный подход для загрузки
        float result[4];
        int idx[4];
        float frac[4];
        
        vst1q_s32(idx, indices);
        vst1q_f32(frac, fractions);
        
        for (int i = 0; i < 4; ++i) {
            int index = idx[i] & s_tableSizeMask;
            int indexNext = (index + 1) & s_tableSizeMask;
            result[i] = s_sineTable[index] * (1.0f - frac[i]) + s_sineTable[indexNext] * frac[i];
        }
        
        return vld1q_f32(result);
    }
#endif

private:
    static std::vector<float> s_sineTable;
    static int s_tableSize;
    static int s_tableSizeMask;
    static double s_scaleFactor;
};

} // namespace binaural