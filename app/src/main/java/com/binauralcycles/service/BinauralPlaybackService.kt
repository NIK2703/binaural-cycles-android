package com.binauralcycles.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.binaural.core.audio.engine.BinauralAudioEngine
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binaural.core.domain.model.BinauralConfig
import com.binaural.core.domain.model.ChannelSwapSettings
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.NormalizationType
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.model.SampleRate
import com.binaural.core.domain.model.VolumeNormalizationSettings
import com.binauralcycles.service.helpers.AudioFocusManager
import com.binauralcycles.service.helpers.HeadsetManager
import com.binauralcycles.service.helpers.MediaSessionManager
import com.binauralcycles.service.helpers.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Сервис для воспроизведения бинауральных ритмов в фоновом режиме.
 * Создаёт и управляет BinauralAudioEngine, который работает в отдельном потоке.
 * 
 * Рефакторинг: бизнес-логика вынесена в helper-классы и Use Cases.
 */
@AndroidEntryPoint
class BinauralPlaybackService : Service() {

    companion object {
        const val ACTION_START = "com.binauralcycles.action.START"
        const val ACTION_STOP = "com.binauralcycles.action.STOP"
        const val ACTION_TOGGLE = "com.binauralcycles.action.TOGGLE"
        const val ACTION_EXIT = "com.binauralcycles.action.EXIT"
        
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

    // Инжектируемые зависимости
    @Inject
    lateinit var playbackStateRepository: PlaybackStateRepository
    
    @Inject
    lateinit var notificationHelper: NotificationHelper
    
    @Inject
    lateinit var headsetManager: HeadsetManager
    
    @Inject
    lateinit var mediaSessionManager: MediaSessionManager
    
    @Inject
    lateinit var audioFocusManager: AudioFocusManager
    
    // Аудио-движок создаётся только в сервисе
    private var audioEngine: BinauralAudioEngine? = null
    
    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // BroadcastReceiver для режима энергосбережения
    private var powerSaveReceiver: android.content.BroadcastReceiver? = null
    
    // Callback для уведомления о переключении пресета
    var onPresetSwitch: ((String) -> Unit)? = null
    
    // Jobs для периодического обновления
    private var notificationUpdateJob: Job? = null
    private var uiFrequencyUpdateJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): BinauralPlaybackService = this@BinauralPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("BinauralPlaybackService", "onCreate()")
        
        // Устанавливаем ссылку на экземпляр сервиса для статических методов
        serviceInstance = this
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Создаём аудио-движок
        audioEngine = BinauralAudioEngine(applicationContext).apply {
            initialize()
        }
        
        // Инициализируем helper-классы
        initializeHelpers(audioManager)
        
        // Создаём notification channel
        notificationHelper.createNotificationChannel(this)
        
        // Запускаем foreground с начальным уведомлением
        startForeground()
        
        // Наблюдаем за состоянием воспроизведения
        observePlaybackState()
        
        // Регистрируем приёмник для режима энергосбережения
        registerPowerSaveReceiver()
        
        // Запускаем ежесекундное обновление частот для UI сразу при создании сервиса.
        // Это гарантирует, что частоты обновляются с момента запуска приложения,
        // даже если onAppForeground() был вызван до установки serviceInstance.
        startUiFrequencyUpdate()
    }
    
    private fun initializeHelpers(audioManager: AudioManager) {
        // Инициализация AudioFocusManager
        audioFocusManager.initialize(audioManager, audioEngine!!)
        audioFocusManager.onAudioFocusLoss = {
            updatePlaybackState(isPlaying = false)
            updateNotification()
        }
        
        // Инициализация HeadsetManager
        headsetManager.initialize(this, audioManager)
        headsetManager.onPausePlayback = {
            audioEngine?.pauseWithFade()
            updatePlaybackState(isPlaying = false)
            updateNotification()
        }
        headsetManager.onResumePlayback = {
            audioFocusManager.requestAudioFocus()
            audioEngine?.resumeWithFade()
        }
        
        // Инициализация MediaSessionManager
        mediaSessionManager.initialize(this)
        mediaSessionManager.onPlay = { audioEngine?.resumeWithFade() }
        mediaSessionManager.onPause = { audioEngine?.pauseWithFade() }
        mediaSessionManager.onStop = {
            audioEngine?.stopWithFade()
            audioFocusManager.abandonAudioFocus()
        }
        mediaSessionManager.onRequestAudioFocus = { audioFocusManager.requestAudioFocus() }
        mediaSessionManager.onAbandonAudioFocus = { audioFocusManager.abandonAudioFocus() }
        mediaSessionManager.onPresetSwitch = { presetId -> onPresetSwitch?.invoke(presetId) }
        
        // Запускаем периодическое обновление для уведомлений
        startNotificationUpdate()
    }
    
    private fun observePlaybackState() {
        serviceScope.launch {
            audioEngine?.isPlaying?.collectLatest { playing ->
                updatePlaybackState(isPlaying = playing)
                mediaSessionManager.updatePlaybackState(playing)
                mediaSessionManager.updateMediaMetadata(this@BinauralPlaybackService)
                updateNotification()
            }
        }
        
        serviceScope.launch {
            audioEngine?.isChannelsSwapped?.collectLatest { swapped ->
                updatePlaybackState(isChannelsSwapped = swapped)
            }
        }
        
        serviceScope.launch {
            audioEngine?.elapsedSeconds?.collectLatest { elapsed ->
                updatePlaybackState(elapsedSeconds = elapsed)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("BinauralPlaybackService", "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> startPlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_TOGGLE -> togglePlayback()
            ACTION_EXIT -> exitApp()
        }
        return START_STICKY
    }
    
    // ========== Lifecycle методы ==========
    
    private fun startForeground() {
        val notification = createCurrentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }
    
    private fun createCurrentNotification(): Notification {
        return notificationHelper.createNotification(
            this,
            BinauralPlaybackService::class.java,
            mediaSessionManager.getSessionToken()
        )
    }
    
    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID, createCurrentNotification())
        } catch (e: Exception) {
            android.util.Log.e("BinauralPlaybackService", "Failed to update notification", e)
        }
    }

    // ========== Управление воспроизведением ==========
    
    fun startPlayback() {
        if (!audioFocusManager.requestAudioFocus()) {
            android.util.Log.w("BinauralPlaybackService", "Could not gain audio focus")
        }
        
        val currentState = playbackStateRepository.playbackState.value
        if (currentState.isPlaying) return
        
        startForeground()
        audioEngine?.play()
        updateNotification()
    }

    fun stopPlayback() {
        audioEngine?.stop()
        audioFocusManager.abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun exitApp() {
        audioEngine?.stopWithFade()
        audioFocusManager.abandonAudioFocus()
        
        // Отправляем Intent в MainActivity для закрытия
        val exitIntent = Intent(this, com.binauralcycles.MainActivity::class.java).apply {
            action = com.binauralcycles.MainActivity.ACTION_EXIT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(exitIntent)
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    // ========== Методы для управления аудио ==========
    
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        audioEngine?.updateConfig(config, relaxationSettings)
    }
    
    fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        audioEngine?.updateRelaxationModeSettings(settings)
    }
    
    /**
     * Обновить глобальные настройки (перестановка каналов, нормализация).
     * Вызывается из SettingsViewModel при изменении настроек с перезапуском воспроизведения.
     */
    fun updateGlobalSettings(
        channelSwapSettings: ChannelSwapSettings,
        volumeNormalizationSettings: VolumeNormalizationSettings
    ) {
        val currentConfig = audioEngine?.currentConfig?.value ?: return
        
        val newConfig = currentConfig.copy(
            channelSwapEnabled = channelSwapSettings.enabled,
            channelSwapIntervalSeconds = channelSwapSettings.intervalSeconds,
            channelSwapFadeEnabled = channelSwapSettings.fadeEnabled,
            channelSwapFadeDurationMs = channelSwapSettings.fadeDurationMs,
            channelSwapPauseDurationMs = channelSwapSettings.pauseDurationMs,
            channelSwapMode = channelSwapSettings.swapMode,
            invertTendencyBehavior = channelSwapSettings.invertTendencyBehavior,
            normalizationType = volumeNormalizationSettings.type,
            volumeNormalizationStrength = volumeNormalizationSettings.strength
        )
        
        audioEngine?.updateConfig(newConfig)
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
        audioEngine?.setFrequencyUpdateInterval(intervalMs)
    }

    fun getFrequencyUpdateInterval(): Int {
        return audioEngine?.getFrequencyUpdateInterval() ?: 100
    }
    
    fun togglePlayback() {
        val currentState = playbackStateRepository.playbackState.value
        if (currentState.isPlaying) {
            headsetManager.resetHeadsetDisconnectFlag()
            audioEngine?.stopWithFade()
            audioFocusManager.abandonAudioFocus()
        } else {
            audioFocusManager.requestAudioFocus()
            headsetManager.resetHeadsetDisconnectFlag()
            audioEngine?.resumeWithFade()
        }
    }
    
    fun play() {
        headsetManager.resetHeadsetDisconnectFlag()
        audioEngine?.play()
    }
    
    fun stop() {
        headsetManager.resetHeadsetDisconnectFlag()
        audioEngine?.stop()
    }
    
    fun stopWithFade() {
        headsetManager.resetHeadsetDisconnectFlag()
        audioEngine?.stopWithFade()
    }
    
    fun pauseWithFade() {
        headsetManager.resetHeadsetDisconnectFlag()
        audioEngine?.pauseWithFade()
    }
    
    fun resumeWithFade() {
        headsetManager.resetHeadsetDisconnectFlag()
        audioEngine?.resumeWithFade()
    }
    
    fun switchPresetWithFade(config: BinauralConfig) {
        audioEngine?.switchPresetWithFade(config)
    }
    
    fun setCurrentPresetName(name: String?) {
        updatePlaybackState(presetName = name)
        mediaSessionManager.updateMediaMetadata(this)
        updateNotification()
    }
    
    fun setPresetIds(ids: List<String>) {
        mediaSessionManager.setPresetIds(ids)
    }
    
    fun setCurrentPresetId(id: String?) {
        mediaSessionManager.setCurrentPresetId(id)
        updatePlaybackState(presetId = id)
    }
    
    fun setResumeOnHeadsetConnect(enabled: Boolean) {
        headsetManager.setResumeOnHeadsetConnect(enabled)
    }
    
    fun onAppForeground() {
        android.util.Log.d("BinauralPlaybackService", "onAppForeground - starting UI frequency updates")
        startUiFrequencyUpdate()
    }
    
    fun onAppBackground() {
        android.util.Log.d("BinauralPlaybackService", "onAppBackground - stopping UI frequency updates")
        stopUiFrequencyUpdate()
    }
    
    // ========== Периодическое обновление частот ==========
    
    private fun startNotificationUpdate() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            while (isActive) {
                delay(10_000) // Каждые 10 секунд
                updateFrequenciesAndNotify()
            }
        }
    }
    
    private fun stopNotificationUpdate() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }
    
    private fun startUiFrequencyUpdate() {
        uiFrequencyUpdateJob?.cancel()
        uiFrequencyUpdateJob = serviceScope.launch {
            while (isActive) {
                delay(1000) // Каждую секунду
                updateFrequenciesAndNotify()
            }
        }
    }
    
    private fun stopUiFrequencyUpdate() {
        uiFrequencyUpdateJob?.cancel()
        uiFrequencyUpdateJob = null
    }
    
    private fun updateFrequenciesAndNotify() {
        val currentState = playbackStateRepository.playbackState.value
        if (currentState.isPlaying) {
            // O(1) получение частот из lookup table
            audioEngine?.updateCurrentFrequencies()
            
            // Обновляем состояние в repository
            val beatFreq = audioEngine?.currentBeatFrequency?.value ?: 0f
            val carrierFreq = audioEngine?.currentCarrierFrequency?.value ?: 0f
            
            updatePlaybackState(beatFrequency = beatFreq, carrierFrequency = carrierFreq)
            
            // Обновляем метаданные и уведомление
            if (beatFreq > 0) {
                mediaSessionManager.updateMediaMetadata(this)
                notificationHelper.updateNotificationSilently(
                    this,
                    getSystemService(android.app.NotificationManager::class.java),
                    mediaSessionManager.getSessionToken()
                )
            }
        }
    }
    
    // ========== Вспомогательные методы ==========
    
    private fun updatePlaybackState(
        isPlaying: Boolean? = null,
        beatFrequency: Float? = null,
        carrierFrequency: Float? = null,
        isChannelsSwapped: Boolean? = null,
        elapsedSeconds: Int? = null,
        presetName: String? = null,
        presetId: String? = null
    ) {
        playbackStateRepository.updateState { state ->
            state.copy(
                isPlaying = isPlaying ?: state.isPlaying,
                currentBeatFrequency = beatFrequency ?: state.currentBeatFrequency,
                currentCarrierFrequency = carrierFrequency ?: state.currentCarrierFrequency,
                isChannelsSwapped = isChannelsSwapped ?: state.isChannelsSwapped,
                elapsedSeconds = elapsedSeconds ?: state.elapsedSeconds,
                currentPresetName = presetName ?: state.currentPresetName,
                currentPresetId = presetId ?: state.currentPresetId
            )
        }
    }
    
    private fun registerPowerSaveReceiver() {
        powerSaveReceiver = object : android.content.BroadcastReceiver() {
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

    override fun onDestroy() {
        android.util.Log.d("BinauralPlaybackService", "onDestroy()")
        
        // Очищаем ссылку на экземпляр сервиса
        serviceInstance = null
        
        // Останавливаем периодические обновления
        stopNotificationUpdate()
        stopUiFrequencyUpdate()
        unregisterPowerSaveReceiver()
        
        // Освобождаем helper-классы
        headsetManager.release(this)
        mediaSessionManager.release()
        audioFocusManager.release()
        
        audioEngine?.release()
        audioEngine = null
        
        audioFocusManager.abandonAudioFocus()
        serviceScope.cancel()
        
        // Сбрасываем состояние в repository
        playbackStateRepository.reset()
        
        super.onDestroy()
    }
}