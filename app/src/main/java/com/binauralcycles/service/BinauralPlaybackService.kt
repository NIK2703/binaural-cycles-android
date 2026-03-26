package com.binauralcycles.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.content.BroadcastReceiver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.binauralcycles.MainActivity
import com.binauralcycles.R
import com.binaural.core.audio.engine.BinauralAudioEngine
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.RelaxationModeSettings
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
        
        private val _currentBeatFrequency = MutableStateFlow(0.0f)
        val currentBeatFrequency: StateFlow<Float> = _currentBeatFrequency.asStateFlow()
        
        private val _currentCarrierFrequency = MutableStateFlow(0.0f)
        val currentCarrierFrequency: StateFlow<Float> = _currentCarrierFrequency.asStateFlow()
        
        private val _isChannelsSwapped = MutableStateFlow(false)
        val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()
        
        private val _elapsedSeconds = MutableStateFlow(0)
        val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
        
        private val _currentPresetName = MutableStateFlow<String?>(null)
        val currentPresetName: StateFlow<String?> = _currentPresetName.asStateFlow()
        
        // Ссылка на экземпляр сервиса для статических методов
        @Volatile
        private var serviceInstance: BinauralPlaybackService? = null
        
        /**
         * Приложение на экране - запускаем частое обновление частот (1 сек)
         */
        fun onAppForeground() {
            android.util.Log.d("BinauralPlaybackService", "onAppForeground static: serviceInstance=${serviceInstance != null}")
            serviceInstance?.onAppForeground()
        }
        
        /**
         * Приложение в фоне - останавливаем частое обновление частот
         */
        fun onAppBackground() {
            android.util.Log.d("BinauralPlaybackService", "onAppBackground static: serviceInstance=${serviceInstance != null}")
            serviceInstance?.onAppBackground()
        }
    }

    // Аудио-движок создаётся только в сервисе
    private var audioEngine: BinauralAudioEngine? = null
    
    // MediaSession для обработки кнопок гарнитуры
    private var mediaSession: MediaSessionCompat? = null
    
    // Список ID пресетов для переключения (next/previous)
    private var presetIds: List<String> = emptyList()
    private var currentPresetId: String? = null
    
    // Callback для уведомления о переключении пресета
    var onPresetSwitch: ((String) -> Unit)? = null
    
    // Возобновление воспроизведения при подключении гарнитуры
    private var resumeOnHeadsetConnect: Boolean = false
    
    // Флаг: воспроизведение было остановлено из-за отключения гарнитуры
    private var wasStoppedByHeadsetDisconnect: Boolean = false
    
    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Интервал обновления частот (из настроек)
    private val _frequencyUpdateIntervalMs = MutableStateFlow(10000) // По умолчанию 10 секунд
    
    // Следим за изменением интервала для перезапуска notificationUpdateJob
    private var notificationIntervalObserver: Job? = null
    
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
        
        serviceInstance = this
        
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
                // Обновляем PlaybackState MediaSession
                updatePlaybackState(playing)
                // Обновляем метаданные (подзаголовок меняется при паузе/воспроизведении)
                updateMediaMetadata()
                // Обновляем notification при изменении состояния воспроизведения
                updateNotificationImmediately()
            }
        }
        
        // Частоты обновляем в UI напрямую из audioEngine
        // Также обновляем уведомление и метаданные при изменении частот
        serviceScope.launch {
            audioEngine?.currentBeatFrequency?.collectLatest { freq ->
                _currentBeatFrequency.value = freq
                // Обновляем уведомление и метаданные при изменении частоты
                if (_isPlaying.value && freq > 0) {
                    updateMediaMetadata()
                    updateNotificationSilently()
                }
            }
        }
        
        serviceScope.launch {
            audioEngine?.currentCarrierFrequency?.collectLatest { freq ->
                _currentCarrierFrequency.value = freq
                // Обновляем уведомление и метаданные при изменении частоты
                if (_isPlaying.value && freq > 0) {
                    updateMediaMetadata()
                    updateNotificationSilently()
                }
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
        
        // Регистрируем приёмник для режима энергосбережения
        registerPowerSaveReceiver()
        
        // Регистрируем приёмники для отслеживания отключения гарнитуры
        registerNoisyAudioReceiver()
        registerAudioDeviceCallback()
        
        // Инициализируем MediaSession для обработки кнопок гарнитуры
        initializeMediaSession()
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

        val title = _currentPresetName.value ?: getString(R.string.notification_playing)

        val content = if (_isPlaying.value) {
            // Показываем частоты только если они установлены (не 0)
            if (_currentBeatFrequency.value > 0 && _currentCarrierFrequency.value > 0) {
                getString(
                    R.string.notification_title,
                    _currentBeatFrequency.value,
                    _currentCarrierFrequency.value
                )
            } else {
                // Если частоты ещё не установлены - показываем название пресета
                _currentPresetName.value ?: getString(R.string.notification_playing)
            }
        } else {
            getString(R.string.notification_paused)
        }

        val playPauseIcon = if (_isPlaying.value) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (_isPlaying.value) getString(R.string.action_pause) else getString(R.string.action_play)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(_isPlaying.value)
            .setOnlyAlertOnce(true) // Предотвращает мерцание иконки при обновлении
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Мультимедиа стиль с кнопками управления
            .addAction(playPauseIcon, playPauseText, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.action_exit), exitPendingIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1) // Показываем play/pause и выход в компактном виде
            )
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
    
    // Job для обновления частот в UI (каждую секунду)
    private var uiFrequencyUpdateJob: Job? = null
    
    // BroadcastReceiver для режима энергосбережения
    private var powerSaveReceiver: BroadcastReceiver? = null
    
    // BroadcastReceiver для отключения гарнитуры (ACTION_AUDIO_BECOMING_NOISY)
    private var noisyAudioReceiver: BroadcastReceiver? = null
    
    // AudioDeviceCallback для отслеживания отключения аудиоустройств (API 23+)
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var hasHeadset = false
    
    /**
     * Регистрирует приёмник для отключения гарнитуры (AUDIO_BECOMING_NOISY)
     */
    private fun registerNoisyAudioReceiver() {
        noisyAudioReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    android.util.Log.d("BinauralPlaybackService", "Audio becoming noisy - isPlaying=${_isPlaying.value}, resumeOnHeadsetConnect=$resumeOnHeadsetConnect")
                    // Устанавливаем флаг только если воспроизведение было активным
                    if (_isPlaying.value) {
                        // Останавливаем воспроизведение с затуханием
                        audioEngine?.pauseWithFade()
                        _isPlaying.value = false
                        updateNotificationImmediately()
                        // Запоминаем, что воспроизведение было остановлено из-за отключения гарнитуры
                        wasStoppedByHeadsetDisconnect = true
                        android.util.Log.d("BinauralPlaybackService", "wasStoppedByHeadsetDisconnect set to true (was playing)")
                    } else {
                        android.util.Log.d("BinauralPlaybackService", "Not playing - ignoring noisy event")
                    }
                }
            }
        }
        
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisyAudioReceiver, filter)
        }
    }
    
    private fun unregisterNoisyAudioReceiver() {
        noisyAudioReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                android.util.Log.e("BinauralPlaybackService", "Error unregistering noisy audio receiver", e)
            }
        }
        noisyAudioReceiver = null
    }
    
    /**
     * Регистрирует AudioDeviceCallback для отслеживания подключения/отключения гарнитуры
     */
    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    // Проверяем, были ли добавлены устройства гарнитуры
                    addedDevices?.forEach { device ->
                        if (isHeadsetDevice(device)) {
                            android.util.Log.d("BinauralPlaybackService", "Headset device added: type=${device.type}, name=${device.productName}")
                        }
                    }
                    
                    val hadNoHeadset = !hasHeadset
                    checkHeadsetDevices()
                    
                    android.util.Log.d("BinauralPlaybackService", "onAudioDevicesAdded: hadNoHeadset=$hadNoHeadset, hasHeadset=$hasHeadset, wasStoppedByHeadsetDisconnect=$wasStoppedByHeadsetDisconnect, resumeOnHeadsetConnect=$resumeOnHeadsetConnect")
                    
                    // Если гарнитуры не было и она появилась, и воспроизведение было остановлено из-за отключения
                    if (hadNoHeadset && hasHeadset && wasStoppedByHeadsetDisconnect && resumeOnHeadsetConnect) {
                        android.util.Log.d("BinauralPlaybackService", "Headset connected - resuming playback (was stopped by headset disconnect)")
                        wasStoppedByHeadsetDisconnect = false
                        requestAudioFocus()
                        audioEngine?.resumeWithFade()
                    }
                }
                
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    // Проверяем, были ли удалены устройства гарнитуры
                    removedDevices?.forEach { device ->
                        if (isHeadsetDevice(device)) {
                            android.util.Log.d("BinauralPlaybackService", "Headset device removed: type=${device.type}, name=${device.productName}")
                        }
                    }
                    
                    val hadHeadset = hasHeadset
                    checkHeadsetDevices()
                    
                    // Если гарнитура была и исчезла во время воспроизведения - останавливаем
                    if (hadHeadset && !hasHeadset && _isPlaying.value) {
                        android.util.Log.d("BinauralPlaybackService", "Headset disconnected - stopping playback")
                        audioEngine?.pauseWithFade()
                        _isPlaying.value = false
                        updateNotificationImmediately()
                        // Запоминаем, что воспроизведение было остановлено из-за отключения гарнитуры
                        wasStoppedByHeadsetDisconnect = true
                    }
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
                private fun checkHeadsetDevices() {
                    audioManager?.let { am ->
                        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        hasHeadset = devices.any { isHeadsetDevice(it) }
                        android.util.Log.d("BinauralPlaybackService", "Headset available: $hasHeadset")
                    }
                }
            }
            
            // Регистрируем callback
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
            
            // Начальная проверка наличия гарнитуры
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.let { devices ->
                hasHeadset = devices.any { device ->
                    when (device.type) {
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
            }
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
     * Регистрирует приёмник для отслеживания изменений режима энергосбережения
     */
    private fun registerPowerSaveReceiver() {
        powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    audioEngine?.applyPowerSaveMode()
                    android.util.Log.d("BinauralPlaybackService", "Power save mode changed")
                }
            }
        }
        
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerSaveReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(powerSaveReceiver, filter)
        }
    }
    
    private fun unregisterPowerSaveReceiver() {
        powerSaveReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                android.util.Log.e("BinauralPlaybackService", "Error unregistering receiver", e)
            }
        }
        powerSaveReceiver = null
    }
    
    /**
     * Запускает периодическое обновление уведомления во время воспроизведения.
     * Фиксированный интервал 10 секунд для уведомления.
     */
    private fun startNotificationUpdateJob() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            while (true) {
                delay(10_000) // Каждые 10 секунд
                if (_isPlaying.value) {
                    updateNotificationSilently()
                }
            }
        }
    }
    
    /**
     * Запускает периодическое обновление частот в UI (каждую секунду).
     * Работает только когда приложение на экране (не в фоне).
     *
     * ВАЖНО: Частоты уже обновляются из native кода через collectLatest (строки 193-210).
     * Здесь мы только обновляем уведомление, чтобы оно было актуальным.
     */
    private fun startUiFrequencyUpdateJob() {
        uiFrequencyUpdateJob?.cancel()
        uiFrequencyUpdateJob = serviceScope.launch {
            while (true) {
                delay(1000) // Каждую секунду
                // Обновляем уведомление с текущими частотами из native кода
                if (_isPlaying.value && _currentBeatFrequency.value > 0) {
                    updateMediaMetadata()
                    updateNotificationSilently()
                }
            }
        }
    }
    
    private fun stopUiFrequencyUpdateJob() {
        uiFrequencyUpdateJob?.cancel()
        uiFrequencyUpdateJob = null
    }
    
    /**
     * Инициализирует MediaSession для обработки кнопок гарнитуры
     */
    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "BinauralPlaybackService").apply {
            // Устанавливаем callback для обработки медиа-кнопок
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    // Toggle: если играет - пауза, если нет - воспроизведение
                    android.util.Log.d("BinauralPlaybackService", "MediaSession: onPlay, isPlaying=${_isPlaying.value}")
                    if (_isPlaying.value) {
                        this@BinauralPlaybackService.pauseWithFade()
                    } else {
                        requestAudioFocus()
                        this@BinauralPlaybackService.resumeWithFade()
                    }
                }
                
                override fun onPause() {
                    // Toggle: если играет - пауза, если нет - воспроизведение
                    android.util.Log.d("BinauralPlaybackService", "MediaSession: onPause, isPlaying=${_isPlaying.value}")
                    if (_isPlaying.value) {
                        this@BinauralPlaybackService.pauseWithFade()
                    } else {
                        requestAudioFocus()
                        this@BinauralPlaybackService.resumeWithFade()
                    }
                }
                
                override fun onStop() {
                    android.util.Log.d("BinauralPlaybackService", "MediaSession: onStop")
                    this@BinauralPlaybackService.stopWithFade()
                    abandonAudioFocus()
                }
                
                override fun onSkipToNext() {
                    // Переключение на следующий пресет
                    android.util.Log.d("BinauralPlaybackService", "MediaSession: onSkipToNext")
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
                    android.util.Log.d("BinauralPlaybackService", "MediaSession: onSkipToPrevious")
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
            
            // Устанавливаем метаданные (название пресета)
            updateMediaMetadata()
            
            // Активируем сессию
            isActive = true
        }
    }
    
    /**
     * Обновляет PlaybackState MediaSession при изменении состояния воспроизведения
     */
    private fun updatePlaybackState(isPlaying: Boolean) {
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
     * Обновляет метаданные MediaSession (название пресета и подзаголовок)
     */
    private fun updateMediaMetadata() {
        val title = _currentPresetName.value ?: getString(R.string.notification_playing)
        
        // Подзаголовок: частоты при воспроизведении, "Пауза" при паузе
        val subtitle = if (_isPlaying.value) {
            if (_currentBeatFrequency.value > 0 && _currentCarrierFrequency.value > 0) {
                getString(
                    R.string.notification_title,
                    _currentBeatFrequency.value,
                    _currentCarrierFrequency.value
                )
            } else {
                _currentPresetName.value ?: getString(R.string.notification_playing)
            }
        } else {
            getString(R.string.notification_paused)
        }
        
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .build()
        )
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
    
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        audioEngine?.updateConfig(config, relaxationSettings)
    }
    
    fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        audioEngine?.updateRelaxationModeSettings(settings)
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

    
    fun getFrequencyUpdateInterval(): Int {
        return audioEngine?.getFrequencyUpdateInterval() ?: 100
    }
    
    fun togglePlayback() {
        if (_isPlaying.value) {
            // Сбрасываем флаг при ручной остановке
            android.util.Log.d("BinauralPlaybackService", "togglePlayback() - stopping, wasStoppedByHeadsetDisconnect = false")
            wasStoppedByHeadsetDisconnect = false
            audioEngine?.stopWithFade()
            abandonAudioFocus()
        } else {
            requestAudioFocus()
            // Сбрасываем флаг при ручном возобновлении
            android.util.Log.d("BinauralPlaybackService", "togglePlayback() - resuming, wasStoppedByHeadsetDisconnect = false")
            wasStoppedByHeadsetDisconnect = false
            audioEngine?.resumeWithFade()
        }
    }
    
    fun play() {
        // Сбрасываем флаг при ручном запуске
        android.util.Log.d("BinauralPlaybackService", "play() - wasStoppedByHeadsetDisconnect = false")
        wasStoppedByHeadsetDisconnect = false
        audioEngine?.play()
    }
    
    fun stop() {
        // Сбрасываем флаг при ручной остановке
        android.util.Log.d("BinauralPlaybackService", "stop() - wasStoppedByHeadsetDisconnect = false")
        wasStoppedByHeadsetDisconnect = false
        audioEngine?.stop()
    }
    
    fun stopWithFade() {
        // Сбрасываем флаг при ручной остановке
        android.util.Log.d("BinauralPlaybackService", "stopWithFade() - wasStoppedByHeadsetDisconnect = false")
        wasStoppedByHeadsetDisconnect = false
        audioEngine?.stopWithFade()
    }
    
    fun pauseWithFade() {
        // Сбрасываем флаг при ручной паузе
        android.util.Log.d("BinauralPlaybackService", "pauseWithFade() - wasStoppedByHeadsetDisconnect = false")
        wasStoppedByHeadsetDisconnect = false
        audioEngine?.pauseWithFade()
    }
    
    fun resumeWithFade() {
        // Сбрасываем флаг при ручном возобновлении
        android.util.Log.d("BinauralPlaybackService", "resumeWithFade() - wasStoppedByHeadsetDisconnect = false")
        wasStoppedByHeadsetDisconnect = false
        audioEngine?.resumeWithFade()
    }
    
    fun switchPresetWithFade(config: BinauralConfig) {
        audioEngine?.switchPresetWithFade(config)
    }
    
    fun setCurrentPresetName(name: String?) {
        _currentPresetName.value = name
        updateMediaMetadata()
        updateNotificationImmediately()
    }
    
    /**
     * Установить список ID пресетов для переключения (next/previous)
     */
    fun setPresetIds(ids: List<String>) {
        presetIds = ids
        // Обновляем PlaybackState для включения/отключения кнопок next/previous
        updatePlaybackState(_isPlaying.value)
    }
    
    /**
     * Установить текущий активный пресет по ID
     */
    fun setCurrentPresetId(id: String?) {
        currentPresetId = id
    }
    
    /**
     * Включить/выключить возобновление воспроизведения при подключении гарнитуры
     */
    fun setResumeOnHeadsetConnect(enabled: Boolean) {
        android.util.Log.d("BinauralPlaybackService", "setResumeOnHeadsetConnect($enabled)")
        resumeOnHeadsetConnect = enabled
        // Если опция выключена, сбрасываем флаг
        if (!enabled) {
            wasStoppedByHeadsetDisconnect = false
        }
    }
    
    /**
     * Приложение на экране - запускаем частое обновление частот (1 сек)
     */
    fun onAppForeground() {
        android.util.Log.d("BinauralPlaybackService", "onAppForeground - starting UI frequency updates")
        startUiFrequencyUpdateJob()
    }
    
    /**
     * Приложение в фоне - останавливаем частое обновление частот
     */
    fun onAppBackground() {
        android.util.Log.d("BinauralPlaybackService", "onAppBackground - stopping UI frequency updates")
        stopUiFrequencyUpdateJob()
    }

    override fun onDestroy() {
        android.util.Log.d("BinauralPlaybackService", "onDestroy()")
        
        serviceInstance = null
        uiFrequencyUpdateJob?.cancel()
        notificationUpdateJob?.cancel()
        unregisterPowerSaveReceiver()
        unregisterNoisyAudioReceiver()
        unregisterAudioDeviceCallback()
        
        // Освобождаем MediaSession
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        
        audioEngine?.release()
        audioEngine = null
        
        abandonAudioFocus()
        serviceScope.cancel()
        
        _isPlaying.value = false
        _currentBeatFrequency.value = 0.0f
        _currentCarrierFrequency.value = 0.0f
        _isChannelsSwapped.value = false
        _elapsedSeconds.value = 0
        
        super.onDestroy()
    }
}