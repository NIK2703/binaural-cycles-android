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
inline double linear(double y1, double y2, double t) {
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
inline double cardinal(double p0, double p1, double p2, double p3, double t, double tension = 0.0) {
    const double t2 = t * t;
    const double t3 = t2 * t;
    
    // Вычисляем касательные с учётом натяжения
    const double s = (1.0 - tension) / 2.0;
    const double m1 = (p2 - p0) * s;
    const double m2 = (p3 - p1) * s;
    
    const double h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    const double h10 = t3 - 2.0 * t2 + t;
    const double h01 = -2.0 * t3 + 3.0 * t2;
    const double h11 = t3 - t2;
    
    return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
}

/**
 * Вычисляет наклон для монотонного сплайна
 * Использует алгоритм Fritsch-Carlson для сохранения монотонности
 */
inline double computeMonotoneSlope(double d1, double d2) {
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
inline double monotone(double p0, double p1, double p2, double p3, double t) {
    // Вычисляем наклоны (разности) между соседними точками
    const double d0 = p1 - p0;  // наклон слева от p1
    const double d1 = p2 - p1;  // наклон между p1 и p2 (основной интервал)
    const double d2 = p3 - p2;  // наклон справа от p2
    
    // Вычисляем касательные в точках p1 и p2 используя гармоническое среднее
    const double m1 = computeMonotoneSlope(d0, d1);
    const double m2 = computeMonotoneSlope(d1, d2);
    
    // Кубическая интерполяция Эрмита
    const double t2 = t * t;
    const double t3 = t2 * t;
    
    const double h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    const double h10 = t3 - 2.0 * t2 + t;
    const double h01 = -2.0 * t3 + 3.0 * t2;
    const double h11 = t3 - t2;
    
    const double result = h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
    
    // Гарантируем отсутствие overshoot
    const double minVal = std::min(p1, p2);
    const double maxVal = std::max(p1, p2);
    return std::clamp(result, minVal, maxVal);
}

/**
 * Ступенчатая интерполяция
 * Значение остаётся постоянным (равным левой точке) до следующей точки
 */
inline double step(double p1) {
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
inline double interpolate(
    InterpolationType type,
    double p0, double p1, double p2, double p3,
    double t,
    float tension = 0.0f
) {
    double result;
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
    return std::max(0.0, result);
}

} // namespace Interpolation

/**
 * Реализация методов FrequencyCurve
 */

/**
 * Внутренняя реализация построения таблицы
 * @param intervalSeconds интервал между значениями таблицы в секундах
 */
inline void FrequencyCurve::buildLookupTableInternal(int intervalSeconds) {
    if (points.size() < 2) {
        // Минимальный размер таблицы
        lowerFreqTable.assign(1, 200.0);
        upperFreqTable.assign(1, 210.0);
        return;
    }
    
    // Размер таблицы = 86400 / intervalSeconds
    // При intervalSeconds = 1 → 86400 значений
    // При intervalSeconds = 10 → 8640 значений
    // При intervalSeconds = 60 → 1440 значений
    const int tableSize = SECONDS_PER_DAY / intervalSeconds;
    
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
    for (int tableIndex = 0; tableIndex < tableSize; ++tableIndex) {
        // Конвертируем индекс таблицы в секунды суток
        const int timeSeconds = tableIndex * intervalSeconds;
        
        // Находим интервал (линейный поиск - выполняется только при построении таблицы)
        int leftIndex = -1;
        for (int j = 0; j < numPoints - 1; ++j) {
            if (sortedPoints[j].timeSeconds <= timeSeconds && timeSeconds < sortedPoints[j + 1].timeSeconds) {
                leftIndex = j;
                break;
            }
        }
        
        // Если не нашли - переход через полночь
        if (leftIndex < 0) {
            leftIndex = numPoints - 1;
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
        
        double ratio = 0.0;
        if (t2 != t1) {
            ratio = static_cast<double>(t - t1) / (t2 - t1);
        }
        
        // Получаем 4 точки для сплайна
        const int prevIndex = (leftIndex - 1 + numPoints) % numPoints;
        const int nextNextIndex = (rightIndex + 1) % numPoints;
        
        // Интерполируем нижнюю частоту
        double lowerP0 = getLowerFreq(sortedPoints[prevIndex]);
        double lowerP1 = getLowerFreq(leftPoint);
        double lowerP2 = getLowerFreq(rightPoint);
        double lowerP3 = getLowerFreq(sortedPoints[nextNextIndex]);
        lowerFreqTable[tableIndex] = std::max(0.0, Interpolation::interpolate(
            interpolationType, lowerP0, lowerP1, lowerP2, lowerP3, ratio, splineTension
        ));
        
        // Интерполируем верхнюю частоту
        double upperP0 = getUpperFreq(sortedPoints[prevIndex]);
        double upperP1 = getUpperFreq(leftPoint);
        double upperP2 = getUpperFreq(rightPoint);
        double upperP3 = getUpperFreq(sortedPoints[nextNextIndex]);
        upperFreqTable[tableIndex] = std::max(0.0, Interpolation::interpolate(
            interpolationType, upperP0, upperP1, upperP2, upperP3, ratio, splineTension
        ));
    }
}

/**
 * Построить lookup table для заданного интервала обновления частот
 * @param intervalMs интервал обновления частот в миллисекундах
 */
inline void FrequencyCurve::buildLookupTable(int intervalMs) {
    // Сохраняем интервал для которого построена таблица
    tableIntervalMs = intervalMs;
    
    // Конвертируем в секунды (минимум 1 секунда)
    const int intervalSeconds = std::max(1, intervalMs / 1000);
    
    buildLookupTableInternal(intervalSeconds);
}

/**
 * Обновить кэш min/max частот
 */
inline void FrequencyCurve::updateCache() {
    if (points.empty()) return;
    
    minLowerFreq = std::numeric_limits<double>::max();
    maxLowerFreq = std::numeric_limits<double>::lowest();
    minUpperFreq = std::numeric_limits<double>::max();
    maxUpperFreq = std::numeric_limits<double>::lowest();
    
    for (const auto& point : points) {
        double lowerFreq = point.carrierFrequency - point.beatFrequency / 2.0;
        double upperFreq = point.carrierFrequency + point.beatFrequency / 2.0;
        
        minLowerFreq = std::min(minLowerFreq, std::max(0.0, lowerFreq));
        maxLowerFreq = std::max(maxLowerFreq, lowerFreq);
        minUpperFreq = std::min(minUpperFreq, std::max(0.0, upperFreq));
        maxUpperFreq = std::max(maxUpperFreq, upperFreq);
    }
    
    // Строим lookup table с текущим интервалом (или значением по умолчанию)
    const int intervalSeconds = std::max(1, tableIntervalMs / 1000);
    buildLookupTableInternal(intervalSeconds);
}

/**
 * Получить частоты каналов для заданного времени через lookup table
 * СЛОЖНОСТЬ: O(1) - прямой доступ по индексу
 */
inline std::pair<double, double> FrequencyCurve::getChannelFrequenciesAt(int32_t timeSeconds) const {
    // Если lookup table не построена, возвращаем значения по умолчанию
    if (lowerFreqTable.empty() || upperFreqTable.empty()) {
        return {200.0, 210.0};
    }
    
    // Нормализуем время в пределах суток
    timeSeconds = ((timeSeconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
    
    // Вычисляем индекс в таблице
    // Каждый элемент таблицы соответствует интервалу в tableIntervalMs миллисекунд
    const int intervalSeconds = std::max(1, tableIntervalMs / 1000);
    const int tableSize = static_cast<int>(lowerFreqTable.size());
    
    // Индекс = timeSeconds / intervalSeconds
    const int index = std::min(timeSeconds / intervalSeconds, tableSize - 1);
    
    return {lowerFreqTable[index], upperFreqTable[index]};
}

} // namespace binaural