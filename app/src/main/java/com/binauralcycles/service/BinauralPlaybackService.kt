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
import com.binauralcycles.BuildConfig
import com.binauralcycles.MainActivity
import com.binauralcycles.R
import com.binaural.core.audio.stream.BinauralStreamManager
import com.binaural.core.audio.stream.ManagerState
import com.binaural.core.audio.model.SampleRate
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
 * Создаёт и управляет `BinauralStreamManager` (фасад над пулом потоков),
 * который работает в отдельном потоке.
 */
class BinauralPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "binaural_playback_channel"
        const val NOTIFICATION_ID = 1001

        /**
         * Интервал обновления уведомления в фоне (мс).
         * Текст уведомления имеет гранулярность "%.1f Гц | %.0f Гц", поэтому
         * 30 с вместо 10 с не ухудшают восприятие, но сокращают число
         * пробуждений CPU втрое.
         */
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 30_000L
        
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
        
        // НОВОЕ: текущее время суток (для UI-индикатора), виртуальное в debug
        private val _currentTimeOfDaySeconds = MutableStateFlow(0)
        val currentTimeOfDaySeconds: StateFlow<Int> = _currentTimeOfDaySeconds.asStateFlow()
        
        // НОВОЕ: включён ли debug-режим виртуального времени
        private val _debugTimeEnabled = MutableStateFlow(false)
        
        private val _currentPresetName = MutableStateFlow<String?>(null)
        val currentPresetName: StateFlow<String?> = _currentPresetName.asStateFlow()
        
        // Ссылка на экземпляр сервиса для статических методов
        @Volatile
        private var serviceInstance: BinauralPlaybackService? = null

        /**
         * Живой экземпляр сервиса или `null`.
         *
         * `null` — это норма, а не ошибка: сервис `START_STICKY`, но сам себя
         * останавливает (`stopSelf()`) при остановке воспроизведения. Поэтому
         * вызыватель обязан уметь работать и без экземпляра — запускать сервис
         * интентами [ACTION_START]/[ACTION_STOP] и читать состояние из
         * статических [StateFlow] выше, которые переживают сам сервис.
         */
        internal val liveInstance: BinauralPlaybackService? get() = serviceInstance
        
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
    private var audioEngine: BinauralStreamManager? = null
    
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
    
    // Флаг: воспроизведение приостановлено из-за временной потери аудиофокуса
    private var wasPausedByTransientFocus: Boolean = false
    
    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Интервал обновления частот (из настроек)
    private val _frequencyUpdateIntervalMs = MutableStateFlow(600_000) // По умолчанию 10 минут
    
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (wasPausedByTransientFocus && !_isPlaying.value) {
                    wasPausedByTransientFocus = false
                    audioEngine?.resumeWithFade()
                }
                audioEngine?.setVolume(audioEngine?.currentConfig?.value?.volume ?: 1.0f)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Другой экземпляр (или другое приложение) забрало фокус.
                // Обязаны остановиться, чтобы не играть одновременно —
                // иначе слышны «скачки частоты» от смешивания двух потоков.
                android.util.Log.w(
                    "BinauralPlaybackService",
                    "AUDIOFOCUS_LOSS: stopping playback (another app/instance took focus)"
                )
                hasAudioFocus = false
                audioEngine?.stop()
                // Обновляем notification только при изменении состояния
                _isPlaying.value = false
                updateNotificationImmediately()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Временная потеря фокуса (звонок, навигатор) - пауза с затуханием
                if (_isPlaying.value) {
                    android.util.Log.d(
                        "BinauralPlaybackService",
                        "AUDIOFOCUS_LOSS_TRANSIENT: pausing playback"
                    )
                    audioEngine?.pauseWithFade()
                    _isPlaying.value = false
                    updateNotificationImmediately()
                    wasPausedByTransientFocus = true
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Приглушение: временно снижаем громкость,
                // восстановление - в AUDIOFOCUS_GAIN
                val volume = audioEngine?.currentConfig?.value?.volume ?: 1.0f
                audioEngine?.setVolume(volume * 0.2f)
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
        audioEngine = BinauralStreamManager(applicationContext).apply {
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
                // Фоновый джоб уведомления нужен только во время воспроизведения:
                // на паузе текст статичен ("Пауза") и обновлять его нечего.
                if (playing) {
                    startNotificationUpdateJob()
                } else {
                    stopNotificationUpdateJob()
                }
            }
        }
        
        // ВНИМАНИЕ: Частоты НЕ обновляем через collectLatest из audioEngine!
        // Это вызывало мерцание некорректных значений при старте/смене пресета.
        // Частоты обновляются только через updateCurrentFrequencies() в:
        // - startUiFrequencyUpdateJob() - каждую секунду (когда приложение на экране)
        // - startNotificationUpdateJob() - каждые 30 секунд, только при воспроизведении
        //   и только при включённом экране
        
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
        
        // Периодическое обновление notification НЕ запускается здесь:
        // оно стартует/останавливается вместе с воспроизведением (см. коллектор
        // audioEngine.isPlaying выше), а не крутится вечно от onCreate().
        
        // Запускаем ежесекундное обновление частот для UI сразу при создании сервиса.
        // Это гарантирует, что частоты обновляются с момента запуска приложения,
        // даже если onAppForeground() был вызван до установки serviceInstance.
        //
        // НО только при включённом экране. Этот джоб останавливается лишь из
        // onAppBackground(), а сервис — sticky: если система пересоздаст его,
        // пока приложение в фоне, onAppBackground() уже не придёт никогда, и без
        // этой проверки цикл крутился бы 1 Гц при выключенном экране до конца
        // жизни процесса (3600 итераций/час: JNI-опрос частот + сборка
        // NotificationContent/MetadataContent для дедупликации). Дублирующий
        // guard внутри цикла — на случай, если экран выключили уже после старта.
        if (isScreenInteractive()) {
            startUiFrequencyUpdateJob()
        }
        
        // Регистрируем приёмник для режима энергосбережения
        registerPowerSaveReceiver()
        
        // Регистрируем приёмники для отслеживания отключения гарнитуры
        registerNoisyAudioReceiver()
        registerScreenStateReceiver()
        registerAudioDeviceCallback()
        
        // Инициализируем MediaSession для обработки кнопок гарнитуры
        initializeMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("BinauralPlaybackService", "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                // Защита от двойного запуска: если уже играем — игнорируем повторный START,
                // чтобы второй экземпляр/интент не создал второй аудио-поток.
                if (_isPlaying.value) {
                    android.util.Log.w(
                        "BinauralPlaybackService",
                        "onStartCommand: already playing, ignoring duplicate ACTION_START"
                    )
                } else {
                    startPlayback()
                }
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

        val content = currentNotificationContent()

        val playPauseIcon = if (_isPlaying.value) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (_isPlaying.value) getString(R.string.action_pause) else getString(R.string.action_play)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.text)
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
     * Содержимое уведомления, которое реально видно пользователю.
     * Сравнивается с предыдущим значением, чтобы не дергать system_server зря.
     *
     * Текст форматируется как "Биения: %.1f Гц | Несущая: %.0f Гц", поэтому
     * дробный шум частот (движок обновляет их каждую секунду) всё равно
     * схлопывается в одну строку — реально текст меняется раз в секунды/минуты,
     * а не раз в секунду.
     */
    private data class NotificationContent(
        val title: String,
        val text: String,
        val playing: Boolean
    )

    /**
     * Вычисляет текущее содержимое уведомления по состоянию сервиса.
     * Единая точка истины для notification и для MediaSession metadata.
     */
    private fun currentNotificationContent(): NotificationContent {
        val title = _currentPresetName.value ?: getString(R.string.notification_playing)
        val text = if (_isPlaying.value) {
            // Показываем частоты только если они установлены (не 0).
            // Частота биений — величина ЗНАКОВАЯ, поэтому проверяем именно «не 0»,
            // а не «больше нуля»: иначе отрицательная частота биений скрывала бы
            // показания. Несущая — физический тон, она всегда > 0.
            if (_currentBeatFrequency.value != 0.0f && _currentCarrierFrequency.value > 0) {
                getString(
                    R.string.notification_title,
                    _currentBeatFrequency.value,
                    _currentCarrierFrequency.value
                )
            } else {
                // Если частоты ещё не установлены - показываем название пресета
                title
            }
        } else {
            getString(R.string.notification_paused)
        }
        return NotificationContent(title, text, _isPlaying.value)
    }

    // Замок для кэша последнего опубликованного уведомления: джобы обновления
    // частот работают на Dispatchers.Default, а смена состояния — из
    // audioFocus/receiver-колбэков, то есть из разных потоков.
    private val notificationLock = Any()

    // Последнее реально отправленное в NotificationManager содержимое.
    private var lastNotifiedContent: NotificationContent? = null

    /**
     * Публикует уведомление, если его содержимое изменилось.
     *
     * `NotificationManager.notify()` — это binder-транзакция, которая будит
     * system_server и SystemUI, плюс полная пересборка Notification с тремя
     * PendingIntent и MediaStyle. До оптимизации это происходило 1 раз в секунду
     * (3600 раз в час) круглосуточно, даже когда текст не менялся.
     *
     * @param force публиковать без сравнения (смена play/pause, старт, остановка)
     * @return true, если уведомление действительно было отправлено
     */
    private fun publishNotification(force: Boolean): Boolean {
        val content = currentNotificationContent()
        synchronized(notificationLock) {
            if (!force && content == lastNotifiedContent) return false
            lastNotifiedContent = content
        }
        return try {
            val notification = createNotification()
            // Используем флаг FLAG_ONLY_ALERT_ONCE через setSilent (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                notification.extras.putBoolean("android.silent", true)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            android.util.Log.e("BinauralPlaybackService", "Failed to update notification", e)
            // Сбрасываем кэш: публикация не удалась, следующая попытка должна пройти
            synchronized(notificationLock) { lastNotifiedContent = null }
            false
        }
    }

    /**
     * Немедленное обновление notification (только для изменения состояния воспроизведения)
     */
    private fun updateNotificationImmediately() {
        publishNotification(force = true)
    }

    /**
     * Тихое обновление notification: публикует, только если текст реально изменился.
     * Используется из периодических джоб, тикающих раз в секунду.
     */
    private fun updateNotificationSilently() {
        publishNotification(force = false)
    }

    // Job для периодического обновления уведомления
    private var notificationUpdateJob: Job? = null
    
    // Job для обновления частот в UI (каждую секунду)
    private var uiFrequencyUpdateJob: Job? = null
    
    // BroadcastReceiver для режима энергосбережения
    private var powerSaveReceiver: BroadcastReceiver? = null
    
    // BroadcastReceiver для отключения гарнитуры (ACTION_AUDIO_BECOMING_NOISY)
    private var noisyAudioReceiver: BroadcastReceiver? = null

    // Кэш состояния экрана (ACTION_SCREEN_ON / ACTION_SCREEN_OFF), чтобы не делать
    // binder-IPC isInteractive каждую секунду из циклов обновления UI/уведомлений (U2).
    private var screenStateReceiver: BroadcastReceiver? = null
    private var isScreenOn: Boolean = true
    
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
     * Регистрирует приёмник ACTION_SCREEN_ON/ACTION_SCREEN_OFF, кэширующий состояние
     * экрана (U2). Без него isScreenInteractive() делал бы binder-IPC (PowerManager
     * .isInteractive) каждую секунду из циклов обновления UI и уведомлений — лишнее
     * пробуждение CPU ради значения, которое меняется крайне редко.
     */
    private fun registerScreenStateReceiver() {
        if (screenStateReceiver != null) return
        // Инициализируем текущим состоянием, чтобы не делать IPC в момент регистрации.
        isScreenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> isScreenOn = true
                    Intent.ACTION_SCREEN_OFF -> isScreenOn = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenStateReceiver, filter)
        }
    }

    private fun unregisterScreenStateReceiver() {
        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                android.util.Log.e("BinauralPlaybackService", "Error unregistering screen state receiver", e)
            }
        }
        screenStateReceiver = null
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
     *
     * Джоб существует ТОЛЬКО во время воспроизведения: раньше он стартовал в
     * onCreate() и крутился бесконечно (while(true) с delay(10с)), просыпаясь
     * 360 раз в час даже на паузе. Теперь старт/стоп привязаны к состоянию
     * audioEngine.isPlaying (см. onCreate).
     *
     * Интервал 30 секунд: текст уведомления имеет гранулярность 0.1 Гц / 1 Гц,
     * за 30 секунд кривая пресета успевает уйти далеко не на одну десятую —
     * частота обновления, заметная пользователю, не страдает.
     */
    private fun startNotificationUpdateJob() {
        if (notificationUpdateJob?.isActive == true) return
        notificationUpdateJob?.cancel()
        android.util.Log.d("BinauralPlaybackService", "startNotificationUpdateJob")
        notificationUpdateJob = serviceScope.launch {
            while (true) {
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
                if (!_isPlaying.value) continue
                // Экран выключен — уведомление никто не читает, а пробуждение
                // CPU ради binder-транзакции в system_server стоит батареи.
                if (!isScreenInteractive()) continue
                // O(1) получение частот из lookup table
                audioEngine?.updateCurrentFrequencies()
                // Копируем значения из audioEngine в сервис для UI
                audioEngine?.currentBeatFrequency?.value?.let { _currentBeatFrequency.value = it }
                audioEngine?.currentCarrierFrequency?.value?.let { _currentCarrierFrequency.value = it }
                // Обновляем уведомление с актуальными частотами.
                // updateMediaMetadata()/updateNotificationSilently() сами
                // отсекают публикацию, если текст не изменился.
                // Частота биений знаковая — sentinel «не 0», а не «> 0».
                if (_currentBeatFrequency.value != 0.0f) {
                    updateMediaMetadata()
                    updateNotificationSilently()
                }
            }
        }
    }

    private fun stopNotificationUpdateJob() {
        if (notificationUpdateJob?.isActive != true) {
            notificationUpdateJob = null
            return
        }
        android.util.Log.d("BinauralPlaybackService", "stopNotificationUpdateJob")
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }

    // U2: возвращает кэш состояния экрана вместо binder-IPC isInteractive.
    // Кэш обновляется приёмником ACTION_SCREEN_ON/OFF (см. registerScreenStateReceiver).
    private fun isScreenInteractive(): Boolean = isScreenOn
    
    /**
     * Запускает периодическое обновление частот в UI (каждую секунду).
     * Работает только когда приложение на экране (не в фоне).
     *
     * O(1) операция: использует предвычисленную lookup table в C++ движке.
     * Это позволяет отображать актуальные частоты каждую секунду,
     * даже если генерация буфера происходит каждые N минут.
     */
    private fun startUiFrequencyUpdateJob() {
        uiFrequencyUpdateJob?.cancel()
        uiFrequencyUpdateJob = serviceScope.launch {
            while (true) {
                delay(1000) // Каждую секунду
                // Экран выключен — UI никто не видит. Пробуждение CPU ради опроса
                // частот и сборки контента уведомления стоит батареи, а толку ноль.
                // Guard обязателен: джоб останавливается только из onAppBackground(),
                // который при пересоздании sticky-сервиса в фоне не вызывается.
                if (!isScreenInteractive()) continue
                // Время суток (реальное/виртуальное) обновляем всегда,
                // чтобы указатель времени на экране был актуальным даже без воспроизведения.
                audioEngine?.updateCurrentFrequencies()
                audioEngine?.currentTimeOfDaySeconds?.value?.let { _currentTimeOfDaySeconds.value = it }
                // Частоты обновляем только при воспроизведении или включённом debug-режиме времени
                if (_isPlaying.value || _debugTimeEnabled.value) {
                    // Копируем значения из audioEngine в сервис для UI
                    audioEngine?.currentBeatFrequency?.value?.let { _currentBeatFrequency.value = it }
                    audioEngine?.currentCarrierFrequency?.value?.let { _currentCarrierFrequency.value = it }
                    // Обновляем уведомление с актуальными частотами.
                    // Частота биений знаковая — sentinel «не 0», а не «> 0».
                    if (_currentBeatFrequency.value != 0.0f) {
                        updateMediaMetadata()
                        updateNotificationSilently()
                    }
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
    // Последние метаданные, отправленные в MediaSession.
    private var lastMetadataContent: NotificationContent? = null

    private fun updateMediaMetadata() {
        val content = currentNotificationContent()
        // setMetadata() рассылает изменение всем подписчикам MediaSession
        // (SystemUI, Wear OS, Android Auto) — вызывать её раз в секунду ради
        // неизменившегося текста слишком дорого.
        synchronized(notificationLock) {
            if (content == lastMetadataContent) return
            lastMetadataContent = content
        }

        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, content.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, content.text)
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

    // ============ Debug virtual time ============

    fun debugSetVirtualTimeEnabled(enabled: Boolean) {
        audioEngine?.debugSetVirtualTimeEnabled(enabled)
        _debugTimeEnabled.value = enabled
    }

    fun debugScrub(timeSeconds: Int) {
        if (!BuildConfig.DEBUG) return
        audioEngine?.debugScrub(timeSeconds)
        _currentTimeOfDaySeconds.value = timeSeconds
    }

    fun debugSetTimeScale(scale: Float) {
        audioEngine?.debugSetTimeScale(scale)
    }

    fun debugSetRunning(running: Boolean) {
        audioEngine?.debugSetRunning(running)
    }

    fun debugResetToRealTime() {
        audioEngine?.debugResetToRealTime()
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

    /**
     * Состояние актёра звукового менеджера — только для диагностики
     * (отладочный командный интерфейс). null, пока движок не создан.
     */
    fun managerState(): ManagerState? = audioEngine?.managerState?.value

    /** СЛЫШИМАЯ позиция кривой (секунды суток) — для диагностики (debug-CLI `audible`). */
    fun audibleTimeOfDaySeconds(): Int = audioEngine?.getAudibleTimeOfDaySeconds() ?: 0

    /**
     * СЛЫШИМАЯ позиция БЕЗ компенсации пропуска — РЕАЛЬНОЕ то, что звучит
     * сейчас (debug-CLI `audibleraw`). Отличается от [audibleTimeOfDaySeconds]
     * на величину переходной задержки кольца трека после мягкого возобновления.
     */
    fun audibleTimeOfDaySecondsRaw(): Int = audioEngine?.getAudibleTimeOfDaySecondsRaw() ?: 0

    /** Последний снимок решателя возобновления (debug-CLI `resumesnap`). */
    fun resumeAccuracyReport(): String? = audioEngine?.getResumeAccuracyReport()

    /**
     * Проверка инварианта «слышимая позиция == сейчас» по требованию
     * (debug-CLI `invcheck`). Фоновый сторож тикает сам и пишет в лог; эта
     * команда — разовый снимок для ручного разбора.
     */
    fun invariantCheck(): String = audioEngine?.checkInvariantNow() ?: "Менеджер не создан"

    /**
     * ФРОНТИР ГЕНЕРАЦИИ (секунды суток) — для диагностики (debug-CLI `audible`):
     * конец уже посчитанного аудио, правая граница окна актуальности пакета.
     */
    fun frontierTimeOfDaySeconds(): Int = audioEngine?.getFrontierTimeOfDaySeconds() ?: 0
    
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
        unregisterScreenStateReceiver()
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
        _currentTimeOfDaySeconds.value = 0
        _debugTimeEnabled.value = false
        
        super.onDestroy()
    }
}