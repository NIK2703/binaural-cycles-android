package com.binauralcycles

import android.app.Application
import com.binaural.data.local.migration.DataMigrationHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BinauralCyclesApp : Application() {
    
    @Inject
    lateinit var dataMigrationHelper: DataMigrationHelper
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        
        // Запускаем миграцию данных в фоновом потоке
        applicationScope.launch {
            dataMigrationHelper.migrateIfNeeded()
        }
    }
}
