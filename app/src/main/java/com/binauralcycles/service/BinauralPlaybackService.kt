package com.binauralcycles.service

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
import androidx.core.app.NotificationCompat
import com.binauralcycles.MainActivity
import com.binauralcycles.R
import com.binaural.core.audio.engine.BinauralAudioEngine
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Сервис для воспроизведения бинауральных ритмов в фоновом режиме.
 * Создаёт и управляет BinauralAudioEngine, который работает в отдельном потоке.
 */
class BinauralPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "binaural_playback_channel"
        const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "com.binauralcycles.action.START"
        const val ACTION_STOP = "com.binauralcycles.action.STOP"
        const val ACTION_TOGGLE = "com.binauralcycles.action.TOGGLE"
        const val ACTION_EXIT = "com.binauralcycles.action.EXIT"
        
        // Статические StateFlows для доступа из ViewModel без привязки к сервису
        private val _isPlaying = MutableStateFlow(false)
        val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
        
        private val _currentBeatFrequency = MutableStateFlow(0.0)
        val currentBeatFrequency: StateFlow<Double> = _currentBeatFrequency.asStateFlow()
        
        private val _currentCarrierFrequency = MutableStateFlow(0.0)
        val currentCarrierFrequency: StateFlow<Double> = _currentCarrierFrequency.asStateFlow()
        
        private val _isChannelsSwapped = MutableStateFlow(false)
        val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()
        
        private val _elapsedSeconds = MutableStateFlow(0)
        val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
    }

    // Аудио-движок создаётся только в сервисе
    private var audioEngine: BinauralAudioEngine? = null
    
    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Интервал обновления частот (из настроек)
    private val _frequencyUpdateIntervalMs = MutableStateFlow(10000) // По умолчанию 10 секунд
    
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioEngine?.setVolume(audioEngine?.currentConfig?.value?.volume ?: 0.7f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                audioEngine?.stop()
                // Обновляем notification только при изменении состояния
                _isPlaying.value = false
                updateNotificationImmediately()
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
        android.util.Log.d("BinauralPlaybackService", "onCreate()")
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        
        // Создаём аудио-движок в сервисе
        audioEngine = BinauralAudioEngine(applicationContext).apply {
            initialize()
        }
        
        // Сразу запускаем foreground с начальным уведомлением
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
            audioEngine?.isPlaying?.collectLatest { playing ->
                _isPlaying.value = playing
                // Обновляем notification при изменении состояния воспроизведения
                updateNotificationImmediately()
            }
        }
        
        // Частоты обновляем в UI напрямую из audioEngine
        serviceScope.launch {
            audioEngine?.currentBeatFrequency?.collectLatest { freq ->
                _currentBeatFrequency.value = freq
            }
        }
        
        serviceScope.launch {
            audioEngine?.currentCarrierFrequency?.collectLatest { freq ->
                _currentCarrierFrequency.value = freq
            }
        }
        
        serviceScope.launch {
            audioEngine?.isChannelsSwapped?.collectLatest { swapped ->
                _isChannelsSwapped.value = swapped
            }
        }
        
        serviceScope.launch {
            audioEngine?.elapsedSeconds?.collectLatest { elapsed ->
                _elapsedSeconds.value = elapsed
            }
        }
        
        // Периодическое обновление notification во время воспроизведения
        startNotificationUpdateJob()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("BinauralPlaybackService", "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                startPlayback()
            }
            ACTION_STOP -> {
                stopPlayback()
            }
            ACTION_TOGGLE -> {
                togglePlayback()
            }
            ACTION_EXIT -> {
                exitApp()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
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

        // Toggle action (Play/Pause)
        val toggleIntent = Intent(this, BinauralPlaybackService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Exit action
        val exitIntent = Intent(this, BinauralPlaybackService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 2, exitIntent,
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

        val playPauseIcon = if (_isPlaying.value) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (_isPlaying.value) getString(R.string.action_pause) else getString(R.string.action_play)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(playPauseIcon, playPauseText, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_exit), exitPendingIntent)
            .setOngoing(_isPlaying.value)
            .setOnlyAlertOnce(true) // Предотвращает мерцание иконки при обновлении
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Немедленное обновление notification (только для изменения состояния воспроизведения)
     */
    private fun updateNotificationImmediately() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            android.util.Log.e("BinauralPlaybackService", "Failed to update notification", e)
        }
    }
    
    /**
     * Тихое обновление notification (только текст, без мерцания иконки)
     * Использует setSilent для предотвращения визуального мерцания
     */
    private fun updateNotificationSilently() {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val notification = createNotification()
            // Используем флаг FLAG_ONLY_ALERT_ONCE через setSilent (API 29+)
            val silentNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                notification.extras.putBoolean("android.silent", true)
                notification
            } else {
                notification
            }
            notificationManager.notify(NOTIFICATION_ID, silentNotification)
        } catch (e: Exception) {
            android.util.Log.e("BinauralPlaybackService", "Failed to update notification silently", e)
        }
    }
    
    // Job для периодического обновления уведомления
    private var notificationUpdateJob: Job? = null
    
    /**
     * Запускает периодическое обновление уведомления во время воспроизведения
     */
    private fun startNotificationUpdateJob() {
        notificationUpdateJob = serviceScope.launch {
            while (true) {
                delay(_frequencyUpdateIntervalMs.value.toLong())
                if (_isPlaying.value) {
                    updateNotificationSilently()
                }
            }
        }
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
        
        audioEngine?.play()
        
        // Обновляем уведомление при старте воспроизведения
        updateNotificationSilently()
        
        // Дополнительное обновление частот после старта воспроизведения
        serviceScope.launch {
            delay(200) // Ждём, пока аудио-движок установит начальные частоты
            updateNotificationSilently()
        }
    }

    fun stopPlayback() {
        audioEngine?.stop()
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun exitApp() {
        // Останавливаем аудио с затуханием
        audioEngine?.stopWithFade()
        abandonAudioFocus()
        
        // Отправляем Intent в MainActivity для закрытия
        val exitIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_EXIT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(exitIntent)
        
        // Останавливаем foreground service
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

    // ============= Методы для управления аудио (асинхронные, вызываются из ViewModel) =============
    
    fun updateConfig(config: BinauralConfig) {
        audioEngine?.updateConfig(config)
    }

    fun updateFrequencyCurve(curve: FrequencyCurve) {
        audioEngine?.updateFrequencyCurve(curve)
    }

    fun setVolume(volume: Float) {
        audioEngine?.setVolume(volume)
    }
    
    fun setSampleRate(rate: SampleRate) {
        audioEngine?.setSampleRate(rate)
    }
    
    fun getSampleRate(): SampleRate {
        return audioEngine?.getSampleRate() ?: SampleRate.MEDIUM
    }
    
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        _frequencyUpdateIntervalMs.value = intervalMs
        audioEngine?.setFrequencyUpdateInterval(intervalMs)
    }

    fun setWavetableOptimizationEnabled(enabled: Boolean) {
        audioEngine?.setWavetableOptimizationEnabled(enabled)
    }

    fun setWavetableSize(size: Int) {
        audioEngine?.setWavetableSize(size)
    }
    
    fun setUseNativeEngine(enabled: Boolean) {
        audioEngine?.setUseNativeEngine(enabled)
    }
    
    fun getFrequencyUpdateInterval(): Int {
        return audioEngine?.getFrequencyUpdateInterval() ?: 100
    }
    
    fun togglePlayback() {
        if (_isPlaying.value) {
            audioEngine?.stopWithFade()
            abandonAudioFocus()
        } else {
            requestAudioFocus()
            audioEngine?.resumeWithFade()
        }
    }
    
    fun play() {
        audioEngine?.play()
    }
    
    fun stop() {
        audioEngine?.stop()
    }
    
    fun stopWithFade() {
        audioEngine?.stopWithFade()
    }
    
    fun pauseWithFade() {
        audioEngine?.pauseWithFade()
    }
    
    fun resumeWithFade() {
        audioEngine?.resumeWithFade()
    }
    
    fun switchPresetWithFade(config: BinauralConfig) {
        audioEngine?.switchPresetWithFade(config)
    }

    override fun onDestroy() {
        android.util.Log.d("BinauralPlaybackService", "onDestroy()")
        
        notificationUpdateJob?.cancel()
        
        audioEngine?.release()
        audioEngine = null
        
        abandonAudioFocus()
        serviceScope.cancel()
        
        _isPlaying.value = false
        _currentBeatFrequency.value = 0.0
        _currentCarrierFrequency.value = 0.0
        _isChannelsSwapped.value = false
        _elapsedSeconds.value = 0
        
        super.onDestroy()
    }
}