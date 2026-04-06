package com.binaural.core.domain.model

/**
 * Тип интерполяции между точками
 */
enum class InterpolationType {
    LINEAR,             // Линейная интерполяция
    CARDINAL,           // Кардинальный сплайн (с параметром tension: 0=Catmull-Rom, 1=линейная)
    MONOTONE,           // Монотонный сплайн (без overshoot, сохраняет форму данных)
    STEP                // Ступенчатая интерполяция (без интерполяции, значение до следующей точки)
}

/**
 * Объект, содержащий алгоритмы интерполяции для расчёта частот между точками.
 * Используется как для генерации аудио, так и для отрисовки графика.
 * 
 * ТИПЫ ИНТЕРПОЛЯЦИИ:
 * - LINEAR: простая линейная интерполяция, без overshoot
 * - CARDINAL: кубический сплайн с параметром tension (0=Catmull-Rom плавный, 1=почти линейный)
 * - MONOTONE: сохраняет форму данных, гарантированно без overshoot
 * - STEP: ступенчатая интерполяция, значение остаётся постоянным до следующей точки
 */
object Interpolation {
    
    /**
     * Линейная интерполяция
     */
    fun linear(y1: Float, y2: Float, t: Float): Float {
        return y1 + t * (y2 - y1)
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
    fun cardinal(p0: Float, p1: Float, p2: Float, p3: Float, t: Float, tension: Float = 0.0f): Float {
        val t2 = t * t
        val t3 = t2 * t
        
        // Вычисляем касательные с учётом натяжения
        val s = (1.0f - tension) / 2.0f
        val m1 = (p2 - p0) * s
        val m2 = (p3 - p1) * s
        
        val h00 = 2.0f * t3 - 3.0f * t2 + 1.0f
        val h10 = t3 - 2.0f * t2 + t
        val h01 = -2.0f * t3 + 3.0f * t2
        val h11 = t3 - t2
        
        return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2
    }
    
    /**
     * Монотонный кубический сплайн (PCHIP - Piecewise Cubic Hermite Interpolating Polynomial)
     * 
     * ОСОБЕННОСТИ:
     * - Гарантирует ОТСУТСТВИЕ OVERSHOOT - значения всегда в пределах [min(p1,p2), max(p1,p2)]
     * - Сохраняет монотонность данных - если p1 < p2, то кривая монотонно возрастает
     * - Проходит через все контрольные точки
     */
    fun monotone(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        // Вычисляем наклоны (разности) между соседними точками
        val d0 = p1 - p0  // наклон слева от p1
        val d1 = p2 - p1  // наклон между p1 и p2 (основной интервал)
        val d2 = p3 - p2  // наклон справа от p2
        
        // Вычисляем касательные в точках p1 и p2 используя гармоническое среднее
        val m1 = computeMonotoneSlope(d0, d1)
        val m2 = computeMonotoneSlope(d1, d2)
        
        // Кубическая интерполяция Эрмита
        val t2 = t * t
        val t3 = t2 * t
        
        val h00 = 2.0f * t3 - 3.0f * t2 + 1.0f
        val h10 = t3 - 2.0f * t2 + t
        val h01 = -2.0f * t3 + 3.0f * t2
        val h11 = t3 - t2
        
        val result = h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2
        
        // Гарантируем отсутствие overshoot
        val minVal = minOf(p1, p2)
        val maxVal = maxOf(p1, p2)
        return result.coerceIn(minVal, maxVal)
    }
    
    /**
     * Вычисляет наклон для монотонного сплайна
     * Использует алгоритм Fritsch-Carlson для сохранения монотонности
     */
    private fun computeMonotoneSlope(d1: Float, d2: Float): Float {
        // Если наклоны имеют разные знаки или один из них нулевой - касательная = 0
        if (d1 * d2 <= 0) return 0.0f
        
        // Гармоническое среднее двух наклонов - это ключ к монотонности
        return 2.0f * d1 * d2 / (d1 + d2)
    }
    
    /**
     * Ступенчатая интерполяция
     * Значение остаётся постоянным (равным левой точке) до следующей точки
     */
    fun step(p1: Float): Float {
        return p1
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
    fun interpolate(
        type: InterpolationType,
        p0: Float, p1: Float, p2: Float, p3: Float,
        t: Float,
        tension: Float = 0.0f
    ): Float {
        val result = when (type) {
            InterpolationType.LINEAR -> linear(p1, p2, t)
            InterpolationType.CARDINAL -> cardinal(p0, p1, p2, p3, t, tension)
            InterpolationType.MONOTONE -> monotone(p0, p1, p2, p3, t)
            InterpolationType.STEP -> step(p1)
        }
        return result.coerceAtLeast(0.0f)
    }
}