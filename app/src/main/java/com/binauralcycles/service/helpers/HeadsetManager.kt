package com.binauralcycles.service.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.binaural.core.domain.repository.PlaybackStateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * События гарнитуры
 */
sealed class HeadsetEvent {
    data object Connected : HeadsetEvent()
    data object Disconnected : HeadsetEvent()
    data object BecameNoisy : HeadsetEvent()
}

/**
 * Helper-класс для управления гарнитурой.
 * Отвечает за отслеживание подключения/отключения гарнитуры
 * и автоматическое управление воспроизведением.
 */
@Singleton
class HeadsetManager @Inject constructor(
    private val playbackStateRepository: PlaybackStateRepository
) {
    // Состояние наличия гарнитуры
    private val _hasHeadset = MutableStateFlow(false)
    val hasHeadset: StateFlow<Boolean> = _hasHeadset.asStateFlow()
    
    // События гарнитуры
    private val _headsetEvents = MutableStateFlow<HeadsetEvent?>(null)
    val headsetEvents: StateFlow<HeadsetEvent?> = _headsetEvents.asStateFlow()
    
    // Настройки
    private var resumeOnHeadsetConnect: Boolean = false
    private var wasStoppedByHeadsetDisconnect: Boolean = false
    
    // Receivers
    private var noisyAudioReceiver: BroadcastReceiver? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var audioManager: AudioManager? = null
    
    // Callback для управления воспроизведением
    var onPausePlayback: (() -> Unit)? = null
    var onResumePlayback: (() -> Unit)? = null
    
    /**
     * Инициализация менеджера гарнитуры.
     * Должна вызываться в onCreate сервиса.
     */
    fun initialize(context: Context, audioManager: AudioManager) {
        this.audioManager = audioManager
        
        registerNoisyAudioReceiver(context)
        registerAudioDeviceCallback(audioManager)
    }
    
    /**
     * Освобождение ресурсов.
     * Должна вызываться в onDestroy сервиса.
     */
    fun release(context: Context) {
        unregisterNoisyAudioReceiver(context)
        unregisterAudioDeviceCallback()
    }
    
    /**
     * Установить настройку возобновления при подключении гарнитуры.
     */
    fun setResumeOnHeadsetConnect(enabled: Boolean) {
        android.util.Log.d("HeadsetManager", "setResumeOnHeadsetConnect($enabled)")
        resumeOnHeadsetConnect = enabled
        if (!enabled) {
            wasStoppedByHeadsetDisconnect = false
        }
    }
    
    /**
     * Сбросить флаг остановки гарнитурой (при ручном управлении).
     */
    fun resetHeadsetDisconnectFlag() {
        wasStoppedByHeadsetDisconnect = false
    }
    
    /**
     * Регистрирует приёмник для отключения гарнитуры (AUDIO_BECOMING_NOISY)
     */
    private fun registerNoisyAudioReceiver(context: Context) {
        noisyAudioReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    val currentState = playbackStateRepository.playbackState.value
                    android.util.Log.d("HeadsetManager", "Audio becoming noisy - isPlaying=${currentState.isPlaying}, resumeOnHeadsetConnect=$resumeOnHeadsetConnect")
                    
                    if (currentState.isPlaying) {
                        // Уведомляем о событии
                        _headsetEvents.value = HeadsetEvent.BecameNoisy
                        
                        // Останавливаем воспроизведение
                        onPausePlayback?.invoke()
                        
                        // Запоминаем, что воспроизведение было остановлено из-за отключения гарнитуры
                        wasStoppedByHeadsetDisconnect = true
                        android.util.Log.d("HeadsetManager", "wasStoppedByHeadsetDisconnect set to true (was playing)")
                    } else {
                        android.util.Log.d("HeadsetManager", "Not playing - ignoring noisy event")
                    }
                }
            }
        }
        
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(noisyAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(noisyAudioReceiver, filter)
        }
    }
    
    private fun unregisterNoisyAudioReceiver(context: Context) {
        noisyAudioReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                android.util.Log.e("HeadsetManager", "Error unregistering noisy audio receiver", e)
            }
        }
        noisyAudioReceiver = null
    }
    
    /**
     * Регистрирует AudioDeviceCallback для отслеживания подключения/отключения гарнитуры
     */
    private fun registerAudioDeviceCallback(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    addedDevices?.forEach { device ->
                        if (isHeadsetDevice(device)) {
                            android.util.Log.d("HeadsetManager", "Headset device added: type=${device.type}, name=${device.productName}")
                        }
                    }
                    
                    val hadNoHeadset = !_hasHeadset.value
                    checkHeadsetDevices(audioManager)
                    
                    android.util.Log.d("HeadsetManager", "onAudioDevicesAdded: hadNoHeadset=$hadNoHeadset, hasHeadset=${_hasHeadset.value}, wasStoppedByHeadsetDisconnect=$wasStoppedByHeadsetDisconnect, resumeOnHeadsetConnect=$resumeOnHeadsetConnect")
                    
                    // Если гарнитуры не было и она появилась, и воспроизведение было остановлено из-за отключения
                    if (hadNoHeadset && _hasHeadset.value && wasStoppedByHeadsetDisconnect && resumeOnHeadsetConnect) {
                        android.util.Log.d("HeadsetManager", "Headset connected - resuming playback (was stopped by headset disconnect)")
                        _headsetEvents.value = HeadsetEvent.Connected
                        wasStoppedByHeadsetDisconnect = false
                        onResumePlayback?.invoke()
                    }
                }
                
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    removedDevices?.forEach { device ->
                        if (isHeadsetDevice(device)) {
                            android.util.Log.d("HeadsetManager", "Headset device removed: type=${device.type}, name=${device.productName}")
                        }
                    }
                    
                    val hadHeadset = _hasHeadset.value
                    checkHeadsetDevices(audioManager)
                    
                    val currentState = playbackStateRepository.playbackState.value
                    // Если гарнитура была и исчезла во время воспроизведения - останавливаем
                    if (hadHeadset && !_hasHeadset.value && currentState.isPlaying) {
                        android.util.Log.d("HeadsetManager", "Headset disconnected - stopping playback")
                        _headsetEvents.value = HeadsetEvent.Disconnected
                        onPausePlayback?.invoke()
                        wasStoppedByHeadsetDisconnect = true
                    }
                }
            }
            
            // Регистрируем callback
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
            
            // Начальная проверка наличия гарнитуры
            checkHeadsetDevices(audioManager)
        }
    }
    
    private fun unregisterAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback?.let {
                audioManager?.unregisterAudioDeviceCallback(it)
            }
        }
        audioDeviceCallback = null
    }
    
    /**
     * Проверяет, является ли устройство гарнитурой/наушниками
     */
    private fun isHeadsetDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID -> true
            else -> false
        }
    }
    
    /**
     * Проверяет наличие подключенной гарнитуры
     */
    private fun checkHeadsetDevices(audioManager: AudioManager) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        _hasHeadset.value = devices.any { isHeadsetDevice(it) }
        android.util.Log.d("HeadsetManager", "Headset available: ${_hasHeadset.value}")
    }
}