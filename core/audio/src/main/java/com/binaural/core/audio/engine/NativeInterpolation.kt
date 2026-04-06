package com.binaural.core.audio.engine

import android.util.Log
import com.binaural.core.domain.model.InterpolationType

/**
 * Статический класс для доступа к C++ интерполяции из UI.
 * Не требует экземпляра NativeAudioEngine.
 * 
 * ОПТИМИЗАЦИЯ: Использует C++ реализацию интерполяции вместо Kotlin,
 * что особенно важно при массовых вызовах (отрисовка графиков).
 */
object NativeInterpolation {
    
    private const val TAG = "NativeInterpolation"
    
    init {
        try {
            System.loadLibrary("binaural-engine")
            Log.d(TAG, "Native library loaded for interpolation")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }
    
    // Нативные методы (статические, не требуют экземпляра движка)
    private external fun nativeInterpolate(
        p0: Float, p1: Float, p2: Float, p3: Float,
        t: Float,
        interpolationType: Int,
        tension: Float
    ): Float
    
    private external fun nativeGenerateInterpolatedCurve(
        timePoints: IntArray,
        values: FloatArray,
        numOutputPoints: Int,
        interpolationType: Int,
        tension: Float
    ): FloatArray?
    
    /**
     * Выполнить интерполяцию одного значения через C++
     * 
     * @param p0 точка до левой границы
     * @param p1 левая граница интервала
     * @param p2 правая граница интервала
     * @param p3 точка после правой границы
     * @param t нормализованная позиция [0, 1]
     * @param interpolationType тип интерполяции
     * @param tension параметр натяжения для CARDINAL (0.0=Catmull-Rom)
     * @return интерполированное значение
     */
    fun interpolate(
        p0: Float, p1: Float, p2: Float, p3: Float,
        t: Float,
        interpolationType: InterpolationType,
        tension: Float = 0.0f
    ): Float {
        val typeInt = when (interpolationType) {
            InterpolationType.LINEAR -> 0
            InterpolationType.CARDINAL -> 1
            InterpolationType.MONOTONE -> 2
            InterpolationType.STEP -> 3
        }
        return nativeInterpolate(p0, p1, p2, p3, t, typeInt, tension)
    }
    
    /**
     * Генерация массива интерполированных значений для графика
     * 
     * ОПТИМИЗАЦИЯ: Один JNI вызов вместо сотен отдельных вызовов интерполяции.
     * 
     * @param timePoints массив временных точек (секунды с начала суток)
     * @param values массив значений в этих точках
     * @param numOutputPoints количество выходных точек (обычно 200 для графика)
     * @param interpolationType тип интерполяции
     * @param tension параметр натяжения
     * @return массив интерполированных значений или null при ошибке
     */
    fun generateInterpolatedCurve(
        timePoints: IntArray,
        values: FloatArray,
        numOutputPoints: Int,
        interpolationType: InterpolationType,
        tension: Float = 0.0f
    ): FloatArray? {
        val typeInt = when (interpolationType) {
            InterpolationType.LINEAR -> 0
            InterpolationType.CARDINAL -> 1
            InterpolationType.MONOTONE -> 2
            InterpolationType.STEP -> 3
        }
        return nativeGenerateInterpolatedCurve(timePoints, values, numOutputPoints, typeInt, tension)
    }
}