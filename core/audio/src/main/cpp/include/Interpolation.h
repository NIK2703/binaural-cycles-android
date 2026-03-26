#pragma once

#include "Config.h"
#include <cmath>
#include <algorithm>

namespace binaural {

/**
 * Алгоритмы интерполяции для расчёта частот между точками
 * 
 * ТИПЫ ИНТЕРПОЛЯЦИИ:
 * - LINEAR: простая линейная интерполяция, без overshoot
 * - CARDINAL: кубический сплайн с параметром tension (0=Catmull-Rom плавный, 1=почти линейный)
 * - MONOTONE: сохраняет форму данных, гарантированно без overshoot
 * - STEP: ступенчатая интерполяция, значение остаётся постоянным до следующей точки
 */
namespace Interpolation {

/**
 * Линейная интерполяция
 */
inline float linear(float y1, float y2, float t) {
    return y1 + t * (y2 - y1);
}

/**
 * Кардинальный сплайн (с параметром tension)
 * 
 * ОСОБЕННОСТИ:
 * - tension = 0.0 -> Catmull-Rom (плавная кривая, возможен overshoot)
 * - tension = 1.0 -> почти линейная интерполяция
 * - tension > 0 -> более "тугая" кривая, меньше overshoot
 * - Проходит через все контрольные точки
 */
inline float cardinal(float p0, float p1, float p2, float p3, float t, float tension = 0.0) {
    const float t2 = t * t;
    const float t3 = t2 * t;
    
    // Вычисляем касательные с учётом натяжения
    const float s = (1.0 - tension) / 2.0;
    const float m1 = (p2 - p0) * s;
    const float m2 = (p3 - p1) * s;
    
    const float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    const float h10 = t3 - 2.0 * t2 + t;
    const float h01 = -2.0 * t3 + 3.0 * t2;
    const float h11 = t3 - t2;
    
    return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
}

/**
 * Вычисляет наклон для монотонного сплайна
 * Использует алгоритм Fritsch-Carlson для сохранения монотонности
 */
inline float computeMonotoneSlope(float d1, float d2) {
    // Если наклоны имеют разные знаки или один из них нулевой - касательная = 0
    if (d1 * d2 <= 0) return 0.0;
    
    // Гармоническое среднее двух наклонов - это ключ к монотонности
    return 2.0 * d1 * d2 / (d1 + d2);
}

/**
 * Монотонный кубический сплайн (PCHIP - Piecewise Cubic Hermite Interpolating Polynomial)
 * 
 * ОСОБЕННОСТИ:
 * - Гарантирует ОТСУТСТВИЕ OVERSHOOT - значения всегда в пределах [min(p1,p2), max(p1,p2)]
 * - Сохраняет монотонность данных - если p1 < p2, то кривая монотонно возрастает
 * - Проходит через все контрольные точки
 */
inline float monotone(float p0, float p1, float p2, float p3, float t) {
    // Вычисляем наклоны (разности) между соседними точками
    const float d0 = p1 - p0;  // наклон слева от p1
    const float d1 = p2 - p1;  // наклон между p1 и p2 (основной интервал)
    const float d2 = p3 - p2;  // наклон справа от p2
    
    // Вычисляем касательные в точках p1 и p2 используя гармоническое среднее
    const float m1 = computeMonotoneSlope(d0, d1);
    const float m2 = computeMonotoneSlope(d1, d2);
    
    // Кубическая интерполяция Эрмита
    const float t2 = t * t;
    const float t3 = t2 * t;
    
    const float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    const float h10 = t3 - 2.0 * t2 + t;
    const float h01 = -2.0 * t3 + 3.0 * t2;
    const float h11 = t3 - t2;
    
    const float result = h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
    
    // Гарантируем отсутствие overshoot
    const float minVal = std::min(p1, p2);
    const float maxVal = std::max(p1, p2);
    return std::clamp(result, minVal, maxVal);
}

/**
 * Ступенчатая интерполяция
 * Значение остаётся постоянным (равным левой точке) до следующей точки
 */
inline float step(float p1) {
    return p1;
}

/**
 * Выполняет интерполяцию указанным методом
 * @param type тип интерполяции
 * @param p0 точка до левой границы
 * @param p1 левая граница интервала
 * @param p2 правая граница интервала
 * @param p3 точка после правой границы
 * @param t нормализованная позиция в интервале [0, 1]
 * @param tension параметр натяжения для CARDINAL (0.0=Catmull-Rom, 1.0=почти линейный)
 * @return интерполированное значение
 */
inline float interpolate(
    InterpolationType type,
    float p0, float p1, float p2, float p3,
    float t,
    float tension = 0.0f
) {
    float result;
    switch (type) {
        case InterpolationType::LINEAR:
            result = linear(p1, p2, t);
            break;
        case InterpolationType::CARDINAL:
            result = cardinal(p0, p1, p2, p3, t, tension);
            break;
        case InterpolationType::MONOTONE:
            result = monotone(p0, p1, p2, p3, t);
            break;
        case InterpolationType::STEP:
            result = step(p1);
            break;
        default:
            result = linear(p1, p2, t);
    }
    return std::max(0.0f, result);
}

} // namespace Interpolation

/**
 * Реализация методов FrequencyCurve
 */

/**
 * Внутренняя реализация построения таблицы
 * Использует фиксированный шаг FREQUENCY_TABLE_INTERVAL_MS (100 мс)
 *
 * ОПТИМИЗАЦИЯ: Использует итеративный поиск O(n) вместо бинарного O(n log n)
 * т.к. время монотонно возрастает при построении таблицы.
 */
inline void FrequencyCurve::buildLookupTableInternal() {
    if (points.size() < 2) {
        // Минимальный размер таблицы
        lowerFreqTable.assign(1, 200.0);
        upperFreqTable.assign(1, 210.0);
        return;
    }
    
    // Фиксированный размер таблицы
    const int tableSize = FREQUENCY_TABLE_SIZE;
    
    // Сортированные точки (предполагаем, что уже отсортированы по времени)
    const auto& sortedPoints = points;
    const int numPoints = static_cast<int>(sortedPoints.size());
    
    // Выделяем память
    lowerFreqTable.resize(tableSize);
    upperFreqTable.resize(tableSize);
    
    // Селекторы для частот каналов
    auto getLowerFreq = [](const FrequencyPoint& p) {
        return p.carrierFrequency - p.beatFrequency / 2.0;
    };
    auto getUpperFreq = [](const FrequencyPoint& p) {
        return p.carrierFrequency + p.beatFrequency / 2.0;
    };
    
    // Предвычисляем частоты для каждого интервала
    // ИСПОЛЬЗУЕМ ИТЕРАТИВНЫЙ ПОИСК - для отсортированных данных это O(n) вместо O(n log n)
    // т.к. timeSeconds монотонно возрастает
    int leftIndex = 0;
    
    for (int tableIndex = 0; tableIndex < tableSize; ++tableIndex) {
        // Конвертируем индекс таблицы в секунды суток
        // FREQUENCY_TABLE_INTERVAL_MS = 100, поэтому шаг = 0.1 секунды
        // tableIndex * 100 / 1000 = tableIndex / 10 (секунды)
        const int timeSeconds = tableIndex * FREQUENCY_TABLE_INTERVAL_MS / 1000;
        
        // Итеративный поиск - двигаемся вперёд пока не найдём нужный интервал
        // Это O(1) амортизированное время, т.к. leftIndex только увеличивается
        while (leftIndex < numPoints - 2 && sortedPoints[leftIndex + 1].timeSeconds <= timeSeconds) {
            ++leftIndex;
        }
        
        const int rightIndex = (leftIndex + 1) % numPoints;
        
        const auto& leftPoint = sortedPoints[leftIndex];
        const auto& rightPoint = sortedPoints[rightIndex];
        
        // Вычисляем нормализованную позицию t в интервале [0, 1]
        int t1 = leftPoint.timeSeconds;
        int t2 = rightPoint.timeSeconds;
        
        // Обработка перехода через полночь
        const bool isWrapping = (leftIndex == numPoints - 1);
        if (isWrapping) {
            t2 += SECONDS_PER_DAY;
        }
        
        int t = timeSeconds;
        if (isWrapping && t < t1) {
            t += SECONDS_PER_DAY;
        }
        
        float ratio = 0.0;
        if (t2 != t1) {
            ratio = static_cast<float>(t - t1) / (t2 - t1);
        }
        
        // Получаем 4 точки для сплайна
        const int prevIndex = (leftIndex - 1 + numPoints) % numPoints;
        const int nextNextIndex = (rightIndex + 1) % numPoints;
        
        // Интерполируем нижнюю частоту
        float lowerP0 = getLowerFreq(sortedPoints[prevIndex]);
        float lowerP1 = getLowerFreq(leftPoint);
        float lowerP2 = getLowerFreq(rightPoint);
        float lowerP3 = getLowerFreq(sortedPoints[nextNextIndex]);
        lowerFreqTable[tableIndex] = std::max(0.0f, Interpolation::interpolate(
            interpolationType, lowerP0, lowerP1, lowerP2, lowerP3, ratio, splineTension
        ));
        
        // Интерполируем верхнюю частоту
        float upperP0 = getUpperFreq(sortedPoints[prevIndex]);
        float upperP1 = getUpperFreq(leftPoint);
        float upperP2 = getUpperFreq(rightPoint);
        float upperP3 = getUpperFreq(sortedPoints[nextNextIndex]);
        upperFreqTable[tableIndex] = std::max(0.0f, Interpolation::interpolate(
            interpolationType, upperP0, upperP1, upperP2, upperP3, ratio, splineTension
        ));
    }
}

/**
 * Построить lookup table с фиксированным шагом FREQUENCY_TABLE_INTERVAL_MS
 */
inline void FrequencyCurve::buildLookupTable() {
    buildLookupTableInternal();
}

/**
 * Обновить кэш min/max частот и перестроить lookup table
 *
 * ВАЖНО: min/max вычисляются по lookup-таблице, а не по контрольным точкам,
 * т.к. при интерполяции CARDINAL возможен overshoot и реальные значения
 * могут отличаться от значений в контрольных точках.
 */
inline void FrequencyCurve::updateCache() {
    if (points.empty()) return;
    
    // Сначала строим lookup table
    buildLookupTable();
    
    // Вычисляем min/max по lookup-таблице (учитывает интерполяцию)
    minLowerFreq = std::numeric_limits<float>::max();
    maxLowerFreq = std::numeric_limits<float>::lowest();
    minUpperFreq = std::numeric_limits<float>::max();
    maxUpperFreq = std::numeric_limits<float>::lowest();
    
    for (size_t i = 0; i < lowerFreqTable.size(); ++i) {
        minLowerFreq = std::min(minLowerFreq, lowerFreqTable[i]);
        maxLowerFreq = std::max(maxLowerFreq, lowerFreqTable[i]);
    }
    
    for (size_t i = 0; i < upperFreqTable.size(); ++i) {
        minUpperFreq = std::min(minUpperFreq, upperFreqTable[i]);
        maxUpperFreq = std::max(maxUpperFreq, upperFreqTable[i]);
    }
}

/**
 * Получить частоты каналов для заданного времени через lookup table
 * Возвращает интерполированные частоты для конкретного момента времени
 *
 * СЛОЖНОСТЬ: O(1) - прямой доступ по индексу + линейная интерполяция
 *
 * ИНТЕРПОЛЯЦИЯ ВНУТРИ ТАБЛИЦЫ:
 * Использует линейную интерполяцию между соседними значениями таблицы,
 * что обеспечивает плавные переходы при любом разрешении таблицы.
 *
 * ДРОБНОЕ ВРЕМЯ:
 * Поддерживает дробные секунды для корректной интерполяции внутри буфера.
 * Например: 0.186 сек позволяет вычислить частоты в середине буфера.
 *
 * ОПТИМИЗАЦИЯ:
 * Использует __builtin_prefetch для предзагрузки следующего значения в кэш.
 */
inline FrequencyTableResult FrequencyCurve::getChannelFrequenciesAt(float timeSeconds) const {
    FrequencyTableResult result = {200.0, 210.0};
    
    // Если lookup table не построена, возвращаем значения по умолчанию
    if (lowerFreqTable.empty() || upperFreqTable.empty()) {
        return result;
    }
    
    // Нормализуем время в пределах суток
    // Используем fmod для корректной работы с отрицательными дробными значениями
    timeSeconds = std::fmod(timeSeconds, static_cast<float>(SECONDS_PER_DAY));
    if (timeSeconds < 0.0f) {
        timeSeconds += static_cast<float>(SECONDS_PER_DAY);
    }
    
    // Фиксированный шаг таблицы 0.1 сек (100 мс)
    constexpr float intervalSeconds = static_cast<float>(FREQUENCY_TABLE_INTERVAL_MS) / 1000.0f;
    const int tableSize = static_cast<int>(lowerFreqTable.size());
    
    // Вычисляем непрерывную позицию в таблице (дробная)
    const float continuousIndex = timeSeconds / intervalSeconds;
    
    // Индекс текущей точки
    const int currentIndex = static_cast<int>(continuousIndex);
    
    // Позиция внутри текущего интервала [0, 1)
    const float t = continuousIndex - static_cast<float>(currentIndex);
    
    // Индекс следующей точки (с циклическим переходом через полночь)
    const int nextIndex = (currentIndex + 1) % tableSize;
    
    // Безопасные индексы (clamping)
    const int safeCurrentIndex = std::min(currentIndex, tableSize - 1);
    const int safeNextIndex = std::min(nextIndex, tableSize - 1);
    
    // ОПТИМИЗАЦИЯ: Prefetch следующего значения для лучшего cache hit
    // Предзагружаем значение, которое потребуется в следующем вызове
    #ifdef __GNUC__
    if (safeNextIndex + 1 < tableSize) {
        __builtin_prefetch(&lowerFreqTable[safeNextIndex + 1], 0, 0);
        __builtin_prefetch(&upperFreqTable[safeNextIndex + 1], 0, 0);
    }
    #endif
    
    // Линейная интерполяция между соседними значениями таблицы
    result.lowerFreq = Interpolation::linear(
        lowerFreqTable[safeCurrentIndex],
        lowerFreqTable[safeNextIndex],
        t
    );
    result.upperFreq = Interpolation::linear(
        upperFreqTable[safeCurrentIndex],
        upperFreqTable[safeNextIndex],
        t
    );
    
    return result;
}

} // namespace binaural