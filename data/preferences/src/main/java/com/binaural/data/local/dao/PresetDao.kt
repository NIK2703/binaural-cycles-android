package com.binaural.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.binaural.data.local.entity.PresetEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с таблицей пресетов.
 */
@Dao
interface PresetDao {
    
    /**
     * Получить все пресеты, отсортированные по дате создания.
     */
    @Query("SELECT * FROM presets ORDER BY createdAt ASC")
    fun getAllPresets(): Flow<List<PresetEntity>>
    
    /**
     * Получить пресет по ID.
     */
    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun getPresetById(id: String): PresetEntity?
    
    /**
     * Получить пресет по ID как Flow.
     */
    @Query("SELECT * FROM presets WHERE id = :id")
    fun getPresetByIdFlow(id: String): Flow<PresetEntity?>
    
    /**
     * Вставить или обновить пресет.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)
    
    /**
     * Вставить или обновить список пресетов.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPresets(presets: List<PresetEntity>)
    
    /**
     * Удалить пресет.
     */
    @Delete
    suspend fun deletePreset(preset: PresetEntity)
    
    /**
     * Удалить пресет по ID.
     */
    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun deletePresetById(id: String)
    
    /**
     * Получить количество пресетов.
     */
    @Query("SELECT COUNT(*) FROM presets")
    suspend fun getPresetCount(): Int
    
    /**
     * Удалить все пресеты.
     */
    @Query("DELETE FROM presets")
    suspend fun deleteAllPresets()
}