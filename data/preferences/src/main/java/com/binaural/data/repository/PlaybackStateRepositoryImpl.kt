package com.binaural.data.repository

import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.model.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory реализация PlaybackStateRepository.
 * Хранит состояние воспроизведения в MutableStateFlow.
 */
@Singleton
class PlaybackStateRepositoryImpl @Inject constructor() : PlaybackStateRepository {
    
    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
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
    }
}
