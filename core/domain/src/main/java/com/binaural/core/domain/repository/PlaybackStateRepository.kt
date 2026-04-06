package com.binaural.core.domain.repository

import com.binaural.core.domain.model.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * Репозиторий для хранения состояния воспроизведения.
 * Является Single Source of Truth для UI и сервиса.
 */
interface PlaybackStateRepository {
    
    /**
     * Текущее состояние воспроизведения
     */
    val playbackState: StateFlow<PlaybackState>
    
    /**
     * Подключён ли сервис
     */
    val isConnected: StateFlow<Boolean>
    
    /**
     * Установить новое состояние
     */
    fun setState(state: PlaybackState)
    
    /**
     * Обновить состояние через функцию-трансформер
     */
    fun updateState(update: (PlaybackState) -> PlaybackState)
    
    /**
     * Установить статус подключения сервиса
     */
    fun setConnected(connected: Boolean)
    
    /**
     * Сбросить состояние к начальному
     */
    fun reset()
}
