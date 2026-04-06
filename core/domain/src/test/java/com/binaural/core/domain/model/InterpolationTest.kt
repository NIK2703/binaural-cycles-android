package com.binaural.core.domain.model

import com.binaural.core.domain.model.Interpolation
import com.binaural.core.domain.model.InterpolationType
import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты для алгоритмов интерполяции.
 */
class InterpolationTest {
    
    // ========== LINEAR интерполяция ==========
    
    @Test
    fun `linear interpolation at start returns first value`() {
        val result = Interpolation.linear(10.0f, 20.0f, 0.0f)
        assertEquals(10.0f, result, 0.001f)
    }
    
    @Test
    fun `linear interpolation at end returns second value`() {
        val result = Interpolation.linear(10.0f, 20.0f, 1.0f)
        assertEquals(20.0f, result, 0.001f)
    }
    
    @Test
    fun `linear interpolation at midpoint returns average`() {
        val result = Interpolation.linear(10.0f, 20.0f, 0.5f)
        assertEquals(15.0f, result, 0.001f)
    }
    
    @Test
    fun `linear interpolation with negative values`() {
        val result = Interpolation.linear(-10.0f, 10.0f, 0.5f)
        assertEquals(0.0f, result, 0.001f)
    }
    
    // ========== CARDINAL интерполяция ==========
    
    @Test
    fun `cardinal passes through control points`() {
        val p0 = 5.0f
        val p1 = 10.0f
        val p2 = 20.0f
        val p3 = 25.0f
        
        // В точке t=0 должен вернуть p1
        val resultStart = Interpolation.cardinal(p0, p1, p2, p3, 0.0f, 0.0f)
        assertEquals(p1, resultStart, 0.001f)
        
        // В точке t=1 должен вернуть p2
        val resultEnd = Interpolation.cardinal(p0, p1, p2, p3, 1.0f, 0.0f)
        assertEquals(p2, resultEnd, 0.001f)
    }
    
    @Test
    fun `cardinal with tension 1 approximates linear`() {
        val p0 = 5.0f
        val p1 = 10.0f
        val p2 = 20.0f
        val p3 = 25.0f
        
        val result = Interpolation.cardinal(p0, p1, p2, p3, 0.5f, 1.0f)
        
        // При tension=1 результат должен быть близок к линейному
        val linear = Interpolation.linear(p1, p2, 0.5f)
        assertEquals(linear, result, 1.0f) // Допускаем небольшое отклонение
    }
    
    @Test
    fun `cardinal with tension 0 creates smooth curve`() {
        val p0 = 0.0f
        val p1 = 10.0f
        val p2 = 10.0f
        val p3 = 20.0f
        
        // Catmull-Rom с плоским участком
        val result = Interpolation.cardinal(p0, p1, p2, p3, 0.5f, 0.0f)
        
        // Результат должен быть между p1 и p2
        assertTrue(result >= minOf(p1, p2))
        assertTrue(result <= maxOf(p1, p2))
    }
    
    // ========== MONOTONE интерполяция ==========
    
    @Test
    fun `monotone passes through control points`() {
        val p0 = 5.0f
        val p1 = 10.0f
        val p2 = 20.0f
        val p3 = 25.0f
        
        val resultStart = Interpolation.monotone(p0, p1, p2, p3, 0.0f)
        assertEquals(p1, resultStart, 0.001f)
        
        val resultEnd = Interpolation.monotone(p0, p1, p2, p3, 1.0f)
        assertEquals(p2, resultEnd, 0.001f)
    }
    
    @Test
    fun `monotone never overshoots for increasing values`() {
        val p0 = 5.0f
        val p1 = 10.0f
        val p2 = 20.0f
        val p3 = 25.0f
        
        for (t in 0..100) {
            val ratio = t / 100.0f
            val result = Interpolation.monotone(p0, p1, p2, p3, ratio)
            
            // Результат должен быть в пределах [p1, p2]
            assertTrue("Overshoot at t=$ratio: $result", result >= p1)
            assertTrue("Overshoot at t=$ratio: $result", result <= p2)
        }
    }
    
    @Test
    fun `monotone never overshoots for decreasing values`() {
        val p0 = 25.0f
        val p1 = 20.0f
        val p2 = 10.0f
        val p3 = 5.0f
        
        for (t in 0..100) {
            val ratio = t / 100.0f
            val result = Interpolation.monotone(p0, p1, p2, p3, ratio)
            
            // Результат должен быть в пределах [p2, p1]
            assertTrue("Overshoot at t=$ratio: $result", result >= p2)
            assertTrue("Overshoot at t=$ratio: $result", result <= p1)
        }
    }
    
    @Test
    fun `monotone preserves monotonicity`() {
        // Возрастающая последовательность
        val points = floatArrayOf(0.0f, 10.0f, 20.0f, 30.0f)
        
        var previous = points[1]
        for (t in 1..100) {
            val ratio = t / 100.0f
            val result = Interpolation.monotone(points[0], points[1], points[2], points[3], ratio)
            
            // Каждый следующий должен быть >= предыдущего
            assertTrue("Not monotone at t=$ratio: $previous > $result", result >= previous - 0.001f)
            previous = result
        }
    }
    
    // ========== STEP интерполяция ==========
    
    @Test
    fun `step returns left value regardless of t`() {
        val p1 = 10.0f
        
        assertEquals(p1, Interpolation.step(p1), 0.001f)
    }
    
    // ========== Общая функция interpolate ==========
    
    @Test
    fun `interpolate with LINEAR type uses linear interpolation`() {
        val result = Interpolation.interpolate(
            InterpolationType.LINEAR,
            5.0f, 10.0f, 20.0f, 25.0f,
            0.5f
        )
        
        val expected = Interpolation.linear(10.0f, 20.0f, 0.5f)
        assertEquals(expected, result, 0.001f)
    }
    
    @Test
    fun `interpolate with CARDINAL type uses cardinal interpolation`() {
        val result = Interpolation.interpolate(
            InterpolationType.CARDINAL,
            5.0f, 10.0f, 20.0f, 25.0f,
            0.5f,
            0.5f
        )
        
        val expected = Interpolation.cardinal(5.0f, 10.0f, 20.0f, 25.0f, 0.5f, 0.5f)
        assertEquals(expected, result, 0.001f)
    }
    
    @Test
    fun `interpolate with MONOTONE type uses monotone interpolation`() {
        val result = Interpolation.interpolate(
            InterpolationType.MONOTONE,
            5.0f, 10.0f, 20.0f, 25.0f,
            0.5f
        )
        
        val expected = Interpolation.monotone(5.0f, 10.0f, 20.0f, 25.0f, 0.5f)
        assertEquals(expected, result, 0.001f)
    }
    
    @Test
    fun `interpolate with STEP type uses step interpolation`() {
        val result = Interpolation.interpolate(
            InterpolationType.STEP,
            5.0f, 10.0f, 20.0f, 25.0f,
            0.5f
        )
        
        assertEquals(10.0f, result, 0.001f)
    }
    
    @Test
    fun `interpolate always returns non-negative value`() {
        // Даже если интерполяция даёт отрицательное значение, результат должен быть >= 0
        val result = Interpolation.interpolate(
            InterpolationType.LINEAR,
            -100.0f, -50.0f, -20.0f, -10.0f,
            0.5f
        )
        
        assertTrue(result >= 0.0f)
    }
}