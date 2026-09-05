package com.binauralcycles.ui.components

import androidx.compose.ui.graphics.Path
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime

/**
 * Кэшированная сетка графика (одна на все карточки одинакового размера)
 */
data class CachedGrid(
    val gridLines: FloatArray,      // Горизонтальные линии (Y-координаты)
    val verticalLines: FloatArray   // Вертикальные линии (X-координаты)
)

/**
 * Глобальный кэш сетки для мини-графиков
 * Сетка зависит только от размеров и одинакова для всех карточек
 */
object GridCache {
    private val cache = mutableMapOf<Pair<Int, Int>, CachedGrid>()
    
    /**
     * Получить или создать закэшированную сетку
     */
    fun getOrCreate(widthPx: Int, heightPx: Int): CachedGrid {
        val key = Pair(widthPx, heightPx)
        return cache.getOrPut(key) {
            val width = widthPx.toFloat()
            val height = heightPx.toFloat()
            CachedGrid(
                gridLines = FloatArray(3) { (height * (it + 1) / 4) },
                verticalLines = FloatArray(7) { (width * (it + 1) * 3 / 24) }
            )
        }
    }
    
    /**
     * Очистить кэш
     */
    fun clear() {
        cache.clear()
    }
}

/**
 * Кэшированная геометрия графика (без цветов и без сетки)
 */
data class CachedGraphGeometry(
    val carrierPath: Path,
    val upperBeatPath: Path,
    val lowerBeatPath: Path,
    val combinedBeatPath: Path,
    val baseCarrierPath: Path? = null,  // Путь базовой кривой (по основным точкам) для режимов ADVANCED и SMOOTH
    val pointPositions: FloatArray,  // [x0, y0, x1, y1, ...]
    val labelTexts: List<String>,
    val virtualPointPositions: FloatArray,  // [x0, y0, x1, y1, ...]
    val isRelaxationMode: Boolean,
    val maxBeat: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CachedGraphGeometry
        // Path не имеет value-equality - сравниваем по ссылке
        if (carrierPath !== other.carrierPath) return false
        if (upperBeatPath !== other.upperBeatPath) return false
        if (lowerBeatPath !== other.lowerBeatPath) return false
        if (combinedBeatPath !== other.combinedBeatPath) return false
        if (baseCarrierPath !== other.baseCarrierPath) return false
        if (!pointPositions.contentEquals(other.pointPositions)) return false
        if (labelTexts != other.labelTexts) return false
        if (!virtualPointPositions.contentEquals(other.virtualPointPositions)) return false
        if (isRelaxationMode != other.isRelaxationMode) return false
        return maxBeat == other.maxBeat
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(carrierPath)
        result = 31 * result + System.identityHashCode(upperBeatPath)
        result = 31 * result + System.identityHashCode(lowerBeatPath)
        result = 31 * result + System.identityHashCode(combinedBeatPath)
        result = 31 * result + System.identityHashCode(baseCarrierPath)
        result = 31 * result + pointPositions.contentHashCode()
        result = 31 * result + labelTexts.hashCode()
        result = 31 * result + virtualPointPositions.contentHashCode()
        result = 31 * result + isRelaxationMode.hashCode()
        result = 31 * result + maxBeat.toRawBits()
        return result
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
            // Полная точность частот (без усечения до 0.01 Гц) - иначе коллизии ключей
            // дают устаревший reuse геометрии для разных пресетов
            hash = 31 * hash + point.carrierFrequency.toRawBits()
            hash = 31 * hash + point.beatFrequency.toRawBits()
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