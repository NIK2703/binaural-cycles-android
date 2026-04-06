package com.binaural.data.local.mapper

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.FrequencyRange
import com.binaural.core.domain.model.InterpolationType
import com.binaural.core.domain.model.RelaxationMode
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.data.local.entity.FrequencyPointEntity
import com.binaural.data.local.entity.PresetEntity
import kotlinx.datetime.LocalTime

/**
 * Mapper для преобразования между Entity и Domain Model.
 */
object PresetMapper {
    
    // ========== Entity -> Domain ==========
    
    /**
     * Преобразовать PresetEntity и список FrequencyPointEntity в BinauralPreset.
     */
    fun toDomain(entity: PresetEntity, points: List<FrequencyPointEntity>): BinauralPreset {
        // Если точек меньше 2, используем дефолтную кривую
        val frequencyCurve = if (points.size < 2) {
            FrequencyCurve.defaultCurve()
        } else {
            FrequencyCurve(
                points = points.map { toDomain(it) },
                carrierRange = FrequencyRange(entity.carrierRangeMin, entity.carrierRangeMax),
                beatRange = FrequencyRange(entity.beatRangeMin, entity.beatRangeMax),
                interpolationType = parseInterpolationType(entity.interpolationType),
                splineTension = entity.splineTension
            )
        }
        
        return BinauralPreset(
            id = entity.id,
            name = entity.name,
            frequencyCurve = frequencyCurve,
            relaxationModeSettings = RelaxationModeSettings(
                enabled = entity.relaxationEnabled,
                mode = parseRelaxationMode(entity.relaxationMode),
                carrierReductionPercent = entity.carrierReductionPercent,
                beatReductionPercent = entity.beatReductionPercent,
                gapBetweenRelaxationMinutes = entity.gapBetweenRelaxationMinutes,
                transitionPeriodMinutes = entity.transitionPeriodMinutes,
                relaxationDurationMinutes = entity.relaxationDurationMinutes,
                smoothIntervalMinutes = entity.smoothIntervalMinutes
            ),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }
    
    /**
     * Преобразовать FrequencyPointEntity в FrequencyPoint.
     */
    fun toDomain(entity: FrequencyPointEntity): FrequencyPoint {
        return FrequencyPoint(
            time = LocalTime(entity.hour, entity.minute),
            carrierFrequency = entity.carrierFrequency,
            beatFrequency = entity.beatFrequency
        )
    }
    
    // ========== Domain -> Entity ==========
    
    /**
     * Преобразовать BinauralPreset в PresetEntity.
     */
    fun toEntity(preset: BinauralPreset): PresetEntity {
        return PresetEntity(
            id = preset.id,
            name = preset.name,
            carrierRangeMin = preset.frequencyCurve.carrierRange.min,
            carrierRangeMax = preset.frequencyCurve.carrierRange.max,
            beatRangeMin = preset.frequencyCurve.beatRange.min,
            beatRangeMax = preset.frequencyCurve.beatRange.max,
            interpolationType = preset.frequencyCurve.interpolationType.name,
            splineTension = preset.frequencyCurve.splineTension,
            relaxationEnabled = preset.relaxationModeSettings.enabled,
            relaxationMode = preset.relaxationModeSettings.mode.name,
            carrierReductionPercent = preset.relaxationModeSettings.carrierReductionPercent,
            beatReductionPercent = preset.relaxationModeSettings.beatReductionPercent,
            gapBetweenRelaxationMinutes = preset.relaxationModeSettings.gapBetweenRelaxationMinutes,
            transitionPeriodMinutes = preset.relaxationModeSettings.transitionPeriodMinutes,
            relaxationDurationMinutes = preset.relaxationModeSettings.relaxationDurationMinutes,
            smoothIntervalMinutes = preset.relaxationModeSettings.smoothIntervalMinutes,
            createdAt = preset.createdAt,
            updatedAt = preset.updatedAt
        )
    }
    
    /**
     * Преобразовать FrequencyPoint в FrequencyPointEntity.
     */
    fun toEntity(point: FrequencyPoint, presetId: String): FrequencyPointEntity {
        return FrequencyPointEntity(
            presetId = presetId,
            hour = point.time.hour,
            minute = point.time.minute,
            carrierFrequency = point.carrierFrequency,
            beatFrequency = point.beatFrequency
        )
    }
    
    /**
     * Преобразовать список точек в Entities.
     */
    fun toEntityList(points: List<FrequencyPoint>, presetId: String): List<FrequencyPointEntity> {
        return points.map { toEntity(it, presetId) }
    }
    
    // ========== Helpers ==========
    
    private fun parseInterpolationType(value: String): InterpolationType {
        return try {
            InterpolationType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            InterpolationType.LINEAR
        }
    }
    
    private fun parseRelaxationMode(value: String): RelaxationMode {
        return try {
            RelaxationMode.valueOf(value)
        } catch (e: IllegalArgumentException) {
            RelaxationMode.SMOOTH
        }
    }
}