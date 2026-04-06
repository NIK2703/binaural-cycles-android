package com.binauralcycles.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управляет lifecycle сервисом воспроизведения.
 * Отвечает за связывание/отвязку сервиса и вызовы foreground/background.
 */
@Singleton
class ServiceLifecycleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private var playbackService: BinauralPlaybackService? = null
    private var isServiceConnected = false
    
    // Callback для уведомления о подключении сервиса
    var onServiceConnected: ((BinauralPlaybackService) -> Unit)? = null
    var onServiceDisconnected: (() -> Unit)? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? BinauralPlaybackService.LocalBinder
            playbackService = binder?.getService()
            isServiceConnected = true
            
            playbackService?.let { service ->
                onServiceConnected?.invoke(service)
            }
            
            android.util.Log.d("ServiceLifecycleManager", "Service connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceConnected = false
            onServiceDisconnected?.invoke()
            android.util.Log.d("ServiceLifecycleManager", "Service disconnected")
        }
    }
    
    /**
     * Подключиться к сервису
     */
    fun connect() {
        if (!isServiceConnected) {
            val intent = Intent(context, BinauralPlaybackService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    /**
     * Отключиться от сервиса
     */
    fun disconnect() {
        if (isServiceConnected) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                // Сервис уже отвязан
            }
            isServiceConnected = false
            playbackService = null
        }
    }
    
    /**
     * Получить подключенный сервис (может быть null)
     */
    fun getService(): BinauralPlaybackService? = playbackService
    
    /**
     * Проверить, подключен ли сервис
     */
    fun isConnected(): Boolean = isServiceConnected
    
    /**
     * Приложение на экране - запускаем частое обновление частот
     * Используем статический метод сервиса для надёжности (работает даже если binding ещё не восстановлен)
     */
    fun onAppForeground() {
        // Сначала пробуем статический метод (работает всегда, если сервис запущен)
        BinauralPlaybackService.onAppForeground()
        // Также вызываем через экземпляр, если подключен
        playbackService?.onAppForeground()
    }
    
    /**
     * Приложение в фоне - останавливаем частое обновление частот
     * Используем статический метод сервиса для надёжности
     */
    fun onAppBackground() {
        // Сначала пробуем статический метод
        BinauralPlaybackService.onAppBackground()
        // Также вызываем через экземпляр, если подключен
        playbackService?.onAppBackground()
    }
}