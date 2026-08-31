package com.binaural.core.audio.stream

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import com.binaural.core.audio.BuildConfig
import com.binaural.core.audio.engine.NativeAudioEngine

import com.binaural.core.audio.model.SampleRate
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
 * Фасад намеренно повторяет API старого `BinauralAudioEngine` (удалён
 * 2026-08-31 как мёртвый) — сервис меняется одной строкой.
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
         * Нижняя граница интервала генерации (длины пакета) — чтобы пакет
         * покрывал хотя бы один WRITE_CHUNK_MS.
         *
         * Верхней КОНСТАНТЫ больше нет, и это осознанно. Раньше здесь стояло
         * 600_000 мс — число, выведенное из «230 МБ влезает в 256 МБ». Но
         * «влезает» зависит от частоты дискретизации: 8 байт на кадр × SR ×
         * секунды. Одна и та же память — это 10 минут при 48 кГц и 60 минут
         * при 8 кГц. Общая константа поэтому либо недодаёт на низких SR
         * (там можно было бы втрое больше), либо вырождается в ограничение
         * «для худшего SR». Теперь предел считается на устройстве от кучи:
         * [PacketMemoryBudget.maxIntervalMsFor], и у каждой частоты он свой.
         */
        private const val MIN_BUFFER_INTERVAL_MS = 1_000
        /** Как часто подтверждаем удержание CPU во время воспроизведения. */
        private const val WAKE_LOCK_RENEW_MS = 5 * 60 * 1000L
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
     * (10 мин).
     *
     * Дефолт 10 минут выбран потому, что он доступен на ЛЮБОЙ частоте
     * дискретизации: 10 мин × 48 кГц × 8 байт = 230 МБ — это и есть худший
     * случай, и он как раз укладывается в 86% кучи 256 МиБ. Переход на 8 кГц
     * не меняет память, но развязывает руки слайдеру: там же 230 МБ — это уже
     * час звука.
     *
     * История. До оптимизации здесь стояло 600_000 и кламп 1 ч, но длинный
     * пакет реально не экономил CPU (генерация ≈ 6.4 нс/кадр при любом
     * размере; CPU/час одинаков при 2 с и 190 с — 1.02 с), а платил 67 МБ
     * на поток (134 МБ в кроссфейде) + page-fault'ы + дорогую пересборку
     * потока на каждом handoff.
     *
     * Главная беда прежней версии — тихое усечение. Реальный предел был не
     * 256 МБ (ABI-константа), а константа в 32 МБ: на 48 кГц это 4.19 млн
     * кадров = **87.4 с**, то есть 600 с из настроек молча превращались в
     * 87.4. Пакет — это только длина одного JNI-вызова, звук от неё не
     * зависит, поэтому усечение безопасно, но настройка лгала: слайдер
     * показывал 600 с, движок жил на 87.4.
     *
     * Теперь предел один и один на всех — [PacketMemoryBudget
     * .engineCeilingBytes]. Его спрашивают и слайдер (какие стопы показать),
     * и [clampToRate], и [createStream], и prepare(). Поэтому усечения нет
     * даже в двух случаях, где оно пряталось раньше: ручной `packetmax`
     * двигает слайдер вместе с собой, а потолок, сузившийся после OOM,
     * переподчиняет интервал на входе в следующий поток.
     */
    private var bufferIntervalMs = 600_000
    private var lastUserIntervalMs = 600_000

    /**
     * Верхний предел интервала генерации для ТЕКУЩЕЙ частоты, мс.
     *
     * Не константа: память на секунду звука proportional частоте
     * (8 байт × SR), поэтому предел в секундах свой для каждого SR. Считается
     * на устройстве от кучи — см. [PacketMemoryBudget].
     */
    private fun maxBufferIntervalMs(): Int =
        PacketMemoryBudget.maxIntervalMsFor(sampleRate.value).coerceAtLeast(MIN_BUFFER_INTERVAL_MS)

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
    /**
     * ЕДИНСТВЕННЫЙ живой поток. Второй слот не существует — ни поля, ни
     * состояния: загружено всегда не более одного потока.
     *
     * ИНВАРИАНТ (строже «не более двух»): новый поток создаётся только после
     * того, как старый ПОЛНОСТЬЮ утилизирован ([onStreamFullyStopped], то есть
     * после `releaseInternal()` — трек снят, движок уничтожен, пакет отдан).
     * Между «старый утих» и «новый зазвучал» поэтому есть пауза: 60 мс
     * стража шейпера ([BinauralStreamImpl] FADE_GUARD_MS) + выход писателя +
     * `prepare()` нового потока. Это сознательная цена.
     *
     * Что это даёт по сравнению со схемой «NEXT готовится под прикрытием
     * фейд-аута»:
     *  - не нужна очередь осиротевших треков (orphan-гейт) и таймаут
     *    ожидания их релиза: третьего трека не бывает в принципе, а на два
     *    кучи клиента AudioFlinger (7 МБ при кольце 2 МБ) хватает всегда;
     *  - при шторме смен промежуточные пресеты не материализуются ВОВСЕ:
     *    очередь — один слот latest-wins, поэтому за серию A→B→C→D создаётся
     *    ровно один поток (на D), а не по потоку на каждую смену;
     *  - из автомата удалены повышение NEXT, дооснащение вооружённого NEXT и
     *    утилизация «зомби» (потока без владельца) — классы отказов, из
     *    которых и выросла оценка «шесть потоков по 33.5 МБ».
     *
     * Цена звука: разрыв ≈ 100–200 мс на каждой смене пресета (замер —
     * позже). При шторме смен это пульсация, а не непрерывный звук —
     * принято как допустимое.
     */
    private var current: BinauralStreamImpl? = null

    private val queue = PlaybackQueue()
    private var serialSeq = 0L
    private var fadeTarget = FadeTarget.STOP
    private var pendingResume = false

    /**
     * Возобновление уже в полёте: замороженный поток освобождается, и его пакет
     * (до 95 с PCM) ещё в куче. Запуск нового потока отложен до полного релиза,
     * иначе на ~150 мс возникали бы ДВА загруженных потока — ровно то, от чего
     * избавляет инвариант одного потока. Повторное нажатие play в этом окне не
     * должно породить второй поток: возобновление и так разыграется.
     */
    private var resumeInFlight = false

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
    /**
     * СЛЫШИМАЯ позиция кривой на момент заморозки (A0), целые секунды.
     * Только для диагностики и как запасной ответ UI-геттера, когда живого
     * потока уже нет; точкой возобновления она НЕ является (см. ниже).
     */
    private var pausedTimeOfDay = 0

    /**
     * Точные координаты замороженного пакета на кривой времени суток.
     *
     *   A0 = [pausedAudibleSeconds] — где звук реально остановился (голова
     *        трека минус недописанный хвост);
     *   F0 = [pausedFrontierSeconds] — фронтир генерации, конец уже
     *        посчитанного аудио.
     *
     * Обе сняты в момент заморозки и обе НЕ двигаются, пока поток на паузе:
     * генерация стоит (писатель припаркован), голова трека стоит.
     *
     * Зачем обе. СУТЬ ПРИЛОЖЕНИЯ — звук для ТЕКУЩЕГО момента суток, поэтому
     * «продолжить с A0» само по себе НЕПРАВИЛЬНО (это была ошибка прошлого
     * фикса). Правильный вопрос другой: успело ли сгенерированное аудио
     * устареть? Пока `now` внутри [A0, F0], звук для него уже посчитан —
     * пакет переиспользуется с пропуском головы; вышел за F0 — пакет
     * пересобирается. Разница A0/F0 и есть окно актуальности.
     *
     * Дробные секунды, а не целые: целые округляют A0 вниз и дают лишний
     * кадр пропуска на каждом возобновлении.
     */
    private var pausedAudibleSeconds = 0f
    private var pausedFrontierSeconds = 0f

    /**
     * Диагностика точности возобновления (только debug-сборка).
     *
     * После каждого возобновления из PAUSED сюда ложится развёрнутый снимок
     * решателя: какое `now` взял резолвер, окно актуальности пакета
     * (lead = F0 − A0), Δ паузы, сколько кадров выброшено и КАКОЙ путь
     * выбран — мягкое продолжение (SOFT) или пересборка (REBUILD). Читается
     * debug-CLI `resumesnap`. Позволяет отделить точность «привязки к сейчас»
     * (она задаётся пропуском Δ·rate кадров) от переходной задержки кольца
     * трека, которую прячет компенсированный `audible` (см.
     * docs/analysis_resume_from_0_position.md, разбор точности).
     */
    @Volatile
    private var lastResumeAccuracy: String? = null

    // Непрерывность при сквозном переключении сегментов: точка кривой времени
    // (секунды суток), с которой стартует СЛЕДУЮЩИЙ поток, и пройденное
    // реальное время на момент переключения. Захватываются живыми из CURRENT в
    // beginHandoff (и перезахватываются в requestHandoff, если за время фейда
    // прилетела спека новее) и уезжают в следующий поток обогащённой спекой.
    //
    // Это не «бесшовный кроссфейд» в прежнем смысле — разрыв звука есть, и он
    // сознательный. Здесь решается другая задача: новый поток обязан
    // продолжить кривую и часы сессии с того места, где их застал переключатель,
    // а не начать сначала.
    private var switchCurveTod: Float? = null
    private var switchElapsedMs: Long = 0L
    private var switchLeftPhase: Float? = null
    private var switchRightPhase: Float? = null
    // Маркер: ближайший launchStream — это продолжение handoff'а (не сбрасывать якорь).
    private var pendingHandoff = false
    // Wall-якорь начала хэндоффа: точный учёт сессионного времени при загрузке
    // следующего потока (см. onStreamFullyStopped, FadeTarget.SWITCH).
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
            // при рестарте Activity ViewModel пушит дефолт 1.0f поверх реальной
            // громкости, и это слышимый скачок уровня.
            if (kotlin.math.abs(this.volume - v) < 0.0001f) return@post
            this.volume = v
            // Живой поток — один, и это он. Громкость, выставленная во время
            // хэндоффа, достаётся уходящему потоку и только ему; новый поток
            // получит базу в launchStream() перед start().
            current?.setVolume(v)
        }
    }

    /** Текущая громкость менеджера (для сверки перед пушем из UI-слоя). */
    fun getVolume(): Float = volume

    fun setSampleRate(rate: SampleRate) {
        StreamLogger.d(TAG, "setSampleRate -> $rate")
        actor.post {
            if (sampleRate == rate) return@post
            sampleRate = rate
            // Предел длины пакета свой для каждой частоты (память на секунду
            // звука proportional частоте), поэтому сохранённый интервал надо
            // переподчинить новому пределу ДО пересборки движка: иначе на
            // 48 кГц пришло бы значение, выбранное на 8 кГц, и пакет молча
            // урезался бы в prepare(). Вниз, а не вверх — см. [clampToRate].
            lastUserIntervalMs = clampToRate(lastUserIntervalMs)
            if (!debugVirtualTime) bufferIntervalMs = lastUserIntervalMs
            onSpecChanged(SpecReason.SAMPLE_RATE)   // пересоздание движка через handoff
        }
    }
    fun getSampleRate(): SampleRate = sampleRate

    /**
     * Втянуть интервал в предел текущей частоты дискретизации.
     *
     * Округление ВНИЗ по лестнице слайдера, а не просто `coerceAtMost`:
     * промежуточное значение вроде 1_800_000 мс при пределе 1_750_000
     * отрезалось бы до 1_750_000 (29.2 мин) — число, которого нет на слайдере
     * и которое пользователь не выбирал. По лестнице получится 25 минут —
     * значение, которое можно показать и можно выбрать.
     */
    private fun clampToRate(intervalMs: Int): Int {
        val maxMs = PacketMemoryBudget.maxIntervalMsFor(sampleRate.value)
        val minutes = (intervalMs / 60_000).coerceAtMost(maxMs / 60_000)
        return PacketMemoryBudget.coerceMinutes(sampleRate.value, minutes) * 60_000
    }

    fun setFrequencyUpdateInterval(intervalMs: Int) {
        // Верхний предел считается от кучи и свой для каждой частоты
        // ([maxBufferIntervalMs]) — общей константы больше нет. Держим предел
        // здесь, чтобы никакое значение из UI не могло вернуть буферы, на
        // которых проект ловил OOM.
        //
        // Справка, почему предел вообще нужен (docs/
        // hotpath_optimization_analysis_2026-08-30.md): длина пакета НЕ влияет
        // на CPU/час (1.02 с при пакете и 2 с, и 190 с) и НЕ влияет на wakeups
        // писателя (их задаёт WRITE_CHUNK_MS). Платит длинный пакет только
        // памятью — вот память его и ограничивает.
        val clamped = intervalMs.coerceIn(MIN_BUFFER_INTERVAL_MS, maxBufferIntervalMs())
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
     * пользователем (иначе смысл настройки теряется), но и не больше предела
     * для текущей частоты ([maxBufferIntervalMs]): длина пакета не влияет ни
     * на CPU, ни на wakeups, только на память, а память и есть предел.
     */
    fun applyPowerSaveMode() {
        actor.post {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            bufferIntervalMs = if (pm.isPowerSaveMode) {
                clampToRate((lastUserIntervalMs * POWER_SAVE_MULTIPLIER))
                    .coerceAtLeast(lastUserIntervalMs)
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

    /**
     * ФРОНТИР ГЕНЕРАЦИИ (секунды суток) живого потока: конец уже посчитанного
     * аудио. Правая граница окна [audible, frontier], внутри которого
     * замороженный пакет ещё актуален. Только для диагностики (debug-CLI).
     */
    fun getFrontierTimeOfDaySeconds(): Int = currentRef.get()?.frontierCurveSeconds()?.toInt() ?: 0

    /**
     * СЛЫШИМАЯ позиция кривой (секунды суток) живого потока, либо замороженная
     * слышимая точка на паузе.
     *
     * ТОЛЬКО диагностика (debug-CLI `audible`). Точкой возобновления это
     * значение НЕ является: возобновление играет ритм для текущего момента
     * суток (см. docs/analysis_resume_from_0_position.md).
     */
    fun getAudibleTimeOfDaySeconds(): Int = currentRef.get()?.getAudibleTimeOfDaySeconds()
        ?: if (pausedTimeOfDay > 0) pausedTimeOfDay else 0

    /**
     * СЛЫШИМАЯ позиция кривой БЕЗ компенсации пропуска — РЕАЛЬНОЕ то, что
     * звучит в динамике прямо сейчас (см. [BinauralStreamImpl.audibleCurveSecondsRaw]).
     * Отличается от [getAudibleTimeOfDaySeconds] на величину переходной задержки
     * кольца трека после мягкого возобновления: на нестареющем пути первая на
     * Δ (длительность паузы) отстаёт от `now`, вторая — уже `now`.
     */
    fun getAudibleTimeOfDaySecondsRaw(): Int {
        val raw = currentRef.get()?.audibleCurveSecondsRaw() ?: return 0
        val v = raw.toInt()
        return ((v % 86400) + 86400) % 86400
    }

    /** Последний снимок решателя возобновления (debug-CLI `resumesnap`). */
    fun getResumeAccuracyReport(): String? = lastResumeAccuracy

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
        when (state) {
            ManagerState.HANDOFF -> {
                // Хэндофф уже идёт: старый поток гаснет, новый ещё НЕ создан.
                //
                // Здесь нечего перевооружать — достаточно обновить единственный
                // слот очереди (latest-wins) и ПЕРЕЗАХВАТИТЬ непрерывность:
                // CURRENT с момента начала фейда ушёл вперёд по кривой, и если
                // оставить enrichment от начала хэндоффа, новый поток стартовал
                // бы с позиции, отставшей на длительность фейда плюс релиз.
                captureContinuity()
                queue.offer(enrichForContinuity(spec))
                StreamLogger.d(TAG, "requestHandoff: HANDOFF уже идёт — spec#${spec.serial} " +
                    "заменяет ожидающую спеку, поток будет создан после релиза старого")
            }
            ManagerState.RUNNING, ManagerState.FADE_IN -> {
                queue.offer(spec)               // коалесценция: побеждает новейший
                beginHandoff()
            }
            ManagerState.PREPARING -> {
                // PREPARING — транзиентное синхронное состояние на актёре;
                // спека уже в очереди, её разберёт автомат по завершении подготовки.
                queue.offer(spec)
            }
            else -> { /* недостижимо: onSpecChanged маршрутизирует иначе */ }
        }
    }

    // Дополнительное runtime-поле (продолжение): запрос НА СТАРТ для паттерна
    // «stopWithFade -> play». В отличие от очереди настроек — это именно намерение стартовать.
    private var pendingPlaySpec: PlaybackSpec? = null

    /**
     * ХЭНДОФФ ПРЕСЕТА: CURRENT уходит фейд-аутом, и ВСЁ. Новый поток здесь
     * НЕ создаётся — он появится только в [onStreamFullyStopped], когда старый
     * будет полностью утилизирован.
     *
     * Так устроен инвариант «загружен не более одного потока»: во время фейда
     * живёт ровно один AudioTrack, один нативный движок и один пакетный буфер.
     * Следствия:
     *  - не нужен orphan-гейт (ожидание, пока старый трек отпустит память
     *    AudioFlinger): второго трека нет, третьего быть не может;
     *  - шторм смен не создаёт промежуточных потоков вовсе — очередь один слот
     *    latest-wins, и за серию A→B→C→D поднимется только D;
     *  - невозможен «зомби» (поток без владельца): ссылка на живой поток одна.
     *
     * Цена — разрыв звука между «старый утих» и «новый зазвучал». Он складывается
     * из трёх слагаемых: FADE_GUARD_MS (стража VolumeShaper, ~60 мс), выход
     * писателя и releaseInternal(), prepare() нового потока.
     */
    private fun beginHandoff() {
        val spec = queue.peek() ?: return
        if (current == null) {
            // Гасить нечего — это не кроссфейд, а обычный запуск.
            StreamLogger.d(TAG, "beginHandoff: current==null — обычный запуск spec#${spec.serial}")
            launchSpec(queue.poll() ?: return)
            return
        }
        // Захватываем живые координаты CURRENT ДО его fade-out — точку на кривой,
        // пройденное время и фазы несущих. Обогащённая спека уходит обратно в
        // очередь: новый поток стартует ровно отсюда, без скачка частоты и без
        // сброса часов сессии.
        captureContinuity()
        pendingHandoff = true
        handoffStartWallMs = System.currentTimeMillis()
        val enriched = enrichForContinuity(spec)
        queue.offer(enriched)
        StreamLogger.d(TAG, "beginHandoff spec#${spec.serial}: фейд-аут CURRENT, загрузка " +
            "spec#${enriched.serial} — после полного релиза (curveTod=$switchCurveTod, " +
            "elapsed=${switchElapsedMs}ms, phase=$switchLeftPhase/$switchRightPhase)")

        // Фейд-аут — единственное, что происходит до момента тишины. Рампа
        // EQUAL_POWER здесь не про «сумму энергий двух потоков» (второго нет),
        // а про форму ухода в тишину: та же кривая, что и у стопа, но короче и
        // без щелчка на стыке с будущим фейд-ином.
        fadeOutCurrent(FadeTarget.SWITCH)
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
        // Колбэк исполняется на нити актёра (у потока controlHandler == actor).
        // Идентичность (captured === current) отсекает потоки, чья судьба уже
        // решена отдельно (стоп/пауза во время фейда).
        captured.stop(
            onFullyStopped = { onStreamReleased(captured) },
            shape = if (crossfade) FadeShape.EQUAL_POWER else FadeShape.LINEAR
        )
    }

    /**
     * Поток полностью освобождён. Фильтр идентичности: релиз осиротевшего
     * потока (стоп/пауза во время фейда, discard) не трогает автомат.
     */
    private fun onStreamReleased(s: BinauralStreamImpl) {
        if (s !== current) {
            StreamLogger.d(TAG, "onStreamReleased: orphan spec#${s.spec.serial} — игнор")
            return
        }
        onStreamFullyStopped()
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
                    // play(), пришедший во время фейд-аута: старт строго после него.
                    //
                    // Никакой подстановки saved-позиции: прерванный стоп — это
                    // тот же свежий старт, звук обязан соответствовать текущему
                    // моменту суток. Якорь поставит prepare()
                    // (resumeCurveTimeSeconds = -1 → engine.getCurrentTimeOfDay()).
                    StreamLogger.d(TAG, "onStreamFullyStopped: STOP -> play пришёл во время фейда, " +
                        "свежий старт spec#${finalSpec.serial} от текущего времени суток")
                    sessionSpec = finalSpec
                    launchSpec(finalSpec)
                } else {
                    if (queued != null) sessionSpec = queued // запомнить для следующего play
                    StreamLogger.d(TAG, "onStreamFullyStopped: STOP -> IDLE (queued=${queued?.serial})")
                    setState(ManagerState.IDLE)
                }
            }

            FadeTarget.SWITCH -> {
                // ШТАТНЫЙ ПУТЬ ХЭНДОФФА (а не аварийный, как раньше).
                //
                // Старый поток к этой точке ПОЛНОСТЬЮ утилизирован: трек снят,
                // движок уничтожен, пакет отдан. current занулён выше, поэтому
                // «повторить хэндофф против живого current» невозможно — и это
                // ровно та гарантия, на которой держится инвариант «загружен не
                // более одного потока»: createStream() ниже — единственный живой
                // поток в процессе.
                val spec = queue.poll()
                if (spec == null) {
                    resetSession()
                    StreamLogger.d(TAG, "onStreamFullyStopped: SWITCH без спек — IDLE")
                    setState(ManagerState.IDLE)
                } else {
                    // Сессионное время: к моменту загрузки нового потока прошло
                    // switchElapsedMs (захвачено на старом) плюс вся длительность
                    // хэндоффа — фейд-аут, стража шейпера и релиз трека. Без
                    // этой поправки часы сессии отставали бы на каждый переход.
                    if (pendingHandoff) {
                        accumulatedMs = switchElapsedMs +
                            (System.currentTimeMillis() - handoffStartWallMs)
                    }
                    StreamLogger.d(TAG, "onStreamFullyStopped: SWITCH -> загрузка spec#${spec.serial} " +
                        "(accumulatedMs=$accumulatedMs)")
                    launchSpec(spec)
                    // Якорь отработан (уехал в обогащённую спеку): следующая
                    // смена снимет его заново. Держать его дальше нельзя —
                    // enrichForContinuity брал бы УСТАРЕВШУЮ точку кривой.
                    resetContinuity()
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
                queue.clear()
                pendingPlaySpec = null
                // Никакого capturePauseMetrics(): жёсткий стоп не возобновляется
                // из PAUSED, а следующий старт (в т.ч. play, пришедший во время
                // фейд-аута) якорится на текущий момент суток сам — см.
                // docs/analysis_resume_from_0_position.md. Снимок позиции нужен
                // только мягкой паузе: там он задаёт окно актуальности пакета.
                fadeOutCurrent(FadeTarget.STOP)
            }
            ManagerState.HANDOFF -> {
                // Хэндофф идёт: фейд CURRENT уже запущен, второй поток ещё не
                // создан — гасить нечего, только меняем цель рампы.
                queue.clear()
                pendingPlaySpec = null
                pendingHandoff = false
                resetContinuity()
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
                // CURRENT гаснет хэндоффом — pause() перехватывает рампу:
                // финалом становится заморозка, а не утилизация. Второго потока
                // нет, поэтому пауза во время смены пресета замораживает СТАРЫЙ
                // поток, а возобновление пойдёт уже по queued-спеке.
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
        // трек доигрывал, поэтому переснимаем по факту заморозки — иначе окно
        // актуальности [A0, F0] было бы сдвинуто назад на длительность фейда.
        capturePauseMetrics()
        setState(ManagerState.PAUSED)
        StreamLogger.d(TAG, "onPausedFully: PAUSED, поток жив spec#${live?.spec?.serial} " +
            "(A0=$pausedAudibleSeconds F0=$pausedFrontierSeconds, " +
            "окно=${normalizeTimeOfDay(pausedFrontierSeconds - pausedAudibleSeconds)}s, " +
            "accumulatedMs=$accumulatedMs, dirty=$pausedSpecDirty)")
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
     * Возобновление из PAUSED.
     *
     * СУТЬ ПРИЛОЖЕНИЯ: возобновление играет ритм для ТЕКУЩЕГО момента суток,
     * а не «продолжает с запомненной отметки». Но из этого НЕ следует, что
     * замороженный пакет надо выбрасывать при любой паузе: он устаревает
     * только когда текущий момент выходит за фронтир генерации.
     *
     * Три ветки:
     *  - настройки менялись на паузе → пересборка потока (звучал бы старый
     *    конфиг);
     *  - `now` внутри [A0, F0] → мягкое продолжение того же потока с
     *    пропуском Δ = now − A0 кадров из пакета;
     *  - `now` за F0 → пакет устарел, пересборка потока.
     */
    private fun onResumeFromPaused() {
        if (resumeInFlight) {
            // Старый замороженный поток ещё отдаёт пакет. Возобновление уже
            // заказано — повторный play ничего не меняет, но второй поток бы
            // создал: колбэк релиза запустил бы spec ещё раз.
            StreamLogger.d(TAG, "onResumeFromPaused: возобновление уже в полёте — игнор")
            return
        }
        if (pausedSpecDirty) {
            captureResumeAccuracy("REBUILD_DIRTY", null, null, null)
            StreamLogger.d(TAG, "onResumeFromPaused: настройки менялись на паузе — новый поток")
            resumeFromPaused()
            return
        }
        val s = current
        if (s == null || !s.isPaused) {
            // Замороженного потока нет — мягкое продолжение невозможно.
            captureResumeAccuracy("REBUILD_NO_STREAM", null, null, null)
            StreamLogger.d(TAG, "onResumeFromPaused: нет замороженного потока — пересборка")
            resumeFromPaused()
            return
        }
        // A0/F0 заморожены (пока поток на паузе генерация стоит и голова трека
        // стоит), поэтому сравнивать можно в любой момент. Если их вообще не
        // снимали (пауза без живого трека), окна нет — пересборка надёжнее,
        // чем пропуск по нулям.
        val a0 = pausedAudibleSeconds
        val f0 = pausedFrontierSeconds
        if (f0 <= 0f) {
            captureResumeAccuracy("REBUILD_NO_FRONTIER", null, null, null)
            StreamLogger.d(TAG, "onResumeFromPaused: фронтир не снят — пересборка")
            resumeFromPaused()
            return
        }
        val now = targetTimeOfDaySeconds()
        val delta = normalizeTimeOfDay(now - a0)
        val window = normalizeTimeOfDay(f0 - a0)
        if (delta <= window) {
            captureResumeAccuracy(
                "SOFT", delta, window,
                (delta * sampleRate.value).toLong()
            )
            StreamLogger.d(TAG, "onResumeFromPaused: пакет актуален (now=$now A0=$a0 F0=$f0, " +
                "Δ=${delta}s из окна ${window}s) — мягкое продолжение с пропуском")
            resumePausedStream(skipSeconds = delta)
        } else {
            captureResumeAccuracy("REBUILD_STALE", delta, window, null)
            StreamLogger.d(TAG, "onResumeFromPaused: пакет устарел (now=$now A0=$a0 F0=$f0, " +
                "Δ=${delta}s > окна ${window}s) — пересборка потока")
            resumeFromPaused()
        }
    }

    /**
     * Снять снимок решателя возобновления для debug-CLI `resumesnap`.
     *
     * Только debug-сборка ([BuildConfig.DEBUG]): в release поле никто не читает,
     * а R8 вырезает и вызов, и тело. Фиксирует, КАКОЙ путь выбрал резолвер и
     * с какими числами — это и есть материал для оценки точности привязки к
     * текущему моменту (см. docs/analysis_resume_from_0_position.md).
     *
     * @param resolution  SOFT — мягкое продолжение нестареющего пакета;
     *                    REBUILD_* — пересборка (устарел / грязная спека / нет
     *                    потока / не снят фронтир).
     * @param delta       Δ = now − A0 (длительность паузы, сек), либо null.
     * @param windowSec   окно актуальности lead = F0 − A0 (сек), либо null.
     * @param skipFrames  сколько кадров выброшено пропуском Δ·rate, либо null.
     */
    private fun captureResumeAccuracy(
        resolution: String,
        delta: Float?,
        windowSec: Float?,
        skipFrames: Long?
    ) {
        if (!BuildConfig.DEBUG) return
        val now = targetTimeOfDaySeconds()
        val sr = sampleRate.value
        lastResumeAccuracy = buildString {
            append("resolution=$resolution\n")
            append("now=${"%05.2f".format(now)}s\n")
            append("A0=$pausedAudibleSeconds F0=$pausedFrontierSeconds\n")
            if (delta != null && windowSec != null) {
                append("Δ(pause)=$delta window(lead)=$windowSec\n")
                // Точность пропуска: квантование кадра даёт ошибку ≤ 1/SR.
                val frameErr = 1.0f / sr
                append("skipFrames=$skipFrames (${"%.1f".format(delta * sr)} ожидалось)\n")
                append("quantizationError≤${"%.3f".format(frameErr)}s @${sr}Гц\n")
                // Переходная задержка до сходимости = длина замороженного кольца
                // трека = lead (window). За это время СЛЫШИМАЯ (raw) отстаёт от
                // now ровно на Δ, затем сходится. Компенсированный `audible`
                // уже равен now и эту задержку прячет.
                append("transientLagUntilConverge≈${windowSec}s (raw audible lags now by Δ=$delta)\n")
            } else {
                append("Δ/окно: — (пересборка, звук стартует с текущего момента суток)\n")
            }
            append("at=${System.currentTimeMillis()}")
        }
    }

    /**
     * Текущий момент суток, к которому обязан быть привязан звук.
     *
     * В debug-виртуальном времени носитель времени — сам движок (часы идут с
     * масштабом и могут быть перемотаны), поэтому настенные часы там не
     * источник истины; в обычном режиме это [realTimeOfDaySeconds].
     */
    private fun targetTimeOfDaySeconds(): Float {
        if (!debugVirtualTime) return realTimeOfDaySeconds()
        val virtual = current?.virtualTimeOfDaySeconds() ?: 0f
        return if (virtual > 0f) virtual else realTimeOfDaySeconds()
    }

    /**
     * Мягкое возобновление: тот же поток и тот же пакет, но с пропуском
     * устаревшей головы.
     *
     * Пакет не перегенерируется, фазы не сбрасываются. Единственное, что
     * меняется, — смещение чтения: писатель выбрасывает первые
     * [skipSeconds]·rate кадров, потому что они соответствуют уже прошедшему
     * времени суток. Слышимый звук догоняет текущий момент через R секунд
     * (кольцо трека доигрывает старый хвост) — на 24-часовой кривой дрейф
     * частоты за это время пренебрежим.
     *
     * @param skipSeconds Δ = now − A0; 0 — пакет не успел устареть, продолжаем
     *        ровно с того же сэмпла.
     */
    private fun resumePausedStream(skipSeconds: Float) {
        val s = current
        if (s == null || !s.isPaused) {
            StreamLogger.w(TAG, "resumePausedStream: поток непригоден (null=${s == null}) — пересоздание")
            resumeFromPaused()
            return
        }
        StreamLogger.d(TAG, "resumePausedStream: spec#${s.spec.serial} — мягкое продолжение " +
            "(буфер сохранён, пропуск ${skipSeconds}s)")
        // Часы сессии: нативный elapsed идёт по wall-clock, поэтому якорь
        // переставляется — иначе в elapsed попала бы вся длительность паузы.
        s.setPlaybackStartTime(System.currentTimeMillis() - accumulatedMs)
        segmentStartWallMs = System.currentTimeMillis()
        setState(ManagerState.FADE_IN)
        _isPlaying.value = true
        updateWakeLock()
        s.setVolume(volume)
        // Якорь UI — текущий момент суток, а не слышимая позиция по голове
        // трека: та ещё R секунд показывает старую точку, пока кольцо доигрывает
        // остаток. Ставим ДО старта записи, чтобы индикатор не мигнул назад.
        if (skipSeconds > 0f) {
            s.reanchorUiTimeline(targetTimeOfDaySeconds())
        }
        if (!s.resume(skipSeconds = skipSeconds, onFullyStarted = {
                if (state == ManagerState.FADE_IN) setState(ManagerState.RUNNING)
            })
        ) {
            // Трек не поддался (например, HAL отобрал устройство) — поднимаем
            // новый поток. Позицию он возьмёт сам: текущий момент суток.
            StreamLogger.e(TAG, "resumePausedStream: возобновление не удалось — пересоздание")
            // Тот же инвариант: сначала полный релиз (пакет + трек), потом новый
            // поток. Здесь current может быть уже null — тогда колбэк сработает
            // синхронно на этой же нити актёра.
            discardPausedCurrent { resumeFromPaused() }
        }
    }

    /**
     * Отцепить и тихо утилизировать замороженный поток. Он уже в нуле по
     * громкости и стоит на паузе — освобождение бесшумно.
     *
     * [afterRelease] исполняется на нити актёра ПОСЛЕ полного релиза: трек
     * снят, движок уничтожен, пакет отдан куче. Всё, что аллоцирует второй
     * пакет, обязано жить здесь — иначе инвариант одного загруженного потока
     * нарушается на время релиза (~150 мс по замеру).
     */
    private fun discardPausedCurrent(afterRelease: (() -> Unit)? = null) {
        val s = current
        if (s == null) {
            afterRelease?.invoke()
            return
        }
        current = null
        currentRef.set(null)
        StreamLogger.d(TAG, "discardPausedCurrent: spec#${s.spec.serial} paused=${s.isPaused}")
        s.stop(onFullyStopped = {
            StreamLogger.d(TAG, "discardPausedCurrent: spec#${s.spec.serial} освобождён")
            afterRelease?.invoke()
        })
    }

    private fun resumeFromPaused() {
        pausedSpecDirty = false
        val base = queue.poll() ?: sessionSpec ?: return
        // Якорь снимается в момент РЕАЛЬНОГО старта: между вызовом и запуском
        // лежит релиз замороженного потока. Снятый заранее якорь прибавил бы
        // эти миллисекунды к сессионным часам.
        val launch = {
            val spec = base.copy(
                volume = volume,
                reason = SpecReason.RESUME,
                resumeAnchorMs = System.currentTimeMillis() - accumulatedMs,
                resumeElapsedMs = accumulatedMs
                // resumeCurveTimeSeconds ОСТАЁТСЯ -1 — сознательно.
                //
                // СУТЬ ПРИЛОЖЕНИЯ: возобновление играет ритм для ТЕКУЩЕГО
                // момента суток. Подставлять сюда pausedTimeOfDay (как делал
                // предыдущий фикс) — значит превратить паузу в «перемотку
                // назад»: после десятиминутной паузы звук продолжал бы с
                // десятиминутной давности точки кривой. prepare() при
                // resumeCurveTimeSeconds = -1 якорит кривую на now явно.
                // Часы сессии при этом продолжаются (resumeElapsedMs =
                // accumulatedMs): пауза в elapsed не идёт.
            )
            sessionSpec = spec
            launchSpec(spec)
        }
        val doomed = current
        if (doomed == null) {
            // Замороженного потока нет (пауза без живого трека) — нечего ждать.
            launch()
            return
        }
        // Замороженный поток звучит по старой спеке И ЕЩЁ ДЕРЖИТ ПАКЕТ: новый
        // поток создаём только после его полного релиза, иначе в куче на эти
        // ~150 мс висели бы два пакета (замер до правки: launchSpec
        // reason=RESUME загруженныхБуферов=1 — единственное место, где
        // инвариант одного потока нарушался).
        resumeInFlight = true
        StreamLogger.d(TAG, "resumeFromPaused: spec#${base.serial} отложен до релиза " +
            "замороженного spec#${doomed.spec.serial} " +
            "(держателей пакета=${BinauralStreamImpl.livePacketHolders()})")
        discardPausedCurrent {
            resumeInFlight = false
            if (state != ManagerState.PAUSED) {
                // Пока пакет отдавался, пришёл stop: сессия уже сброшена,
                // запускать нечего — иначе play пережил бы stop.
                StreamLogger.d(TAG, "resumeFromPaused: state=$state — возобновление отменено")
                return@discardPausedCurrent
            }
            if (pausedSpecDirty) {
                // Пока пакет отдавался, пользователь успел сменить пресет:
                // onSpecChanged положил свежую спеку в sessionSpec и поднял
                // флаг. Запускать [base] — значит молча проиграть старое.
                // Пересобираем спеку; current уже null, поэтому ждать нечего.
                StreamLogger.d(TAG, "resumeFromPaused: настройки обновились за время релиза — пересборка")
                resumeFromPaused()
                return@discardPausedCurrent
            }
            launch()
        }
    }

    /**
     * Снять координаты замороженного пакета (A0/F0) и часы сессии.
     *
     * A0 и F0 — границы окна актуальности: пока текущий момент суток внутри
     * [A0, F0], звук для него уже сгенерирован. Снимаются и ДО фейд-аута
     * (здесь), и ПО ФАКТУ заморозки ([onPausedFully]) — за время рампы трек
     * доигрывает, и снимок, сделанный до неё, отстал бы на длительность фейда.
     */
    private fun capturePauseMetrics() {
        current?.let {
            pausedElapsedSeconds = it.getElapsedSeconds()
            // СЛЫШИМАЯ позиция: где звук реально остановился (голова трека
            // минус недописанный хвост), а не UI-часы и не фронтир генерации.
            val audible = it.audibleCurveSeconds()
            pausedAudibleSeconds = audible ?: pausedTimeOfDay.toFloat()
            pausedTimeOfDay = it.getAudibleTimeOfDaySeconds()
            pausedFrontierSeconds = it.frontierCurveSeconds()
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
     * Создать и запустить поток по спеке. Единственная точка, где поток вообще
     * создаётся, — вызывается только когда [current] уже null (старый поток
     * утилизирован), поэтому загружен всегда ровно один поток.
     */
    private fun launchSpec(spec: PlaybackSpec) {
        StreamLogger.d(TAG, "launchSpec spec#${spec.serial} reason=${spec.reason} " +
            "загруженныхБуферов=${BinauralStreamImpl.livePacketHolders()}")
        val candidate = createStream(spec)
        if (!candidate.prepare()) {
            // Ошибка подготовки: стабильное состояние + сессия сохранена для повторной попытки
            StreamLogger.e(TAG, "launchSpec: prepare spec#${spec.serial} не удался (retryable)")
            // Якорь непрерывности больше некому отдать: поток не родился. Без
            // сброса pendingHandoff висел бы до следующего launchStream и
            // подменил accumulatedMs при следующей смене.
            pendingHandoff = false
            resetContinuity()
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
        // Потолок мог сузиться ПОСЛЕ того, как пользователь выбрал интервал:
        // выученный после OOM или ручной `packetmax` из debug-команд. Слайдер
        // увидит новые стопы только при следующей перекомпозиции, поэтому
        // переподчиняем значение пределу здесь, на входе в поток — иначе
        // prepare() молча урезал бы его, и настройка снова соврала бы.
        //
        // Виртуальное время не трогаем: там интервал намеренно 250 мс, ниже
        // минимальной стопы слайдера, и кламп превратил бы его в 60 с.
        val intervalMs = if (debugVirtualTime) bufferIntervalMs else {
            val clamped = clampToRate(bufferIntervalMs)
            if (clamped != bufferIntervalMs) {
                StreamLogger.d(TAG, "createStream: интервал переподчинён потолку " +
                    "$bufferIntervalMs -> $clamped мс")
                bufferIntervalMs = clamped
            }
            clamped
        }
        return BinauralStreamImpl(
            context = context,
            spec = spec,
            controlHandler = actor,
            bufferIntervalMs = intervalMs,
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
        StreamLogger.e(TAG, "handleRuntimeError: $message (stream spec#${stream.spec.serial}, isCurrent=${current === stream})")
        Log.e(TAG, "runtime error: $message")
        // Ссылка на живой поток одна ([current]), поэтому «потока без владельца»
        // не бывает: ошибка не от current означает, что поток уже утилизирован.
        if (current !== stream) return
        capturePauseMetrics()
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
        pausedAudibleSeconds = 0f
        pausedFrontierSeconds = 0f
        pausedSpecDirty = false
        pendingResume = false
        pendingPlaySpec = null
        pendingHandoff = false
        resumeInFlight = false
        lastResumeAccuracy = null
        resetContinuity()
    }
}
