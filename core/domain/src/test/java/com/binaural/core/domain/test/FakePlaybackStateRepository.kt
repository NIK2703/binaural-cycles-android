package com.binaural.core.domain.test

import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.model.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake реализация PlaybackStateRepository для тестирования.
 * Хранит состояние воспроизведения в памяти.
 */
class FakePlaybackStateRepository : PlaybackStateRepository {
    
    private val _playbackState = MutableStateFlow(PlaybackState())
    private val _isConnected = MutableStateFlow(false)
    
    // ========== Методы для настройки в тестах ==========
    
    /**
     * Установить начальное состояние воспроизведения
     */
    fun setInitialState(state: PlaybackState) {
        _playbackState.value = state
    }
    
    /**
     * Установить статус подключения
     */
    fun setConnectedState(connected: Boolean) {
        _isConnected.value = connected
    }
    
    // ========== Реализация интерфейса ==========
    
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    override fun setState(state: PlaybackState) {
        _playbackState.value = state
    }
    
    override fun updateState(update: (PlaybackState) -> PlaybackState) {
        _playbackState.value = update(_playbackState.value)
    }
    
    override fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }
    
    override fun reset() {
        _playbackState.value = PlaybackState()
        _isConnected.value = false
    }
}