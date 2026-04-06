package com.binaural.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.binaural.data.local.entity.FrequencyPointEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с таблицей точек частотной кривой.
 */
@Dao
interface FrequencyPointDao {
    
    /**
     * Получить все точки для пресета, отсортированные по времени.
     */
    @Query("SELECT * FROM frequency_points WHERE presetId = :presetId ORDER BY hour, minute")
    fun getPointsForPreset(presetId: String): Flow<List<FrequencyPointEntity>>
    
    /**
     * Получить все точки для пресета (suspend версия).
     */
    @Query("SELECT * FROM frequency_points WHERE presetId = :presetId ORDER BY hour, minute")
    suspend fun getPointsForPresetSync(presetId: String): List<FrequencyPointEntity>
    
    /**
     * Вставить одну точку.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: FrequencyPointEntity)
    
    /**
     * Вставить список точек.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<FrequencyPointEntity>)
    
    /**
     * Удалить все точки для пресета.
     */
    @Query("DELETE FROM frequency_points WHERE presetId = :presetId")
    suspend fun deletePointsForPreset(presetId: String)
    
    /**
     * Атомарно заменить все точки для пресета.
     */
    @Transaction
    suspend fun replacePointsForPreset(presetId: String, points: List<FrequencyPointEntity>) {
        deletePointsForPreset(presetId)
        insertPoints(points)
    }
    
    /**
     * Удалить все точки.
     */
    @Query("DELETE FROM frequency_points")
    suspend fun deleteAllPoints()
}