package com.binaural.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity для хранения пресетов бинаурального ритма.
 * Содержит основную информацию о пресете и настройках кривой.
 * Точки частотной кривой хранятся отдельно в FrequencyPointEntity.
 */
@Entity(tableName = "presets", indices = [androidx.room.Index("createdAt")])
data class PresetEntity(
    @PrimaryKey
    val id: String,
    
    val name: String,
    
    // FrequencyCurve settings
    val carrierRangeMin: Float,
    val carrierRangeMax: Float,
    val beatRangeMin: Float,
    val beatRangeMax: Float,
    val interpolationType: String,
    val splineTension: Float,
    
    // RelaxationModeSettings
    val relaxationEnabled: Boolean,
    val relaxationMode: String,
    val carrierReductionPercent: Int,
    val beatReductionPercent: Int,
    val gapBetweenRelaxationMinutes: Int,
    val transitionPeriodMinutes: Int,
    val relaxationDurationMinutes: Int,
    val smoothIntervalMinutes: Int,
    
    // Timestamps
    val createdAt: Long,
    val updatedAt: Long
)