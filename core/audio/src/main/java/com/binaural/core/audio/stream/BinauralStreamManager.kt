package com.binaural.core.audio.stream

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import com.binaural.core.audio.engine.NativeAudioEngine

import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralConfig
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ManagerState { IDLE, PREPARING, FADE_IN, RUNNING, HANDOFF, FADE_OUT_PAUSE, FADE_OUT_STOP, PAUSED }

/**
 * Актор-менеджер бинауральных потоков.
 * Единственный владелец состояния; все команды приходят сообщениями в его лупер,
 * поэтому гонки флагов (как в старом движке) отсутствуют структурно.
 *
 * Фасад намеренно повторяет API BinauralAudioEngine — сервис меняется одной строкой.
 */
class BinauralStreamManager(private val context: Context) {

    init {
        StreamLogger.init(context)
    }

    interface Listener {
        fun onStateChanged(state: ManagerState) {}
        fun onError(message: String) {}
    }

    companion object {
        private const val TAG = "BinauralStreamMgr"
        private const val WAKE_LOCK_TAG = "BinauralBeats:StreamManager"
        private const val POWER_SAVE_MULTIPLIER = 3
        /**
         * Границы интервала генерации (длины пакета) — пользовательская
         * настройка. Верхняя = 600 с (10 мин): это потолок, с которым буфер
         * влезает целиком на любом SR (600 с @48 кГц = 230 МБ на поток <
         * 256 МБ предела BinauralStreamImpl.maxBufferBytes на 64-бит). Выше —
         * упирается в RSS-бюджет LMK и на мощном устройстве, поэтому слайдер
         * не даёт выбрать больше. Нижняя — чтобы пакет покрывал хотя бы один
         * WRITE_CHUNK_MS.
         */
        private const val MIN_BUFFER_INTERVAL_MS = 1_000
        private const val MAX_BUFFER_INTERVAL_MS = 600_000
        /** Как часто подтверждаем удержание CPU во время воспроизведения. */
        private const val WAKE_LOCK_RENEW_MS = 5 * 60 * 1000L
        /**
         * Сколько максимум ждём освобождения старого трека, прежде чем начать
         * новый хэндофф (см. [orphanReleasing]). Защита от залипания автомата,
         * если колбэк релиза не придёт.
         */
        private const val ORPHAN_WAIT_MAX_MS = 2000L
    }

    private enum class FadeTarget { SWITCH, PAUSE, STOP }

    // ---------------- Актор ----------------
    private val actorThread = HandlerThread("BinauralStreamActor").apply { start() }
    private val actor = Handler(actorThread.looper)

    // ---------------- Входные настройки (пишутся только на актёре) ----------------
    private var config = BinauralConfig()
    private var relaxation = RelaxationModeSettings()
    @Volatile private var sampleRate = SampleRate.MEDIUM
    private var volume = 1.0f
    /**
     * Интервал генерации (= длина одного пакета, который нативный движок
     * считает за один JNI-вызов). Пользовательская настройка, дефолт 600 с
     * (10 мин) — слайдер ограничен ровно этим значением.
     *
     * История: до оптимизации здесь стояло 600_000 и кламп 1 ч, но длинный
     * пакет реально не экономил CPU (генерация ≈ 6.4 нс/кадр при любом
     * размере; CPU/час одинаков при 2 с и 190 с — 1.02 с), а платил 67 МБ
     * на поток (134 МБ в кроссфейде) + page-fault'ы + дорогую пересборку
     * потока на каждом handoff. Поэтому рабочий потолок осознанно снижен до
     * 600 с — ровно столько, сколько влезает в 256 МБ direct-буфера на
     * любом SR (см. BinauralStreamImpl.maxBufferBytes), то есть настройка
     * работает «как задумано», без тихого усечения. Дефолт совпадает с
     * MAX_BUFFER_INTERVAL_MS, поэтому не упирается в кламп и честно виден в UI.
     */
    private var bufferIntervalMs = 600_000
    private var lastUserIntervalMs = 600_000

    // Debug virtual time (применяется к каждому новому потоку)
    private var debugVirtualTime = false
    private var debugTimeScale = 1.0f
    private var debugRunning = true
    private var debugScrubPending: Int? = null

    // ---------------- Runtime (только актёр; чтение из других потоков — см. отметки) ----------------
    /**
     * Состояние автомата. @Volatile: [updateCurrentFrequencies] читает его из
     * потока опроса UI, чтобы заморозить часы сессии на паузе.
     */
    @Volatile
    private var state = ManagerState.IDLE
    private var current: BinauralStreamImpl? = null
    private var next: BinauralStreamImpl? = null

    /**
     * Старый CURRENT, уже сменяемый повышенным NEXT, но ещё НЕ отпустивший
     * AudioTrack. Именно он ломает инвариант «в кроссфейде звучат ровно два
     * потока» — на этот инвариант рассчитаны и EQUAL_POWER (sin²+cos²=1),
     * и предел кольца [BinauralStreamImpl] в 2 МБ.
     *
     * Замер на 23049PCD8G (смена пресета каждые 300 мс): треки накапливались,
     * потому что повышение NEXT происходит в момент ТИШИНЫ старого (≈310 мс),
     * а трек отпускается позже. Кучи клиента AudioFlinger (7 МБ при кольце
     * 2 МБ) хватает ровно на три; четвёртый падал:
     *   AF::TrackBase(123): not enough memory for AudioTrack size=2097384
     *   createTrack_l() initCheck failed -12
     *   beginHandoff: prepare NEXT spec#8 не удался — старый продолжает играть
     * Поэтому пока [orphanReleasing] жив, новый хэндофф НЕ начинается: спека
     * ждёт в очереди (побеждает новейшая) и стартует из [onStreamReleased].
     * Итог при любом темпе переключений — максимум CURRENT + NEXT + один
     * догорающий, то есть ровно то, что влезает в кучу AudioFlinger.
     */
    private var orphanReleasing: BinauralStreamImpl? = null
    private var orphanSinceMs = 0L

    private val queue = PlaybackQueue()
    private var serialSeq = 0L
    private var fadeTarget = FadeTarget.STOP
    private var pendingResume = false

    /**
     * За время паузы успели смениться настройки (конфиг, частота дискретизации,
     * debug-время, громкость не в счёт). Живой замороженный поток им не
     * соответствует: возобновление пойдёт через пересоздание потока с той же
     * слышимой позиции, а не через мягкое продолжение.
     */
    private var pausedSpecDirty = false

    // Сессия для resume
    private var sessionSpec: PlaybackSpec? = null
    private var accumulatedMs = 0L
    private var segmentStartWallMs = 0L
    private var pausedElapsedSeconds = 0
    private var pausedTimeOfDay = 0

    // ФИКС 3. Непрерывность при сквозном переключении сегментов (бесшовный кроссфейд):
    // точка кривой времени (секунды суток), с которой стартует NEXT, и пройденное
    // реальное время на момент переключения. Захватываются живыми из CURRENT в
    // beginHandoff/rearmNextIfStale и передаются в NEXT через обогащённую спеку.
    private var switchCurveTod: Float? = null
    private var switchElapsedMs: Long = 0L
    private var switchLeftPhase: Float? = null
    private var switchRightPhase: Float? = null
    // Маркер: ближайший launchStream — это продолжение handoff'а (не сбрасывать якорь).
    private var pendingHandoff = false
    // Wall-якорь начала хэндоффа: точный учёт сессионного времени при повышении NEXT.
    private var handoffStartWallMs = 0L

    // Снапшот для геттеров из других потоков
    private val currentRef = java.util.concurrent.atomic.AtomicReference<BinauralStreamImpl?>(null)

    var listener: Listener? = null

    // ---------------- UI-потоки (совместимые со старым движком) ----------------
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _managerState = MutableStateFlow(ManagerState.IDLE)
    val managerState: StateFlow<ManagerState> = _managerState.asStateFlow()
    private val _currentConfig = MutableStateFlow(BinauralConfig())
    val currentConfig: StateFlow<BinauralConfig> = _currentConfig.asStateFlow()
    private val _currentBeatFrequency = MutableStateFlow(0f)
    val currentBeatFrequency: StateFlow<Float> = _currentBeatFrequency.asStateFlow()
    private val _currentCarrierFrequency = MutableStateFlow(0f)
    val currentCarrierFrequency: StateFlow<Float> = _currentCarrierFrequency.asStateFlow()
    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()
    private val _currentTimeOfDaySeconds = MutableStateFlow(0)
    val currentTimeOfDaySeconds: StateFlow<Int> = _currentTimeOfDaySeconds.asStateFlow()
    private val _isChannelsSwapped = MutableStateFlow(false)
    val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()

    /** Для совместимости со старым API сервиса. */
    fun initialize() { /* актор уже запущен в конструкторе */ }

    // ================================================================== ФАСАД

    /**
     * Дедупликация настроек.
     *
     * Без неё каждый повторный пуш того же конфига (а их при рестарте Activity
     * прилетает 3–4 штуки: из onServiceConnected и из коллекторов DataStore)
     * рождает отдельный `PlaybackSpec` с новым serial и — если состояние уже не
     * RUNNING — отдельный кроссфейд. Четыре полных пересборки потока за секунду
     * = четыре AudioTrack, четыре direct-буфера и слышимый щелчок.
     */
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        actor.post {
            if (this.config == config && this.relaxation == relaxationSettings) return@post
            this.config = config
            this.relaxation = relaxationSettings
            _currentConfig.value = config
            onSpecChanged(SpecReason.SETTINGS)
        }
    }

    fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        actor.post {
            if (relaxation == settings) return@post
            relaxation = settings
            onSpecChanged(SpecReason.SETTINGS)
        }
    }

    fun updateFrequencyCurve(curve: FrequencyCurve) {
        actor.post {
            val merged = config.copy(frequencyCurve = curve)
            if (merged == config) return@post
            config = merged
            _currentConfig.value = config
            onSpecChanged(SpecReason.SETTINGS)
        }
    }

    fun play() = actor.post { onPlay() }
    fun stop() = stopWithFade()
    fun stopWithFade() = actor.post { onStop() }
    fun pauseWithFade() = actor.post { onPause() }
    fun resumeWithFade() = actor.post { onResume() }
    fun switchPresetWithFade(config: BinauralConfig) = updateConfig(config) // handoff автоматический

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        StreamLogger.d(TAG, "setVolume $volume -> $v")
        actor.post {
            // Повторная установка того же значения — не только лишний binder-вызов:
            // при рестарте Activity ViewModel пушит дефолт 0.7f поверх реальной
            // громкости, и это слышимый скачок уровня.
            if (kotlin.math.abs(this.volume - v) < 0.0001f) return@post
            this.volume = v
            current?.setVolume(v)   // live, без handoff
            // NEXT уже вооружён, но ЕЩЁ НЕ ЗВУЧИТ — громкость обязана дойти и до
            // него. Иначе правка уровня внутри окна кроссфейда (~250 мс между
            // beginHandoff и promoteNextToCurrent) досталась бы только уходящему
            // потоку: он замолкает, а новый пресет зазвучит по-старому.
            // Безопасно: на PREPARED-треке setVolume — это база, фейд-ин её
            // перемножает (шейпер 0->1), звука до старта нет.
            next?.setVolume(v)
        }
    }

    /** Текущая громкость менеджера (для сверки перед пушем из UI-слоя). */
    fun getVolume(): Float = volume

    fun setSampleRate(rate: SampleRate) {
        StreamLogger.d(TAG, "setSampleRate -> $rate")
        actor.post {
            if (sampleRate == rate) return@post
            sampleRate = rate
            onSpecChanged(SpecReason.SAMPLE_RATE)   // пересоздание движка через handoff
        }
    }
    fun getSampleRate(): SampleRate = sampleRate

    fun setFrequencyUpdateInterval(intervalMs: Int) {
        // Верхний предел 60 с — не «разумный расход памяти», а структурная
        // граница. Замер (docs/hotpath_optimization_analysis_2026-08-30.md):
        // длина пакета НЕ влияет на CPU/час (1.02 с при пакете и 2 с, и 190 с)
        // и НЕ влияет на wakeups писателя (их задаёт WRITE_CHUNK_MS). Платит
        // длинный пакет только памятью: 60 с @48 кГц = 23 МБ на поток.
        // Держим предел здесь, чтобы никакое значение из UI не могло вернуть
        // 67-мегабайтные буферы, на которых проект ловил OOM.
        val clamped = intervalMs.coerceIn(MIN_BUFFER_INTERVAL_MS, MAX_BUFFER_INTERVAL_MS)
        actor.post {
            // Повтор того же значения обязан быть пустым: иначе режим
            // энергосбережения (applyPowerSaveMode утром/вечером) каждый раз
            // перебивался бы пушем из ViewModel.
            if (bufferIntervalMs == clamped && lastUserIntervalMs == clamped) return@post
            if (!debugVirtualTime) lastUserIntervalMs = clamped
            bufferIntervalMs = clamped   // применится к следующему потоку
        }
    }
    fun getFrequencyUpdateInterval(): Int = bufferIntervalMs

    /**
     * Реакция на системный режим энергосбережения.
     *
     * ВАЖНО (фикс инвертированной логики): ранее здесь стояло
     * `(lastUserIntervalMs * POWER_SAVE_MULTIPLIER).coerceAtMost(60_000)`.
     * `coerceAtMost` — это min, поэтому при дефолте 600_000 мс (10 мин)
     * результат оказывался 60_000 мс (1 мин): в режиме энергосбережения
     * генерация запускалась в 10 раз ЧАЩЕ. Имелся в виду верхний предел
     * 60 минут = 3_600_000 мс.
     *
     * Правильная семантика: в энергосбережении буфер НЕ короче заданного
     * пользователем (иначе смысл настройки теряется), но и не больше
     * MAX_BUFFER_INTERVAL_MS — предела структурного, а не «разумного»:
     * длина пакета не влияет ни на CPU, ни на wakeups, только на память.
     */
    fun applyPowerSaveMode() {
        actor.post {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            bufferIntervalMs = if (pm.isPowerSaveMode) {
                (lastUserIntervalMs * POWER_SAVE_MULTIPLIER)
                    .coerceIn(lastUserIntervalMs, MAX_BUFFER_INTERVAL_MS)
            } else {
                lastUserIntervalMs
            }
        }
    }

    /** Поллинг частот для UI/уведомления (O(1) lookup в нативном движке активного потока). */
    fun updateCurrentFrequencies() {
        val s = currentRef.get()
        if (s != null) {
            s.getFrequenciesAtCurrentTime()?.let {
                _currentBeatFrequency.value = it.first
                _currentCarrierFrequency.value = it.second
            }
            _currentTimeOfDaySeconds.value = s.getCurrentTimeOfDay()
            // Часы сессии на паузе стоят: нативный elapsed считается по
            // wall-clock и иначе включил бы в себя всю длительность паузы.
            // (При возобновлении якорь переставляется — см. resumePausedStream.)
            _elapsedSeconds.value = if (state == ManagerState.PAUSED) {
                pausedElapsedSeconds
            } else {
                s.getElapsedSeconds()
            }
            val swapped = s.isChannelsSwapped()
            if (_isChannelsSwapped.value != swapped) _isChannelsSwapped.value = swapped
        } else {
            // Пауза/простой: держим замороженные значения (как и старый код — без воспроизведения не обновляли)
            _elapsedSeconds.value = pausedElapsedSeconds
            if (pausedTimeOfDay > 0) _currentTimeOfDaySeconds.value = pausedTimeOfDay
        }
    }

    fun getFrequenciesAtCurrentTime(): Pair<Float, Float>? = currentRef.get()?.getFrequenciesAtCurrentTime()

    // ---------------- Debug virtual time ----------------

    fun debugSetVirtualTimeEnabled(enabled: Boolean) = actor.post {
        StreamLogger.d(TAG, "debugSetVirtualTimeEnabled $enabled")
        debugVirtualTime = enabled
        if (enabled) { lastUserIntervalMs = bufferIntervalMs; bufferIntervalMs = 250 }
        else bufferIntervalMs = lastUserIntervalMs
        if (isActiveState()) requestHandoff(buildSpec(SpecReason.DEBUG))
    }
    fun debugScrub(timeSeconds: Int) = actor.post {
        StreamLogger.d(TAG, "debugScrub ${timeSeconds}s")
        debugScrubPending = timeSeconds
        _currentTimeOfDaySeconds.value = timeSeconds
        if (isActiveState()) requestHandoff(buildSpec(SpecReason.DEBUG))
    }
    fun debugSetTimeScale(scale: Float) = actor.post {
        StreamLogger.d(TAG, "debugSetTimeScale $scale")
        debugTimeScale = scale.coerceIn(1f, 60f)
        if (isActiveState()) requestHandoff(buildSpec(SpecReason.DEBUG))
    }
    fun debugSetRunning(running: Boolean) = actor.post {
        StreamLogger.d(TAG, "debugSetRunning $running")
        debugRunning = running
        if (isActiveState()) requestHandoff(buildSpec(SpecReason.DEBUG))
    }
    fun debugResetToRealTime() = actor.post {
        debugVirtualTime = false; debugScrubPending = null
        bufferIntervalMs = lastUserIntervalMs
        if (isActiveState()) requestHandoff(buildSpec(SpecReason.DEBUG))
    }

    fun release() {
        StreamLogger.d(TAG, "release()")
        actor.post {
            queue.clear()
            discardNext()
            orphanReleasing = null          // менеджер всё равно утилизируется
            current?.stop(onFullyStopped = { /* утилизация */ })
            current = null; currentRef.set(null)
            resetSession()
            _isPlaying.value = false
            setState(ManagerState.IDLE)
            updateWakeLock()
        }
        StreamLogger.flush()
        actorThread.quitSafely()
    }

    // ================================================================== ЛОГИКА АКТЁРА

    private fun isActiveState() =
        state == ManagerState.RUNNING || state == ManagerState.FADE_IN || state == ManagerState.HANDOFF

    private fun buildSpec(reason: SpecReason) = PlaybackSpec(
        serial = ++serialSeq,
        config = config,
        relaxation = relaxation,
        sampleRate = sampleRate,
        volume = volume,
        reason = reason
    )

    private fun setState(newState: ManagerState) {
        if (state != newState) {
            state = newState
            _managerState.value = newState
            listener?.onStateChanged(newState)
        }
    }

    /** Любое изменение настроек: маршрутизация по состояниям. */
    private fun onSpecChanged(reason: SpecReason) {
        when (state) {
            ManagerState.IDLE -> sessionSpec = buildSpec(reason)
            ManagerState.PAUSED -> {
                // PAUSED держит живой замороженный поток, звучащий по СТАРОЙ
                // спеке. Настройки применяются только при возобновлении, поэтому
                // помечаем замороженный поток «грязным» — иначе мягкое
                // возобновление молча проигнорировало бы новую спеку.
                val spec = buildSpec(reason)
                sessionSpec = spec
                if (current?.spec?.audioEquals(spec) != true) pausedSpecDirty = true
            }
            ManagerState.FADE_OUT_PAUSE, ManagerState.FADE_OUT_STOP -> queue.offer(buildSpec(reason))
            else -> requestHandoff(buildSpec(reason))
        }
    }

    private fun requestHandoff(spec: PlaybackSpec) {
        // Быстрый путь: изменилась только громкость — потоки не пересоздаём.
        val cur = current
        if (state == ManagerState.RUNNING && cur != null && cur.spec.audioEquals(spec)) {
            cur.setVolume(spec.volume); return
        }
        queue.offer(spec)                       // коалесценция: побеждает новейший
        when (state) {
            ManagerState.HANDOFF -> rearmNextIfStale()
            ManagerState.RUNNING, ManagerState.FADE_IN -> beginHandoff()
            // ...начало requestHandoff() уже написано выше...
            ManagerState.PREPARING -> {
                // PREPARING — транзиентное синхронное состояние на актёре;
                // спека уже в очереди, её разберёт автомат по завершении подготовки.
            }
            else -> { /* недостижимо: onSpecChanged маршрутизирует иначе */ }
        }
    }

    // Дополнительное runtime-поле (продолжение): запрос НА СТАРТ для паттерна
    // «stopWithFade -> play». В отличие от очереди настроек — это именно намерение стартовать.
    private var pendingPlaySpec: PlaybackSpec? = null

    /**
     * true — третий живой AudioTrack не влезет в кучу клиента AudioFlinger,
     * новый хэндофф начинать рано (см. [orphanReleasing]).
     *
     * Самоочистка двойная: по факту [StreamLifecycle.RELEASED] и по таймауту
     * [ORPHAN_WAIT_MAX_MS]. Второе — не «на всякий случай», а защита от
     * залипания: если колбэк релиза по какой-то причине не придёт, автомат
     * обязан продолжить работу, даже ценой лишнего трека.
     */
    private fun handoffBlocked(): Boolean {
        val o = orphanReleasing ?: return false
        if (o.lifecycle == StreamLifecycle.RELEASED) {
            orphanReleasing = null
            return false
        }
        if (System.currentTimeMillis() - orphanSinceMs > ORPHAN_WAIT_MAX_MS) {
            StreamLogger.w(TAG, "handoffBlocked: старый spec#${o.spec.serial} " +
                "(lc=${o.lifecycle}) не освободился за ${ORPHAN_WAIT_MAX_MS}мс — не ждём")
            orphanReleasing = null
            return false
        }
        return true
    }

    /**
     * Разыграть очередь хэндоффов, когда больше ничто не мешает. Вызывается и
     * сразу после повышения NEXT (обычно — «не сейчас»), и из [onStreamReleased],
     * когда старый трек наконец отпущен.
     */
    private fun drainQueuedHandoff() {
        if (handoffBlocked()) return
        if (!isActiveState()) return
        val spec = queue.peek() ?: return
        // Уже играем ровно это — очередь просто устарела.
        if (current?.spec?.audioEquals(spec) == true) {
            queue.poll()
            return
        }
        if (state == ManagerState.HANDOFF) rearmNextIfStale() else beginHandoff()
    }

    /**
     * HANDOFF-КРОССФЕЙД: фейд-аут CURRENT стартует НЕМЕДЛЕННО (до всякой
     * подготовки), NEXT готовится внутри окна фейд-аута и начинает фейд-ин
     * ровно в момент тишины CURRENT — см. [onStreamSilent]/[promoteNextToCurrent].
     *
     * Порядок строго последовательный (сначала гасим старое, потом поднимаем
     * новое), но БЕЗ разрыва: переходы стыкуются в одном сообщении актёра.
     */
    private fun beginHandoff() {
        val spec = queue.peek() ?: return
        // Третий живой AudioTrack не влезет в кучу клиента AudioFlinger
        // (см. [orphanReleasing]) — спека остаётся в очереди, а стартует она
        // из onStreamReleased(). Побеждает новейшая, промежуточные пресеты не
        // теряются: просто последний из серии приходит на смену первому.
        if (handoffBlocked()) {
            StreamLogger.d(TAG, "beginHandoff отложен: spec#${spec.serial} ждёт освобождения " +
                "старого трека spec#${orphanReleasing?.spec?.serial}")
            return
        }
        if (current == null) {
            // Гасить нечего — это не кроссфейд, а обычный запуск.
            StreamLogger.d(TAG, "beginHandoff: current==null — обычный запуск spec#${spec.serial}")
            launchSpec(queue.poll() ?: return)
            return
        }
        // ФИКС 3. Захватываем живые координаты CURRENT ДО его fade-out — точку на
        // кривой и пройденное время. NEXT стартует ровно отсюда => бесшовный
        // кроссфейд без скачка фазы/частоты и без сброса часов сессии.
        captureContinuity()
        pendingHandoff = true
        handoffStartWallMs = System.currentTimeMillis()
        val enriched = enrichForContinuity(spec)
        // Обогащённую спеку кладём обратно в очередь: если prepare() не успеет к
        // моменту тишины CURRENT, спеку поднимет onStreamFullyStopped() — и она
        // обязана нести continuity, иначе новый поток стартовал бы с нуля.
        queue.offer(enriched)
        StreamLogger.d(TAG, "beginHandoff spec#${spec.serial}: фейд-аут CURRENT стартует немедленно, " +
            "фейд-ин NEXT — ровно в момент его тишины (curveTod=$switchCurveTod, " +
            "elapsed=${switchElapsedMs}ms, phase=$switchLeftPhase/$switchRightPhase)")

        // 1. ФЕЙД-АУТ ПЕРВЫМ, до всякой подготовки.
        //
        // Раньше первым готовился NEXT и только потом гасился CURRENT: реакция
        // на нажатие откладывалась на prepare() (создание AudioTrack через
        // binder + allocateDirect + генерация первого пакета — десятки
        // миллисекунд на нити актёра, а актёр в это время не исполняет вообще
        // ничего, включая сам фейд). Теперь звук уходит в тишину в первом же
        // сообщении актёра.
        //
        // Порядок строго последовательный, а не параллельный: два бинауральных
        // пресета с РАЗНЫМИ несущими и биениями, звучащие одновременно, дают
        // биения между собой (разностная частота |f1-f2| попадает в слышимый
        // диапазон и воспринимается как грязь/гул) — ровно поэтому
        // перекрывающийся equal-power кроссфейд здесь звучит хуже, чем
        // «догасить старое, поднять новое». Перекрытия нет, но и разрыва нет:
        // фейд-ин стартует в том же сообщении актёра, где CURRENT дошёл до нуля.
        fadeOutCurrent(FadeTarget.SWITCH)

        // 2. NEXT готовится ПОД ПРИКРЫТИЕМ фейд-аута: 250 мс с большим запасом
        //    хватает на prepare(). Если подготовка всё же не успела к моменту
        //    тишины — спека не теряется, её поднимет onStreamFullyStopped().
        val candidate = createStream(enriched)
        if (!candidate.prepare()) {
            StreamLogger.e(TAG, "beginHandoff: prepare NEXT spec#${spec.serial} не удался — " +
                "фейд-аут уже идёт, спеку поднимет onStreamFullyStopped")
            listener?.onError("stream prepare failed (spec#${spec.serial}); restarting")
            pendingHandoff = false
            return
        }
        next = candidate
        // Громкость ДО start(): база нужна к первому кадру под множителем ~0.
        candidate.setVolume(volume)
        StreamLogger.d(TAG, "beginHandoff spec#${spec.serial}: NEXT вооружён и ждёт тишины CURRENT")
    }

    /**
     * Шторм настроек во время кроссфейда.
     * - NEXT уже ЗВУЧИТ (защитная ветка): повышаем его досрочно до current,
     *   хвост очереди разберёт [promoteNextToCurrent].
     * - NEXT не звучит (вооружён/отсутствует) — тихая замена вооружённого
     *   потока. Стартовать его здесь НЕЛЬЗЯ: фейд-ин обязан начаться ровно в
     *   момент тишины CURRENT, иначе два пресета зазвучат одновременно
     *   (см. комментарий про биения в [beginHandoff]). Стартует его
     *   [promoteNextToCurrent] из [onStreamSilent].
     */
    private fun rearmNextIfStale() {
        val spec = queue.peek() ?: return
        val n = next
        if (n != null && n.spec.audioEquals(spec)) return

        if (n != null && n.lifecycle == StreamLifecycle.PLAYING) {
            // ФИКС Щ1. Промоушен потока, который ещё в fade-in, — это щелчок.
            // Повышенный NEXT тут же получает fade-out от нового beginHandoff(),
            // а applyShaper() в ветке «замена активного шейпера» между
            // setVolume(base) и apply(PLAY) даёт эффективную громкость
            // userVolume·fromC² — провал втрое при fromC≈0.3.
            //
            // Правильно — дождаться конца fade-in: спека уже лежит в очереди
            // (latest-wins), и её разберёт promoteNextToCurrent().
            if (n.isFadingIn) {
                StreamLogger.d(TAG, "rearmNextIfStale: NEXT spec#${n.spec.serial} ещё в fade-in — " +
                    "повышение отложено до конца фейд-ина; spec#${spec.serial} ждёт в очереди")
                return
            }
            StreamLogger.d(TAG, "rearmNextIfStale: NEXT spec#${n.spec.serial} играет — повышаем и новый хэндофф к spec#${spec.serial}")
            promoteNextToCurrent()   // сама поднимет хвост очереди и вызовет beginHandoff
            return
        }

        // NEXT отсутствует или ещё не звучал — тихая замена.
        if (n != null) {
            StreamLogger.d(TAG, "rearmNextIfStale: вооружённый NEXT (lc=${n.lifecycle}) устарел — тихая замена")
            n.abort() // никогда не звучал — бесшумно
            next = null
        }
        // Непрерывность: координаты CURRENT (ещё жив) обновляем для нового NEXT.
        captureContinuity()
        val enriched = enrichForContinuity(spec)
        val candidate = createStream(enriched)
        if (!candidate.prepare()) {
            StreamLogger.w(TAG, "rearmNextIfStale: prepare не удался; повтор по завершении фейда")
            return
        }
        next = candidate
        candidate.setVolume(volume)
        StreamLogger.d(TAG, "rearmNextIfStale: NEXT spec#${spec.serial} вооружён и ждёт тишины CURRENT")
    }

    /** Сменить цель идущего/нового фейда И запустить фейд. */
    private fun fadeOutCurrent(target: FadeTarget) {
        retargetFade(target)
        val s = current
        if (s == null) {
            StreamLogger.d(TAG, "fadeOutCurrent: current==null, сразу onStreamFullyStopped (target=$target)")
            onStreamFullyStopped()
            return
        }
        StreamLogger.d(TAG, "fadeOutCurrent target=$target spec#${s.spec.serial} lifecycle=${s.lifecycle}")
        val captured = s
        val crossfade = target == FadeTarget.SWITCH
        // Колбэки исполняются на нити актёра (у потока controlHandler == actor).
        // Идентичность (captured === current) отсекает «осиротевшие» потоки,
        // чья судьба уже решена отдельно (раннее повышение при шторме, discard).
        captured.stop(
            onFullyStopped = { onStreamReleased(captured) },
            onSilent = if (crossfade) ({ onStreamSilent(captured) }) else null,
            shape = if (crossfade) FadeShape.EQUAL_POWER else FadeShape.LINEAR
        )
    }

    /**
     * CURRENT дошёл до нуля в кроссфейде: уже тих, релиз ещё может идти.
     *
     * Это и есть точка старта фейд-ина NEXT. Важно, что повышение и старт
     * происходят В ТОМ ЖЕ сообщении актёра, в котором CURRENT сообщил о тишине:
     * между окончанием фейд-аута и началом фейд-ина нет ни ожидания релиза
     * старого трека, ни таймера — только длительность самого перехода.
     */
    private fun onStreamSilent(s: BinauralStreamImpl) {
        if (s !== current) return   // осиротел (шторм/стоп) — его судьба решена отдельно
        if (fadeTarget != FadeTarget.SWITCH || state != ManagerState.HANDOFF) return
        val n = next ?: return
        if (n.lifecycle != StreamLifecycle.PREPARED) return
        promoteNextToCurrent()
    }

    /**
     * Поток полностью освобождён. Фильтр идентичности: релиз осиротевшего потока
     * (раннее повышение при шторме, discard при стопе/паузе) не трогает автомат.
     */
    private fun onStreamReleased(s: BinauralStreamImpl) {
        // Освободился слот под третий AudioTrack — разыгрываем отложенный хэндофф.
        if (s === orphanReleasing) {
            orphanReleasing = null
            StreamLogger.d(TAG, "onStreamReleased: старый spec#${s.spec.serial} освобождён — " +
                "разыгрываем отложенный хэндофф")
            drainQueuedHandoff()
            return
        }
        if (s !== current) {
            StreamLogger.d(TAG, "onStreamReleased: orphan spec#${s.spec.serial} — игнор")
            return
        }
        onStreamFullyStopped()
    }

    /**
     * Повышение NEXT — ровно в момент тишины CURRENT.
     *
     * Кроссфейд ПОСЛЕДОВАТЕЛЬНЫЙ: до этой точки звучал только CURRENT (фейд-аут),
     * отсюда звучит только NEXT (фейд-ин). Перекрытия нет — два бинауральных
     * пресета с разными несущими, sounding одновременно, порождают слышимые
     * разностные биения. Разрыва тоже нет: [onStreamSilent] приходит из того же
     * сообщения актёра, в котором CURRENT дошёл до нуля, и start() NEXT
     * исполняется сразу за ним.
     *
     * Фикс 3 сохранён: NEXT несёт switchCurveTod/switchElapsedMs из обогащённой спеки.
     */
    private fun promoteNextToCurrent() {
        val n = next ?: return
        val outgoing = current
        StreamLogger.d(TAG, "promoteNextToCurrent: spec#${n.spec.serial} — CURRENT в тишине, " +
            "фейд-ин NEXT стартует без паузы")
        next = null
        current = n
        currentRef.set(n)
        // Старый поток уже в тишине, но трек отпустит только после выхода
        // писателя — всё это время он занимает слот в куче AudioFlinger.
        orphanReleasing = outgoing
        orphanSinceMs = System.currentTimeMillis()
        sessionSpec = n.spec
        // Сессионное время: NEXT несёт switchElapsedMs + фактическую длительность кроссфейда.
        accumulatedMs = switchElapsedMs + (System.currentTimeMillis() - handoffStartWallMs)
        segmentStartWallMs = System.currentTimeMillis()
        pendingHandoff = false
        _isPlaying.value = true
        updateWakeLock()

        if (n.lifecycle == StreamLifecycle.PREPARED) {
            // Штатный путь: NEXT молчит, стартуем его фейд-ин здесь же.
            setState(ManagerState.FADE_IN)
            if (!n.start(
                    onFullyStarted = {
                        StreamLogger.d(TAG, "promoteNextToCurrent: NEXT spec#${n.spec.serial} " +
                            "фейд-ин завершён — кроссфейд окончен")
                        if (state == ManagerState.FADE_IN) setState(ManagerState.RUNNING)
                    },
                    shape = FadeShape.EQUAL_POWER
                )
            ) {
                StreamLogger.e(TAG, "promoteNextToCurrent: start NEXT spec#${n.spec.serial} не удался")
                n.abort()
                current = null
                currentRef.set(null)
                _isPlaying.value = false
                setState(ManagerState.IDLE)
                listener?.onError("next stream start failed")
                updateWakeLock()
                return
            }
        } else {
            // Защитная ветка: NEXT уже звучит (промоушен из rearmNextIfStale).
            setState(ManagerState.RUNNING)
        }
        // Хвост шторма: если за время кроссфейда прилетела спека новее — сразу новый хэндофф.
        val tail = queue.poll()
        if (tail != null && !tail.audioEquals(n.spec)) {
            StreamLogger.d(TAG, "promoteNextToCurrent: хвост очереди spec#${tail.serial} новее — новый хэндофф")
            queue.offer(tail)
            // НЕ beginHandoff() напрямую: трек старого потока ([orphanReleasing])
            // ещё не отпущен, и prepare NEXT провалился бы на создании AudioTrack.
            drainQueuedHandoff()
        } else {
            resetContinuity()
        }
    }

    /**
     * Выбросить NEXT без щелчка: играющий — фейд-аут от текущего множителя
     * (огибающая непрерывна), не играющий — тихий abort.
     */
    private fun discardNext() {
        val n = next ?: return
        next = null
        pendingHandoff = false
        when (n.lifecycle) {
            StreamLifecycle.PLAYING -> {
                StreamLogger.d(TAG, "discardNext: spec#${n.spec.serial} играет — fade-out и релиз")
                n.stop(
                    onFullyStopped = { /* релиз завершится независимо; состояние не трогаем */ },
                    shape = FadeShape.EQUAL_POWER
                )
            }
            StreamLifecycle.CREATED, StreamLifecycle.PREPARED, StreamLifecycle.FAILED -> n.abort()
            else -> {} // STOPPING/RELEASED — уже уходит сам
        }
    }

    /** Ретаргет уже идущего фейда без повторного stream.stop (он идемпотентен). */
    private fun retargetFade(target: FadeTarget) {
        fadeTarget = target
        StreamLogger.d(TAG, "retargetFade -> $target")
        setState(
            when (target) {
                FadeTarget.SWITCH -> ManagerState.HANDOFF
                FadeTarget.PAUSE -> ManagerState.FADE_OUT_PAUSE
                FadeTarget.STOP -> ManagerState.FADE_OUT_STOP
            }
        )
        if (target != FadeTarget.SWITCH) _isPlaying.value = false
    }

    /**
     * Старый поток завершил fade-out в ноль и ПОЛНОСТЬЮ освобождён.
     * ЕДИНСТВЕННЫЙ момент, когда разыгрывается очередь воспроизведения.
     */
    private fun onStreamFullyStopped() {
        StreamLogger.d(TAG, "onStreamFullyStopped fadeTarget=$fadeTarget queueSize=${queue.size()}")
        current = null
        currentRef.set(null)
        when (fadeTarget) {
            FadeTarget.PAUSE -> {
                // Сюда попадаем, только если мягкая пауза не состоялась (поток
                // ушёл в утилизацию): позиция переносится в snapped-значениях,
                // а возобновление пойдёт через новый поток.
                pendingHandoff = false
                resetContinuity()
                accumulatedMs += System.currentTimeMillis() - segmentStartWallMs
                // Настройки, прилетевшие во время фейда: живой поток (если он
                // ещё есть) им не соответствует — возобновление пересоберёт его.
                queue.poll()?.let { sessionSpec = it; pausedSpecDirty = true }
                setState(ManagerState.PAUSED)
                StreamLogger.d(TAG, "onStreamFullyStopped: PAUSE -> накоплено accumulatedMs=$accumulatedMs")
                if (pendingResume) {
                    pendingResume = false
                    onResumeFromPaused()
                }
            }

            FadeTarget.STOP -> {
                val queued = queue.poll()
                val playSpec = pendingPlaySpec
                pendingPlaySpec = null
                resetSession()
                // ФИКС Б4: если во время фейд-аута прилетели настройки новее снапшота,
                // снятого в момент play(), берём их (последняя команда побеждает).
                val finalSpec = when {
                    playSpec == null -> null
                    queued != null && !queued.audioEquals(playSpec) -> queued.copy(reason = SpecReason.PLAY)
                    else -> playSpec
                }
                if (finalSpec != null) {
                    // play(), пришедший во время фейд-аута: старт строго после него
                    StreamLogger.d(TAG, "onStreamFullyStopped: STOP -> play пришёл во время фейда, старт spec#${finalSpec.serial}")
                    sessionSpec = finalSpec
                    launchSpec(finalSpec)
                } else {
                    if (queued != null) sessionSpec = queued // запомнить для следующего play
                    StreamLogger.d(TAG, "onStreamFullyStopped: STOP -> IDLE (queued=${queued?.serial})")
                    setState(ManagerState.IDLE)
                }
            }

            FadeTarget.SWITCH -> {
                // Сюда попадаем, только если кроссфейд не состоялся: NEXT не был
                // подготовлен к моменту тишины CURRENT (prepare не удался или не
                // успел) либо погиб в рантайме. current к этому моменту уже
                // занулён выше, поэтому «повторить хэндофф против живого current»
                // невозможно — поднимаем спеку обычным запуском.
                val spec = queue.poll()
                if (spec == null) {
                    resetSession()
                    StreamLogger.d(TAG, "onStreamFullyStopped: SWITCH без спек — IDLE")
                    setState(ManagerState.IDLE)
                } else {
                    StreamLogger.w(TAG, "onStreamFullyStopped: SWITCH без NEXT -> launchSpec spec#${spec.serial}")
                    launchSpec(spec)
                }
            }
        }
        updateWakeLock()
    }

    // ================================================================== Обработчики команд

    private fun onPlay() {
        StreamLogger.d(TAG, "onPlay state=${state.name} queueSize=${queue.size()}")
        when (state) {
            ManagerState.IDLE -> {
                val spec = queue.poll() ?: buildSpec(SpecReason.PLAY)
                accumulatedMs = 0L
                sessionSpec = spec
                StreamLogger.d(TAG, "onPlay: IDLE -> launchSpec spec#${spec.serial}")
                launchSpec(spec)
            }
            ManagerState.PAUSED -> onResume()
            ManagerState.FADE_OUT_PAUSE -> {
                // Дать фейду дойти до PAUSED и сразу возобновить
                StreamLogger.d(TAG, "onPlay: во время FADE_OUT_PAUSE -> pendingResume")
                pendingResume = true
            }
            ManagerState.FADE_OUT_STOP -> {
                // Паттерн перезапуска (stopWithFade -> play): старт строго после фейда
                StreamLogger.d(TAG, "onPlay: во время FADE_OUT_STOP -> pendingPlaySpec")
                pendingPlaySpec = buildSpec(SpecReason.PLAY)
            }
            else -> { /* PREPARING/FADE_IN/RUNNING/HANDOFF: идемпотентно */ }
        }
    }

    private fun onStop() {
        StreamLogger.d(TAG, "onStop state=${state.name}")
        when (state) {
            ManagerState.RUNNING, ManagerState.FADE_IN -> {
                next?.abort(); next = null
                queue.clear()
                pendingPlaySpec = null
                fadeOutCurrent(FadeTarget.STOP)
            }
            ManagerState.HANDOFF -> {
                // Кроссфейд идёт: гасим ОБА потока без щелчков.
                // Фейд CURRENT уже идёт — только ретаргет; NEXT гасим сами.
                queue.clear()
                pendingPlaySpec = null
                pendingHandoff = false
                resetContinuity()
                discardNext()
                retargetFade(FadeTarget.STOP)
            }
            ManagerState.FADE_OUT_PAUSE -> {
                queue.clear()
                pendingResume = false
                pendingPlaySpec = null
                retargetFade(FadeTarget.STOP)
            }
            ManagerState.FADE_OUT_STOP -> {
                // ФИКС Б1: повторный stop во время идущего fade-out в ноль.
                // Без сброса pendingPlaySpec от более раннего play доживёт до
                // onStreamFullyStopped и запустит воспроизведение вопреки stop.
                queue.clear()
                pendingPlaySpec = null
                pendingResume = false
                retargetFade(FadeTarget.STOP) // идемпотентно: цель уже STOP
            }
            ManagerState.PAUSED -> {
                // PAUSED держит ЖИВОЙ замороженный поток (AudioTrack + нативный
                // движок + посчитанный пакет). Раньше его не существовало —
                // релиз происходил внутри fade-out. Теперь освобождать надо явно.
                queue.clear()
                discardPausedCurrent()
                resetSession()          // в т.ч. pausedSpecDirty = false
                setState(ManagerState.IDLE)
                updateWakeLock()
            }
            else -> { /* IDLE: идемпотентно */ }
        }
    }

    private fun onPause() {
        StreamLogger.d(TAG, "onPause state=${state.name}")
        when (state) {
            ManagerState.RUNNING, ManagerState.FADE_IN -> {
                capturePauseMetrics()
                pauseCurrentSoftly()
            }
            ManagerState.HANDOFF -> {
                capturePauseMetrics()
                pendingHandoff = false
                resetContinuity()
                discardNext()
                // CURRENT уже гаснет кроссфейдом — pause() перехватывает рампу:
                // финалом становится заморозка, а не утилизация.
                pauseCurrentSoftly()
            }
            ManagerState.FADE_OUT_STOP -> {
                capturePauseMetrics()
                pendingPlaySpec = null
                pendingResume = false
                retargetFade(FadeTarget.PAUSE)
            }
            ManagerState.FADE_OUT_PAUSE -> {
                // ФИКС Б2: повторная пауза во время идущего fade-out обязана снять
                // намерение возобновления, иначе по завершении фейда сработает
                // «призрачный» resumeFromPaused вопреки финальному pause.
                pendingResume = false
                capturePauseMetrics()
                retargetFade(FadeTarget.PAUSE) // идемпотентно
            }
            else -> { /* IDLE/PAUSED: no-op */ }
        }
    }

    /**
     * МЯГКАЯ ПАУЗА. Звук гасится рампой, после чего трек уходит в pause() —
     * но НЕ в утилизацию: AudioTrack, нативный движок (фазы, своп, положение
     * на кривой) и уже сгенерированный пакет остаются живы. Раньше пауза
     * уничтожала поток целиком, выбрасывая до 60 минут посчитанного PCM и
     * пересоздавая движок на возобновлении.
     */
    private fun pauseCurrentSoftly() {
        val s = current
        if (s == null) {
            StreamLogger.d(TAG, "pauseCurrentSoftly: current==null — сразу PAUSED")
            onPausedFully()
            return
        }
        retargetFade(FadeTarget.PAUSE)      // _isPlaying=false, state=FADE_OUT_PAUSE
        if (!s.pause(onPaused = ::onPausedFully)) {
            // Поток уже утилизируется — мягкая пауза невозможна, прежний путь.
            StreamLogger.w(TAG, "pauseCurrentSoftly: мягкая пауза недоступна spec#${s.spec.serial} — утилизация")
            fadeOutCurrent(FadeTarget.PAUSE)
        }
    }

    /**
     * Мягкая пауза состоялась: поток заморожен, но ЖИВ и ждёт возобновления.
     */
    private fun onPausedFully() {
        if (fadeTarget != FadeTarget.PAUSE) {
            StreamLogger.d(TAG, "onPausedFully: цель уже $fadeTarget — игнор (поток утилизирован)")
            return
        }
        accumulatedMs += System.currentTimeMillis() - segmentStartWallMs
        // Настройки, прилетевшие за время фейда: замороженный поток звучит по
        // старой спеке, поэтому возобновление пересоберёт его с той же позиции.
        val queued = queue.poll()
        val live = current
        if (queued != null) {
            sessionSpec = queued
            if (live?.spec?.audioEquals(queued) != true) pausedSpecDirty = true
        }
        // Позиция снималась в capturePauseMetrics() ДО фейд-аута: за время рампы
        // трек доигрывал, поэтому переснимаем по факту заморозки — иначе путь
        // через пересборку потока отстал бы на длительность фейда.
        live?.let {
            pausedElapsedSeconds = it.getElapsedSeconds()
            pausedTimeOfDay = it.getAudibleTimeOfDaySeconds()
        }
        setState(ManagerState.PAUSED)
        StreamLogger.d(TAG, "onPausedFully: PAUSED, поток жив spec#${live?.spec?.serial} " +
            "(accumulatedMs=$accumulatedMs, dirty=$pausedSpecDirty)")
        updateWakeLock()
        if (pendingResume) {
            pendingResume = false
            onResumeFromPaused()
        }
    }

    private fun onResume() {
        StreamLogger.d(TAG, "onResume state=${state.name} pausedSpecDirty=$pausedSpecDirty")
        when (state) {
            ManagerState.IDLE -> onPlay()   // ещё не играли — старт (play() сам обработает IDLE)
            ManagerState.PAUSED -> onResumeFromPaused()
            ManagerState.FADE_OUT_PAUSE -> {
                // ФИКС 2. Разворот рампы (reverseFadeToPlaying) САМ вызывает щелчок —
                // это тот же разрыв непрерывности громкости. Не делаем разворот: ждём,
                // пока текущий fade-out дойдёт до PAUSED, и там возобновляем чистым
                // стартом (fade-in из нуля — бесшумно).
                StreamLogger.d(TAG, "onResume: в FADE_OUT_PAUSE -> ждём PAUSED, затем resumeFromPaused")
                pendingResume = true
            }
            ManagerState.FADE_OUT_STOP -> {
                // Зеркало onPlay(): пока старый поток гаснет в ноль, намерение
                // играть надо запомнить, иначе нажатие «play» в этом окне
                // молча терялось (else -> no-op) и воспроизведение «не
                // возобновлялось 10-20 с». Старт разыграет onStreamFullyStopped.
                StreamLogger.d(TAG, "onResume: во время FADE_OUT_STOP -> pendingPlaySpec")
                pendingPlaySpec = buildSpec(SpecReason.PLAY)
            }
            else -> { /* no-op */ }
        }
    }

    /**
     * Возобновление из PAUSED: мягкое (тот же поток и тот же пакет) либо —
     * если за паузу менялись настройки — через новый поток с той же позиции.
     */
    private fun onResumeFromPaused() {
        if (pausedSpecDirty) {
            StreamLogger.d(TAG, "onResumeFromPaused: настройки менялись на паузе — новый поток")
            resumeFromPaused()
        } else {
            resumePausedStream()
        }
    }

    /**
     * Мягкое возобновление: тот же поток продолжает с сохранённого сэмпла.
     * Пакет не перегенерируется, позиция на кривой не двигается, фазы не
     * сбрасываются — звук продолжается ровно там, где был остановлен.
     */
    private fun resumePausedStream() {
        val s = current
        if (s == null || !s.isPaused) {
            StreamLogger.w(TAG, "resumePausedStream: поток непригоден (null=${s == null}) — пересоздание")
            resumeFromPaused()
            return
        }
        StreamLogger.d(TAG, "resumePausedStream: spec#${s.spec.serial} — мягкое продолжение (буфер сохранён)")
        // Часы сессии: нативный elapsed идёт по wall-clock, поэтому якорь
        // переставляется — иначе в elapsed попала бы вся длительность паузы.
        s.setPlaybackStartTime(System.currentTimeMillis() - accumulatedMs)
        segmentStartWallMs = System.currentTimeMillis()
        setState(ManagerState.FADE_IN)
        _isPlaying.value = true
        updateWakeLock()
        s.setVolume(volume)
        if (!s.resume(onFullyStarted = {
                if (state == ManagerState.FADE_IN) setState(ManagerState.RUNNING)
            })
        ) {
            // Трек не поддался (например, HAL отобрал устройство) — продолжаем
            // новым потоком с той же слышимой позиции.
            StreamLogger.e(TAG, "resumePausedStream: возобновление не удалось — пересоздание")
            discardPausedCurrent()
            resumeFromPaused()
        }
    }

    /**
     * Отцепить и тихо утилизировать замороженный поток. Он уже в нуле по
     * громкости и стоит на паузе — освобождение бесшумно.
     */
    private fun discardPausedCurrent() {
        val s = current ?: return
        current = null
        currentRef.set(null)
        StreamLogger.d(TAG, "discardPausedCurrent: spec#${s.spec.serial} paused=${s.isPaused}")
        s.stop(onFullyStopped = { /* состояние решают вызывающие */ })
    }

    private fun resumeFromPaused() {
        // Замороженный поток (если есть) звучит по старой спеке — утилизируем
        // его до запуска нового, иначе он останется висеть без владельца.
        discardPausedCurrent()
        pausedSpecDirty = false
        val base = queue.poll() ?: sessionSpec ?: return
        val spec = base.copy(
            volume = volume,
            reason = SpecReason.RESUME,
            resumeAnchorMs = System.currentTimeMillis() - accumulatedMs,
            resumeElapsedMs = accumulatedMs,
            // ФИКС 3. Возобновляем с того же времени суток по кривой — частота и
            // фаза продолжаются без скачка (как при сквозном переключении).
            // pausedTimeOfDay — СЛЫШИМАЯ позиция (по голове воспроизведения),
            // а не значение UI-часов: точка продолжения совпадает с графиком.
            resumeCurveTimeSeconds = pausedTimeOfDay
        )
        sessionSpec = spec
        launchSpec(spec)
    }

    private fun capturePauseMetrics() {
        current?.let {
            pausedElapsedSeconds = it.getElapsedSeconds()
            // СЛЫШИМАЯ позиция: точка возобновления обязана совпасть с тем
            // местом графика, где звук реально остановился.
            pausedTimeOfDay = it.getAudibleTimeOfDaySeconds()
        }
    }

    // ---- ФИКС 3. Непрерывность сквозного переключения сегментов ----
    private fun captureContinuity() {
        // Читаем ЖИВЫЕ координаты старого потока (ещё играет). Если current уже
        // нет (fallback), оставляем последнее захваченное значение.
        current?.let {
            switchCurveTod = it.getCurrentCurveTimeSeconds()
            switchElapsedMs = it.getElapsedMs()
            // ФИКС RC-2: живые фазы несущих для бесшовного кроссфейда.
            it.getPhases()?.let { (l, r) ->
                switchLeftPhase = l
                switchRightPhase = r
            }
        }
    }

    private fun enrichForContinuity(spec: PlaybackSpec): PlaybackSpec {
        // Отладочный скраб — ЯВНАЯ установка времени оператором, непрерывность
        // её не перебивает. Порядок в prepare(): сначала nativeCustomizer
        // (applyNativeDebug -> engine.debugScrub), ПОТОМ
        // setCurveTime(spec.resumeCurveTimeSeconds) — то есть подставленное
        // здесь текущее время CURRENT молча вернуло бы NEXT на старую позицию,
        // и перемотка перестала бы работать.
        if (debugScrubPending != null) return spec
        val tod = switchCurveTod ?: return spec
        return spec.copy(
            resumeCurveTimeSeconds = tod.toInt(),
            resumeElapsedMs = switchElapsedMs,
            resumeLeftPhase = switchLeftPhase,
            resumeRightPhase = switchRightPhase
        )
    }

    private fun resetContinuity() {
        switchCurveTod = null
        switchElapsedMs = 0L
        switchLeftPhase = null
        switchRightPhase = null
    }

    // ================================================================== Запуск потоков

    /**
     * Подготовить (если нет готового armed NEXT) и запустить спеку.
     */
    private fun launchSpec(spec: PlaybackSpec) {
        StreamLogger.d(TAG, "launchSpec spec#${spec.serial} reason=${spec.reason} armedNext=${next?.spec?.serial} armedLifecycle=${next?.lifecycle}")
        val armed = next
        if (armed != null && armed.spec.audioEquals(spec) && armed.lifecycle == StreamLifecycle.PREPARED) {
            StreamLogger.d(TAG, "launchSpec: переиспользуем armed NEXT spec#${spec.serial}")
            next = null
            launchStream(armed)
            return
        }
        armed?.abort()
        next = null
        val candidate = createStream(spec)
        if (!candidate.prepare()) {
            // Ошибка подготовки: стабильное состояние + сессия сохранена для повторной попытки
            StreamLogger.e(TAG, "launchSpec: prepare spec#${spec.serial} не удался (retryable)")
            _isPlaying.value = false
            sessionSpec = spec
            if (spec.reason == SpecReason.RESUME) {
                setState(ManagerState.PAUSED) // можно повторить resume
            } else {
                setState(ManagerState.IDLE)   // можно повторить play
            }
            listener?.onError("stream prepare failed (spec#${spec.serial}); retryable")
            updateWakeLock()
            return
        }
        launchStream(candidate)
    }

    private fun launchStream(stream: BinauralStreamImpl) {
        StreamLogger.d(TAG, "launchStream spec#${stream.spec.serial} sr=${stream.spec.sampleRate} beat=${stream.spec.config.frequencyCurve.getBeatFrequencyAt(kotlinx.datetime.LocalTime(0, 0))}")
        // Не-handoff запуск (PLAY/RESUME) — новый сегмент без непрерывности; сбрасываем
        // якорь. При handoff (PRESET_SWITCH/SETTINGS) якорь захвачен в beginHandoff и
        // должен дожить до старта NEXT — continuity применится в enrichForContinuity.
        if (pendingHandoff) {
            pendingHandoff = false
        } else {
            resetContinuity()
        }
        current = stream
        currentRef.set(stream)
        sessionSpec = stream.spec
        segmentStartWallMs = System.currentTimeMillis()
        setState(ManagerState.FADE_IN)
        _isPlaying.value = true
        updateWakeLock()
        // ФИКС: применяем громкость ДО start() — шейпер fade-in стартует с базы
        // userVolume, и первый кадр пишется уже на нужной громкости.
        stream.setVolume(volume)
        if (!stream.start(onFullyStarted = { setState(ManagerState.RUNNING) })) {
            // Старт трека не удался: поток не успел зазвучать
            StreamLogger.e(TAG, "launchStream: start spec#${stream.spec.serial} не удался")
            stream.abort()
            current = null
            currentRef.set(null)
            _isPlaying.value = false
            setState(ManagerState.IDLE)
            listener?.onError("stream start failed")
            updateWakeLock()
        } else {
            StreamLogger.d(TAG, "launchStream: start spec#${stream.spec.serial} успешно, fade-in идёт")
        }
    }

    private fun createStream(spec: PlaybackSpec): BinauralStreamImpl {
        return BinauralStreamImpl(
            context = context,
            spec = spec,
            controlHandler = actor,
            bufferIntervalMs = bufferIntervalMs,
            nativeCustomizer = { engine -> applyNativeDebug(engine) },
            onRuntimeError = { stream, message -> actor.post { handleRuntimeError(stream, message) } }
        )
    }

    /** Применение состояния debug-времени к свежему нативному движку (исполняется на актёре). */
    private fun applyNativeDebug(engine: NativeAudioEngine) {
        if (debugVirtualTime) {
            engine.debugSetVirtualTimeEnabled(true)
            engine.debugSetTimeScale(debugTimeScale)
            engine.debugSetRunning(debugRunning)
            engine.setBatchDurationMinutes(0)
        }
        debugScrubPending?.let { scrub ->
            engine.debugScrub(scrub)
            debugScrubPending = null
        }
    }

    /** Ошибка писателя (генерация/запись): гасим остаток с фейдом и уходим в IDLE (retryable). */
    private fun handleRuntimeError(stream: BinauralStreamImpl, message: String) {
        StreamLogger.e(TAG, "handleRuntimeError: $message (stream spec#${stream.spec.serial}, isCurrent=${current === stream}, isNext=${next === stream})")
        Log.e(TAG, "runtime error: $message")
        if (next === stream) {
            // ФИКС З1. abort() гасит только неигравший поток (CREATED/PREPARED/FAILED).
            // PLAYING-поток от abort() не гас, а ссылка next на него уже занулена:
            // его AudioTrack и direct-буфер оставались живы навсегда, и погасить
            // их было некому — поток уже не current и не next. Чем дольше живёт
            // процесс, тем больше таких зомби (и тем громче симптом «после долгой
            // работы»). discardNext() гасит играющий NEXT фейдом, остальные — тихо.
            discardNext()
            listener?.onError("next stream error: $message")
            return
        }
        if (current !== stream) return
        capturePauseMetrics()
        discardNext()
        queue.clear()
        pendingPlaySpec = null
        pendingResume = false
        fadeTarget = FadeTarget.STOP
        setState(ManagerState.FADE_OUT_STOP)
        _isPlaying.value = false
        listener?.onError("playback error: $message (retryable)")
        stream.stop(
            onFullyStopped = {
                current = null
                currentRef.set(null)
                resetSession()
                setState(ManagerState.IDLE)
                updateWakeLock()
            }
        )
    }

    // ================================================================== WakeLock

    private val wakeLockLock = Any()
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Периодическое подтверждение удержания CPU.
     *
     * Даже без TTL лок теоретически может снять кто-то ещё (или сам PowerManager
     * при смене профиля). Переспрашиваем раз в 5 минут, пока играем, — цена
     * пустая: если `isHeld`, вызов ничего не делает.
     */
    private val wakeLockRenew = object : Runnable {
        override fun run() {
            if (!wakeLockNeeded()) {
                releaseWakeLock()
                return
            }
            acquireWakeLock()
            actor.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    private fun wakeLockNeeded() = isActiveState() ||
        state == ManagerState.FADE_OUT_PAUSE ||
        state == ManagerState.FADE_OUT_STOP

    private fun updateWakeLock() {
        // Снимаем хвост предыдущего подтверждения: перепланирование должно
        // оставаться идемпотентным, иначе за долгую сессию копий набежит десятки.
        actor.removeCallbacks(wakeLockRenew)
        if (wakeLockNeeded()) {
            acquireWakeLock()
            actor.postDelayed(wakeLockRenew, WAKE_LOCK_RENEW_MS)
        } else {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() = synchronized(wakeLockLock) {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    // Счётчик ссылок нам не нужен: acquire/release идемпотентны.
                    setReferenceCounted(false)
                }
            }
            // НИКАКОГО TTL.
            //
            // Раньше здесь было acquire(ttlMs) с TTL = maxOf(10 мин,
            // bufferIntervalMs + 120 с). WakeLock.acquire(timeout) —
            // САМОРАСПУСКАЮЩИЙСЯ лок: по истечении TTL система его снимает.
            // При этом updateWakeLock() вызывается только на переходах
            // состояния, а пока менеджер стоит в RUNNING, переходов нет —
            // и условие `if (wakeLock?.isHeld != true)` повторно лок не брало.
            //
            // Итог: через 12 минут после начала воспроизведения CPU оставался
            // без удержания. Запас до underrun — около секунды
            // (TRACK_BUFFER_MS 3000 минус WRITE_CHUNK_MS 2000), поэтому в Doze
            // или при экономии заряда писатель не успевал подлить трек: PCM
            // рвался на произвольном отсчёте — щелчок, а если сработёт
            // onRuntimeError — полный обрыв воспроизведения.
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
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
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    // ================================================================== Сессия

    private fun resetSession() {
        sessionSpec = null
        accumulatedMs = 0L
        segmentStartWallMs = 0L
        pausedElapsedSeconds = 0
        pausedTimeOfDay = 0
        pausedSpecDirty = false
        pendingResume = false
        pendingPlaySpec = null
        pendingHandoff = false
        resetContinuity()
    }
}
