package com.binauralcycles.service.helpers

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binauralcycles.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper-класс для управления MediaSession.
 * Отвечает за обработку медиа-кнопок гарнитуры и обновление metadata.
 */
@Singleton
class MediaSessionManager @Inject constructor(
    private val playbackStateRepository: PlaybackStateRepository
) {
    private var mediaSession: MediaSessionCompat? = null
    
    // Список ID пресетов для переключения (next/previous)
    private var presetIds: List<String> = emptyList()
    private var currentPresetId: String? = null
    
    // Callback для переключения пресетов
    var onPresetSwitch: ((String) -> Unit)? = null
    
    // Callback для управления воспроизведением
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onRequestAudioFocus: (() -> Boolean)? = null
    var onAbandonAudioFocus: (() -> Unit)? = null
    
    /**
     * Инициализация MediaSession.
     * Должна вызываться в onCreate сервиса.
     */
    fun initialize(context: Context) {
        mediaSession = MediaSessionCompat(context, "BinauralPlaybackService").apply {
            // Устанавливаем callback для обработки медиа-кнопок
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    val currentState = playbackStateRepository.playbackState.value
                    android.util.Log.d("MediaSessionManager", "MediaSession: onPlay, isPlaying=${currentState.isPlaying}")
                    if (currentState.isPlaying) {
                        onPause?.invoke()
                    } else {
                        onRequestAudioFocus?.invoke()
                        onPlay?.invoke()
                    }
                }
                
                override fun onPause() {
                    val currentState = playbackStateRepository.playbackState.value
                    android.util.Log.d("MediaSessionManager", "MediaSession: onPause, isPlaying=${currentState.isPlaying}")
                    if (currentState.isPlaying) {
                        onPause?.invoke()
                    } else {
                        onRequestAudioFocus?.invoke()
                        onPlay?.invoke()
                    }
                }
                
                override fun onStop() {
                    android.util.Log.d("MediaSessionManager", "MediaSession: onStop")
                    onStop?.invoke()
                    onAbandonAudioFocus?.invoke()
                }
                
                override fun onSkipToNext() {
                    // Переключение на следующий пресет
                    android.util.Log.d("MediaSessionManager", "MediaSession: onSkipToNext")
                    val currentIndex = presetIds.indexOf(currentPresetId)
                    if (currentIndex >= 0 && currentIndex < presetIds.size - 1) {
                        val nextId = presetIds[currentIndex + 1]
                        onPresetSwitch?.invoke(nextId)
                    } else if (presetIds.isNotEmpty()) {
                        // Зацикливание: с последнего на первый
                        onPresetSwitch?.invoke(presetIds[0])
                    }
                }
                
                override fun onSkipToPrevious() {
                    // Переключение на предыдущий пресет
                    android.util.Log.d("MediaSessionManager", "MediaSession: onSkipToPrevious")
                    val currentIndex = presetIds.indexOf(currentPresetId)
                    if (currentIndex > 0) {
                        val prevId = presetIds[currentIndex - 1]
                        onPresetSwitch?.invoke(prevId)
                    } else if (presetIds.isNotEmpty()) {
                        // Зацикливание: с первого на последний
                        onPresetSwitch?.invoke(presetIds.last())
                    }
                }
            })
            
            // Устанавливаем начальное состояние
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0f)
                    .build()
            )
            
            // Активируем сессию
            isActive = true
        }
        
        // Устанавливаем начальные метаданные
        updateMediaMetadata(context)
    }
    
    /**
     * Освобождение ресурсов.
     * Должна вызываться в onDestroy сервиса.
     */
    fun release() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }
    
    /**
     * Получить token MediaSession для использования в уведомлениях.
     */
    fun getSessionToken(): MediaSessionCompat.Token? = mediaSession?.sessionToken
    
    /**
     * Установить список ID пресетов для переключения (next/previous).
     */
    fun setPresetIds(ids: List<String>) {
        presetIds = ids
        // Обновляем PlaybackState для включения/отключения кнопок next/previous
        val currentState = playbackStateRepository.playbackState.value
        updatePlaybackState(currentState.isPlaying)
    }
    
    /**
     * Установить текущий активный пресет по ID.
     */
    fun setCurrentPresetId(id: String?) {
        currentPresetId = id
    }
    
    /**
     * Обновляет PlaybackState MediaSession при изменении состояния воспроизведения.
     */
    fun updatePlaybackState(isPlaying: Boolean) {
        // Базовые действия
        var actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP
        
        // Добавляем переключение пресетов если есть список
        if (presetIds.size > 1) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    0,
                    0f
                )
                .build()
        )
    }
    
    /**
     * Обновляет метаданные MediaSession (название пресета и подзаголовок).
     */
    fun updateMediaMetadata(context: Context) {
        val currentState = playbackStateRepository.playbackState.value
        val title = currentState.currentPresetName ?: context.getString(R.string.notification_playing)
        
        // Подзаголовок: частоты при воспроизведении, "Пауза" при паузе
        val subtitle = if (currentState.isPlaying) {
            if (currentState.currentBeatFrequency > 0 && currentState.currentCarrierFrequency > 0) {
                context.getString(
                    R.string.notification_title,
                    currentState.currentBeatFrequency,
                    currentState.currentCarrierFrequency
                )
            } else {
                currentState.currentPresetName ?: context.getString(R.string.notification_playing)
            }
        } else {
            context.getString(R.string.notification_paused)
        }
        
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .build()
        )
    }
}