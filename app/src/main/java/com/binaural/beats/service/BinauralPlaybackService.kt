package com.binaural.beats.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.binaural.beats.MainActivity
import com.binaural.beats.R
import com.binaural.core.audio.engine.BinauralAudioEngine
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class BinauralPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "binaural_playback_channel"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "com.binaural.beats.action.START"
        const val ACTION_STOP = "com.binaural.beats.action.STOP"
        const val ACTION_TOGGLE = "com.binaural.beats.action.TOGGLE"
        
        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
        
        private val _currentBeatFrequency = MutableStateFlow(0.0)
        val currentBeatFrequency: StateFlow<Double> = _currentBeatFrequency.asStateFlow()
        
        private val _currentCarrierFrequency = MutableStateFlow(0.0)
        val currentCarrierFrequency: StateFlow<Double> = _currentCarrierFrequency.asStateFlow()
        
        private val _isChannelsSwapped = MutableStateFlow(false)
        val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()
    }

    @Inject
    lateinit var audioEngine: BinauralAudioEngine
    
    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioEngine.setVolume(audioEngine.currentConfig.value.volume)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                audioEngine.stop()
                updateNotification()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Временная потеря - можно приостановить
            }
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {
                // Восстановление после временной потери
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BinauralPlaybackService = this@BinauralPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        audioEngine.initialize(serviceScope)
        
        // Сразу запускаем foreground с начальным уведомлением
        // Это обязательно для Android 8+ при использовании startForegroundService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        // Наблюдаем за состоянием воспроизведения
        serviceScope.launch {
            audioEngine.isPlaying.collect { playing ->
                _isPlaying.value = playing
                if (playing) {
                    updateNotification()
                } else {
                    updateNotification()
                }
            }
        }
        
        serviceScope.launch {
            audioEngine.currentBeatFrequency.collect { freq ->
                _currentBeatFrequency.value = freq
                updateNotification()
            }
        }
        
        serviceScope.launch {
            audioEngine.currentCarrierFrequency.collect { freq ->
                _currentCarrierFrequency.value = freq
            }
        }
        
        serviceScope.launch {
            audioEngine.isChannelsSwapped.collect { swapped ->
                _isChannelsSwapped.value = swapped
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startPlayback()
            }
            ACTION_STOP -> {
                stopPlayback()
            }
            ACTION_TOGGLE -> {
                if (_isPlaying.value) {
                    stopPlayback()
                } else {
                    startPlayback()
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(this, BinauralPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val title = if (_isPlaying.value) {
            getString(R.string.notification_playing)
        } else {
            getString(R.string.notification_paused)
        }
        
        val content = getString(
            R.string.notification_content,
            _currentBeatFrequency.value,
            _currentCarrierFrequency.value
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(
                if (_isPlaying.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (_isPlaying.value) getString(R.string.action_stop) else getString(R.string.action_play),
                stopPendingIntent
            )
            .setStyle(MediaStyle().setShowActionsInCompactView(0))
            .setOngoing(_isPlaying.value)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    fun startPlayback() {
        if (!requestAudioFocus()) {
            android.util.Log.w("BinauralPlaybackService", "Could not gain audio focus")
        }
        
        if (_isPlaying.value) {
            return
        }
        
        // Запускаем foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        audioEngine.play()
    }

    fun stopPlayback() {
        audioEngine.stop()
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestAudioFocus(): Boolean {
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

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    // Методы для управления аудио
    fun updateConfig(config: BinauralConfig) {
        audioEngine.updateConfig(config)
    }

    fun updateFrequencyCurve(curve: FrequencyCurve) {
        audioEngine.updateFrequencyCurve(curve)
    }

    fun setVolume(volume: Float) {
        audioEngine.setVolume(volume)
    }

    override fun onDestroy() {
        audioEngine.release()
        abandonAudioFocus()
        serviceScope.cancel()
        _isPlaying.value = false
        super.onDestroy()
    }
}