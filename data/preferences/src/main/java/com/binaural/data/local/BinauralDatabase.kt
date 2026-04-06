package com.binaural.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.binaural.data.local.dao.FrequencyPointDao
import com.binaural.data.local.dao.PresetDao
import com.binaural.data.local.entity.FrequencyPointEntity
import com.binaural.data.local.entity.PresetEntity

/**
 * Room Database для хранения пресетов бинаурального ритма.
 */
@Database(
    entities = [
        PresetEntity::class,
        FrequencyPointEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BinauralDatabase : RoomDatabase() {
    
    abstract fun presetDao(): PresetDao
    abstract fun frequencyPointDao(): FrequencyPointDao
}