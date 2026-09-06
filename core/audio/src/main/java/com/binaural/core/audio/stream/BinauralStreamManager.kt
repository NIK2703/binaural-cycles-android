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

/**
 * Состояние автомата менеджера.
 *
 * `RECREATING` — ПЕРЕСОЗДАНИЕ трека (аварийный путь [BinauralStreamManager]
 * смена частоты дискретизации либо отказ [BinauralStream.retune]): CURRENT
 * гаснет, новый поток поднимается только после ПОЛНОГО релиза старого.
 * Второго ОДНОВРЕМЕННО звучащего трека при этом нет — состояние описывает
 * разрыв, а не перекрытие. Штатная смена настроек сюда не приходит вообще:
 * она идёт через [BinauralStream.retune] на живом треке, в RUNNING.
 */
enum class ManagerState {
    IDLE, PREPARING, FADE_IN, RUNNING, RECREATING, FADE_OUT_PAUSE, FADE_OUT_STOP, PAUSED
}

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

        // ---- Сторож инварианта «звук == сейчас» (только debug) ----
        /** Как часто снимать слышимую позицию. */
        private const val WATCHDOG_PERIOD_MS = 500L
        /** Допустимое расхождение слышимой позиции с «сейчас», секунды. */
        private const val WATCHDOG_TOL_SEC = 2f
        /** Как долго расхождение должно ДЕРЖАТЬСЯ, чтобы считаться нарушением. */
        private const val WATCHDOG_SUSTAIN_MS = 3_000L
        /**
         * Грейс после старта потока: стартовый пакет (2 с) и разгон кольца
         * трека дают легальное расхождение, которое нечего логировать.
         */
        private const val WATCHDOG_GRACE_MS = 3_000L
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
     * ЕДИНСТВЕННЫЙ живой поток.
     *
     * Второго слота больше не существует. Прежний инвариант («не больше двух
     * AudioTrack») держался на [retune]: смена настроек, скраб и смена
     * пресета перенастраивают ЖИВОЙ трек, а не создают новый, поэтому
     * держать уходящий поток не нужно — а вместе с ним не нужны ни сторож
     * его утилизации, ни запрет доращивания пакета, ни расчёт «момента
     * тишины» по живому множителю шейпера.
     *
     * Второй поток создаётся ровно в одном случае — [recreateTrack]:
     * смена частоты дискретизации (трек физически другой) либо отказ
     * [BinauralStream.retune]. И там новый поток поднимается только ПОСЛЕ
     * полного релиза старого, то есть одновременно жив всегда один.
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

    /**
     * СКРАБ: сдвиг ОСИ времени суток в секундах, [0, 86400). 0 = звук следует
     * за реальным моментом суток (обычный режим).
     *
     * Модель (docs/plan_playback_scrub_handle.md §2): сдвигается не «позиция
     * трека», а ОСЬ — `ось(t) = normalize(реальное_сейчас + сдвиг)`. Кривая
     * при этом продолжает эволюционировать под прослушиванием, а всё, что
     * производно от времени суток (знаковая раскладка каналов, relaxation,
     * beat scatter), остаётся консистентным. Замороженная позиция дала бы
     * застывший звук — ровно то, чего слушать не надо.
     *
     * Сдвиг — СКАЛЯР, а не захваченный якорь: якорь устаревает за время
     * фейд-аута и релиза старого потока, скаляр же применяется к «сейчас»
     * уже внутри `prepare()` и устареть не может.
     *
     * Пишется и читается ТОЛЬКО на нити актёра.
     */
    private var scrubOffsetSec = 0

    /**
     * Сдвиг оси, замороженный на паузе. PAUSED держит ЖИВОЙ поток со СТАРОЙ
     * осью, поэтому [scrubOffsetSec] (уже новый) к нему неприменим до
     * возобновления; сравнивать приходится с этим снимком.
     */
    private var pausedScrubOffsetSec = 0

    /**
     * СКРАБ: живой (или замороженный на паузе) поток стоит на оси
     * предпросмотра, хотя флаг [scrubOffsetSec] уже снят — то есть возврат к
     * реальному «сейчас» ЗАКАЗАН, но ещё не воплощён в звуке.
     *
     * Зачем отдельный флаг, если сдвиг и так лежит в [scrubOffsetSec].
     * Потому что тихий [resetScrub] стирает сдвиг, но сам поток не трогает:
     * он рассчитан на то, что попутный хэндофф (сохранение, отмена правок
     * кривой, смена пресета) подберёт обнулённый сдвиг и вернёт звук.
     * Попутного хэндоффа может и не быть — тогда звук остаётся на оси
     * предпросмотра навсегда, а все сторожи видят `scrubOffsetSec == 0` и
     * считают, что делать нечего. Ровно это и происходило при выходе из
     * редактора без правок кривой: ЛИНИЯ возвращалась на «сейчас», а
     * ВОСПРОИЗВЕДЕНИЕ оставалось на времени предпросмотра.
     *
     * Флаг отвечает на другой вопрос: «ось того потока, который звучит,
     * сдвинута?» — а не «какой сдвиг задан». Поэтому:
     *  - `true` выставляет [clearScrubState], глядя на ось ЖИВОГО потока;
     *  - `false` выставляет [launchStream], когда поток со спекой
     *    материализовался (ось спеки — это и есть ось звука);
     *  - `false` выставляет [resetSession], когда потока не стало вовсе.
     *
     * Пишется и читается ТОЛЬКО на нити актёра.
     */
    private var scrubNeedsRealignment = false

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

    // НЕПРЕРЫВНОСТЬ БОЛЬШЕ НЕ ЗАХВАТЫВАЕТСЯ. Раньше при каждом хэндоффе
    // снимались фазы несущих («иначе NEXT стартует с фазы 0 и интерферирует
    // с уходящим») и часы сессии, а позиция кривой наследовалась с протухшим
    // якорем (лаг копился от правки к правке, цепочка 0 → 0 → 0 защёлкивалась).
    // Всё это нужно было ровно для одного — перекрытия двух потоков.
    // Перекрытия нет: [BinauralStream.retune] меняет конфиг на ЖИВОМ треке,
    // то есть движок, фазы и elapsed продолжаются сами, без переноса.
    // Единственный случай, где что-то надо переносить, — [recreateTrack]:
    // там переносится только [PlaybackSpec.resumeElapsedMs] (часы сессии),
    // а кривую новый поток якорит на «сейчас» в `prepare()`.

    // Состояние сторожа инварианта (только debug-сборка).
    private var watchdogBreachSinceMs = 0L
    private var watchdogGraceUntilMs = 0L

    // Снапшот для геттеров из других потоков
    private val currentRef = java.util.concurrent.atomic.AtomicReference<BinauralStreamImpl?>(null)

    var listener: Listener? = null

    // ---------------- UI-потоки (совместимые со старым движком) ----------------
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    // Состояние автомата. Пишется и читается ТОЛЬКО на нити актёра; наружу
    // уходит копией через [_managerState].
    private var state = ManagerState.IDLE
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

    /**
     * СКРАБ: РЕАЛЬНЫЙ момент времени суток — без сдвига предпросмотра.
     * Серая линия на графике (§5.1 плана).
     *
     * Зачем отдельный поток, если реальное время можно получить как
     * `ось − сдвиг`: ось ([currentTimeOfDaySeconds]) и сдвиг
     * ([scrubOffsetSeconds]) — это ДВА разных StateFlow, которые доезжают до
     * UI в непредсказуемом порядке. Вычитание на стороне UI неизбежно
     * смешивает разновозрастные значения, а пришедший первым сдвиг уже нельзя
     * «приклеить» к пришедшей позже оси — серая линия уезжала на величину
     * сдвига и висела на цели перетаскивания до следующего изменения оси
     * (то есть до минуты, квантование телеметрии). docs/…scrub_handle.md §14.7.
     *
     * Здесь оба времени считаются в ОДНОМ вызове из ОДНОЙ базы — позиции
     * потока, который реально звучит. Поэтому они гарантированно одной
     * «свежести», а расстояние между красной и серой линиями в точности
     * равно сдвигу, включая окно кроссфейда.
     */
    // null — «ещё ничего не публиковали». Ноль здесь означал бы полночь и был
    // бы отрисован как настоящее время (проверено на устройстве: на старте
    // серая линия вставала на 00:00). У времени суток нет осмысленного
    // «пустого» числа — только отсутствие значения.
    private val _unshiftedTimeOfDaySeconds = MutableStateFlow<Int?>(null)
    val unshiftedTimeOfDaySeconds: StateFlow<Int?> = _unshiftedTimeOfDaySeconds.asStateFlow()
    private val _isChannelsSwapped = MutableStateFlow(false)
    val isChannelsSwapped: StateFlow<Boolean> = _isChannelsSwapped.asStateFlow()
    /** СКРАБ: активный сдвиг оси времени суток (0 = звук за реальным сейчас). */
    private val _scrubOffsetSeconds = MutableStateFlow(0)
    val scrubOffsetSeconds: StateFlow<Int> = _scrubOffsetSeconds.asStateFlow()

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

    // ---------------- Скраб: предпросмотр другого времени суток ----------------

    /**
     * СКРАБ: сдвинуть ось времени суток так, чтобы звучало время [timeOfDaySeconds].
     *
     * Сдвиг считается от РЕАЛЬНОГО «сейчас» ([baseTimeOfDaySeconds]), а не от
     * текущего сдвинутого положения: иначе повторный скраб на ту же цель
     * накапливал бы дельту и ось уезжала бы всё дальше.
     *
     * docs/plan_playback_scrub_handle.md
     */
    fun scrubTo(timeOfDaySeconds: Int) = actor.post {
        val target = ((timeOfDaySeconds % 86400) + 86400) % 86400
        val delta = normalizeTimeOfDay(target - baseTimeOfDaySeconds()).toInt()
        StreamLogger.d(TAG, "scrubTo ${formatTod(target)} (сдвиг=$delta с, " +
            "прежний=${scrubOffsetSec} с, state=$state)")
        applyScrub(delta)
    }

    /**
     * СКРАБ: вернуть прослушивание к реальному текущему моменту суток.
     *
     * Идемпотентно, но «нечего делать» проверяется ШИРЕ, чем «сдвиг ноль».
     * Тихий [resetScrub] стирает [scrubOffsetSec], но сам звук оставляет на
     * оси предпросмотра — возврат разыгрывает попутный хэндофф, а его может
     * не быть. Поэтому здесь три условия: заданный сдвиг, замороженный на
     * паузе сдвиг и [scrubNeedsRealignment] (звук ещё на сдвинутой оси).
     * Раньше сторож был `scrubOffsetSec == 0` и после тихого сброса молча
     * отключался — из-за этого выход из редактора без правок кривой
     * возвращал ЛИНИЮ, но не ВОСПРОИЗВЕДЕНИЕ.
     */
    fun scrubReset() = actor.post {
        if (scrubOffsetSec == 0 && pausedScrubOffsetSec == 0 && !scrubNeedsRealignment) return@post
        StreamLogger.d(TAG, "scrubReset (сдвиг=${scrubOffsetSec} с, замороженный=${pausedScrubOffsetSec} с, " +
            "звукНаСдвинутойОси=$scrubNeedsRealignment, state=$state)")
        applyScrub(0)
        // Возврат заказан: хэндофф поднимется на оси 0, а [launchStream]
        // пересчитает флаг по факту материализации потока. Снимаем здесь,
        // чтобы повторный scrubReset (страховки навигации/жизненного цикла
        // вызывают его по несколько раз) не заказал второй кроссфейд.
        scrubNeedsRealignment = false
    }

    /**
     * СКРАБ: сбросить сдвиг оси, не трогая сам поток.
     *
     * Сдвинутая ось — это осознанная ложь о времени, поэтому она обязана жить
     * ровно столько, сколько пользователь про неё помнит: полный стоп, смена
     * пресета и выход из редактора стирают её, а пауза и правки настроек —
     * НЕТ (иначе править кривую под прослушивание было бы нельзя).
     */
    fun resetScrub() = actor.post {
        if (scrubOffsetSec != 0 || pausedScrubOffsetSec != 0 || scrubNeedsRealignment) {
            StreamLogger.d(TAG, "resetScrub: сдвиг ${scrubOffsetSec} с снят (state=$state)")
        }
        clearScrubState()
        // Тихий сброс оставил звук на старой оси; возврат на реальную ось
        // делает только перенастройка. Разыгрываем её здесь, а не ждём
        // «следующего изменения настроек»: соседний пресет может нести
        // ТУ ЖЕ кривую, и updateConfig() его продедуплицирует — ось тогда
        // НЕ вернулась бы, а менеджер уже показал scrub=0 (ловушка V9
        // tools/dbgscrub.sh: INVARIANT НАРУШЕН на величину старого сдвига).
        // scrubNeedsRealignment выставлен по оси ЖИВОГО потока, поэтому
        // условие ровно закрывает этот случай и не срабатывает вхолостую.
        if (scrubNeedsRealignment) onSpecChanged(SpecReason.SETTINGS)
    }

    private fun clearScrubState() {
        // Сдвиг стирается, а звук остаётся где был: возврат на реальную ось
        // сделает только хэндофф. Запоминаем, нужен ли он, по оси потока,
        // который звучит (или заморожен на паузе) ПРЯМО СЕЙЧАС, — а не по
        // заданному сдвигу, который сейчас обнуляем.
        val live = current
        scrubNeedsRealignment = live?.spec?.scrubOffsetSec?.let { it != 0 } ?: false
        scrubOffsetSec = 0
        pausedScrubOffsetSec = 0
        _scrubOffsetSeconds.value = 0
    }

    /** Применить сдвиг на нити актёра и разыграть его через обычный маршрут спеки. */
    private fun applyScrub(delta: Int) {
        // PAUSED держит живой поток со СТАРОЙ осью: её и запоминаем, чтобы
        // возобновление знало, где звучал замороженный пакет.
        pausedScrubOffsetSec = if (state == ManagerState.PAUSED) scrubOffsetSec else delta
        scrubOffsetSec = delta
        _scrubOffsetSeconds.value = delta
        // Ось UI обязана поехать ВМЕСТЕ со сдвигом, а не на следующем тике
        // опроса (1 Гц): иначе красная линия на графике до секунды висела бы
        // на старом «сейчас» и жест выглядел бы не сработавшим. Поправку §3.6
        // считает сам updateCurrentFrequencies: старый поток ещё в слоте, его
        // spec.scrubOffsetSec — прежний, поэтому поправка равна ровно новому
        // сдвигу и ось UI встаёт на цель немедленно.
        updateCurrentFrequencies()
        onSpecChanged(SpecReason.SCRUB)
    }

    private fun formatTod(seconds: Int): String =
        "%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60)

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
            // СКРАБ: оба времени UI считаются из ОДНОЙ базы — позиции потока,
            // который реально звучит. Во время кроссфейда `current` — это ещё
            // старый поток со старым сдвигом, поэтому снимать надо именно
            // ЕГО сдвиг: тогда получается реальное «сейчас», а целевая ось =
            // реальное «сейчас» + общий сдвиг менеджера. Разность сдвигов
            // (прежняя формула оси) в этой записи содержится автоматически —
            // но рядом с ней теперь публикуется и второе число, без которого
            // UI приходилось вычитать одно из другого (§14.7 плана).
            val realSec =
                normalizeTimeOfDay(s.getCurrentTimeOfDay().toFloat() - s.spec.scrubOffsetSec).toInt()
            _unshiftedTimeOfDaySeconds.value = realSec
            _currentTimeOfDaySeconds.value =
                normalizeTimeOfDay(realSec.toFloat() + scrubOffsetSec).toInt()
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
            if (pausedTimeOfDay > 0) {
                // СКРАБ: замороженная точка снята на оси замороженного пакета
                // ([pausedScrubOffsetSec]), а сдвиг с тех пор мог измениться —
                // скраб на паузе легален. Показываем ЦЕЛЕВУЮ ось, иначе линия
                // осталась бы там, где звук замер, и не отразила бы выбор.
                // Реальное «сейчас» здесь тоже заморожено (звук стоит) —
                // иначе расстояние между линиями перестало бы быть сдвигом.
                val frozenRealSec =
                    normalizeTimeOfDay(pausedTimeOfDay.toFloat() - pausedScrubOffsetSec).toInt()
                _unshiftedTimeOfDaySeconds.value = frozenRealSec
                _currentTimeOfDaySeconds.value =
                    normalizeTimeOfDay(frozenRealSec.toFloat() + scrubOffsetSec).toInt()
            } else {
                // Нет потока: ось публикуем только если она куда-то сдвинута
                // (звук встанет на неё при старте), а реальное «сейчас» —
                // ВСЕГДА. Именно оно держит серую линию на месте с первого
                // же кадра после скраба, пока ось ещё едет.
                val baseSec = baseTimeOfDaySeconds().toInt()
                _unshiftedTimeOfDaySeconds.value = baseSec
                if (scrubOffsetSec != 0) {
                    _currentTimeOfDaySeconds.value =
                        normalizeTimeOfDay(baseSec.toFloat() + scrubOffsetSec).toInt()
                }
            }
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
        // Пара к оси: оператор переставил ось, значит «реальным сейчас» для
        // графиков становится ось минус активный сдвиг предпросмотра.
        _unshiftedTimeOfDaySeconds.value =
            normalizeTimeOfDay(timeSeconds.toFloat() - scrubOffsetSec).toInt()
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
            clearScrubState()
            resetSession()
            _isPlaying.value = false
            setState(ManagerState.IDLE)
            updateWakeLock()
        }
        StreamLogger.flush()
        actorThread.quitSafely()
    }

    // ================================================================== ЛОГИКА АКТЁРА

    /**
     * Состояния, в которых поток звучит (или выходит на звук) и очередь
     * настроек имеет смысл разыгрывать.
     *
     * [ManagerState.RECREATING] сознательно ВКЛЮЧЕН — иначе жесты, пришедшие
     * во время пересоздания трека, не попали бы в очередь вовсе и потерялись.
     * Но саму очередь в этом состоянии разыгрывать нельзя: [current] ещё
     * жив и гаснет, перенастраивать его поздно — сторож на этот случай стоит
     * в [tryAdvanceQueue].
     */
    private fun isActiveState() =
        state == ManagerState.RUNNING || state == ManagerState.FADE_IN ||
            state == ManagerState.RECREATING

    private fun buildSpec(reason: SpecReason) = PlaybackSpec(
        serial = ++serialSeq,
        config = config,
        relaxation = relaxation,
        sampleRate = sampleRate,
        volume = volume,
        reason = reason,
        // Сдвиг оси — часть спеки: он переживает хэндофф и пересборку потока.
        scrubOffsetSec = scrubOffsetSec
    )

    /**
     * Нужен ли РЕАЛЬНЫЙ хэндофф (новый поток), или можно ограничиться
     * подстройкой громкости живого.
     *
     * [PlaybackSpec.audioEquals] сравнивает только то, что СЛЫШНО (кривая,
     * relaxation, частота), поэтому скраб — та же кривая на другой оси — для
     * него «ничего не изменилось». Без явного сравнения сдвига предпросмотр
     * молча деградировал бы в `setVolume` и не применялся бы вовсе (ровно эта
     * ловушка уже делает бесполезным `debugScrub`).
     */
    private fun needsHandoff(cur: BinauralStreamImpl, next: PlaybackSpec): Boolean =
        !cur.spec.audioEquals(next) || cur.spec.scrubOffsetSec != next.scrubOffsetSec

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
                // спеке. Раньше настройки применялись только при возобновлении
                // (поток помечался «грязным» и пересобирался). Теперь их можно
                // применить НА МЕСТЕ: тишина уже есть, писатель припаркован, и
                // [BinauralStreamImpl.retune] обходится без провала вообще.
                val spec = buildSpec(reason)
                sessionSpec = spec
                val cur = current
                if (cur == null) {
                    pausedSpecDirty = true
                    return
                }
                // Ничего значимого не изменилось (например, подвигали только
                // громкость) — будить писателя незачем.
                if (!needsHandoff(cur, spec)) return
                if (cur.spec.sampleRate == spec.sampleRate && cur.retune(spec) { ok ->
                        // Пакет перестроен: окно актуальности A0/F0 описывает
                        // уже выброшенный PCM, снимаем его заново. Отказ же
                        // означает ровно то, что означал флаг раньше: на
                        // возобновлении поток надо пересобрать.
                        if (ok) recapturePausedWindow(cur) else pausedSpecDirty = true
                    }) {
                    StreamLogger.d(TAG, "onSpecChanged: PAUSED retune spec#${cur.spec.serial} -> " +
                        "spec#${spec.serial} ($reason) принят — пересборки на возобновлении не будет")
                } else {
                    pausedSpecDirty = true
                }
            }
            ManagerState.FADE_OUT_PAUSE, ManagerState.FADE_OUT_STOP -> queue.offer(buildSpec(reason))
            else -> requestHandoff(buildSpec(reason))
        }
    }

    private fun requestHandoff(spec: PlaybackSpec) {
        // Быстрый путь: изменилась только громкость — потоки не пересоздаём.
        val cur = current
        if (cur != null && !needsHandoff(cur, spec) && isActiveState()) {
            cur.setVolume(spec.volume); return
        }
        // Коалесценция: один слот, побеждает новейший. Шторм A→B→C→D
        // материализуется в одно приседание: [PlaybackQueue] хранит только
        // последнюю спеку, а [BinauralStreamImpl.retune] — тоже один слот,
        // поэтому пришедший во время приседания жест лишь переставляет цель.
        queue.offer(spec)
        StreamLogger.d(TAG, "requestHandoff: spec#${spec.serial} в очередь (state=$state, " +
            "reason=${spec.reason})")
        tryAdvanceQueue()
    }

    /**
     * Разыграть очередь: поднять NEXT на ту спеку, которая ещё не звучит.
     *
     * Единственная точка принятия решения «создавать поток или нет», поэтому
     * все пути (смена настройки, смена пресета, частоты, догон после полного
     * релиза старого) обязаны приходить сюда, а не вызывать [launchSpec]
     * напрямую.
     *
     * Условия, при которых очередь НЕ разыгрывается:
     *  - состояние [ManagerState.RECREATING] — CURRENT гаснет и скоро будет
     *    утилизирован: перенастраивать его уже поздно, а новый поток
     *    поднимать рано (инвариант «загружен ровно один поток»). Спеку
     *    подберёт [onStreamFullyStopped] из ветки SWITCH;
     *  - состояние не активное (IDLE/PAUSED/FADE_OUT_*) — там очередь
     *    разбирают свои обработчики ([onPlay], [onResumeFromPaused],
     *    [onStreamFullyStopped]), у них свои правила якорения.
     */
    private fun tryAdvanceQueue() {
        if (state == ManagerState.RECREATING) {
            StreamLogger.d(TAG, "tryAdvanceQueue: RECREATING — спеку подберёт " +
                "onStreamFullyStopped после полного релиза CURRENT")
            return
        }
        val queued = queue.peek() ?: return
        val cur = current
        if (cur == null) {
            // Гасить нечего — это не переход, а обычный запуск.
            launchSpec(queue.poll() ?: return)
            return
        }
        if (!isActiveState()) {
            StreamLogger.d(TAG, "tryAdvanceQueue: состояние $state — разберёт свой обработчик")
            return
        }
        if (!needsHandoff(cur, queued)) {
            // За время ожидания успело совпасть с живым потоком — Nothing to do.
            queue.poll()
            cur.setVolume(volume)
            return
        }
        beginTransition(queue.poll() ?: return)
    }

    // Дополнительное runtime-поле (продолжение): запрос НА СТАРТ для паттерна
    // «stopWithFade -> play». В отличие от очереди настроек — это именно намерение стартовать.
    private var pendingPlaySpec: PlaybackSpec? = null

    /**
     * ЕДИНЫЙ ПЕРЕХОД: КАК БЫ ни изменилась спека, смена звучит одинаково —
     * коротким «приседанием» на живом потоке.
     *
     * Смена пресета, правка настройки, смена частоты дискретизации и скраб
     * идут через ОДИН метод: у пользователя это один и тот же жест
     * «изменилось что-то, зазвучи по-новому».
     *
     * ОСНОВНОЙ ПУТЬ — [BinauralStream.retune]: тот же AudioTrack и тот же
     * нативный движок, приседание [RETUNE_RAMP_MS] вниз и столько же вверх.
     * Второго потока не существует вовсе, поэтому перекрытие исключено по
     * построению: нечему интерферировать и нечем обмениваться ушами
     * (docs/analysis_scrub_storm_click_risk.md §4.1, §4.2, §4.4).
     *
     * Второй поток остаётся ровно в двух случаях, и оба ведут в
     * [recreateTrack]:
     *  - сменилась частота дискретизации — трек физически надо пересоздавать;
     *  - [BinauralStream.retune] отказал (поток не в PLAYING, не успел в
     *    дедлайн и т.п.) — аварийная ветвь, ценой разрыва звука.
     *
     * @param spec уже изъята из очереди: очередь из одного слота, новейшая
     *             вытесняет прежнюю.
     */
    private fun beginTransition(spec: PlaybackSpec) {
        val old = current
        if (old == null) {
            // Гасить нечего — это не переход, а обычный запуск.
            StreamLogger.d(TAG, "beginTransition: current==null — обычный запуск spec#${spec.serial}")
            launchSpec(spec)
            return
        }
        // ПЕРЕНАСТРОЙКА ЖИВОГО ПОТОКА (docs/plan_handoff_single_track.md):
        // тот же AudioTrack и тот же движок, короткое приседание вместо
        // второго трека. Второй трек остаётся только для смены частоты
        // дискретизации — там трек физически надо пересоздавать, — и как
        // аварийный путь, если [BinauralStream.retune] отказался.
        if (old.spec.sampleRate == spec.sampleRate && old.retune(spec) { ok ->
                if (!ok) {
                    StreamLogger.w(TAG, "beginTransition: retune spec#${spec.serial} не удался — " +
                        "пересоздание трека (разрыв звука ~100–200 мс)")
                    recreateTrack(spec)
                }
            }) {
            sessionSpec = spec
            StreamLogger.d(TAG, "beginTransition: retune spec#${old.spec.serial} -> " +
                "spec#${spec.serial} принят (причина=${spec.reason}) — второй трек не создан")
            return
        }
        recreateTrack(spec)
    }

    /**
     * ПЕРЕСОЗДАНИЕ ТРЕКА — единственный путь, где на мгновение существует
     * второй поток… ровно наоборот: где НОВЫЙ поток создаётся только после
     * ПОЛНОГО релиза старого.
     *
     * Сюда приходят смена частоты дискретизации (трек физически другой) и
     * отказ [BinauralStream.retune]. Между «утих» и «зазвучал» лежит разрыв
     * ~100–200 мс — цена, которую платим только в этих двух случаях; штатная
     * смена настроек/пресета его не слышит вовсе (docs/plan_handoff_single_track.md).
     *
     * Инвариант «загружен ровно один поток» сохраняется тривиально: [current]
     * гасится и утилизируется, и лишь [onStreamFullyStopped] (ветка SWITCH)
     * поднимает новую спеку из очереди.
     */
    private fun recreateTrack(spec: PlaybackSpec) {
        // Часы сессии: новый движок стартует с elapsed=0 — переносим накопленное
        accumulatedMs += System.currentTimeMillis() - segmentStartWallMs
        queue.offer(spec.copy(resumeElapsedMs = accumulatedMs))
        StreamLogger.d(TAG, "recreateTrack spec#${spec.serial}: фейд-аут CURRENT, " +
            "загрузка после полного релиза (разрыв звука ожидаем)")
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
        // Форма всегда LINEAR: перекрытия нет ни в одном переходе, делить
        // мощность не с кем (см. KDoc [FadeShape.EQUAL_POWER]).
        // Колбэк исполняется на нити актёра (у потока controlHandler == actor).
        // Идентичность (captured === current) отсекает потоки, чья судьба уже
        // решена отдельно (стоп/пауза во время фейда).
        captured.stop(
            onFullyStopped = { onStreamReleased(captured) },
            shape = FadeShape.LINEAR
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
                FadeTarget.SWITCH -> ManagerState.RECREATING
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
                    // (resumeAnchor = NONE → engine.getCurrentTimeOfDay()).
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
                // ПЕРЕСОЗДАНИЕ ТРЕКА ([recreateTrack]) — аварийная ветвь:
                // смена частоты дискретизации или отказ [BinauralStream.retune].
                // Штатная смена настроек/пресета сюда не приходит вообще: она
                // перенастраивает живой поток и остаётся в RUNNING.
                //
                // Старый поток к этой точке ПОЛНОСТЬЮ утилизирован: трек снят,
                // движок уничтожен, пакет отдан. current занулён выше, поэтому
                // «повторить переход против живого current» невозможно — и это
                // ровно та гарантия, на которой держится инвариант «загружен не
                // более одного потока»: createStream() ниже — единственный живой
                // поток в процессе.
                //
                // Часы сессии уже перенесены в recreateTrack (через
                // resumeElapsedMs), поэтому здесь их не трогаем.
                val spec = queue.poll()
                if (spec == null) {
                    resetSession()
                    StreamLogger.d(TAG, "onStreamFullyStopped: SWITCH без спек — IDLE")
                    setState(ManagerState.IDLE)
                } else {
                    StreamLogger.d(TAG, "onStreamFullyStopped: SWITCH -> загрузка spec#${spec.serial} " +
                        "(accumulatedMs=$accumulatedMs)")
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
            else -> { /* PREPARING/FADE_IN/RUNNING/RECREATING: идемпотентно */ }
        }
    }

    private fun onStop() {
        StreamLogger.d(TAG, "onStop state=${state.name}")
        // СКРАБ: полный стоп возвращает прослушивание к реальному моменту суток.
        clearScrubState()
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
            ManagerState.RECREATING -> {
                // CURRENT гаснет ради пересоздания трека. Направление рампы
                // меняем на STOP: спеку из очереди подбирать больше некому и
                // не нужно, стоп побеждает.
                queue.clear()
                pendingPlaySpec = null
                fadeOutCurrent(FadeTarget.STOP)
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
            ManagerState.RECREATING -> {
                capturePauseMetrics()
                // CURRENT гаснет ради пересоздания — pause() перехватывает
                // рампу: финалом становится заморозка, а не утилизация.
                // Второго потока нет, поэтому пауза замораживает СТАРЫЙ поток,
                // а возобновление пойдёт уже по queued-спеке.
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
     *  - `now` внутри [A0, F0] → мягкое продолжение того же потока: писатель
     *    перематывается на кадр `T = A0 + Δ·rate` (Δ = now − A0) и сбрасывает
     *    кольцо трека. Пакет сохранён, переходной задержки нет;
     *  - `now` за F0 → пакет устарел, пересборка потока.
     *
     * Граница ветвей именно F0, а не «сколько реально можно пропустить»:
     * сброс кольца делает пропускаемой ЛЮБУЮ величину вплоть до фронтира,
     * потому что целевой кадр отсчитывается от слышимой позиции A0, а не от
     * курсора записи.
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
                // Переходной задержки НЕТ: перемотка ставит курсор на абсолютный
                // кадр T = A0 + Δ·rate и СБРАСЫВАЕТ кольцо трека (flush), иначе
                // первые R секунд звучал бы PCM, записанный до паузы. Пакет при
                // этом сохранён целиком — сбрасываются только копии, уже
                // отданные треку.
                append("transientLag=0s (кольцо сброшено flush, пакет сохранён)\n")
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
    private fun baseTimeOfDaySeconds(): Float {
        if (!debugVirtualTime) return realTimeOfDaySeconds()
        val virtual = current?.virtualTimeOfDaySeconds() ?: 0f
        return if (virtual > 0f) virtual else realTimeOfDaySeconds()
    }

    /**
     * Тот же момент, но СО СДВИГОМ СКРАБА: на сдвинутой оси «сейчас» для
     * слушателя — сдвинутое время, а не реальное. Решатель возобновления
     * сравнивает с A0/F0 именно его: иначе он решил бы, что замороженный
     * пакет «успел устареть» на величину сдвига, и зря пересобрал бы поток.
     */
    private fun targetTimeOfDaySeconds(): Float =
        normalizeTimeOfDay(baseTimeOfDaySeconds() + scrubOffsetSec)

    /**
     * Мягкое возобновление: тот же поток и тот же пакет, но с перемоткой на
     * позицию, соответствующую текущему моменту суток.
     *
     * Пакет НЕ перегенерируется, фазы не сбрасываются. Меняется только точка
     * чтения: писатель встаёт на кадр `T = A0 + Δ·rate` — вперёд или назад,
     * пакет в памяти цел — и СБРАСЫВАЕТ кольцо трека, где лежат копии PCM,
     * записанные до паузы. Без сброса кольца первые R секунд звучала бы
     * СТАРАЯ позиция (кольцо принадлежит треку, а не пакету, и пропуском
     * кадров пакета его не убрать) — ровно это и выглядело как «продолжение
     * с той же позиции».
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
                resumeElapsedMs = accumulatedMs,
                // Якорь и фазы сбрасываются ЯВНО, а не «по умолчанию из base».
                //
                // СУТЬ ПРИЛОЖЕНИЯ: пересборка играет ритм для ТЕКУЩЕГО момента
                // суток. Подставлять сюда pausedTimeOfDay (как делал предыдущий
                // фикс) — значит превратить паузу в «перемотку назад»: после
                // десятиминутной паузы звук продолжал бы с десятиминутной
                // давности точки кривой. prepare() при [CurveAnchor.NONE]
                // якорит кривую на now явно.
                //
                // Явный сброс обязателен потому, что [base] берётся из очереди
                // или из [sessionSpec] и теоретически может нести внешний якорь:
                // унаследовав его, «свежий старт» молча приземлился бы на
                // старую точку кривой — ровно то же «продолжение с той же
                // позиции», только уже на пути пересборки.
                resumeAnchor = CurveAnchor.NONE
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
        // СКРАБ: снимок оси замороженного пакета. PAUSED держит ЖИВОЙ поток со
        // СТАРОЙ осью (его spec.scrubOffsetSec), поэтому общий [scrubOffsetSec]
        // к замороженному пакету неприменим: `pausedTimeOfDay` снят на старой
        // оси, и решать возобновление надо относительно НЕЁ. Это каноническое
        // место снимка — на ВХОДЕ в паузу; в applyScrub поле пишется лишь на
        // тот случай, что сдвиг меняется уже внутри паузы (там старая ось
        // берётся из scrubOffsetSec, ещё не перезаписанного).
        pausedScrubOffsetSec = current?.spec?.scrubOffsetSec ?: scrubOffsetSec
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

    /**
     * Переснять окно актуальности замороженного пакета ПОСЛЕ [BinauralStreamImpl.retune].
     *
     * Перенастройка на паузе выбрасывает старый PCM и генерирует новый пакет от
     * «сейчас», поэтому прежние A0/F0 описывают уже несуществующее аудио: без
     * пересъёма решение на возобновлении (SOFT против REBUILD) опиралось бы на
     * них ошибочно. Состав полей тот же, что в [capturePauseMetrics].
     */
    private fun recapturePausedWindow(s: BinauralStreamImpl) {
        if (state != ManagerState.PAUSED || current !== s) return
        val audible = s.audibleCurveSeconds()
        if (audible == null) {
            // Позиция нечитаема — пересборка надёжнее, чем пропуск по нулям.
            pausedSpecDirty = true
            return
        }
        pausedAudibleSeconds = audible
        pausedTimeOfDay = s.getAudibleTimeOfDaySeconds()
        pausedFrontierSeconds = s.frontierCurveSeconds()
        pausedScrubOffsetSec = s.spec.scrubOffsetSec
        StreamLogger.d(TAG, "recapturePausedWindow: A0=$audible F0=$pausedFrontierSeconds " +
            "(сдвиг=${pausedScrubOffsetSec} с) — пакет перестроен перенастройкой")
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

    /**
     * Занять слот current и запустить поток.
     *
     * @return true — трек стартовал; false — старт не удался, поток утилизирован.
     *
     * ВАЖНО: при неудаче слот current зануляется и автомат уходит в IDLE.
     * Если до вызова в слоте был ЖИВОЙ поток, вызывающий обязан вернуть его
     * на место — иначе старый поток останется без владельца и будет звучать
     * вечно.
     *
     * Форма рампы входа всегда LINEAR: перекрытия нет ни при каких условиях,
     * equal-power нечего делить (см. KDoc [FadeShape.EQUAL_POWER]).
     */
    private fun launchStream(stream: BinauralStreamImpl): Boolean {
        StreamLogger.d(TAG, "launchStream spec#${stream.spec.serial} sr=${stream.spec.sampleRate} beat=${stream.spec.config.frequencyCurve.getBeatFrequencyAt(kotlinx.datetime.LocalTime(0, 0))}")
        // СКРАБ: с этого мгновения ось звука — это ось спеки потока. Возврат на
        // реальное «сейчас» нужен ровно тогда, когда эта ось ещё сдвинута.
        // Флаг снимает зависимость от [scrubOffsetSec]: тихий сброс стирает
        // заданный сдвиг раньше, чем звук реально вернётся.
        scrubNeedsRealignment = stream.spec.scrubOffsetSec != 0
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
            return false
        }
        // Грейс сторожу: стартовый пакет и разгон кольца дают легальное
        // расхождение слышимой позиции с «сейчас».
        watchdogGraceUntilMs = System.currentTimeMillis() + WATCHDOG_GRACE_MS
        watchdogBreachSinceMs = 0L
        StreamLogger.d(TAG, "launchStream: start spec#${stream.spec.serial} успешно, fade-in идёт")
        return true
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
                clearScrubState()
                resetSession()
                setState(ManagerState.IDLE)
                updateWakeLock()
            }
        )
    }

    // ============================================================ Сторож инварианта

    /**
     * Сторож инварианта «слышимая позиция кривой == сейчас».
     *
     * Generic-проверка на ВЕСЬ класс ошибок «звук уехал от настенных часов», а
     * не только на тот, с которого начался разбор протухшего кэша. Любая
     * будущая правка якорения, паузы или кроссфейда либо держит |Δ| в пределах
     * допуска, либо попадает в этот лог.
     *
     * Почему выдерживается [WATCHDOG_SUSTAIN_MS], а не срабатывает сразу:
     * легальные переходные процессы (стартовый пакет 2 с, доигрывание кольца
     * трека, fade-in) дают кратковременное расхождение. Устойчивое
     * расхождение — это уже нарушение сути приложения.
     *
     * Только debug: в release [BuildConfig.DEBUG] = false и сторож не тикает.
     */
    private val invariantWatchdog = object : Runnable {
        override fun run() {
            if (!BuildConfig.DEBUG) return
            watchdogBreachSinceMs = checkInvariant(watchdogBreachSinceMs)
            if (isActiveState()) actor.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    /** Возвращает новое значение «нарушение длится с» (0 = нарушения нет). */
    private fun checkInvariant(breachSinceMs: Long): Long {
        if (!isActiveState()) return 0L
        if (System.currentTimeMillis() < watchdogGraceUntilMs) return 0L
        val s = currentRef.get()
        if (s == null || !s.hasLiveEngine()) return 0L
        // Некомпенсированная позиция: «что реально в динамике», без поправки
        // на перемотку. Компенсированная [BinauralStreamImpl.audibleCurveSeconds]
        // скрыла бы настоящее отставание звука.
        val raw = s.audibleCurveSecondsRaw() ?: return 0L
        // ЦЕЛЬ — ось СО СДВИГОМ СКРАБА. Иначе легальный предпросмотр другого
        // времени суток выглядел бы как нарушение сути приложения: Δ равнялась
        // бы величине сдвига, и сторож перестал бы отличать «звук уехал» от
        // «оператор слушает другое время».
        val now = normalizeTimeOfDay(realTimeOfDaySeconds() + scrubOffsetSec)
        val delta = CurveAnchorRules.circularDistance(raw, now)
        if (delta <= WATCHDOG_TOL_SEC) {
            if (breachSinceMs != 0L) {
                StreamLogger.d(TAG, "INVARIANT: расхождение закрылось за " +
                    "${System.currentTimeMillis() - breachSinceMs}мс (Δ=${"%.2f".format(delta)}с)")
            }
            return 0L
        }
        val since = if (breachSinceMs != 0L) breachSinceMs else System.currentTimeMillis()
        val held = System.currentTimeMillis() - since
        if (held >= WATCHDOG_SUSTAIN_MS) {
            StreamLogger.e(TAG, "INVARIANT НАРУШЕН: слышимая позиция ${"%.1f".format(raw)} " +
                "отличается от now=${"%.1f".format(now)} на ${"%.2f".format(delta)}с " +
                "уже ${held}мс (порог ${WATCHDOG_TOL_SEC}с / ${WATCHDOG_SUSTAIN_MS}мс); " +
                "state=$state spec#${s.spec.serial} reason=${s.spec.reason} " +
                "anchor=${s.spec.resumeAnchor} frontier=${"%.1f".format(s.frontierCurveSeconds())} " +
                "scrub=${s.spec.scrubOffsetSec}/${scrubOffsetSec}")
        }
        return since
    }

    private fun startInvariantWatchdog() {
        if (!BuildConfig.DEBUG) return
        actor.removeCallbacks(invariantWatchdog)
        watchdogBreachSinceMs = 0L
        actor.postDelayed(invariantWatchdog, WATCHDOG_PERIOD_MS)
    }

    private fun stopInvariantWatchdog() {
        actor.removeCallbacks(invariantWatchdog)
        watchdogBreachSinceMs = 0L
        watchdogGraceUntilMs = 0L
    }

    /** Диагностическая проверка по требованию (debug-CLI `invcheck`). */
    fun checkInvariantNow(): String {
        val s = currentRef.get()
        if (s == null) return "нет активного потока (state=$state)"
        val raw = s.audibleCurveSecondsRaw()
        val base = realTimeOfDaySeconds()
        val now = normalizeTimeOfDay(base + scrubOffsetSec)
        val delta = raw?.let { CurveAnchorRules.circularDistance(it, now) }
        return "state=$state spec#${s.spec.serial} reason=${s.spec.reason} " +
            "anchor=${s.spec.resumeAnchor} now=${"%.2f".format(now)} " +
            "realtime=${"%.2f".format(base)} scrub=${s.spec.scrubOffsetSec}/${scrubOffsetSec} " +
            "audibleraw=${raw?.let { "%.2f".format(it) } ?: "н/д"} " +
            "Δ=${delta?.let { "%.2f".format(it) } ?: "н/д"}с " +
            "порог=${WATCHDOG_TOL_SEC}с/${WATCHDOG_SUSTAIN_MS}мс " +
            "нарушение=${if (watchdogBreachSinceMs != 0L) "да (${System.currentTimeMillis() - watchdogBreachSinceMs}мс)" else "нет"}"
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
            startInvariantWatchdog()
        } else {
            releaseWakeLock()
            // Сторож осознанно гасится и на пути в паузу: замороженный пакет
            // УЖЕ отстаёт от настенных часов, и это штатное состояние паузы,
            // а не нарушение. Проверка возобновляется при возобновлении.
            stopInvariantWatchdog()
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
        // СКРАБ: потока не стало — возвращать на реальную ось больше нечего.
        scrubNeedsRealignment = false
        resumeInFlight = false
        lastResumeAccuracy = null
    }
}
