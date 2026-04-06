package com.binauralcycles.service.helpers

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.binaural.core.audio.engine.BinauralAudioEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper-класс для управления аудио-фокусом.
 * Отвечает за запрос и освобождение аудио-фокуса системы.
 */
@Singleton
class AudioFocusManager @Inject constructor() {
    
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    
    // Ссылка на аудио-движок для управления громкостью
    private var audioEngine: BinauralAudioEngine? = null
    
    // Callback при потере фокуса
    var onAudioFocusLoss: (() -> Unit)? = null
    
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioEngine?.setVolume(audioEngine?.currentConfig?.value?.volume ?: 0.7f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                audioEngine?.stop()
                onAudioFocusLoss?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Временная потеря - можно приостановить
            }
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                // Восстановление после временной потери
            }
        }
    }
    
    /**
     * Инициализация менеджера аудио-фокуса.
     * Должна вызываться в onCreate сервиса.
     */
    fun initialize(audioManager: AudioManager, audioEngine: BinauralAudioEngine) {
        this.audioManager = audioManager
        this.audioEngine = audioEngine
    }
    
    /**
     * Освобождение ресурсов.
     * Должна вызываться в onDestroy сервиса.
     */
    fun release() {
        abandonAudioFocus()
        audioEngine = null
        audioManager = null
    }
    
    /**
     * Запросить аудио-фокус.
     * @return true если фокус получен, false иначе
     */
    fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            
            val result = audioManager?.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        
        return hasAudioFocus
    }
    
    /**
     * Освободить аудио-фокус.
     */
    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }
    
    /**
     * Проверить, есть ли аудио-фокус.
     */
    fun hasFocus(): Boolean = hasAudioFocus
}