package com.binaural.data.preferences.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.repository.PresetRepository
import com.binaural.core.domain.repository.SettingsRepository
import com.binaural.data.datasource.local.PreferencesDataSource
import com.binaural.data.datasource.local.PreferencesDataSourceImpl
import com.binaural.data.datasource.local.PresetLocalDataSource
import com.binaural.data.datasource.local.PresetRoomDataSource
import com.binaural.data.local.BinauralDatabase
import com.binaural.data.local.migration.DataMigrationHelper
import com.binaural.data.repository.PlaybackStateRepositoryImpl
import com.binaural.data.repository.PresetRepositoryImpl
import com.binaural.data.repository.SettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "binaural_preferences")

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindPresetRepository(
        impl: PresetRepositoryImpl
    ): PresetRepository
    
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository
    
    @Binds
    @Singleton
    abstract fun bindPlaybackStateRepository(
        impl: PlaybackStateRepositoryImpl
    ): PlaybackStateRepository
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataSourceModule {
    
    @Binds
    @Singleton
    abstract fun bindPreferencesDataSource(
        impl: PreferencesDataSourceImpl
    ): PreferencesDataSource
    
    @Binds
    @Singleton
    abstract fun bindPresetLocalDataSource(
        impl: PresetRoomDataSource
    ): PresetLocalDataSource
}

@Module
@InstallIn(SingletonComponent::class)
object PreferencesModule {

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.dataStore
    }
    
    @Provides
    @Singleton
    fun provideContext(
        @ApplicationContext context: Context
    ): Context {
        return context
    }
    
    @Provides
    @Singleton
    fun provideBinauralDatabase(
        @ApplicationContext context: Context
    ): BinauralDatabase {
        return Room.databaseBuilder(
            context,
            BinauralDatabase::class.java,
            "binaural_database"
        ).build()
    }
    
    @Provides
    @Singleton
    fun providePresetDao(database: BinauralDatabase) = database.presetDao()
    
    @Provides
    @Singleton
    fun provideFrequencyPointDao(database: BinauralDatabase) = database.frequencyPointDao()
    
    @Provides
    @Singleton
    fun provideDataMigrationHelper(
        presetDao: com.binaural.data.local.dao.PresetDao,
        frequencyPointDao: com.binaural.data.local.dao.FrequencyPointDao,
        dataStore: DataStore<Preferences>
    ): DataMigrationHelper {
        return DataMigrationHelper(presetDao, frequencyPointDao, dataStore)
    }
}
