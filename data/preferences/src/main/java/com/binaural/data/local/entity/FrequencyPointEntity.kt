package com.binaural.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity для хранения точек частотной кривой.
 * Каждая точка привязана к пресету через ForeignKey.
 */
@Entity(
    tableName = "frequency_points",
    foreignKeys = [
        ForeignKey(
            entity = PresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("presetId"), Index("presetId", "hour", "minute")]
)
data class FrequencyPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val presetId: String,
    
    val hour: Int,
    
    val minute: Int,
    
    val carrierFrequency: Float,
    
    val beatFrequency: Float
)