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
    private var bufferIntervalMs = 600_000
    private var lastUserIntervalMs = 600_000

    // Debug virtual time (применяется к каждому новому потоку)
    private var debugVirtualTime = false
    private var debugTimeScale = 1.0f
    private var debugRunning = true
    private var debugScrubPending: Int? = null

    // ---------------- Runtime (только актёр) ----------------
    private var state = ManagerState.IDLE
    private var current: BinauralStreamImpl? = null
    private var next: BinauralStreamImpl? = null
    private val queue = PlaybackQueue()
    private var serialSeq = 0L
    private var fadeTarget = FadeTarget.STOP
    private var pendingResume = false

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

    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings = RelaxationModeSettings()) {
        actor.post {
            this.config = config
            this.relaxation = relaxationSettings
            _currentConfig.value = config
            onSpecChanged(SpecReason.SETTINGS)
        }
    }

    fun updateRelaxationModeSettings(settings: RelaxationModeSettings) {
        actor.post { relaxation = settings; onSpecChanged(SpecReason.SETTINGS) }
    }

    fun updateFrequencyCurve(curve: FrequencyCurve) {
        actor.post {
            config = config.copy(frequencyCurve = curve)
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
        actor.post { this.volume = v; current?.setVolume(v) }  // live, без handoff
    }

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
        val clamped = intervalMs.coerceIn(1000, 60 * 60 * 1000)
        actor.post {
            if (!debugVirtualTime) lastUserIntervalMs = clamped
            bufferIntervalMs = clamped   // применится к следующему потоку
        }
    }
    fun getFrequencyUpdateInterval(): Int = bufferIntervalMs

    fun applyPowerSaveMode() {
        actor.post {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            bufferIntervalMs = if (pm.isPowerSaveMode)
                (lastUserIntervalMs * POWER_SAVE_MULTIPLIER).coerceAtMost(60_000)
            else lastUserIntervalMs
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
            _elapsedSeconds.value = s.getElapsedSeconds()
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
            ManagerState.IDLE, ManagerState.PAUSED -> sessionSpec = buildSpec(reason)
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
     * HANDOFF-КРОССФЕЙД: NEXT готовится и СТАРТУЕТ (фейд-ин) параллельно с фейд-аутом
     * CURRENT. «Дырки» тишины нет: окно перекрытия = длительность фейда.
     * Ошибка подготовки/старта NEXT — НЕ повод трогать старый поток.
     */
    private fun beginHandoff() {
        val spec = queue.peek() ?: return
        // ФИКС 3. Захватываем живые координаты CURRENT ДО его fade-out — точку на
        // кривой и пройденное время. NEXT стартует ровно отсюда => бесшовный
        // кроссфейд без скачка фазы/частоты и без сброса часов сессии.
        captureContinuity()
        pendingHandoff = true
        handoffStartWallMs = System.currentTimeMillis()
        val enriched = enrichForContinuity(spec)
        StreamLogger.d(TAG, "beginHandoff spec#${spec.serial}: кроссфейд — NEXT стартует одновременно с fade-out CURRENT (curveTod=$switchCurveTod, elapsed=${switchElapsedMs}ms)")
        val candidate = createStream(enriched)
        if (!candidate.prepare()) {
            StreamLogger.e(TAG, "beginHandoff: prepare NEXT spec#${spec.serial} не удался — старый продолжает играть")
            listener?.onError("stream prepare failed (spec#${spec.serial}), keeping current playback")
            pendingHandoff = false
            return // старый продолжает играть; спека остаётся в очереди для повторной попытки
        }
        next = candidate
        setState(ManagerState.HANDOFF)
        // Громкость ДО start(): база нужна к первому кадру под множителем ~0.
        candidate.setVolume(volume)

        // NEXT начинает фейд-ин (equal-power sin) первым, на ~1 мс раньше фейд-аута
        // CURRENT: лучше микроскопическое перекрытие, чем микродырка тишины.
        if (!candidate.start(
                onFullyStarted = {
                    StreamLogger.d(TAG, "beginHandoff: NEXT spec#${spec.serial} fade-in завершён, ждём повышения")
                },
                shape = FadeShape.EQUAL_POWER
            )
        ) {
            StreamLogger.e(TAG, "beginHandoff: start NEXT spec#${spec.serial} не удался — старый продолжает играть")
            candidate.abort()   // ни разу не звучал — тихо
            next = null
            pendingHandoff = false
            setState(ManagerState.RUNNING)
            listener?.onError("next stream start failed; keeping current playback")
            return
        }
        // CURRENT уходит в зеркальный фейд-аут (equal-power cos); onSilent повысит NEXT.
        fadeOutCurrent(FadeTarget.SWITCH)
    }

    /**
     * Шторм настроек во время кроссфейда.
     * - NEXT уже ЗВУЧИТ: повышаем его досрочно до current и запускаем новый кроссфейд
     *   к новейшей спеке (хвост очереди разбирает promoteNextToCurrent). Осиротевший
     *   старый поток доигрывает свой фейд сам — его колбэки отфильтрованы.
     * - NEXT не звучит (armed/отсутствует): бесшумная замена + немедленный старт.
     */
    private fun rearmNextIfStale() {
        val spec = queue.peek() ?: return
        val n = next
        if (n != null && n.spec.audioEquals(spec)) return

        if (n != null && n.lifecycle == StreamLifecycle.PLAYING) {
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
        if (!candidate.start(
                onFullyStarted = { StreamLogger.d(TAG, "rearmNextIfStale: NEXT spec#${spec.serial} fade-in завершён") },
                shape = FadeShape.EQUAL_POWER
            )
        ) {
            candidate.abort()
            next = null
            pendingHandoff = false
        }
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
     * Повышаем играющий NEXT до current — без повторного запуска и без паузы.
     */
    private fun onStreamSilent(s: BinauralStreamImpl) {
        if (s !== current) return   // осиротел (шторм/стоп) — его судьба решена отдельно
        if (fadeTarget != FadeTarget.SWITCH || state != ManagerState.HANDOFF) return
        val n = next ?: return
        if (n.lifecycle != StreamLifecycle.PLAYING) return
        promoteNextToCurrent()
    }

    /**
     * Поток полностью освобождён. Фильтр идентичности: релиз осиротевшего потока
     * (раннее повышение при шторме, discard при стопе/паузе) не трогает автомат.
     */
    private fun onStreamReleased(s: BinauralStreamImpl) {
        if (s !== current) {
            StreamLogger.d(TAG, "onStreamReleased: orphan spec#${s.spec.serial} — игнор")
            return
        }
        onStreamFullyStopped()
    }

    /**
     * NEXT уже играет: делаем его current ровно в момент тишины старого.
     * Фикс 3 сохранён: NEXT несёт switchCurveTod/switchElapsedMs из обогащённой спеки.
     */
    private fun promoteNextToCurrent() {
        val n = next ?: return
        StreamLogger.d(TAG, "promoteNextToCurrent: spec#${n.spec.serial} — кроссфейд завершён")
        next = null
        current = n
        currentRef.set(n)
        sessionSpec = n.spec
        // Сессионное время: NEXT несёт switchElapsedMs + фактическую длительность кроссфейда.
        accumulatedMs = switchElapsedMs + (System.currentTimeMillis() - handoffStartWallMs)
        segmentStartWallMs = System.currentTimeMillis()
        pendingHandoff = false
        setState(ManagerState.RUNNING)
        _isPlaying.value = true
        updateWakeLock()
        // Хвост шторма: если за время кроссфейда прилетела спека новее — сразу новый хэндофф.
        val tail = queue.poll()
        if (tail != null && !tail.audioEquals(n.spec)) {
            StreamLogger.d(TAG, "promoteNextToCurrent: хвост очереди spec#${tail.serial} новее — новый хэндофф")
            queue.offer(tail)
            beginHandoff()
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
                // Handoff (если шёл) прерван паузой — это не продолжение сегмента.
                pendingHandoff = false
                resetContinuity()
                accumulatedMs += System.currentTimeMillis() - segmentStartWallMs
                queue.poll()?.let { sessionSpec = it } // настройки, прилетевшие во время фейда
                setState(ManagerState.PAUSED)
                StreamLogger.d(TAG, "onStreamFullyStopped: PAUSE -> накоплено accumulatedMs=$accumulatedMs")
                if (pendingResume) {
                    pendingResume = false
                    resumeFromPaused()
                }
            }

            FadeTarget.STOP -> {
                val queued = queue.poll()
                val playSpec = pendingPlaySpec
                pendingPlaySpec = null
                resetSession()
                if (playSpec != null) {
                    // play(), пришедший во время фейд-аута: старт строго после него
                    StreamLogger.d(TAG, "onStreamFullyStopped: STOP -> play пришёл во время фейда, старт spec#${playSpec.serial}")
                    sessionSpec = playSpec
                    launchSpec(playSpec)
                } else {
                    if (queued != null) sessionSpec = queued // запомнить для следующего play
                    StreamLogger.d(TAG, "onStreamFullyStopped: STOP -> IDLE (queued=${queued?.serial})")
                    setState(ManagerState.IDLE)
                }
            }

            FadeTarget.SWITCH -> {
                if (current != null) {
                    // Нормальный кроссфейд: повышение уже состоялось (а релиз старого
                    // отфильтрован по идентичности в onStreamReleased). Сюда попадаем,
                    // только если NEXT не дожил до повышения (например, погиб в рантайме):
                    // спека в очереди — повторяем хэндофф против живого current.
                    val spec = queue.poll()
                    if (spec != null && !current!!.spec.audioEquals(spec)) {
                        StreamLogger.d(TAG, "onStreamFullyStopped: SWITCH, NEXT не выжил — повторный хэндофф spec#${spec.serial}")
                        queue.offer(spec)
                        beginHandoff()
                    }
                } else {
                    // Вырожденный случай (ток был без звука) — легаси-последовательная схема
                    val spec = queue.poll()
                    if (spec == null) {
                        resetSession()
                        StreamLogger.d(TAG, "onStreamFullyStopped: SWITCH без спек — IDLE")
                        setState(ManagerState.IDLE)
                    } else {
                        StreamLogger.d(TAG, "onStreamFullyStopped: SWITCH -> launchSpec spec#${spec.serial}")
                        launchSpec(spec)
                    }
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
                resetContinuity()
                discardNext()
                retargetFade(FadeTarget.STOP)
            }
            ManagerState.FADE_OUT_PAUSE -> {
                queue.clear()
                pendingResume = false
                retargetFade(FadeTarget.STOP)
            }
            ManagerState.PAUSED -> {
                resetSession()
                setState(ManagerState.IDLE)
                updateWakeLock()
            }
            else -> { /* IDLE/FADE_OUT_STOP: идемпотентно */ }
        }
    }

    private fun onPause() {
        StreamLogger.d(TAG, "onPause state=${state.name}")
        when (state) {
            ManagerState.RUNNING, ManagerState.FADE_IN -> {
                capturePauseMetrics()
                fadeOutCurrent(FadeTarget.PAUSE)
            }
            ManagerState.HANDOFF -> {
                capturePauseMetrics()
                resetContinuity()
                discardNext()
                // Новейший спека из очереди станет sessionSpec по завершении фейда
                retargetFade(FadeTarget.PAUSE)
            }
            ManagerState.FADE_OUT_STOP -> {
                capturePauseMetrics()
                pendingPlaySpec = null
                retargetFade(FadeTarget.PAUSE)
            }
            else -> { /* IDLE/PAUSED/FADE_OUT_PAUSE: no-op */ }
        }
    }

    private fun onResume() {
        StreamLogger.d(TAG, "onResume state=${state.name}")
        when (state) {
            ManagerState.IDLE -> onPlay()   // ещё не играли — старт (play() сам обработает IDLE)
            ManagerState.PAUSED -> resumeFromPaused()
            ManagerState.FADE_OUT_PAUSE -> {
                // ФИКС 2. Разворот рампы (reverseFadeToPlaying) САМ вызывает щелчок —
                // это тот же разрыв непрерывности громкости. Не делаем разворот: ждём,
                // пока текущий fade-out дойдёт до PAUSED, и там возобновляем чистым
                // стартом (fade-in из нуля — бесшумно).
                StreamLogger.d(TAG, "onResume: в FADE_OUT_PAUSE -> ждём PAUSED, затем resumeFromPaused")
                pendingResume = true
            }
            else -> { /* no-op */ }
        }
    }

    private fun resumeFromPaused() {
        val base = queue.poll() ?: sessionSpec ?: return
        val spec = base.copy(
            volume = volume,
            reason = SpecReason.RESUME,
            resumeAnchorMs = System.currentTimeMillis() - accumulatedMs,
            resumeElapsedMs = accumulatedMs,
            // ФИКС 3. Возобновляем с того же времени суток по кривой — частота и
            // фаза продолжаются без скачка (как при сквозном переключении).
            resumeCurveTimeSeconds = pausedTimeOfDay
        )
        sessionSpec = spec
        launchSpec(spec)
    }

    private fun capturePauseMetrics() {
        current?.let {
            pausedElapsedSeconds = it.getElapsedSeconds()
            pausedTimeOfDay = it.getCurrentTimeOfDay()
        }
    }

    // ---- ФИКС 3. Непрерывность сквозного переключения сегментов ----
    private fun captureContinuity() {
        // Читаем ЖИВЫЕ координаты старого потока (ещё играет). Если current уже
        // нет (fallback), оставляем последнее захваченное значение.
        current?.let {
            switchCurveTod = it.getCurrentCurveTimeSeconds()
            switchElapsedMs = it.getElapsedMs()
        }
    }

    private fun enrichForContinuity(spec: PlaybackSpec): PlaybackSpec {
        val tod = switchCurveTod ?: return spec
        return spec.copy(
            resumeCurveTimeSeconds = tod.toInt(),
            resumeElapsedMs = switchElapsedMs
        )
    }

    private fun resetContinuity() {
        switchCurveTod = null
        switchElapsedMs = 0L
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
            next = null
            stream.abort()
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

    private fun updateWakeLock() {
        val needed = state == ManagerState.RUNNING ||
            state == ManagerState.FADE_IN ||
            state == ManagerState.HANDOFF ||
            state == ManagerState.FADE_OUT_PAUSE ||
            state == ManagerState.FADE_OUT_STOP
        if (needed) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() = synchronized(wakeLockLock) {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            }
            // TTL обязан покрыть запись пакета целиком (до 60 мин)
            val ttlMs = maxOf(10 * 60 * 1000L, bufferIntervalMs.toLong() + 120_000L)
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(ttlMs)
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
        pendingResume = false
        pendingPlaySpec = null
        resetContinuity()
    }
}
