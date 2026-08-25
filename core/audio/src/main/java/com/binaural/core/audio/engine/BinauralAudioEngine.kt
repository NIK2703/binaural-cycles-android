package com.binaural.core.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeShaper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Частота дискретизации аудио
 */
enum class SampleRate(val value: Int) {
    ULTRA_LOW(8000),
    VERY_LOW(16000),
    LOW(22050),
    MEDIUM(44100),
    HIGH(48000);
    
    companion object {
        fun fromValue(value: Int): SampleRate = entries.find { it.value == value } ?: MEDIUM
    }
}

/**
 * Движок для генерации и воспроизведения бинауральных ритмов.
 * Работает в отдельном потоке (HandlerThread) для исключения задержек в UI.
 * 
 * АРХИТЕКТУРА:
 * - Генерация аудио делегируется в NativeAudioEngine (C++)
 * - Этот класс управляет AudioTrack и VolumeShaper для воспроизведения
 * - VolumeShaper обеспечивает плавные переходы при старте/остановке
 */
class BinauralAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "BinauralAudioEngine"
        // Запас 3 секунды (фикс Qwen, P3): генерация следующего пакета занимает
        // ~30 мс CPU-спайком; при буфере в 1 с любой спайк/GC со стороны другого
        // процесса или системы даёт underrun на границе пакетов (слышимый стык).
        private const val BUFFER_SIZE_MS = 3000
        private const val WAKE_LOCK_TAG = "BinauralBeats:PlaybackWakeLock"
        private const val THREAD_NAME = "BinauralAudioThread"
        private const val MIN_VOLUME = 0.001f
        // ЕДИНАЯ длительность всех программных фейдов: старт/resume (вход),
        // стоп/пауза (выход), догоняющий подъём после отменённой паузы,
        // duck/unduck и микро-рамп громкости. Вход == выход по длительности.
        // (Фейды автоперестановки каналов настраиваются отдельно в нативном
        // движке — channelSwapFadeDurationMs — и сюда не входят.)
        private const val FADE_DURATION_MS = 250L
        // Порог «скачка» громкости, требующего рампы вместо ступеньки
        private const val VOLUME_JUMP_THRESHOLD = 0.08f
        // Целевой уровень единичного множителя шейпера поверх базы трека
        private const val FADE_IDENTITY = 1.0f
        // |старт-цель| меньше порога = рамп неслышим, живой шейпер НЕ трогаем
        private const val FADE_EQUALITY_EPSILON = 0.01f
        // Запас сверх рампа до отложенного закрытия (доехать до 1.0 в микшере)
        private const val SHAPER_CLOSE_MARGIN_MS = 50L

        // Множитель интервала при Battery Saver (3x = 30 сек вместо 10 сек)
        private const val POWER_SAVE_INTERVAL_MULTIPLIER = 3

        // C2: максимальный размер буфера в минутах — разрешённый UI диапазон 1-60 мин
        private const val MAX_BUFFER_MINUTES = 60

        // C2: жёсткий байтовый кап на direct-буфер (применяется и в старте, и при реаллокации).
        // При 48000 Гц стерео float: 8 байт/с на сэмпл-канал => ~11.6 мин аудио
        private const val MAX_BUFFER_BYTES = 256 * 1024 * 1024

        // Токен для отмены callbacks при переключении частоты дискретизации
        private val RESTART_PLAYBACK_TOKEN = Any()

        // Выделенный токен отложенного освобождения умирающего AudioTrack:
        // переживает точечные чистки очереди (null-чистки больше не применяем)
        private val RELEASE_DYING_TRACK_TOKEN = Any()
    }
    
    // Атомарные ссылки для потокобезопасного доступа
    private val configRef = AtomicReference(BinauralConfig())
    private val isActive = AtomicBoolean(false)
    private val pendingSampleRate = AtomicReference<SampleRate?>(null)
    private val pendingFrequencyUpdateIntervalMs = AtomicReference<Int?>(null)

    // Запросы на операции с fade (потокобезопасные)
    private val stopWithFadeRequested = AtomicBoolean(false)
    private val pauseWithFadeRequested = AtomicBoolean(false)
    private val presetSwitchRequested = AtomicReference<BinauralConfig?>(null)

    // C7: запрос на перестроение нативной конфигурации после живого редактирования кривой
    private val pendingCurveUpdate = AtomicBoolean(false)

    // C7: последние применённые настройки релаксации — нужны для корректной
    // развёртки виртуальных точек при отложенном применении кривой
    @Volatile
    private var lastRelaxationSettings = RelaxationModeSettings()

    // Пресет, ожидающий применения через fade-перезапуск (см. checkFadeRequests)
    @Volatile
    private var pendingPresetConfig: BinauralConfig? = null

    // Флаг для отслеживания запланированной операции перезапуска после смены частоты
    @Volatile
    private var restartPlaybackScheduled = false

    // Токен для debounce операций переключения частоты дискретизации
    private val SAMPLE_RATE_CHANGE_TOKEN = Any()

    // Текущие настройки
    private var sampleRate: Int = SampleRate.MEDIUM.value
    private var frequencyUpdateIntervalMs: Int = 600_000

    // C4: последний пользовательский интервал (для восстановления после debug-времени)
    @Volatile
    private var lastUserIntervalMs: Int = 600_000

    // C4: включён ли debug-виртуальный таймлайн (интервал форсируется в 250 мс)
    @Volatile
    private var debugVirtualTimeEnabled = false

    // DirectByteBuffer для zero-copy генерации
    // Запись в AudioTrack выполняется порциями не больше audioTrackBufferSize
    private var directAudioBuffer: java.nio.ByteBuffer? = null
    private var audioTrackBufferSize = 0  // Размер буфера AudioTrack в байтах

    // AudioTrack
    private var audioTrack: AudioTrack? = null

    // HandlerThread для генерации аудио
    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    @Volatile
    private var isGenerating = false

    // WakeLock для предотвращения засыпания (K5: volatile — доступ из 3 потоков)
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    // VolumeShaper для плавного изменения громкости при старте/остановке
    @Volatile
    private var volumeShaper: VolumeShaper? = null
    
    // Громкость, установленная пользователем (0.0 - 1.0)
    // Сохраняется между сессиями воспроизведения и не изменяется при fade
    private var userVolume: Float = 1.0f

    // ===== Универсальный фейд-механизм (столп 1) =====
    // Живой шейпер - ЕДИНСТВЕННЫЙ источник текущего уровня (VolumeShaper.volume
    // интерполирует в реальном времени). Софтверная интерполяция и двойная
    // бухгалтерия currentVolume удалены как источник щелчков.

    // Блокировка слота volumeShaper: инсталлы приходят с main/Default/аудио.
    // Внутри лока запрещены sleep - только свопы ссылок.
    private val shaperLock = Any()

    // Атомарный снимок активного рампа (читатель видит старый или новый целиком).
    private class ActiveFade(
        @JvmField val startLevel: Float,
        @JvmField val targetLevel: Float,
        @JvmField val startedAtMs: Long,
        @JvmField val durationMs: Long,
    )

    @Volatile
    private var activeFade: ActiveFade? = null

    @Volatile
    private var isFadeInProgress: Boolean = false

    // ЭПОХА: каждый успешный инсталл увеличивает; отложенное закрытие действует
    // только при совпадении поколений (stale-дедлайн не убьёт свежий шейпер).
    private val fadeGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    // Отложенное закрытие (дедлайн, поколение); только для инсталлов на 1.0.
    @Volatile
    private var pendingShaperCloseDeadlineMs: Long = 0L
    @Volatile
    private var pendingShaperCloseGeneration: Long = 0L

    // ===== Протокол владения сессией (столп 2) =====
    // Инкремент РОВНО ОДИН РАЗ в каждой точке мутации playback-состояния.
    // AtomicLong: ++ должен быть атомарным RMW (bump с Main и Default).
    private val transitionGen = java.util.concurrent.atomic.AtomicLong(0L)

    // Поколение СОБСТВЕННОЙ сессии: терминальные сбросы старого кадра цикла
    // (stopPlayback/cleanupPlayback) не имеют права убивать уже перерождённую
    // сессию (RC1: isGenerating=false выставляется ДО медленного cleanup,
    // и старт, вклинившийся в этот зазор, оглушался его слепыми reset'ами).
    @Volatile
    private var activeSessionGen: Long = -1L

    // Ворота bypass-teardown: глубина стека активных fadeOutStopBlocking.
    // Быстрые тапы наслаивают вызовы; булев флаг первого finally ронял
    // защиту, пока вторые ещё находились внутри.
    private val teardownDepth = java.util.concurrent.atomic.AtomicInteger(0)

    // Анти-щелчок: штамп завершения последнего инлайн fade-out. Внешние
    // оркестрации (смена пресета, restart настроек) ждут его изменения вместо
    // слепого delay(300), который гонится с фактической длительностью фейда.
    @Volatile
    var lastFadeOutCompletedAtMs: Long = 0L
        private set

    // Анти-щелчок: активен duck (CAN_DUCK / смена аудио-устройства).
    @Volatile
    private var isDucked: Boolean = false

    // Время начала воспроизведения
    private var playbackStartTime = 0L
    
    // Накопленное время для pause/resume
    private var accumulatedElapsedMs = 0L

    // Нативный движок (C++) - всегда используется для генерации аудио
    private var nativeEngine: NativeAudioEngine? = null

    // StateFlows для UI
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentConfig = MutableStateFlow(BinauralConfig())
    val currentConfig: StateFlow<BinauralConfig> = _currentConfig.asStateFlow()

    private val _currentBeatFrequency = MutableStateFlow(0.0f)
    val currentBeatFrequency: StateFlow<Float> = _currentBeatFrequency.asStateFlow()

    private val _currentCarrierFrequency = MutableStateFlow(0.0f)
    val currentCarrierFrequency: StateFlow<Float> = _currentCarrierFrequency.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
    
    // НОВОЕ: текущее время суток (реальное или виртуальное в debug)
    private val _currentTimeOfDaySeconds = MutableStateFlow(0)
    val currentTimeOfDaySeconds: StateFlow<Int> = _currentTimeOfDaySeconds.asStateFlow()
    
    private val _isChannelsSwapped = MutableStateFlow(false)
    val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()

    /**
     * Получить текущие частоты по текущему времени суток.
     * O(1) операция - использует предвычисленную lookup table в C++.
     * @return Pair(beatFrequency, carrierFrequency) или null если конфиг не установлен
     */
    fun getFrequenciesAtCurrentTime(): Pair<Float, Float>? {
        return nativeEngine?.getFrequenciesAtCurrentTime()
    }
    
    /**
     * Обновить текущие частоты из lookup table.
     * Вызывается периодически из UI для отображения актуальных частот.
     */
    fun updateCurrentFrequencies() {
        val result = getFrequenciesAtCurrentTime()
        if (result != null) {
            _currentBeatFrequency.value = result.first
            _currentCarrierFrequency.value = result.second
        }
        // НОВОЕ: время суток (реальное или виртуальное из нативного движка)
        nativeEngine?.let {
            _currentTimeOfDaySeconds.value = it.getCurrentTimeOfDay()
        }
    }

    /**
     * Инициализация движка. Должна вызываться один раз при создании.
     */
    fun initialize() {
        // THREAD_PRIORITY_AUDIO (-16) обеспечивает наилучший приоритет для аудио-потока
        // Это предотвращает задержки и прерывания при генерации звука
        audioThread = HandlerThread(THREAD_NAME, android.os.Process.THREAD_PRIORITY_AUDIO).apply { start() }
        audioHandler = Handler(audioThread!!.looper)
        
        // Инициализируем нативный движок
        nativeEngine = NativeAudioEngine()
        nativeEngine?.initialize()
        nativeEngine?.setSampleRate(sampleRate)

        // Отпечаток версии: позволяет убедиться, что на устройстве НОВАЯ сборка
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                     else @Suppress("DEPRECATION") pi.versionCode.toLong()
            Log.i(TAG, "ENGINE_INIT: pkg=${context.packageName} ver=${pi.versionName} code=$vc")
        }

        Log.d(TAG, "Audio engine initialized on thread: ${audioThread?.name}")
    }

    /**
     * Обновить конфигурацию (потокобезопасно)
     */
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        // Диагностика стыков: кто подменяет конфиг во время воспроизведения?
        // Стек покажет триггер (relaxation / Flow / UI), если частоты скачут.
        if (isActive.get()) {
            Log.w(TAG, "updateConfig() while playing", Throwable("updateConfig stacktrace"))
        }
        configRef.set(config)
        _currentConfig.value = config
        lastRelaxationSettings = relaxationSettings
        // ВАЖНО: НЕ обновляем userVolume из config.volume!
        // userVolume управляется ТОЛЬКО через setVolume() от слайдера пользователя.
        // VolumeShaper используется исключительно для плавного затухания/восстановления
        // и не должен менять базовую громкость воспроизведения.
        // config.volume используется только при сохранении/загрузке настроек.
        nativeEngine?.updateConfig(config, relaxationSettings)
        // НЕ обновляем частоты здесь - native engine может ещё не иметь актуальных данных.
        // Частоты обновляются ежесекундно в startUiFrequencyUpdateJob() через lookup table.
    }
    
    /**
     * Обновить настройки режима расслабления (потокобезопасно)
     */
    fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        lastRelaxationSettings = settings
        nativeEngine?.updateRelaxationModeSettings(settings)
    }

    /**
     * Обновить кривую частот (потокобезопасно).
     * C7: нативная конфигурация перестраивается в generateAudioLoop (pendingCurveUpdate).
     */
    fun updateFrequencyCurve(curve: FrequencyCurve) {
        val currentConfig = configRef.get()
        configRef.set(currentConfig.copy(frequencyCurve = curve))
        _currentConfig.value = configRef.get()
        pendingCurveUpdate.set(true)
    }

    /**
     * Начать воспроизведение (потокобезопасно)
     */
    fun play() {
        Log.d(TAG, "play() called, isPlaying=${_isPlaying.value}, isActive=${isActive.get()}")
        
        val handler = audioHandler
        if (handler == null) {
            Log.e(TAG, "AudioHandler is null! Cannot start playback")
            return
        }
        
        // Если идёт fade-out, прерываем его
        if (isActive.get() && !_isPlaying.value) {
            Log.d(TAG, "Interrupting fade-out")
            // P0-протокол: bump ПЕРВЫМ делом - спящий в runFadeOutInline цикл
            // по пробуждении увидит чужое поколение и не тронет состояние.
            transitionGen.incrementAndGet()

            // P3: точечные чистки вместо removeCallbacksAndMessages(null),
            // который стирал отложенный dyingTrack.release().
            handler.removeCallbacksAndMessages(RESTART_PLAYBACK_TOKEN)
            handler.removeCallbacksAndMessages(SAMPLE_RATE_CHANGE_TOKEN)
            stopWithFadeRequested.set(false)
            pauseWithFadeRequested.set(false)

            // Порядок против щелчка: СНАЧАЛА глушим рендер (pause), затем
            // закрываем шейпер. close() на середине фейда отдал бы полный
            // gain на ещё звучащий буфер.
            try {
                audioTrack?.pause()
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing AudioTrack: ${e.message}")
            }
            isDucked = false
            synchronized(shaperLock) { retireShaperLocked() }

            isActive.set(false)
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }

            handler.postDelayed({
                // Призрачный старт: сессию могли успеть похоронить за 100 мс
                if (!isActive.get() && !_isPlaying.value) {
                    startNewPlayback(handler)
                }
            }, 100)
            return
        }
        
        if (isActive.get()) {
            Log.d(TAG, "Already active, returning")
            return
        }
        
        startNewPlayback(handler)
    }
    
    private fun startNewPlayback(handler: Handler) {
        Log.d(TAG, "startNewPlayback() called, isActive=${isActive.get()}")
        
        if (isActive.get()) {
            Log.w(TAG, "startNewPlayback() - already active, returning")
            return
        }

        // P2: defer на время bypass-teardown (fadeOutStopBlocking). PLAY во
        // время teardown - ожидание, а не конкуренция за нативное состояние.
        val deferStartMs = System.currentTimeMillis()
        while (teardownDepth.get() > 0 && System.currentTimeMillis() - deferStartMs < 400) {
            try { Thread.sleep(15) } catch (e: InterruptedException) { break }
        }
        if (teardownDepth.get() > 0) {
            Log.e(TAG, "startNewPlayback() - teardown still in progress after 400ms, aborting start")
            return
        }
        // Пока ждали, могла родиться другая сессия
        if (isActive.get()) {
            Log.w(TAG, "startNewPlayback() - activated concurrently, returning")
            return
        }

        // ===== SESSION BIRTH: bump -> scrub -> activate =====
        // Призрачные флаги существуют только при isActive==false; рождение -
        // единственный переход false->true, скраб в том же окне до активации.
        transitionGen.incrementAndGet()
        stopWithFadeRequested.set(false)
        pauseWithFadeRequested.set(false)
        isActive.set(true)
        // Фиксируем владение: только teardown этой же генерации вправе
        // терминально сбрасывать флаги (см. stopPlayback/cleanupPlayback).
        activeSessionGen = transitionGen.get()
        playbackStartTime = System.currentTimeMillis()

        // Применяем отложенный пресет (смена через fade-перезапуск, F5)
        pendingPresetConfig?.let { cfg ->
            nativeEngine?.updateConfig(cfg, lastRelaxationSettings)
            configRef.set(cfg)
            _currentConfig.value = cfg
            pendingPresetConfig = null
            Log.d(TAG, "startNewPlayback(): applied pending preset config")
        }

        Log.d(TAG, "startNewPlayback() - calling nativeEngine.resetState() and play()")

        // Гонка со старым циклом генерации: startNewPlayback выполняется в
        // вызывающем потоке, а generateAudioLoop живёт на audioHandler-потоке.
        // Если цикл ещё не вышел (isActive уже false, но текущий
        // nativeGenerateBufferDirect/запись в трек досматривается), resetState()+play()
        // разорвут m_state нативного движка (фазы свопа, timeline, TREND-init).
        // Ждём завершения цикла ограниченно; таймаут — деградация к прежнему
        // поведению с предупреждением в лог.
        if (isGenerating) {
            val waitStartMs = System.currentTimeMillis()
            while (isGenerating && System.currentTimeMillis() - waitStartMs < 500) {
                Thread.sleep(10)
            }
            if (isGenerating) {
                Log.w(TAG, "startNewPlayback() - generation loop still running after 500ms, proceeding anyway")
            } else {
                Log.d(TAG, "startNewPlayback() - waited ${System.currentTimeMillis() - waitStartMs}ms for old loop to finish")
            }
        }

        nativeEngine?.resetState()
        nativeEngine?.play()
        // НЕ обновляем частоты здесь - native engine может ещё не иметь актуальных данных.
        // Частоты обновляются ежесекундно в startUiFrequencyUpdateJob() через lookup table.
        
        // Сообщаем UI о начале воспроизведения
        _isPlaying.value = true

        acquireWakeLock()
        Log.d(TAG, "startNewPlayback() - posting startPlayback to handler")
        handler.post(::startPlayback)
    }

    // Вызывается только через handler.post на единственном аудио-потоке —
    // сериализация обеспечена лупером; @Synchronized здесь создавал дедлок:
    // монитор удерживался всю сессию (включая sleep паузы), а
    // acquireWakeLock из resumeWithFade (main/binder) ждал бы вечно.
    private fun startPlayback() {
        Log.d(TAG, "startPlayback() called, isActive=${isActive.get()}")

        // Проверяем что не идёт перезапуск с другой частотой
        if (restartPlaybackScheduled) {
            Log.w(TAG, "startPlayback() - restart is scheduled, skipping")
            return
        }

        if (!isActive.get()) {
            Log.w(TAG, "startPlayback() - isActive is false, returning")
            return
        }

        Log.d(TAG, "startPlayback() on thread: ${Thread.currentThread().name}")

        try {
            // Максимальный размер буфера в сэмплах (из MAX_BUFFER_MINUTES)
            val maxSamplesPerChannelLimit = sampleRate * 60 * MAX_BUFFER_MINUTES
            
            // Учитываем отложенный интервал при создании буфера
            // pendingFrequencyUpdateIntervalMs может быть установлен до нажатия play
            val effectiveIntervalMs = pendingFrequencyUpdateIntervalMs.get() ?: frequencyUpdateIntervalMs
            
            // Вычисляем размер буфера на основе эффективного интервала с ограничением
            val requestedSamplesPerChannel = (sampleRate.toLong() * effectiveIntervalMs / 1000).toInt()
            val samplesPerChannel = minOf(requestedSamplesPerChannel, maxSamplesPerChannelLimit)

            // F7: честность капа — реальный интервал урезан лимитом буфера
            val effectiveBufferIntervalMs = samplesPerChannel * 1000L / sampleRate
            if (effectiveBufferIntervalMs < effectiveIntervalMs - 999) {
                Log.w(TAG, "Buffer interval capped by buffer limit: requested=${effectiveIntervalMs}ms, effective=${effectiveBufferIntervalMs}ms")
            }

            // Создаём DirectByteBuffer для zero-copy генерации
            // Размер: samplesPerChannel * 2 канала * 4 байта на float
            // Дополнительно ограничиваем MAX_BUFFER_BYTES для защиты от OOM
            val directBufferSize = minOf(
                samplesPerChannel * 2 * 4,
                MAX_BUFFER_BYTES
            )

            if (directAudioBuffer == null || directAudioBuffer!!.capacity() < directBufferSize) {
                directAudioBuffer = allocateDirectBuffer(directBufferSize, sampleRate)
                if (directAudioBuffer != null) {
                    Log.d(TAG, "Created DirectByteBuffer: $directBufferSize bytes (${directBufferSize / 1024 / 1024} MB) for interval ${effectiveBufferIntervalMs}ms")
                } else {
                    Log.e(TAG, "Failed to allocate DirectByteBuffer ($directBufferSize bytes)")
                }
            }
            
            createAudioTrack()
            // Fade-in от MIN_VOLUME до полной громкости (1.0 как множитель)
            // AudioTrack уже имеет базовую громкость userVolume,
            // VolumeShaper работает как множитель: итоговая = userVolume × VolumeShaper.value
            // Fade-in на СВЕЖЕМ треке: шейпера ещё нет -> старт задаём явно.
            // Вооружаем ДО play(): окно между close(old)/arm(new) немо.
            installFade(
                targetMultiplier = FADE_IDENTITY,
                fromMultiplier = MIN_VOLUME,
                closeOnComplete = true,
            )
            audioTrack?.play()
            
            // ВАЖНО: Не используем отложенный callback для установки userVolume!
            // Если пользователь изменит громкость во время fade-in, callback перезапишет
            // новое значение на старое. Вместо этого VolumeShaper плавно приводит громкость
            // к userVolume, и после завершения шейпера просто освобождаем ресурсы.
            // Громкость установлена через AudioTrack.setVolume(userVolume) в createAudioTrack()
            // или через setVolume() от слайдера пользователя.
            // F6: закрытие по завершении выполняет generateAudioLoop
            // (поколение + дедлайн вооружены внутри installFade).
            
            generateAudioLoop()
            Log.d(TAG, "startPlayback() - generateAudioLoop() completed normally")
        } catch (e: Exception) {
            Log.e(TAG, "Playback error", e)
        } finally {
            Log.d(TAG, "startPlayback() - finally block, calling cleanupPlayback()")
            cleanupPlayback()
        }
    }
    
    private fun createAudioTrack() {
        val encoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioFormat.ENCODING_PCM_FLOAT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            encoding
        )
        
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val bufferSize = maxOf(minBufferSize, sampleRate * 2 * bytesPerSample * BUFFER_SIZE_MS / 1000)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Устанавливаем громкость пользователя как базовую.
        // userVolume - единственный источник истины для громкости.
        // VolumeShaper работает как множитель поверх AudioTrack.setVolume():
        // итоговая_громкость = AudioTrack.volume × VolumeShaper.value
        // При fade-in: userVolume × [0.001→1.0] = плавное нарастание от MIN_VOLUME до userVolume
        audioTrack?.setVolume(userVolume)
        audioTrackBufferSize = bufferSize
        Log.d(TAG, "AudioTrack created: sampleRate=$sampleRate, bufferSize=$bufferSize")
    }

    /**
     * F8: аллокация direct-буфера с защитой от OOM — при нехватке памяти размер
     * уменьшается вдвое до минимума max(audioTrackBufferSize, 1с аудио).
     * @return буфер или null, если недоступен даже минимальный размер
     */
    private fun allocateDirectBuffer(sizeBytes: Int, rateHz: Int): java.nio.ByteBuffer? {
        val minSize = maxOf(audioTrackBufferSize, rateHz * 2 * 4)
        var size = sizeBytes
        while (true) {
            try {
                return java.nio.ByteBuffer.allocateDirect(size)
                    .order(java.nio.ByteOrder.nativeOrder())
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "OutOfMemoryError allocating ${size / 1024 / 1024} MB direct buffer")
                if (size <= minSize) return null
                size = maxOf(minSize, size / 2)
            }
        }
    }
    
    // ===== УНИВЕРСАЛЬНАЯ ТОЧКА ГРОМКОСТИ (installFade) =====

    /** Живой множитель из активного шейпера. Вызывать под shaperLock. */
    private fun readLiveMultiplierLocked(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shaper = volumeShaper
            if (shaper != null) {
                return try {
                    shaper.volume
                } catch (e: Exception) {
                    FADE_IDENTITY
                }
            }
        }
        return FADE_IDENTITY
    }

    /** Потокобезопасное чтение слышимого множителя для оркестраций. */
    private fun currentAudibleMultiplier(): Float =
        synchronized(shaperLock) { readLiveMultiplierLocked() }

    private fun armDeferredCloseLocked(durationMs: Long) {
        pendingShaperCloseDeadlineMs =
            System.currentTimeMillis() + durationMs + SHAPER_CLOSE_MARGIN_MS
        pendingShaperCloseGeneration = fadeGeneration.get()
    }

    private fun resetFadeArmingLocked() {
        pendingShaperCloseDeadlineMs = 0L
        pendingShaperCloseGeneration = 0L
    }

    /** Закрыть текущий шейпер и обнулить fade-состояние. Только под локом. */
    private fun retireShaperLocked() {
        try {
            volumeShaper?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing shaper: ${e.message}")
        }
        volumeShaper = null
        activeFade = null
        isFadeInProgress = false
    }

    /** Терминальный сброс (stop/cleanup/interrupt): инвалидирует armed-close поколением. */
    private fun resetFadeBookkeeping() {
        synchronized(shaperLock) {
            fadeGeneration.incrementAndGet()
            activeFade = null
            isFadeInProgress = false
            resetFadeArmingLocked()
        }
    }

    /**
     * ЕДИНАЯ точка установки ЛЮБОГО перехода громкости (fade-in/out, duck/
     * unduck, микро-рамп слайдера, догон). Кликобезопасность по построению:
     *  1. Старт всегда из живого состояния (volumeShaper.volume), либо явный
     *     fromMultiplier - единственный случай: заведомо тихий новый трек.
     *  2. Новый шейпер создаётся ДО закрытия старого.
     *  3. Снимок рампа публикуется атомарно (один своп ссылки).
     *  4. Поколение инвалидирует чужие armed-close мгновенно.
     *  5. |старт-цель| < эпсилон => чистый no-op; живой шейпер НЕ закрывается.
     *
     * Политика базы: AudioTrack.setVolume держит ТОЛЬКО userVolume (слайдер);
     * шейпер - только множитель. Базу этот метод не трогает (кроме фолбэка).
     *
     * @param closeOnComplete вооружить отложенное закрытие. Обязано быть false
     *   при target≈0 (закрытие на нуле = взрыв до полного gain).
     */
    private fun installFade(
        targetMultiplier: Float,
        durationMs: Long = FADE_DURATION_MS,
        cubic: Boolean = false,
        fromMultiplier: Float? = null,
        closeOnComplete: Boolean = false,
    ): Boolean {
        val target = targetMultiplier.coerceIn(0.0f, 1.0f)

        synchronized(shaperLock) {
            val track = audioTrack
            if (track == null || !isActive.get()) {
                activeFade = null
                isFadeInProgress = false
                resetFadeArmingLocked()
                return false
            }

            val start = (fromMultiplier ?: readLiveMultiplierLocked())
                .coerceIn(MIN_VOLUME, 1.0f)

            // Неслышимый переход: ничего не трогаем. Сидящий шейпер уже выдаёт
            // этот уровень. Прежний early-return здесь закрывал живой duck -
            // детонация при следующем unduck; теперь - чистый no-op.
            if (kotlin.math.abs(start - target) < FADE_EQUALITY_EPSILON) {
                activeFade = null
                isFadeInProgress = false
                return true
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                try {
                    track.setVolume(userVolume * target)
                } catch (e: Exception) {
                    Log.e(TAG, "Legacy volume apply failed: ${e.message}")
                }
                volumeShaper = null
                activeFade = null
                isFadeInProgress = false
                resetFadeArmingLocked()
                return true
            }

            return try {
                val config = VolumeShaper.Configuration.Builder()
                    .setDuration(durationMs)
                    .setCurve(floatArrayOf(0f, 1f), floatArrayOf(start, target))
                    .setInterpolatorType(
                        if (cubic) VolumeShaper.Configuration.INTERPOLATOR_TYPE_CUBIC
                        else VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR
                    )
                    .build()

                // Создать-новый-ДО-закрытия-старого: щели с полным gain нет.
                val newShaper = track.createVolumeShaper(config)
                try {
                    volumeShaper?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing previous shaper: ${e.message}")
                }
                volumeShaper = newShaper

                activeFade = ActiveFade(start, target, System.currentTimeMillis(), durationMs)
                isFadeInProgress = true

                fadeGeneration.incrementAndGet()

                if (closeOnComplete) {
                    armDeferredCloseLocked(durationMs)
                } else {
                    // Инсталл без автозакрытия (duck, fade-out) отменяет висящий
                    // дедлайн: его шейпер должен ПЕРЕЖИТЬ рамп.
                    resetFadeArmingLocked()
                }

                Log.d(TAG, "installFade: $start -> $target ${durationMs}ms gen=${fadeGeneration.get()} close=$closeOnComplete")
                true
            } catch (e: Exception) {
                Log.e(TAG, "installFade failed: ${e.message}")
                // Провал инсталла = ОСТАВЛЯЕМ предыдущий шейпер работать:
                // он всё ещё подключён и выдаёт корректный уровень, база
                // трека не тронута => состояние остаётся согласованным
                // (ни взрыва громкости, ни отравленной нулевой базы с
                // последующей вечной тишиной после resume).
                activeFade = null
                isFadeInProgress = false
                resetFadeArmingLocked()
                false
            }
        }
    }

    private fun generateAudioLoop() {
        Log.d(TAG, "generateAudioLoop() started, isActive=${isActive.get()}")

        val engine = nativeEngine
        if (engine == null) {
            Log.e(TAG, "generateAudioLoop() - nativeEngine is null, returning")
            return
        }

        // Локальная копия sampleRate для защиты от изменений во время работы
        val localSampleRate = sampleRate
        // Максимальный размер буфера в сэмплах (из MAX_BUFFER_MINUTES)
        val maxSamplesPerChannelLimit = localSampleRate * 60 * MAX_BUFFER_MINUTES
        // C2: тот же байтовый кап, что и в startPlayback — стерео float = 8 байт/сэмпл
        val maxSamplesByBytesLimit = MAX_BUFFER_BYTES / 8

        var currentIntervalMs = frequencyUpdateIntervalMs
        var samplesPerChannel = minOf(
            (localSampleRate.toLong() * currentIntervalMs / 1000).toInt(),
            maxSamplesPerChannelLimit,
            maxSamplesByBytesLimit
        )

        isGenerating = true
        Log.d(TAG, "generateAudioLoop() - entering main loop, isActive=${isActive.get()}, audioTrack=$audioTrack")

        while (isActive.get() && audioTrack != null) {
            // C6: признак ожидающих изменений собираем ДО их потребления,
            // чтобы эта итерация сгенерировала короткий пакет (~1 с) и
            // изменение прозвучало быстро, а не через полный интервал.
            val hasPendingChanges = pendingSampleRate.get() != null ||
                pendingFrequencyUpdateIntervalMs.get() != null ||
                pendingCurveUpdate.get() ||
                presetSwitchRequested.get() != null ||
                pendingPresetConfig != null ||
                stopWithFadeRequested.get() ||
                pauseWithFadeRequested.get()

            applyPendingSettings()

            if (frequencyUpdateIntervalMs != currentIntervalMs) {
                currentIntervalMs = frequencyUpdateIntervalMs
                samplesPerChannel = minOf(
                    (localSampleRate.toLong() * currentIntervalMs / 1000).toInt(),
                    maxSamplesPerChannelLimit,
                    maxSamplesByBytesLimit
                )

                // F7: честность капа — реальный интервал урезан лимитом буфера
                val effectiveBufferIntervalMs = samplesPerChannel * 1000L / localSampleRate
                if (effectiveBufferIntervalMs < currentIntervalMs - 999) {
                    Log.w(TAG, "Buffer interval capped by buffer limit: requested=${currentIntervalMs}ms, effective=${effectiveBufferIntervalMs}ms")
                }

                // Пересоздаём буфер если нужно больше места
                val requiredSize = samplesPerChannel * 2 * 4
                if (directAudioBuffer == null || directAudioBuffer!!.capacity() < requiredSize) {
                    directAudioBuffer = allocateDirectBuffer(requiredSize, localSampleRate)
                    if (directAudioBuffer != null) {
                        Log.d(TAG, "Resized DirectByteBuffer: $requiredSize bytes (${requiredSize / 1024 / 1024} MB) for interval ${effectiveBufferIntervalMs}ms")
                    } else {
                        Log.e(TAG, "Failed to resize DirectByteBuffer ($requiredSize bytes)")
                    }
                }
            }

            checkFadeRequests()

            // F5: закрываем шейпер завершённого fade-in. ВАЖНО: выше guard-sleep,
            // иначе брошенный (например резюмом) шейпер не закрылся бы на паузе
            // Отложенное закрытие шейпера, доехавшего до identity. Действительно
            // ТОЛЬКО при совпадении поколений: stale-дедлайн прошлой операции
            // умирает молча, а не убивает свежий шейпер посреди рампа.
            if (pendingShaperCloseDeadlineMs > 0L &&
                System.currentTimeMillis() >= pendingShaperCloseDeadlineMs) {
                synchronized(shaperLock) {
                    // Время и поколение проверяем ТОЛЬКО под локом: иначе
                    // инсталл, вклинившийся между чтением времени и локом,
                    // получал close своего свежего шейпера посреди рампа.
                    if (pendingShaperCloseGeneration == fadeGeneration.get() &&
                        System.currentTimeMillis() >= pendingShaperCloseDeadlineMs
                    ) {
                        retireShaperLocked()
                        Log.d(TAG, "Deferred shaper close: generation matched")
                    } else {
                        Log.d(TAG, "Deferred shaper close: stale, discarded")
                    }
                    resetFadeArmingLocked()
                }
            }

            // Мягкая пауза: трек уже на паузе — запись в него заблокирует WRITE_BLOCKING.
            // Держим цикл живым для мягкого resume, но не генерируем и не пишем.
            if (!_isPlaying.value) {
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                continue
            }

            // C6: короткий пакет при ожидающих изменениях. Таймлайн непрерывен:
            // движок генерирует ровно effectiveSamples по sample-driven оси.
            val effectiveSamples = if (hasPendingChanges) {
                minOf(samplesPerChannel, localSampleRate)
            } else {
                samplesPerChannel
            }

            // C8: продление WakeLock на длинных сессиях
            acquireWakeLock()

            // Получаем актуальный буфер (может измениться при ресайзе)
            val directBuffer = directAudioBuffer
            if (directBuffer == null) {
                Log.e(TAG, "generateAudioLoop() - directAudioBuffer is null, returning")
                break
            }

            // Zero-copy генерация через DirectByteBuffer.
            // ВАЖНО: generated может быть немного меньше samplesPerChannel
            // (целочисленное округление длительностей сегментов в нативном
            // планировщике). Ниже пишем в AudioTrack ровно generated сэмплов.
            directBuffer.clear()
            val generated = engine.generateBufferDirect(directBuffer, effectiveSamples)
            
            if (generated <= 0) {
                Log.e(TAG, "Native buffer generation failed or not playing")
                break
            }

            // ВНИМАНИЕ: Частоты НЕ обновляем здесь!
            // Это вызывало мерцание некорректных значений при старте/смене пресета.
            // Частоты обновляются только через updateCurrentFrequencies() в:
            // - startUiFrequencyUpdateJob() - каждую секунду (когда приложение на экране)
            // - startNotificationUpdateJob() - каждые 10 секунд (всегда)
            
            val swapped = engine.isChannelsSwapped()
            if (_isChannelsSwapped.value != swapped) {
                _isChannelsSwapped.value = swapped
            }
            
            val elapsed = engine.getElapsedSeconds()
            if (_elapsedSeconds.value != elapsed) {
                _elapsedSeconds.value = elapsed
            }

            if (!isActive.get()) break

            val currentAudioTrack = audioTrack
            if (currentAudioTrack == null) {
                Log.d(TAG, "AudioTrack is null, stopping")
                break
            }

            // Запись в AudioTrack через DirectByteBuffer.
            // КРИТИЧНО: пишем ровно generated сэмплов (generated * 2 канала * 4 байта),
            // а не полный размер буфера. Остаток буфера содержит мусор от прошлого
            // пакета — его запись и была причиной щелчка + скачка частот на стыке.
            directBuffer.position(0)
            val sizeInBytes = generated * 2 * 4

            // Диагностика HAL: позиция головы воспроизведения и underrun'ы
            // до/после записи пакета.
            val headBefore = currentAudioTrack.playbackHeadPosition
            val underrunBefore = currentAudioTrack.underrunCount

            val writeResult = try {
                // Записываем порциями не больше audioTrackBufferSize
                var totalWritten = 0
                var interruptedByRequest = false
                while (totalWritten < sizeInBytes && isActive.get()) {
                    // C3: управление должно работать посреди досыпки пакета,
                    // а не после дренирования всех 10 минут. Недописанный остаток
                    // доигрывает буфер AudioTrack сам, флаги потребит
                    // checkFadeRequests следующей итерации.
                    if (pauseWithFadeRequested.get() ||
                        stopWithFadeRequested.get() ||
                        presetSwitchRequested.get() != null
                    ) {
                        interruptedByRequest = true
                        Log.i(TAG, "PKG_WRITE interrupted by control request: " +
                              "$totalWritten/$sizeInBytes bytes")
                        break
                    }
                    val remaining = sizeInBytes - totalWritten
                    // Кап чанка ~1с аудио — гранулярность реакции на флаги
                    val chunkSize = minOf(
                        remaining,
                        audioTrackBufferSize,
                        localSampleRate * 2 * 4
                    )

                    directBuffer.position(totalWritten)
                    directBuffer.limit(totalWritten + chunkSize)

                    val written = currentAudioTrack.write(directBuffer, chunkSize, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        Log.e(TAG, "DirectByteBuffer write failed at offset $totalWritten: $written")
                        break
                    }
                    totalWritten += written
                }

                // Частичная запись по запросу управления — НЕ ошибка:
                // иначе разрыв внешнего цикла убивал сессию без fade/pause
                when {
                    interruptedByRequest -> totalWritten
                    totalWritten == sizeInBytes -> sizeInBytes
                    else -> -1
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack write error: ${e.message}")
                -1
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack write exception: ${e.message}")
                -1
            }

            if (writeResult < 0) {
                Log.d(TAG, "Write failed, result=$writeResult")
                break
            }

            // Диагностика HAL после записи пакета
            val headAfter = currentAudioTrack.playbackHeadPosition
            val underrunAfter = currentAudioTrack.underrunCount
            Log.i(TAG, "PKG_WRITE: gen=$generated headDelta=${headAfter - headBefore} " +
                  "underrun=$underrunBefore->$underrunAfter " +
                  "capFrames=${currentAudioTrack.bufferCapacityInFrames} " +
                  "trackSr=${currentAudioTrack.sampleRate} sysMs=${System.currentTimeMillis()}")
        }

        isGenerating = false
        Log.d(TAG, "generateAudioLoop() ended")
    }
    
    private fun checkFadeRequests() {
        if (stopWithFadeRequested.get()) {
            Log.d(TAG, "Starting fade-out for stop")
            // F1/F3: фейд и остановка выполняются инлайн — посты из живого
            // цикла генерации не доставляются (лупер занят startPlayback)
            val genAtFadeStart = transitionGen.get()
            runFadeOutInline()

            // POST-SLEEP RECHECK - симметричное C2 обобщение на STOP: пока мы
            // спали, кто-то мутировал состояние (resume/play-interrupt/сторонний
            // teardown) - решение о завершении остановки НЕ НАШЕ. Без этой
            // проверки резюм поверх спящего стоп-фейда жёстко убивался циклом.
            val stopSuperseded = transitionGen.get() != genAtFadeStart || !isActive.get()
            if (!stopSuperseded) {
                isActive.set(false)
                if (pendingPresetConfig != null) {
                    // Если ждала смена пресета — после fade-out перезапускаем
                    // с новым конфигом (свежий таймлайн), а не останавливаемся.
                    audioHandler?.post { play() }
                } else {
                    stopPlayback()
                }
            } else {
                Log.i(TAG, "STOP fade superseded mid-flight; aborting stop completion")
            }
            stopWithFadeRequested.set(false)
            return
        }

        if (pauseWithFadeRequested.get()) {
            Log.d(TAG, "Starting fade-out for pause")
            // F1/F2: фейд и пауза выполняются инлайн, без мёртвых постов
            val genAtFadeStart = transitionGen.get()
            runFadeOutInline()

            // Двухуровневый C2: gen ловит кросс-сессионные суперседы,
            // executePause сохраняет собственную проверку _isPlaying
            val pauseSuperseded = transitionGen.get() != genAtFadeStart || !isActive.get()
            if (!pauseSuperseded) {
                executePause()
            } else {
                Log.i(TAG, "PAUSE fade superseded mid-flight; aborting pause")
            }
            pauseWithFadeRequested.set(false)
            return
        }
        
        presetSwitchRequested.getAndSet(null)?.let { newConfig ->
            // НЕ применяем конфиг мгновенно на границе пакетов — это давало
            // резкую смену частот без перехода. Маршрутизируем через fade-out →
            // перезапуск: конфиг применится в startNewPlayback().
            Log.w(TAG, "PRESET_SWITCH requested -> will apply via fade-restart at sysTimeMs=${System.currentTimeMillis()}")
            pendingPresetConfig = newConfig
            stopWithFadeRequested.set(true)
        }
    }
    
    /**
     * F1: инлайн fade-out — создаёт и запускает VolumeShaper синхронно
     * в вызывающем потоке и ждёт окончания затухания. Пост-колбэк здесь
     * недопустим: он не доставлялся бы из живого цикла генерации.
     */
    private fun runFadeOutInline() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        isDucked = false // активный duck поглощается фейдом остановки

        val level = currentAudibleMultiplier()
        if (level > MIN_VOLUME) {
            // closeOnComplete=false ОБЯЗАТЕЛЬНО: закрытие шейпера на нуле
            // отдало бы полный gain. Трек гасится позже паузой/стопом.
            val genAtFade = transitionGen.get()
            installFade(targetMultiplier = 0.0f)
            try {
                Thread.sleep(FADE_DURATION_MS + 50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // RC2: нас перекрыли во сне (resume/play-interrupt восстановили
            // громкость своим рампом). Оставленный нами нулевой шейпер
            // похоронил бы их подъём => вечная тишина при играющем UI.
            if (transitionGen.get() != genAtFade && _isPlaying.value) {
                Log.i(TAG, "Orphaned fade-out superseded mid-sleep; restoring level")
                installFade(targetMultiplier = FADE_IDENTITY, closeOnComplete = true)
            }
        }

        // Фейд завершился; штамп - для внешних оркестраций и диагностики.
        synchronized(shaperLock) {
            activeFade = null
            isFadeInProgress = false
        }
        lastFadeOutCompletedAtMs = System.currentTimeMillis()
    }

    private fun executePause() {
        // C2: resume успел выполниться в UI-потоке, пока мы спали в
        // runFadeOutInline — отменяем паузу и прибираем брошенный шейпер.
        if (_isPlaying.value) {
            Log.d(TAG, "executePause: resume won the race during inline fade, pausing aborted")
            // Догоняющий подъём: старт - ЖИВОЙ уровень брошенного шейпера
            // (середина отменённого fade-out). Никаких ручных close.
            installFade(targetMultiplier = FADE_IDENTITY, closeOnComplete = true)
            return
        }

        _isPlaying.value = false

        val currentSessionMs = System.currentTimeMillis() - playbackStartTime
        accumulatedElapsedMs += currentSessionMs
        Log.d(TAG, "executePause: accumulatedElapsedMs=$accumulatedElapsedMs")

        // F2: прямой вызов — пост в audioHandler не доставился бы из живого цикла
        try {
            audioTrack?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing AudioTrack: ${e.message}")
        }
        // RC2c: resume вклинился между _isPlaying=true и нашим pause() -
        // иначе его track.play() упирается в нашу паузу и запись клинится
        // в WRITE_BLOCKING при формально играющем UI.
        if (_isPlaying.value) {
            try { audioTrack?.play() } catch (e: Exception) { }
        }

        // Пауза не должна держать WakeLock до истечения TTL; resume перевыделяет
        releaseWakeLock()
    }
    
    private fun applyPendingSettings() {
        pendingSampleRate.getAndSet(null)?.let { newRate ->
            if (sampleRate != newRate.value) {
                sampleRate = newRate.value
                Log.d(TAG, "Applied pending sample rate: ${newRate.value}")
            }
        }
        
        pendingFrequencyUpdateIntervalMs.getAndSet(null)?.let { newInterval ->
            frequencyUpdateIntervalMs = newInterval
            Log.d(TAG, "Applied pending frequency update interval: ${newInterval}ms")
        }

        // C7: перестройка нативной lookup-таблицы между записями в AudioTrack —
        // live-редактирование кривой активного пресета становится слышимым
        if (pendingCurveUpdate.compareAndSet(true, false)) {
            nativeEngine?.updateConfig(configRef.get(), lastRelaxationSettings)
            Log.d(TAG, "Applied pending curve update")
        }
    }

    /**
     * Остановить воспроизведение немедленно
     */
    fun stop() {
        Log.d(TAG, "stop() called")

        // INV-1: вход в жёсткую остановку инвалидирует параллельные
        // инлайн-фейды цикла; INV-5: хоронит и отложенную смену пресета.
        transitionGen.incrementAndGet()
        pendingPresetConfig = null

        if (!isActive.get()) {
            // Ничего слышимого: идемпотентный teardown.
            _isPlaying.value = false
            stopWithFadeRequested.set(false)
            pauseWithFadeRequested.set(false)
            audioHandler?.removeCallbacksAndMessages(RESTART_PLAYBACK_TOKEN)
            audioHandler?.removeCallbacksAndMessages(SAMPLE_RATE_CHANGE_TOKEN)
            audioHandler?.removeCallbacksAndMessages(RELEASE_DYING_TRACK_TOKEN)
            audioHandler?.post(::stopPlayback)
            return
        }

        if (Thread.currentThread() === audioThread) {
            // Внутренний вызов из аудио-потока: делегировать циклу, который мы
            // блокируем, невозможно - легаси-инлайн (вызывающий не Main).
            runFadeOutInline()
            isActive.set(false)
            _isPlaying.value = false
            stopWithFadeRequested.set(false)
            pauseWithFadeRequested.set(false)
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }
            audioHandler?.removeCallbacksAndMessages(RESTART_PLAYBACK_TOKEN)
            audioHandler?.removeCallbacksAndMessages(SAMPLE_RATE_CHANGE_TOKEN)
            audioHandler?.post(::stopPlayback)
            return
        }

        // Off-thread (Main/Binder): делегируем слышимый фейд живому циклу через
        // флаг - он гасит звук на АУДИО-потоке между чанками записи (C3).
        // НОЛЬ блокировки Main (раньше здесь спали ~310 мс = 19 кадров jank).
        stopWithFadeRequested.set(true)
        pauseWithFadeRequested.set(false)   // stop сильнее висящей паузы
        pendingPresetConfig = null          // stop сильнее отложенного пресета
        _isPlaying.value = false            // UI/уведомление мгновенно;
        // цикл съест флаг ДО guard-sleep псевдопаузы, голодания стопа нет
    }
    
    /**
     * Остановить воспроизведение с плавным затуханием
     */
    /**
     * INV-3 validated-set: флаг ставится только если сессия пережила запись.
     * Закрывает TOCTOU: жест, прочитавший живую сессию, но записанный в мёртвую,
     * больше не оставляет призрачный флаг следующей сессии.
     */
    private fun requestStopWithFadeValidated(): Boolean {
        val genAtEntry = transitionGen.get()
        stopWithFadeRequested.set(true)
        if (isActive.get() && transitionGen.get() == genAtEntry) return true
        stopWithFadeRequested.set(false)
        Log.d(TAG, "stopWithFade request discarded: owning session ended during request")
        return false
    }

    private fun requestPauseWithFadeValidated(): Boolean {
        val genAtEntry = transitionGen.get()
        pauseWithFadeRequested.set(true)
        if (isActive.get() && transitionGen.get() == genAtEntry) return true
        pauseWithFadeRequested.set(false)
        Log.d(TAG, "pauseWithFade request discarded: owning session ended during request")
        return false
    }

    fun stopWithFade() {
        Log.d(TAG, "stopWithFade() called, isActive=${isActive.get()}, isPlaying=${_isPlaying.value}")

        // Проверяем isActive, а не _isPlaying!
        // isActive остаётся true во время fade-out, что предотвращает
        // накопление нескольких fade-out операций при быстрых переключениях
        if (!isActive.get()) {
            Log.d(TAG, "stopWithFade() - not active, returning")
            return
        }

        if (!requestStopWithFadeValidated()) return

        _isPlaying.value = false
        // K2: через флаг — checkFadeRequests в следующей (сокращённой до ~1с)
        // итерации запустит fade-out; completion не голодает на длинном пакете
    }

    private fun stopPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        volumeShaper?.close()
        volumeShaper = null
        isDucked = false
        resetFadeBookkeeping()

        // INV-5: отложенный пресет умирает вместе с сессией.
        pendingPresetConfig = null

        // Сброс нативного m_isPlaying: без него флаг остаётся true до следующего
        // startNewPlayback — окно рассинхрона getCurrentTimeOfDaySeconds/J4.
        // Безопасно: setPlaying(false,*) меняет только атомик, таймлайн не трогает.
        nativeEngine?.stop()

        releaseWakeLock()
        resetState()

        // RC1: гасим состояние ТОЛЬКО своей сессии. Если после коммита нашего
        // стопа уже родилась новая (пользователь быстро нажал Play), её
        // isActive/_isPlaying свергать нельзя - иначе старт оглушается.
        if (transitionGen.get() == activeSessionGen) {
            _isPlaying.value = false
            isActive.set(false)
        }
        
        Log.d(TAG, "stopPlayback() completed, userVolume=$userVolume")
    }
    
    private fun cleanupPlayback() {
        Log.d(TAG, "cleanupPlayback() called, isActive=${isActive.get()}, isGenerating=$isGenerating")
        
        Log.d(TAG, "cleanupPlayback() - stopping and releasing AudioTrack")
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        volumeShaper?.close()
        volumeShaper = null

        releaseWakeLock()

        // RC1: терминальные сбросы - только если это НЕ чужая новорождённая
        // сессия; иначе ограничиваемся ресурсным хвостом (трек/шейпер выше).
        if (transitionGen.get() == activeSessionGen) {
            _isPlaying.value = false
            isActive.set(false)
            isDucked = false
            resetFadeBookkeeping()
            stopWithFadeRequested.set(false)
            pauseWithFadeRequested.set(false)
            pendingPresetConfig = null
        }
        
        Log.d(TAG, "cleanupPlayback() completed, userVolume=$userVolume")
    }
    
    private fun resetState() {
        _elapsedSeconds.value = 0
        accumulatedElapsedMs = 0L
        Log.d(TAG, "resetState() completed")
    }

    /**
     * Приостановить воспроизведение с плавным затуханием
     */
    fun pauseWithFade() {
        Log.d(TAG, "pauseWithFade() called, isActive=${isActive.get()}, isPlaying=${_isPlaying.value}")

        // Проверяем isActive, а не _isPlaying - аналогично stopWithFade()
        if (!isActive.get()) {
            Log.d(TAG, "pauseWithFade() - not active, returning")
            return
        }

        if (!requestPauseWithFadeValidated()) return

        _isPlaying.value = false
        // K2: через флаг — checkFadeRequests в следующей (сокращённой до ~1с)
        // итерации запустит fade-out и executePause без голодания лупера
    }

    /**
     * Возобновить воспроизведение с плавным нарастанием
     */
    fun resumeWithFade() {
        Log.d(TAG, "resumeWithFade() called, isActive=${isActive.get()}")
        
        if (_isPlaying.value) return
        
        // МЯГКАЯ ПАУЗА (isActive==true, трек на паузе, цикл генерации жив):
        // продолжаем с ТОГО ЖЕ места кривой и той же фазой — без resetState и
        // без ре-янкора таймлайна на wall-clock (иначе скачок частот + щелчок).
        // F4: всё последовательно в вызывающем потоке, без постов в audioHandler
        // (они не доставлялись бы из живого цикла генерации).
        if (isActive.get()) {
            // INV-1: мягкий resume мутирует playback-состояние - инвалидирует
            // все спящие инлайн-фейды (P0: спящий STOP-фейд больше не убьёт
            // возобновлённую сессию; PAUSE-ветка получит supersede по gen).
            transitionGen.incrementAndGet()

            // Микро-перепроверка: teardown успел между чтением isActive и бампом
            if (!isActive.get()) {
                Log.d(TAG, "resumeWithFade: session torn down concurrently, falling back to play()")
                play()
                return
            }

            _isPlaying.value = true

            // Устаревшие запросы стопа/паузы аннулируем: resume отменяет их,
            // а консумеры флагов вдобавок защищены gen-recheck.
            stopWithFadeRequested.set(false)
            pauseWithFadeRequested.set(false)
            // Отложенный пресет был причиной отменённого стопа - resume его
            // хоронит (иначе вечные ~1с пакеты hasPendingChanges + сюрприз-
            // рестарт чужого пресета при следующем stopWithFade).
            pendingPresetConfig = null

            // F9: восстанавливаем опорную точку таймлайна перед снятием нативной паузы
            playbackStartTime = System.currentTimeMillis() - accumulatedElapsedMs
            nativeEngine?.setPlaybackStartTime(playbackStartTime)

            nativeEngine?.play(preserveTimeline = true)
            // C5: рамп от живого уровня сидящего шейпера (после fade-out ~0),
            // армим ДО track.play(): на паузе рендера нет, щель нема.
            isDucked = false
            val rampArmed = installFade(targetMultiplier = FADE_IDENTITY, closeOnComplete = true)
            if (!rampArmed && currentAudibleMultiplier() <= MIN_VOLUME) {
                // Рамп не встал, а сидящий шейпер молчит - иначе вечная тишина
                // под играющим UI. Жёстко восстанавливаем слышимость.
                Log.w(TAG, "resumeWithFade: fade install failed at silence; hard restore")
                synchronized(shaperLock) { retireShaperLocked() }
                try { audioTrack?.setVolume(userVolume) } catch (e: Exception) { }
            }
            audioTrack?.play()
            acquireWakeLock()
            return
        }
        
        play()
    }

    /**
     * Переключить пресет с плавным затуханием/нарастанием
     */
    fun switchPresetWithFade(config: BinauralConfig) {
        Log.d(TAG, "switchPresetWithFade() called")

        if (!isActive.get()) {
            updateConfig(config)
            return
        }

        presetSwitchRequested.set(config)
    }

    /**
     * Анти-щелчок: фейд остановки/паузы ещё не дошёл до тишины
     * (запрошен или выполняется). Позволяет внешним оркестрациям
     * ждать фактическое завершение вместо слепых задержек.
     */
    fun isStopFadePending(): Boolean =
        isActive.get() || stopWithFadeRequested.get() || pauseWithFadeRequested.get()

    /**
     * БЫСТРЫЙ клик-безопасный рестарт для смены настроек/пресета.
     *
     * Полностью независим от цикла генерации (его задержки на больших буферах
     * и порождали лаги и гонки):
     *   1) асинхронный рамп громкости в ноль единой длительности FADE_DURATION_MS;
     *   2) ожидание рампа обычным сном ВЫЗЫВАЮЩЕГО потока (не цикла);
     *   3) детерминированная остановка: сброс запросов, пауза+стоп трека
     *      ДО закрытия шейпера (без всплеска громкости), снятие постов;
     *   4) work() применяется в гарантированной тишине.
     *
     * Старт НЕТ: вызывающая сторона решает сама (позволяет отмене серии
     * оставить звук выключенным). Старый трек освобождается отложенно
     * (после распрямления цикла генерации).
     */
    /**
     * @return false только при abort по суперседу (resume/стоп вмешались в
     * рамп): вызывающая серия обязана повторить попытку, иначе её work() и
     * выбор настройки будут молча потеряны.
     */
    fun fadeOutStopBlocking(work: (() -> Unit)? = null): Boolean {
        return fadeOutStopBlockingInternal(work)
    }

    private fun fadeOutStopBlockingInternal(work: (() -> Unit)? = null): Boolean {
        val wasAudible = isActive.get() && _isPlaying.value
        // INV-4: ворота bypass-teardown для startNewPlayback
        teardownDepth.incrementAndGet()
        // RC6: сериализуем ПЕРЕКРЫВАЮЩИЕСЯ teardown'ы. Второй параллельный вызов
        // (вторая серия пресета/настроек) не должен стартовать второй слышимый
        // fade-out поверх живого звука - это и есть щелчок смены пресета.
        // Возвращаем false сразу, без сна: проигравшая серия повторит попытку
        // после завершения первой (итог - единичный последовательный fade).
        if (teardownDepth.get() > 1) {
            teardownDepth.decrementAndGet()
            return false
        }
        try {
            val genAtEntry = transitionGen.get()

            if (wasAudible) {
                isDucked = false
                if (currentAudibleMultiplier() > MIN_VOLUME) {
                    installFade(targetMultiplier = 0.0f)
                    try {
                        Thread.sleep(FADE_DURATION_MS + 60)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }

                // POST-SLEEP RECHECK: резюм/play успели вмешаться в сон - сессия
                // осталась жить, teardown ОТМЕНЯЕТСЯ целиком (до коммита мы
                // тронули только громкость, которую resume уже восстанавливает).
                if (transitionGen.get() != genAtEntry || !isActive.get()) {
                    Log.i(TAG, "fadeOutStopBlocking: superseded mid-fade, aborting teardown")
                    return false
                }
            }

            // ================= COMMIT POINT =================
            // INV-1: инвалидируем параллельные инлайн-фейды цикла;
            // validated-setter'ы после этого момента корректно откатятся.
            transitionGen.incrementAndGet()
            // Порядок важен: resume проверяет сперва _isPlaying - инверсия
            // оставляла окно, где тап резюма тихо терялся.
            _isPlaying.value = false
            isActive.set(false)
            stopWithFadeRequested.set(false)
            pauseWithFadeRequested.set(false)

            val dyingTrack = audioTrack
            audioTrack = null
            try {
                dyingTrack?.pause()
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing dying track: ${e.message}")
            }
            synchronized(shaperLock) { retireShaperLocked() }
            isDucked = false
            try {
                dyingTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping dying track: ${e.message}")
            }
            // Точечная чистка чужих токенов; свой релиз - под выделенным токеном,
            // чтобы никакая широкая чистка его не сняла (утечка AudioTrack).
            audioHandler?.removeCallbacksAndMessages(RESTART_PLAYBACK_TOKEN)
            audioHandler?.removeCallbacksAndMessages(SAMPLE_RATE_CHANGE_TOKEN)
            if (dyingTrack != null) {
                audioHandler?.postDelayed({
                    try {
                        dyingTrack.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing dying track: ${e.message}")
                    }
                }, RELEASE_DYING_TRACK_TOKEN, 1500L)
            }

            // Нативный стоп под двойной защитой: defer по teardownDepth
            // и gen-guard - чужое нативное состояние не перечёркиваем.
            if (transitionGen.get() == genAtEntry + 1) {
                nativeEngine?.stop()
            } else {
                Log.w(TAG, "fadeOutStopBlocking: skipping nativeEngine.stop(), superseded")
            }

            releaseWakeLock()
            resetState()

            // INV-5: отложенный пресет умирает вместе с сессией; work() накатит
            // актуальный конфиг поверх чистого состояния.
            pendingPresetConfig = null

            // Работа последней: ensureActive() первой строкой лямбды выбрасывает
            // CancellationException ДО мутаций конфига; движок к этому моменту
            // уже согласованно остановлен - исключение оставляет валидную тишину.
            work?.invoke()
            return true
        } finally {
            teardownDepth.decrementAndGet()
        }
    }

    /**
     * Плавно приглушить громкость (AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
     * смена аудио-устройства). Та же единая длительность FADE_DURATION_MS.
     * Уровень — множитель поверх userVolume.
     */
    /**
     * Плавно приглушить громкость (CAN_DUCK, смена аудио-устройства).
     * Старт - живой уровень (середина чужого рампа тоже корректен); кубическая
     * кривая мягче на концах. Шейпер переживает рамп (без автозакрытия).
     * Гейт на _isPlaying: невидимые шейперы на софт-паузе отравляли бы
     * последующие быстрые пути (стоп-из-тишины и др.).
     */
    fun duckTo(level: Float) {
        val track = audioTrack
        if (track == null || !_isPlaying.value) return

        val target = level.coerceIn(0.05f, 1.0f)
        if (installFade(
                targetMultiplier = target,
                cubic = true,
                closeOnComplete = false,
            )
        ) {
            isDucked = true
            Log.d(TAG, "Ducked to x$target")
        }
    }

    /**
     * Снять duck симметричным рампом той же единой длительности.
     */
    /**
     * Снять duck симметричным рампом к единице той же единой длительности.
     * Старт читается из живого шейпера - даже если duck шёл мгновение назад.
     */
    fun unduck() {
        if (!isDucked) return
        isDucked = false
        if (!_isPlaying.value) return

        if (installFade(targetMultiplier = FADE_IDENTITY, closeOnComplete = true)) {
            Log.d(TAG, "Unducked")
        }
    }

    /**
     * Установить громкость в реальном времени.
     *
     * Плавный drag слайдера идёт ступеньками меньше VOLUME_JUMP_THRESHOLD —
     * применяется напрямую (мгновенный отклик). РЕДКИЙ скачок (тап по треку,
     * сброс) больше порога сглаживается микро-рампом той же единой
     * длительности FADE_DURATION_MS, чтобы не щёлкать на произвольной фазе.
     */
    fun setVolume(volume: Float) {
        val clampedVolume = volume.coerceIn(0f, 1f)
        val previous = userVolume
        userVolume = clampedVolume // слайдер - единственный авторитет базы

        val track = audioTrack
        if (track == null || !isActive.get()) return

        val jump = kotlin.math.abs(clampedVolume - previous)

        if (jump <= VOLUME_JUMP_THRESHOLD || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Обычный drag: мгновенный отклик. База меняется и под активным
            // fade/duck - шейпер-множитель масштабирует её пропорционально.
            track.setVolume(clampedVolume)
            return
        }

        // Скачок (тап по треку): базу ставим сразу в ЦЕЛЬ, а шейпер ведёт
        // ЭФФЕКТИВНЫЙ уровень previous*live -> clamped*live. Учёт живого
        // множителя (duck/чужой рамп) обязателен: голый ratio давал x1/d
        // мгновенный прыжок и молча уничтожал активный duck.
        val liveMult = currentAudibleMultiplier()
        val ratio = if (clampedVolume > 0.01f) {
            ((previous * liveMult) / clampedVolume).coerceIn(0f, 1f)
        } else 0f
        track.setVolume(clampedVolume)
        val closeAfter = liveMult >= FADE_IDENTITY - FADE_EQUALITY_EPSILON
        if (!installFade(
                targetMultiplier = liveMult,
                fromMultiplier = ratio,
                closeOnComplete = closeAfter,
            )
        ) {
            // Инсталл сорвался - фиксируем конечное состояние принудительно
            track.setVolume(clampedVolume)
        }
    }
    
    /**
     * Установить частоту дискретизации
     *
     * АСИНХРОННАЯ РЕАЛИЗАЦИЯ:
     * - Все операции выполняются в audioHandler потоке
     * - Debounce предотвращает множественные перезапуски при быстрых переключениях
     * - Нет блокировок (synchronized) и Thread.sleep
     */
    fun setSampleRate(rate: SampleRate) {
        Log.d(TAG, "setSampleRate() called: ${rate.value} Hz")

        val handler = audioHandler
        if (handler == null) {
            Log.w(TAG, "setSampleRate() - audioHandler is null, updating sampleRate directly")
            if (sampleRate != rate.value) {
                sampleRate = rate.value
                nativeEngine?.setSampleRate(sampleRate)
            }
            return
        }

        // Отменяем предыдущие запросы на переключение (debounce)
        handler.removeCallbacksAndMessages(SAMPLE_RATE_CHANGE_TOKEN)

        // Если уже идёт перезапуск, просто обновляем pending rate
        if (restartPlaybackScheduled) {
            Log.d(TAG, "setSampleRate() - restart already scheduled, updating pending rate to ${rate.value} Hz")
            pendingSampleRate.set(rate)
            return
        }

        // Проверяем, нужно ли вообще переключение
        if (sampleRate == rate.value) {
            Log.d(TAG, "setSampleRate() - already at ${rate.value} Hz, skipping")
            return
        }

        // Планируем переключение с небольшой задержкой для debounce
        handler.postAtTime({
            executeSampleRateChange(rate)
        }, SAMPLE_RATE_CHANGE_TOKEN, System.currentTimeMillis() + 50)
    }

    /**
     * Выполнить переключение частоты дискретизации
     * Вызывается только из audioHandler потока
     */
    private fun executeSampleRateChange(rate: SampleRate) {
        Log.d(TAG, "executeSampleRateChange() called: ${rate.value} Hz")

        // Проверяем, не изменилось ли значение пока ждали в очереди
        if (sampleRate == rate.value) {
            Log.d(TAG, "executeSampleRateChange() - already at ${rate.value} Hz, skipping")
            return
        }

        val wasPlaying = _isPlaying.value

        if (wasPlaying) {
            // Останавливаем генерацию
            isActive.set(false)
            _isPlaying.value = false
            restartPlaybackScheduled = true

            // Отменяем все запланированные операции restart с другим токеном
            audioHandler?.removeCallbacksAndMessages(RESTART_PLAYBACK_TOKEN)

            // Анти-щелчок: гасим до нуля перед жёстким stop() трека
            // (симметрично fade-in'у после пересоздания).
            runFadeOutInline()

            // Останавливаем AudioTrack
            try {
                audioTrack?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
            }

            // Обновляем частоту дискретизации
            sampleRate = rate.value
            nativeEngine?.setSampleRate(sampleRate)
            Log.d(TAG, "Sample rate changed to ${sampleRate} Hz")

            // Освобождаем AudioTrack и перезапускаем воспроизведение
            audioHandler?.postAtTime({
                Log.d(TAG, "Executing scheduled restart: release AudioTrack and play")
                restartPlaybackScheduled = false

                // Проверяем, не запросили ли другую частоту пока выполнялся restart
                val pendingRate = pendingSampleRate.getAndSet(null)
                if (pendingRate != null && pendingRate.value != sampleRate) {
                    Log.d(TAG, "Pending sample rate change detected: ${pendingRate.value} Hz, executing")
                    executeSampleRateChange(pendingRate)
                    return@postAtTime
                }

                // Освобождаем и пересоздаём AudioTrack
                audioTrack?.release()
                audioTrack = null

                // Запускаем воспроизведение с новой частотой
                play()
            }, RESTART_PLAYBACK_TOKEN, System.currentTimeMillis() + 50)
        } else {
            // Если не воспроизводится, просто обновляем параметр
            sampleRate = rate.value
            nativeEngine?.setSampleRate(sampleRate)
            Log.d(TAG, "Sample rate changed to ${sampleRate} Hz (not playing)")
        }
    }
    
    fun getSampleRate(): SampleRate = SampleRate.fromValue(sampleRate)
    
    /**
     * Установить интервал генерации буфера
     * @param intervalMs интервал в миллисекундах (от 1 секунды до 60 минут)
     */
    fun setFrequencyUpdateInterval(intervalMs: Int) {
        // Максимум 60 минут = 3,600,000 мс
        val clampedInterval = intervalMs.coerceIn(1000, 60 * 60 * 1000)
        // C4: запоминаем пользовательский выбор — debug-время форсирует 250 мс,
        // и без этого восстановление вернуло бы не то, что выбрал пользователь
        if (!debugVirtualTimeEnabled) {
            lastUserIntervalMs = clampedInterval
        }
        pendingFrequencyUpdateIntervalMs.set(clampedInterval)
        Log.d(TAG, "Buffer generation interval set to $clampedInterval ms (${clampedInterval / 60000} min)")
    }

    fun getFrequencyUpdateInterval(): Int = frequencyUpdateIntervalMs
    
    /**
     * Проверить, включён ли режим энергосбережения
     */
    fun isPowerSaveMode(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isPowerSaveMode
    }
    
    /**
     * Получить адаптивный интервал обновления с учётом Battery Saver
     * В режиме энергосбережения интервал увеличивается в 3 раза
     */
    fun getAdaptiveFrequencyUpdateInterval(): Int {
        val baseInterval = frequencyUpdateIntervalMs
        return if (isPowerSaveMode()) {
            (baseInterval * POWER_SAVE_INTERVAL_MULTIPLIER).coerceAtMost(60000)
        } else {
            baseInterval
        }
    }
    
    /**
     * Применить адаптивный интервал при изменении режима энергосбережения
     * Вызывается из сервиса при получении ACTION_POWER_SAVE_MODE_CHANGED
     */
    fun applyPowerSaveMode() {
        val adaptiveInterval = getAdaptiveFrequencyUpdateInterval()
        if (adaptiveInterval != frequencyUpdateIntervalMs) {
            pendingFrequencyUpdateIntervalMs.set(adaptiveInterval)
            Log.d(TAG, "Power save mode changed, interval adjusted to $adaptiveInterval ms")
        }
    }

    // C1: отдельный лок для WakeLock — @Synchronized(this) блокировал бы
    // вызывающий поток resume на весь монитор сессии (ANR)
    private val wakeLockLock = Any()

    private fun acquireWakeLock() = synchronized(wakeLockLock) {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            }
            // K3: TTL должен покрывать запись пакета целиком (до 60 мин),
            // иначе лок истекает в середине длинной записи
            val ttlMs = maxOf(10 * 60 * 1000L, frequencyUpdateIntervalMs.toLong() + 120_000L)
            // C8: продлеваем только когда истёк — сессии дольше 10 минут остаются защищёнными
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(ttlMs)
                Log.d(TAG, "WakeLock acquired for ${ttlMs}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() = synchronized(wakeLockLock) {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    fun release() {
        val thread = audioThread
        val handler = audioHandler
        stop()
        // Отложенный финал встаёт В ХВОСТ живому циклу: доставится сразу после
        // его распрямления (стоп-флаг гарантирует выход) и переживёт quitSafely.
        // Никогда не освобождаем натив под работающим циклом генерации.
        val finish = Runnable {
            nativeEngine?.release()
            nativeEngine = null
            thread?.quitSafely()
            Log.d(TAG, "Audio engine released")
        }
        audioThread = null
        val h = audioHandler
        audioHandler = null
        h?.post(finish) ?: finish.run()
    }

    // ============ Debug virtual time (только debug-сборка) ============

    fun debugSetVirtualTimeEnabled(enabled: Boolean) {
        nativeEngine?.debugSetVirtualTimeEnabled(enabled)
        if (enabled) {
            debugVirtualTimeEnabled = true
            lastUserIntervalMs = frequencyUpdateIntervalMs
            // 250 мс: низкая латентность scrub + плотное следование кривой при scale до 60
            // (каждый буфер покрывает лишь scale*0.25 виртуальных секунд линейного рампа).
            pendingFrequencyUpdateIntervalMs.set(250)
            // Отключаем батч-генерацию, чтобы не было больших "замороженных" кусков.
            nativeEngine?.setBatchDurationMinutes(0)
        } else {
            debugVirtualTimeEnabled = false
            // C4: возврат к ПОЛЬЗОВАТЕЛЬСКОМУ интервалу (не хардкод)
            pendingFrequencyUpdateIntervalMs.set(lastUserIntervalMs)
        }
    }

    fun debugScrub(timeSeconds: Int) {
        nativeEngine?.debugScrub(timeSeconds)
        _currentTimeOfDaySeconds.value = timeSeconds
    }

    fun debugSetTimeScale(scale: Float) {
        nativeEngine?.debugSetTimeScale(scale)
    }

    fun debugSetRunning(running: Boolean) {
        nativeEngine?.debugSetRunning(running)
    }

    fun debugResetToRealTime() {
        nativeEngine?.debugResetToRealTime()
    }
}