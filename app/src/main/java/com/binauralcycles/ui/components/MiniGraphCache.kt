package com.binauralcycles.ui.components

import androidx.compose.ui.graphics.Path
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime

/**
 * Кэшированная геометрия графика (без цветов)
 */
data class CachedGraphGeometry(
    val carrierPath: Path,
    val upperBeatPath: Path,
    val lowerBeatPath: Path,
    val combinedBeatPath: Path,
    val gridLines: FloatArray,
    val verticalLines: FloatArray,
    val pointPositions: FloatArray,  // [x0, y0, x1, y1, ...]
    val labelTexts: List<String>,
    val virtualPointPositions: FloatArray,  // [x0, y0, x1, y1, ...]
    val isRelaxationMode: Boolean,
    val maxBeat: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true  // Упрощённое сравнение по ссылке
    }
    
    override fun hashCode(): Int {
        return System.identityHashCode(this)
    }
}

/**
 * Ключ для кэша графиков
 */
data class GraphCacheKey(
    val pointsHash: Int,
    val interpolationType: InterpolationType,
    val splineTension: Float,
    val relaxationEnabled: Boolean,
    val relaxationMode: Int,
    val relaxationSettingsHash: Int,
    val widthPx: Int,
    val heightPx: Int,
    val carrierRangeMin: Float,
    val carrierRangeMax: Float
)

/**
 * Глобальный кэш геометрии мини-графиков
 * Хранит вычисленную геометрию для быстрого повторного использования
 */
object MiniGraphCache {
    private val cache = mutableMapOf<GraphCacheKey, CachedGraphGeometry>()
    private const val MAX_CACHE_SIZE = 50  // Максимальное количество закэшированных графиков
    
    /**
     * Получить или создать закэшированную геометрию
     */
    fun getOrCreate(
        points: List<FrequencyPoint>,
        virtualPoints: List<FrequencyPoint>,
        widthPx: Int,
        heightPx: Int,
        carrierRangeMin: Float,
        carrierRangeMax: Float,
        interpolationType: InterpolationType,
        splineTension: Float,
        relaxationModeSettings: RelaxationModeSettings,
        computeGeometry: () -> CachedGraphGeometry
    ): CachedGraphGeometry {
        val key = createKey(
            points, 
            widthPx, 
            heightPx, 
            carrierRangeMin, 
            carrierRangeMax,
            interpolationType, 
            splineTension, 
            relaxationModeSettings
        )
        
        return cache.getOrPut(key) {
            // Очистка старых записей при переполнении
            if (cache.size >= MAX_CACHE_SIZE) {
                val oldestKey = cache.keys.first()
                cache.remove(oldestKey)
            }
            computeGeometry()
        }
    }
    
    /**
     * Очистить кэш (например, при смене темы)
     */
    fun clear() {
        cache.clear()
    }
    
    /**
     * Удалить записи для определённого пресета
     */
    fun removeForPoints(points: List<FrequencyPoint>) {
        val pointsHash = computePointsHash(points)
        cache.keys.removeAll { it.pointsHash == pointsHash }
    }
    
    private fun createKey(
        points: List<FrequencyPoint>,
        widthPx: Int,
        heightPx: Int,
        carrierRangeMin: Float,
        carrierRangeMax: Float,
        interpolationType: InterpolationType,
        splineTension: Float,
        relaxationModeSettings: RelaxationModeSettings
    ): GraphCacheKey {
        return GraphCacheKey(
            pointsHash = computePointsHash(points),
            interpolationType = interpolationType,
            splineTension = splineTension,
            relaxationEnabled = relaxationModeSettings.enabled,
            relaxationMode = relaxationModeSettings.mode.ordinal,
            relaxationSettingsHash = computeRelaxationSettingsHash(relaxationModeSettings),
            widthPx = widthPx,
            heightPx = heightPx,
            carrierRangeMin = carrierRangeMin,
            carrierRangeMax = carrierRangeMax
        )
    }
    
    private fun computePointsHash(points: List<FrequencyPoint>): Int {
        var hash = 17
        for (point in points) {
            hash = 31 * hash + point.time.toSecondOfDay()
            hash = 31 * hash + (point.carrierFrequency * 100).toInt()
            hash = 31 * hash + (point.beatFrequency * 100).toInt()
        }
        return hash
    }
    
    private fun computeRelaxationSettingsHash(settings: RelaxationModeSettings): Int {
        var hash = 17
        hash = 31 * hash + (settings.carrierReductionPercent * 100).toInt()
        hash = 31 * hash + (settings.beatReductionPercent * 100).toInt()
        hash = 31 * hash + settings.transitionPeriodMinutes
        hash = 31 * hash + settings.relaxationDurationMinutes
        hash = 31 * hash + settings.gapBetweenRelaxationMinutes
        return hash
    }
}