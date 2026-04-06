package com.binaural.core.domain.usecase

import com.binaural.core.domain.repository.PlaybackStateRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * События гарнитуры для обработки UseCase
 */
sealed class HeadsetAction {
    data object Pause : HeadsetAction()
    data object Resume : HeadsetAction()
    data object Stop : HeadsetAction()
}

/**
 * UseCase для обработки событий гарнитуры.
 * Инкапсулирует бизнес-логику реакции на подключение/отключение гарнитуры.
 */
@Singleton
class HandleHeadsetEventUseCase @Inject constructor(
    private val playbackStateRepository: PlaybackStateRepository
) {
    // Callback для выполнения действий
    var onPause: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onRequestAudioFocus: (() -> Boolean)? = null
    
    // Настройки
    private var resumeOnHeadsetConnect: Boolean = false
    private var wasStoppedByHeadsetDisconnect: Boolean = false
    
    /**
     * Установить настройку возобновления при подключении гарнитуры.
     */
    fun setResumeOnHeadsetConnect(enabled: Boolean) {
        resumeOnHeadsetConnect = enabled
        if (!enabled) {
            wasStoppedByHeadsetDisconnect = false
        }
    }
    
    /**
     * Получить текущее состояние настройки возобновления.
     */
    fun getResumeOnHeadsetConnect(): Boolean = resumeOnHeadsetConnect
    
    /**
     * Сбросить флаг остановки гарнитурой.
     */
    fun resetHeadsetDisconnectFlag() {
        wasStoppedByHeadsetDisconnect = false
    }
    
    /**
     * Проверить, было ли воспроизведение остановлено из-за отключения гарнитуры.
     */
    fun wasStoppedByHeadsetDisconnect(): Boolean = wasStoppedByHeadsetDisconnect
    
    /**
     * Обработать событие "гарнитура отключена во время воспроизведения".
     */
    fun handleHeadsetDisconnected(): HeadsetAction? {
        val currentState = playbackStateRepository.playbackState.value
        
        if (currentState.isPlaying) {
            // Останавливаем воспроизведение с затуханием
            onPause?.invoke()
            wasStoppedByHeadsetDisconnect = true
            return HeadsetAction.Pause
        }
        
        return null
    }
    
    /**
     * Обработать событие "гарнитура подключена".
     * @return действие, которое нужно выполнить, или null
     */
    fun handleHeadsetConnected(): HeadsetAction? {
        // Если воспроизведение было остановлено из-за отключения гарнитуры
        // и опция возобновления включена
        if (wasStoppedByHeadsetDisconnect && resumeOnHeadsetConnect) {
            wasStoppedByHeadsetDisconnect = false
            onRequestAudioFocus?.invoke()
            onResume?.invoke()
            return HeadsetAction.Resume
        }
        
        return null
    }
    
    /**
     * Обработать событие "аудио стало шумным" (ACTION_AUDIO_BECOMING_NOISY).
     * Это событие происходит когда аудио-устройство отключается
     * и аудио начинает воспроизводиться через динамики.
     */
    fun handleAudioBecameNoisy(): HeadsetAction? {
        val currentState = playbackStateRepository.playbackState.value
        
        if (currentState.isPlaying) {
            onPause?.invoke()
            wasStoppedByHeadsetDisconnect = true
            return HeadsetAction.Pause
        }
        
        return null
    }
    
    /**
     * Обработать ручную остановку воспроизведения.
     * Сбрасывает флаг остановки гарнитурой.
     */
    fun handleManualStop() {
        wasStoppedByHeadsetDisconnect = false
    }
    
    /**
     * Обработать ручное возобновление воспроизведения.
     * Сбрасывает флаг остановки гарнитурой.
     */
    fun handleManualResume() {
        wasStoppedByHeadsetDisconnect = false
    }
}