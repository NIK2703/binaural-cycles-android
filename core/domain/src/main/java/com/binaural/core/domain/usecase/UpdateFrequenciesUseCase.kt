package com.binaural.core.domain.usecase

import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.service.AudioEngineInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UseCase для периодического обновления частот воспроизведения.
 * Инкапсулирует логику периодического расчёта текущих частот
 * и обновления состояния в PlaybackStateRepository.
 */
@Singleton
class UpdateFrequenciesUseCase @Inject constructor(
    private val playbackStateRepository: PlaybackStateRepository
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // Job для периодического обновления
    private var notificationUpdateJob: Job? = null
    private var uiFrequencyUpdateJob: Job? = null
    
    // Текущие частоты
    private val _currentBeatFrequency = MutableStateFlow(0f)
    private val _currentCarrierFrequency = MutableStateFlow(0f)
    
    val currentBeatFrequency: StateFlow<Float> = _currentBeatFrequency.asStateFlow()
    val currentCarrierFrequency: StateFlow<Float> = _currentCarrierFrequency.asStateFlow()
    
    // Аудио-движок для получения частот (абстракция)
    private var audioEngine: AudioEngineInterface? = null
    
    // Callback при обновлении частот
    var onFrequencyUpdate: (() -> Unit)? = null
    
    /**
     * Установить аудио-движок для получения частот.
     */
    fun setAudioEngine(engine: AudioEngineInterface?) {
        this.audioEngine = engine
    }
    
    /**
     * Запустить периодическое обновление для UI (каждую секунду).
     * Работает когда приложение на экране.
     */
    fun startUiFrequencyUpdate() {
        uiFrequencyUpdateJob?.cancel()
        uiFrequencyUpdateJob = scope.launch {
            while (isActive) {
                delay(1000) // Каждую секунду
                updateFrequencies()
            }
        }
    }
    
    /**
     * Остановить обновление для UI.
     */
    fun stopUiFrequencyUpdate() {
        uiFrequencyUpdateJob?.cancel()
        uiFrequencyUpdateJob = null
    }
    
    /**
     * Запустить периодическое обновление для уведомления (каждые 10 секунд).
     */
    fun startNotificationUpdate() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = scope.launch {
            while (isActive) {
                delay(10_000) // Каждые 10 секунд
                updateFrequencies()
            }
        }
    }
    
    /**
     * Остановить обновление для уведомления.
     */
    fun stopNotificationUpdate() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }
    
    /**
     * Остановить все обновления.
     */
    fun stopAll() {
        stopUiFrequencyUpdate()
        stopNotificationUpdate()
    }
    
    /**
     * Обновить частоты один раз.
     */
    fun updateFrequencies() {
        val currentState = playbackStateRepository.playbackState.value
        if (currentState.isPlaying) {
            // O(1) получение частот из lookup table
            audioEngine?.updateCurrentFrequencies()
            
            // Копируем значения из audioEngine
            audioEngine?.currentBeatFrequency?.value?.let { _currentBeatFrequency.value = it }
            audioEngine?.currentCarrierFrequency?.value?.let { _currentCarrierFrequency.value = it }
            
            // Обновляем состояние в repository
            playbackStateRepository.updateState { state ->
                state.copy(
                    currentBeatFrequency = _currentBeatFrequency.value,
                    currentCarrierFrequency = _currentCarrierFrequency.value
                )
            }
            
            // Уведомляем callback
            if (_currentBeatFrequency.value > 0) {
                onFrequencyUpdate?.invoke()
            }
        }
    }
}