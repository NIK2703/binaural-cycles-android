package com.binauralcycles

import android.app.Application
import com.binauralcycles.debug.DebugCommandBus
import com.binauralcycles.debug.DebugCommandExecutor
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BinauralCyclesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Исполнитель adb-команд подключается ЗДЕСЬ, а не в BinauralViewModel:
        // тот живёт, пока на экране активити, и при погасшем экране любая
        // команда отвечала «ViewModel не подключён». Проверять кроссфейд нужно
        // именно в фоне — не будя устройство и не мешая никому.
        // В release BuildConfig.DEBUG == false, и R8 вырезает блок целиком.
        if (BuildConfig.DEBUG) {
            DebugCommandBus.attach(DebugCommandExecutor(this))
        }
    }
}
