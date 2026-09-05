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
import com.binaural.core.audio.model.FrequencyMath
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime
import java.util.Locale
import kotlin.math.abs
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

        /**
         * Сколько максимум уходящий поток кроссфейда вправе BLOCKировать
         * следующие смены пресета.
         *
         * Штатный релиз — это [BinauralStreamImpl] снимает трек (pause()
         * прерывает заблокированный write()), писатель выходит на следующем
         * витке, движок уничтожается: единицы — десятки миллисекунд. Плохой
         * случай — писатель застрял в generate/write, и [finalizeStop] опрашивает
         * латч до `WRITE_CHUNK_MS + 2 с` (≈ 10 с). Всё это время [outgoing] не
         * `null`, а значит [tryAdvanceQueue] не поднимает NEXT: пользователь жал
         «сменить пресет», а звук не меняется десять секунд.
         *
         * Поэтому по истечении срока уходящий снимается принудительно.
         * Безопасно: его рампа давно на нуле, база трека обнулена в
         * finalizeStop, то есть в эфире от него нет ничего.
         */
        private const val OUTGOING_RELEASE_TIMEOUT_MS = 1_200L
        /** Как часто проверяем застрявший релиз уходящего потока. */
        private const val OUTGOING_REAPER_PERIOD_MS = 200L

        /**
         * ЖЁСТКИЙ предел сторожа уходящего потока, мс.
         *
         * [OUTGOING_RELEASE_TIMEOUT_MS] — это срок, после которого релиз считается
         * застрявшим. Но снимать поток, чья рампа ещё НЕ дошла до нуля, — значит
         * рубить амплитуду, то есть дать щелчок (docs/analysis_scrub_storm_click_risk.md
         * R7). Поэтому при «релиз застрял, но рампа не в нуле» сторож ждёт до
         * этого второго предела — и только потом снимает принудительно в любом
         * состоянии. Предел обязан остаться конечным: трек и пакет обязаны
         * вернуться в кучу, иначе кроссфейды встанут навсегда.
         */
        private const val OUTGOING_REAPER_HARD_TIMEOUT_MS = 2_500L

        /**
         * ШТОРМ: окно успокоения после последнего жеста, мс — БАЗОВОЕ.
         *
         * Распространяется на ВСЕ причины ([SpecReason]): серия жестов
         * осмысленна у любой из них, а промежуточные состояния не имеют
         * ценности (слушать нужно итог, а не каждый шаг). Для SCRUB это серия
         * коротких фликов «чик-чик-чик» ([FrequencyGraph] вызывает `onCommit`
         * только из `onDragEnd`), для PRESET_SWITCH — «листание» пресета
         * кнопкой next/prev, для SETTINGS — «прокрутка» ползунка, которая
         * тоже стреляет десятками жестов.
         *
         * Каждый такой жест — ПОЛНЫЙ хэндофф: новый `AudioTrack`, новый
         * нативный движок, пересборка таблиц кривой, [TRANSITION_FADE_MS]
         * перекрытия. Очередь из одного слота уже не даст материализоваться
         * больше двух потокам, но она НЕ схлопывает цепочку: каждый жест,
         * пришедший во время перехода, разыгрывается сразу после релиза
         * уходящего. Поэтому спека ждёт, пока поток жестов стихнет. Окно
         * отсчитывается от ПОСЛЕДНЕГО жеста.
         *
         * Цена — причина применяется на это время позже. 150 мс меньше одного
         * перехода ([TRANSITION_FADE_MS]) и не воспринимается как задержка.
         *
         * docs/analysis_scrub_storm_click_risk.md (R2, §4.2, §4.3)
         */
        private const val HANDOFF_STORM_SETTLE_MS = 150L

        /**
         * ШТОРМ: окно успокоения, когда серия УЖЕ ОПОЗНАНА, мс.
         *
         * docs/analysis_scrub_storm_click_risk.md (§4.3) — закрытие открытого
         * вопроса третьей волны.
         *
         * Базовая величина ([HANDOFF_STORM_SETTLE_MS], 150 мс) схлопывает
         * серию лишь наполовину: 10 жестов с реальным интервалом ~250 мс
         * давали 5–6 переходов. Причина арифметическая — после релиза
         * уходящего код ждёт остаток окна, и жест с интервалом `S` успевает
         * продлить ожидание только если `S < W`:
         *
         * ```
         * жест в ожидании ⟺ lastGesture + S < lastGesture + W ⟺ S < W
         * ```
         *
         * При `W = 150` и `S = 250` каждый второй жест проскакивает в
         * переход — отсюда «5 переходов на 10 жестов».
         *
         * Подымать базовое окно до 300 мс нельзя: это штраф ОДИНОЧНОМУ жесту
         * (скраб отзывался бы на 300 мс позже), а одиночный жест — главный
         * сценарий. Поэтому окно ДВУХУРОВНЕВОЕ: как только обнаружено, что
         * жест не первый (предыдущий ещё не разыгран либо переход в полёте),
         * ожидание продлевается до этого значения. При `S = 250` серия любой
         * длины схлопывается в 1–2 перехода, а одиночный жест по-прежнему
         * платит только [HANDOFF_STORM_SETTLE_MS].
         *
         * Значение выбрано с запасом к измеренному интервалу доставки жестов
         * (~250 мс в `tools/dbgstorm.sh`, где каждый жест — это `am broadcast`)
         * и к верхней границе человеческого «чик-чик-чик» (~150 мс).
         */
        private const val HANDOFF_STORM_EXTEND_MS = 300L

        /**
         * ЕДИНАЯ длительность перехода, мс.
         *
         * docs/analysis_scrub_storm_click_risk.md (§4.3).
         *
         * Слышимая длина перехода обязана быть одной и той же для смены
         * пресета, правки настройки, смены частоты дискретизации и скраба:
         * иначе один и тот же жест пользователя звучит по-разному в
         * зависимости от причины, и это читается как «иногда щёлкает».
         * Равно [BinauralStreamImpl.DEFAULT_FADE_MS]: перекрытие — 250 мс
         * сразу, а «приседание» без перекрытия — два плеча по
         * [ZERO_OVERLAP_LEG_MS] = 125 мс, в сумме те же 250 мс.
         *
         * Отличается только ХАРАКТЕР (смешение против приседания), и это
         * различие физически неустранимо: когерентные тоны нельзя смешивать
         * вовсе (§4.1).
         */
        private const val TRANSITION_FADE_MS = 250L

        /**
         * ШТОРМ: пауза перед повторной попыткой поднять NEXT, мс.
         *
         * `prepare()` чаще всего не удаётся из-за кучи клиента AudioFlinger
         * (`createTrack_l -12`): второй трек не влезает, пока предыдущий ещё
         * не отпущен. Она освобождается почти сразу, поэтому имеет смысл
         * попробовать снова, а не платить разрывом.
         *
         * Повтор — для ВСЕХ причин, не только скраба: разрыв 100–200 мс
         * ([beginHandoffSequential]) не нужен ни смене пресета, ни правке
         * настройки, если через 400 мс `prepare()` почти наверняка пройдёт.
         */
        private const val HANDOFF_RETRY_DELAY_MS = 400L

        /**
         * ШТОРМ: сколько раз переоткладывать неудавшийся хэндофф.
         *
         * Без предела вечный отказ `prepare()` дал бы бесконечный цикл попыток.
         * После лимита спека всё-таки идёт последовательным путём: настройка
         * обязана примениться, пусть и с разрывом. Счётчик сбрасывается КАЖДЫМ
         * новым жестом — у пользователя всегда свежий бюджет.
         */
        private const val HANDOFF_RETRY_MAX = 3

        /**
         * ПОРОГ КОГЕРЕНТНОСТИ, Гц: расстройка каналов CURRENT и NEXT, при
         * которой переход обязан идти с НУЛЕВЫМ ПЕРЕКРЫТИЕМ.
         *
         * docs/analysis_scrub_storm_click_risk.md (§4.1, §4.2).
         *
         * Если тоны различаются меньше чем на этот порог, они КОГЕРЕНТНЫ: за
         * время перекрытия ([TRANSITION_FADE_MS] = 250 мс) разность фаз уходит не
         * больше чем на `Δf · T = 4 · 0.25 = 1` цикл, то есть фазы успевают
         * разойтись, но не успевают УСРЕДНИТЬСЯ. Складываются не мощности, а
         * амплитуды: сумма равна √2·|cos(φ/2)|, где φ — разность фаз в момент
         * стыка, а она СЛУЧАЙНА — [BinauralStream.getPhases] читает фазу
         * ФРОНТИРА генерации, обгоняющего слышимое на глубину кольца
         * AudioTrack (2 МиБ = 5.46 с @48 кГц ⇒ 400–2200 циклов несущей).
         * Итог: от ПОЛНОЙ взаимной компенсации до +3 дБ. Никакая форма рампы
         * этого не лечит: при когерентности провал дают и линейная, и
         * равносильная кривые.
         *
         * Поэтому когерентный хэндофф не кроссфейдится: NEXT поднимается
         * только когда CURRENT реально ушёл в ноль (хук тишины).
         *
         * 4 Гц выбраны как «один цикл биений на перекрытие». Ниже порога
         * лежат самые частые случаи: скраб (Δf ≈ 0.3 Гц), правка настройки
         * (Δf = 0 — кривая та же!), смена пресета на пресет с той же
         * несущей. Выше — смена пресета на другой звук, там перекрытие
         * честно работает по мощности и звучит мягче приседания.
         */
        private const val COHERENT_DETUNE_HZ = 4.0f

        /**
         * Длительность одного плеча «приседания» при нулевом перекрытии, мс.
         *
         * Стык приходится на нулевую амплитуду: интерференции нет в принципе,
         * а ступеньки нет по построению. Платим «приседанием» из двух плеч
         * по 125 мс — в сумме те же [TRANSITION_FADE_MS], что и у перехода
         * с перекрытием: слышимая длина перехода одна для всех маршрутов
         * (§4.3). Провал плавный, а не обрыв, поэтому паузой не читается.
         *
         * Раньше было 120 мс на плечо (240 в сумме) — величина, ничем не
         * связанная с длительностью кроссфейда.
         *
         * docs/analysis_scrub_storm_click_risk.md (§4.1, §4.2, §4.3)
         */
        private const val ZERO_OVERLAP_LEG_MS = TRANSITION_FADE_MS / 2
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
     * Звучащий поток. Второй слот — [outgoing] — существует только на
     * длительность кроссфейда.
     *
     * ПРЕЖНИЙ ИНВАРИАНТ («загружен всегда ровно один поток») был строже, но
     * стоил звуку разрыва: новый поток создавался лишь ПОСЛЕ полной утилизации
     * старого ([onStreamFullyStopped]), а между «утих» и «зазвучал» лежали
     * стража шейпера + выход писателя + `prepare()` — ≈100–200 мс тишины на
     * каждой смене пресета. Взамен он давал: отсутствие осиротевших треков,
     * отсутствие «зомби», свёртку шторма смен в один поток и гарантию, что
     * двух больших пакетов в куче не бывает.
     *
     * НОВЫЙ ИНВАРИАНТ (кроссфейд с опережением, docs/plan_crossfade_lead.md):
     * ослаблен ровно на длительность перекрытия и сохраняет всё ценное:
     *  - треков по-прежнему не больше ДВУХ (кольцо 2 МБ × 2 влезает в кучу
     *    клиента AudioFlinger — проверено на устройстве; при 3 МБ второй трек
     *    не создавался вовсе, `createTrack_l -12`); третьего не бывает:
     *    [tryAdvanceQueue] отказывается поднимать NEXT, пока [outgoing] не пуст;
     *  - очередь по-прежнему один слот latest-wins: шторм A→B→C→D
     *    материализует два потока, а не четыре;
     *  - NEXT живёт на СТАРТОВОМ пакете (750 КБ) — доращивание запрещено до
     *    релиза [outgoing], поэтому двух больших буферов не существует;
     *  - «зомби» невозможен: ссылок ровно две, [current] и [outgoing], обе под
     *    контролем актёра.
     *
     * Что получено: разрыв звука исчез полностью (NEXT стартует ДО фейд-аута),
     * а весь путь утилизации старого потока — пауза трека, опрос писателя до
     * 10 с, релиз движка — ушёл ПОД звучащий новый поток и больше не лежит на
     * критическом пути звука вообще.
     */
    private var current: BinauralStreamImpl? = null

    /**
     * УХОДЯЩИЙ поток кроссфейда: уже затухает (или затух) и ждёт утилизации.
     *
     * Не `null` только во время кроссфейда (норма — ≤ 300 мс). Это НЕ второй
     * равноправный поток: он не получает ни команд, ни громкости, ни пауз —
     * единственное, что с ним делают, это ждут [onOutgoingReleased].
     *
     * Пока он не `null`, [tryAdvanceQueue] не поднимает следующий NEXT — тем
     * самым держится жёсткий предел «не больше двух AudioTrack». Если release
     * затягивается сверх [OUTGOING_RELEASE_TIMEOUT_MS] (писатель застрял в
     * write()), поток снимается принудительно: к тому моменту он давным-давно
     * в нуле, и услышать его невозможно.
     */
    private var outgoing: BinauralStreamImpl? = null

    /**
     * Отложенное действие, которое обязано исполниться, когда [outgoing]
     * освободится. Один слот: доживает последнее (например, пересборка потока
     * на возобновлении, заказанная во время кроссфейда).
     */
    private var pendingAfterOutgoing: (() -> Unit)? = null

    /** Wall-момент начала кроссфейда — для детекта застрявшего релиза. */
    private var outgoingStartWallMs = 0L

    /**
     * ШТОРМ: момент, до которого спеку разыгрывать рано — для ЛЮБОЙ причины.
     * Переставляется КАЖДЫМ жестом, то есть серия любой длины стоит один-два
     * перехода. См. [HANDOFF_STORM_SETTLE_MS], [HANDOFF_STORM_EXTEND_MS] и
     * docs/analysis_scrub_storm_click_risk.md (R2, §4.2, §4.3).
     */
    private var settleAtMs = 0L
    /** ШТОРМ: отложенный перебор очереди уже заказан (не постим по сто раз). */
    private var settleScheduled = false

    /**
     * ШТОРМ: серия ОПОЗНАНА — как минимум один жест догнал предыдущий,
     * который ещё не успел разыграться.
     *
     * Признак ставится в [requestHandoff]: предыдущий жест ещё ждёт своего
     * окна ([settleAtMs] в будущем) либо переход уже в полёте ([outgoing] или
     * [pendingSilentSwitch] непусты). Снимается в [tryAdvanceQueue] ровно в
     * тот момент, когда спеку наконец разыгрывают: если следующий жест
     * одиночный, он снова платит только базовое окно.
     *
     * Смысл: не штрафовать одиночный жест окном, достаточным для схлопывания
     * серии. См. [HANDOFF_STORM_EXTEND_MS].
     */
    private var stormDetected = false

    private val settleRunnable = Runnable {
        settleScheduled = false
        if (isActiveState()) tryAdvanceQueue()
    }

    /** ШТОРМ: повторная попытка после неудачного `prepare()` (все причины). */
    private var handoffRetryScheduled = false
    private var handoffRetryAttempts = 0

    private val handoffRetryRunnable = Runnable {
        handoffRetryScheduled = false
        if (isActiveState()) tryAdvanceQueue()
    }

    private fun scheduleHandoffRetry() {
        if (handoffRetryScheduled) return
        handoffRetryScheduled = true
        actor.postDelayed(handoffRetryRunnable, HANDOFF_RETRY_DELAY_MS)
    }

    /**
     * NEXT, подготовленный и ждущий старта, — вторая половина перехода
     * с НУЛЕВЫМ ПЕРЕКРЫТИЕМ (см. [ZERO_OVERLAP_LEG_MS]).
     *
     * Поток уже прошёл `prepare()`: трек создан, первый пакет сгенерирован на
     * НОВОЙ оси, но `start()` ещё не звался — звука от него нет вовсе. Слот
     * [current] при этом продолжает занимать уходящий поток, поэтому пауза,
     * стоп и UI-геттеры работают со звучащим звуком, а не с тишиной.
     * Старт происходит из хука тишины: см. [startPendingSilentSwitch].
     *
     * Непустое значение == переход в полёте, и [tryAdvanceQueue] обязан его
     * учитывать: второй NEXT поднимать нельзя ни по памяти (два трека), ни по
     * смыслу (подъём раньше нуля вернул бы интерференцию).
     */
    private var pendingSilentSwitch: BinauralStreamImpl? = null

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

    // Непрерывность при сквозном переключении сегментов.
    //
    // Наследуется ТОЛЬКO то, что нельзя вычислить заново: фазы несущих
    // (иначе NEXT стартует с фазы 0 и интерферирует с уходящим — провал
    // огибающей) и часы сессии ( elapsed ).
    //
    // ПОЗИЦИЯ КРИВОЙ НЕ НАСЛЕДУЕТСЯ, и это осознанно. Раньше следующий поток
    // якорился на замороженную позицию старого, снятую в beginHandoff; за
    // фейд-аут и релиз (0.3–1 с) она успевала отстать от настенных часов, и
    // лаг КОПИЛСЯ от правки к правке (в логе шторма — минус 1 с за 7
    // хендоверов). А когда захват попадал в окно FADE_IN, он читал протухший
    // нативный кэш = 0, и цепочка 0 → 0 → 0 защёлкивалась навсегда.
    //
    // Теперь новый поток встаёт на «сейчас» в prepare() — единственной точке
    // якорения. Скачок позиции кривой при этом не больше длительности
    // хэндоффа (< 1 с кривой): для гладкой кривой неразличимо, зато инвариант
    // «звук == сейчас» выполняется точно и без накопления лага.
    // См. docs/handoff_anchor_zero_analysis_plan.md, P1.4.
    private var switchElapsedMs: Long = 0L
    private var switchLeftPhase: Float? = null
    private var switchRightPhase: Float? = null
    // Состояние сторожа инварианта (только debug-сборка).
    private var watchdogBreachSinceMs = 0L
    private var watchdogGraceUntilMs = 0L

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
        if (scrubOffsetSec != 0 || pausedScrubOffsetSec != 0) {
            StreamLogger.d(TAG, "resetScrub: сдвиг ${scrubOffsetSec} с снят (state=$state)")
        }
        clearScrubState()
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
            // Уходящий поток кроссфейда: дожидаться его рампы негде — нить
            // актёра сейчас будет остановлена, и отложенный колбэк релиза не
            // исполнился бы вовсе, оставив трек и пакет в куче навсегда.
            actor.removeCallbacks(outgoingReaper)
            // ШТОРМ: отложенный перебор очереди тоже снимаем — нить
            // актёра останавливается, отложенный колбэк не исполнился бы.
            actor.removeCallbacks(settleRunnable)
            actor.removeCallbacks(handoffRetryRunnable)
            settleScheduled = false
            handoffRetryScheduled = false
            handoffRetryAttempts = 0
            settleAtMs = 0L
            stormDetected = false
            pendingAfterOutgoing = null
            // СКРАБ с нулевым перекрытием: NEXT уже подготовлен, но ещё не
            // стартовал и слот [current] не занимает — владельца у него нет.
            // Нить актёра сейчас остановится, хук тишины не исполнится
            // никогда, поэтому утилизуем явно: иначе созданный трек и пакет
            // остались бы в куче навсегда.
            pendingSilentSwitch?.abort()
            pendingSilentSwitch = null
            outgoing?.releaseNow()
            outgoing = null
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

    private fun isActiveState() =
        state == ManagerState.RUNNING || state == ManagerState.FADE_IN || state == ManagerState.HANDOFF

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
                // спеке. Настройки применяются только при возобновлении, поэтому
                // помечаем замороженный поток «грязным» — иначе мягкое
                // возобновление молча проигнорировало бы новую спеку.
                val spec = buildSpec(reason)
                sessionSpec = spec
                val cur = current
                if (cur == null || needsHandoff(cur, spec)) pausedSpecDirty = true
            }
            ManagerState.FADE_OUT_PAUSE, ManagerState.FADE_OUT_STOP -> queue.offer(buildSpec(reason))
            else -> requestHandoff(buildSpec(reason))
        }
    }

    private fun requestHandoff(spec: PlaybackSpec) {
        // Быстрый путь: изменилась только громкость — потоки не пересоздаём.
        val cur = current
        if (cur != null && !needsHandoff(cur, spec) &&
            (state == ManagerState.RUNNING || state == ManagerState.FADE_IN || state == ManagerState.HANDOFF)
        ) {
            cur.setVolume(spec.volume); return
        }
        // Коалесценция: один слот, побеждает новейший. Шторм A→B→C→D
        // материализуется не более чем в два потока (текущий + один NEXT).
        queue.offer(spec)
        // ШТОРМ: окно переставляется КАЖДЫМ жестом, поэтому серия любой длины
        // стоит один-два перехода (см. [HANDOFF_STORM_SETTLE_MS]).
        // Порядок важен: сначала окно, потом попытка разыграть — иначе первый
        // жест серии прошёл бы немедленно, а это как раз тот переход, который
        // тут же перебивается следующим.
        //
        // Окно — для ВСЕХ причин (§4.3): серия жестов осмысленна у любой из
        // них (ползунок настройки стреляет ими так же, как флики скраба),
        // а 150 мс меньше длительности самого перехода ([TRANSITION_FADE_MS]),
        // то есть поверх него и не слышны.
        val now = System.currentTimeMillis()
        // Серия == предыдущий жест ещё не разыгран: его окно не истекло, либо
        // переход уже в полёте и следующий жест ждёт релиза уходящего.
        // Одиночный жест этого не увидит и заплатит только базовое окно.
        if (settleAtMs > now || outgoing != null || pendingSilentSwitch != null) {
            stormDetected = true
        }
        settleAtMs = now + if (stormDetected) HANDOFF_STORM_EXTEND_MS else HANDOFF_STORM_SETTLE_MS
        // Новый жест — свежий бюджет повторов: если прошлый хэндофф выбил
        // лимит и ушёл последовательным путём, следующий снова начнёт с
        // переоткладывания (причина отказа — временная нехватка кучи).
        handoffRetryAttempts = 0
        StreamLogger.d(TAG, "requestHandoff: spec#${spec.serial} в очередь (state=$state, " +
            "reason=${spec.reason}, outgoing=${outgoing?.spec?.serial})")
        tryAdvanceQueue()
    }

    /**
     * Разыграть очередь: поднять NEXT на ту спеку, которая ещё не звучит.
     *
     * Единственная точка принятия решения «создавать поток или нет», поэтому
     * все пути (смена настройки, смена пресета, частоты, догон после релиза
     * уходящего) обязаны приходить сюда, а не вызывать [launchSpec] напрямую.
     *
     * Два условия, при которых очередь НЕ разыгрывается:
     *  - [outgoing] не `null` — идёт кроссфейд, третий трек поднимать нельзя.
     *    Спека дождётся [onOutgoingReleased];
     *  - состояние не активное (IDLE/PAUSED/FADE_OUT_*) — там очередь
     *    разбирают свои обработчики ([onPlay], [onResumeFromPaused],
     *    [onStreamFullyStopped]), у них свои правила якорения.
     */
    private fun tryAdvanceQueue() {
        if (outgoing != null) {
            StreamLogger.d(TAG, "tryAdvanceQueue: кроссфейд идёт (outgoing " +
                "spec#${outgoing?.spec?.serial}) — разыграем после его релиза")
            return
        }
        // Переход с нулевым перекрытием: NEXT подготовлен и ждёт, пока CURRENT
        // уйдёт в ноль. Второй NEXT поднимать нельзя: это уже третий трек
        // (отказ AudioFlinger -12) и, главное, перекрытие — ровно то, чего
        // этот переход избегает. Спека дождётся [onOutgoingReleased].
        if (pendingSilentSwitch != null) {
            StreamLogger.d(TAG, "tryAdvanceQueue: переход без перекрытия в полёте " +
                "(spec#${pendingSilentSwitch?.spec?.serial}) — разыграем после его старта")
            return
        }
        val queued = queue.peek() ?: return
        val cur = current
        if (cur == null) {
            // Гасить нечего — это не кроссфейд, а обычный запуск. Спека из
            // очереди НЕ обогащена непрерывностью, поэтому доработанный якорь
            // надо закрыть здесь, иначе он доживёт до следующей смены.
            val spec = queue.poll() ?: return
            if (pendingHandoff) {
                accumulatedMs = switchElapsedMs + (System.currentTimeMillis() - handoffStartWallMs)
                resetContinuity()
            }
            launchSpec(spec)
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
        // ШТОРМ: каждый жест — полный переход, а в перекрытии звучат два тона.
        // Ждём, пока поток жестов стихнет, и берём последнюю спеку: серия любой
        // длины стоит один-два перехода. Побочная выгода — если серия
        // закончилась там же, откуда началась (магнит «к сейчас» у скраба),
        // [needsHandoff] выше снимет спеку вовсе и звук не шелохнётся.
        //
        // Окно отсчитывается от ПОСЛЕДНЕГО жеста, поэтому проверка здесь
        // работает и на догоне: переход, разыгранный сразу после релиза
        // уходящего, тоже ждёт, если за время перехода пришли новые жесты
        // (§4.3 — «отложенный старт»).
        val wait = settleAtMs - System.currentTimeMillis()
        if (wait > 0) {
            if (!settleScheduled) {
                settleScheduled = true
                actor.postDelayed(settleRunnable, wait)
            }
            StreamLogger.d(TAG, "tryAdvanceQueue: ждём ${wait}мс (${queued.reason}, " +
                "spec#${queued.serial}, сдвиг=${queued.scrubOffsetSec} с, " +
                (if (stormDetected) "серия" else "одиночный жест"))
            return
        }
        // Спеку разыгрываем: серия (если была) стихла. Следующий одиночный
        // жест снова получит базовое окно, а не продлённое.
        stormDetected = false
        beginTransition(queue.poll() ?: return)
    }

    // Дополнительное runtime-поле (продолжение): запрос НА СТАРТ для паттерна
    // «stopWithFade -> play». В отличие от очереди настроек — это именно намерение стартовать.
    private var pendingPlaySpec: PlaybackSpec? = null

    /**
     * ЕДИНЫЙ ПЕРЕХОД: КАК БЫ ни изменилась спека, CURRENT уходит в ноль,
     * и только после полного затухания поднимается NEXT.
     *
     * docs/analysis_scrub_storm_click_risk.md (§4.3, §4.4).
     *
     * Смена пресета, правка настройки, смена частоты дискретизации и скраб
     * идут через ОДИН метод: у пользователя это один и тот же жест
     * «изменилось что-то, зазвучи по-новому», и звучать он обязан одинаково.
     *
     * **Перекрытия нет ни при каких условиях.** Раньше часть переходов
     * кроссфейдилась (решение принимала [logCoherenceVerdict]): считалось,
     * что ДАЛЬНИЕ по частоте тоны складываются по мощности и смешивать их
     * безопасно. Это верно по ЭНЕРГИИ, но не по восприятию: в перекрытии
     * звучат ДВА разных звука одновременно, и при перестановке каналов
     * (правка настройки) уши меняются местами внутри перекрытия — оба тона
     * в обоих ушах, модуляция на |beat| (§4.2-2). Когерентный же случай
     * вообще давал случайный провал до нуля (§4.1). Отказ от перекрытия
     * убирает оба класса дефекта сразу и не зависит от вердикта.
     *
     * Цена — «приседание» вместо смешения: [ZERO_OVERLAP_LEG_MS] вниз,
     * стык на нулевой амплитуде, [ZERO_OVERLAP_LEG_MS] вверх. Ступеньки нет
     * (обе рампы идут до/из нуля), интерференции нечему возникать.
     *
     * @param spec уже изъята из очереди: дожила до своего окна и победила
     *             (очередь из одного слота, новейшая вытесняет прежнюю).
     */
    private fun beginTransition(spec: PlaybackSpec) {
        val old = current
        if (old == null) {
            // Гасить нечего — это не переход, а обычный запуск.
            StreamLogger.d(TAG, "beginTransition: current==null — обычный запуск spec#${spec.serial}")
            launchSpec(spec)
            return
        }
        // Расстройка CURRENT/NEXT больше НИЧЕГО не маршрутизирует: переход
        // один и всегда без перекрытия. Замер остался как диагностика — по
        // Δf в логе видно, чем грозил бы кроссфейд на этом жесте (§4.4).
        logCoherenceVerdict(old, spec)
        beginSilentSwitch(old, spec)
    }

    /**
     * ПЕРЕХОД С НУЛЕВЫМ ПЕРЕКРЫТИЕМ — для КОГЕРЕНТНОГО хэндоффа
     * (решение принимает [isCoherentHandoff]).
     *
     * Почему когерентный переход нельзя кроссфейдить. Когерентность значит,
     * что CURRENT и NEXT играют ПОЧТИ ОДИНАКОВУЮ частоту (скраб: сдвиг на
     * 5 минут при точках кривой в 3 часах друг от друга даёт Δf ≈ 0.3 Гц;
     * правка настройки: Δf = 0 ровно). Такие тоны складываются не мощностями,
     * а амплитудами: сумма равна √2·|cos(φ/2)|, где φ — разность фаз. А φ
     * случайна: фазы наследуются от ФРОНТИРА генерации CURRENT, который
     * обгоняет слышимое на величину кольца AudioTrack (2 МиБ = 5.46 с @48 кГц,
     * чанк 3.46 с), то есть на 400–2200 циклов несущей; дробная часть
     * равномерна. Итог — от полной взаимной компенсации (провал до нуля,
     * примерно каждый шестой скраб) до +3 дБ. Формы рампы это не лечит: при
     * когерентности провал дают и линейная, и равносильная кривые.
     *
     * Решение: NEXT не звучит, пока CURRENT не ушёл в ноль. Перекрытия нет —
     * интерференции нечему возникать, а стык приходится на нулевую амплитуду,
     * поэтому скачка тоже нет. Слышимо как короткое «приседание» длиной
     * 2×[ZERO_OVERLAP_LEG_MS] = [TRANSITION_FADE_MS] — ровно столько же,
     * сколько длится переход с перекрытием, — а не как щелчок и не как пауза.
     *
     * Почему NEXT готовится ЗАРАНЕЕ, а не после релиза (как в
     * [beginHandoffSequential]): между «утих» и «зазвучал» тогда лежат пауза
     * трека, выход писателя и разбор буфера — те самые 100–200 мс разрыва.
     * Здесь трек, движок и первый пакет готовы ЕЩЁ ДО начала ухода, а старт
     * (запись подготовленного пакета + play) занимает единицы миллисекунд.
     *
     * Почему старт из хука тишины, а не из [onOutgoingReleased]: последний
     * приходит уже ПОСЛЕ паузы трека и выхода писателя — то есть с той же
     * задержкой, от которой этот переход и избавляет.
     */
    private fun beginSilentSwitch(old: BinauralStreamImpl, spec: PlaybackSpec) {
        // Координаты непрерывности: часы сессии
        // и фазы несущих читаются ДО fade-out, иначе читать их будет нечего.
        captureContinuity()
        handoffStartWallMs = System.currentTimeMillis()
        val enriched = enrichForContinuity(spec)
        val next = createStream(enriched)

        if (!next.prepare()) {
            // Тот же порядок: CURRENT не тронут, звук
            // идёт дальше, хэндофф переоткладывается.
            next.abort()
            if (handoffRetryAttempts < HANDOFF_RETRY_MAX) {
                handoffRetryAttempts++
                StreamLogger.e(TAG, "beginSilentSwitch: prepare NEXT spec#${enriched.serial} " +
                    "не удался (${spec.reason}, попытка $handoffRetryAttempts/$HANDOFF_RETRY_MAX) — " +
                    "переоткладываем на ${HANDOFF_RETRY_DELAY_MS}мс, CURRENT spec#${old.spec.serial} " +
                    "продолжает играть")
                resetContinuity()
                queue.offer(spec)
                scheduleHandoffRetry()
                return
            }
            StreamLogger.e(TAG, "beginSilentSwitch: prepare NEXT spec#${enriched.serial} не удался — " +
                "переход на последовательный хэндофф (CURRENT spec#${old.spec.serial} не тронут)")
            beginHandoffSequential(spec)
            return
        }

        pendingHandoff = true
        // NEXT обязан НЕ расти, пока уходящий держит свой пакет (тот же мотив,
        // два больших буфера = отказ PacketMemoryBudget).
        next.setPacketGrowthAllowed(false)
        // Владение передаётся СРАЗУ, а не по хуку нуля. Между «уходящий начал
        // гаснуть» и «NEXT зазвучал» лежит [ZERO_OVERLAP_LEG_MS] — на интервале,
        // где актёр принимает и другие сообщения. Оставь мы [current] на
        // уходящем потоке, пауза/стоп по жесту пользователя достались бы ему
        // (а его судьба уже решена: stop() идемпотентен и колбэк не пришёл бы
        // вовсе — автомат залип бы в FADE_OUT_*), а NEXT остался бы без
        // владельца: трек создан, пакет в куче, стартовать некому.
        current = next
        currentRef.set(next)
        sessionSpec = enriched
        pendingSilentSwitch = next
        outgoing = old
        outgoingStartWallMs = System.currentTimeMillis()
        setState(ManagerState.HANDOFF)
        scheduleOutgoingReaper()
        // Причина в строке обязательна: штормовые прогоны считают переходы
        // ПО ПРИЧИНЕ — иначе посторонние SETTINGS-пуши ViewModel (идущие
        // поверх сценария с интервалом ~1 с) раздувают счётчик чужими
        // хэндоффами (грабли волн 3–4, §4.4).
        StreamLogger.d(TAG, "beginSilentSwitch: spec#${old.spec.serial} гаснет " +
            "(${ZERO_OVERLAP_LEG_MS}мс), spec#${enriched.serial} ждёт нуля " +
            "(причина=${spec.reason}, сдвиг=${spec.scrubOffsetSec} с, elapsed=${switchElapsedMs}мс)")

        old.stopWithSilentHook(
            onFullyStopped = { onOutgoingReleased(old) },
            // Форма уже не влияет на интерференцию (перекрытия нет), но
            // cos-уход согласован с sin-входом NEXT: набор и спад симметричны.
            shape = FadeShape.EQUAL_POWER,
            fadeOutMsOverride = ZERO_OVERLAP_LEG_MS,
            onSilent = { startPendingSilentSwitch() }
        )
    }

    /**
     * Вторая половина [beginSilentSwitch]: CURRENT ушёл в ноль — поднимаем NEXT.
     *
     * Исполняется на нити актёра из хука тишины, то есть ВНУТРИ завершения
     * рампы уходящего потока, до его паузы и утилизации. Стык выходит на
     * нулевой амплитуде, а всё тяжёлое (трек, движок, пакет) готово заранее.
     */
    private fun startPendingSilentSwitch() {
        val next = pendingSilentSwitch ?: return
        pendingSilentSwitch = null
        if (next !== current) {
            // Пока ждали нуля, поток успели снять: пауза/стоп по жесту
            // пользователя утилизировали NEXT вместе со слотом [current]
            // (stop() на нестартовавшем потоке — это abort). Стартовать нечего.
            StreamLogger.w(TAG, "startPendingSilentSwitch: spec#${next.spec.serial} уже не CURRENT " +
                "— старт отменён (поток снят во время приседания)")
            return
        }
        accumulatedMs = switchElapsedMs + (System.currentTimeMillis() - handoffStartWallMs)
        if (!launchStream(next, fadeInShape = FadeShape.EQUAL_POWER, fadeInMsOverride = ZERO_OVERLAP_LEG_MS)) {
            // Откат владения НЕ нужен: [launchStream] уже занулил слот, а
            // уходящий поток в этот момент гасится и скоро освободится.
            // Намерение кладём обратно в очередь — [onOutgoingReleased]
            // поднимет поток заново.
            StreamLogger.e(TAG, "startPendingSilentSwitch: start NEXT spec#${next.spec.serial} " +
                "не удался — намерение возвращено в очередь")
            next.abort()
            pendingHandoff = false
            resetContinuity()
            queue.offer(next.spec)
            return
        }
        // Переход состоялся — бюджет повторов больше не нужен.
        handoffRetryAttempts = 0
        StreamLogger.d(TAG, "startPendingSilentSwitch: SWAP по нулю — spec#${next.spec.serial} " +
            "поднимается (${ZERO_OVERLAP_LEG_MS}мс, причина=${next.spec.reason}), перекрытия не было")
    }

    /**
     * ПОСЛЕДОВАТЕЛЬНЫЙ хэндофф — аварийный путь, сохранённый из прежней схемы.
     *
     * Отличается от [beginSilentSwitch] одним: NEXT создаётся ТОЛЬКО после полного
     * релиза CURRENT, поэтому между «утих» и «зазвучал» лежит разрыв ~100–200 мс.
     * Живёт ровно один поток, поэтому требования к памяти минимальны — это и
     * делает его надёжной запасной ветвью, когда кроссфейд не влез.
     */
    private fun beginHandoffSequential(spec: PlaybackSpec) {
        val enriched = enrichForContinuity(spec)
        pendingHandoff = true
        handoffStartWallMs = System.currentTimeMillis()
        queue.offer(enriched)
        StreamLogger.d(TAG, "beginHandoffSequential spec#${enriched.serial}: фейд-аут CURRENT, " +
            "загрузка после полного релиза (разрыв звука ожидаем)")
        fadeOutCurrent(FadeTarget.SWITCH)
    }

    /**
     * Уходящий поток полностью утилизирован: трек снят, движок уничтожен,
     * пакет отдан куче. Кроссфейд закрыт.
     */
    private fun onOutgoingReleased(s: BinauralStreamImpl) {
        if (s !== outgoing) {
            StreamLogger.d(TAG, "onOutgoingReleased: orphan spec#${s.spec.serial} — игнор")
            return
        }
        outgoing = null
        StreamLogger.d(TAG, "onOutgoingReleased: spec#${s.spec.serial} утилизирован за " +
            "${System.currentTimeMillis() - outgoingStartWallMs}мс — " +
            "пакетодержателей=${BinauralStreamImpl.livePacketHolders()}")

        // Память уходящего отдана: NEXT можно доращивать пакет.
        current?.setPacketGrowthAllowed(true)

        // Отложенное действие (пересборка и т.п.) имеет приоритет перед
        // очередью: оно ждало именно этого момента.
        val pending = pendingAfterOutgoing
        pendingAfterOutgoing = null
        if (pending != null) {
            pending.invoke()
            return
        }
        if (isActiveState()) tryAdvanceQueue()
    }

    /**
     * Исполнить [action], когда уходящего потока не останется; если его уже
     * нет — прямо сейчас. Один слот: доживает последнее.
     *
     * Нужно там, где аллокация второго ПОЛНОГО пакета недопустима, пока
     * уходящий держит свой (пересборка на возобновлении —
     * [resumeFromPaused]).
     */
    private fun afterOutgoingReleased(action: () -> Unit) {
        if (outgoing == null) {
            action()
        } else {
            StreamLogger.d(TAG, "afterOutgoingReleased: отложено до релиза " +
                "spec#${outgoing?.spec?.serial}")
            pendingAfterOutgoing = action
        }
    }

    /**
     * Сторож застрявшего релиза уходящего потока.
     *
     * Штатно [onOutgoingReleased] приходит за десятки миллисекунд. Если
     * писатель застрял (длинный `write()`, генерация полного пакета),
     * [finalizeStop] опрашивает латч до ~10 с — и всё это время [outgoing] не
     * `null`, то есть смена пресета BLOCKируется. По истечении срока снимаем
     * принудительно: к этому моменту поток в нуле по громкости, услышать его
     * невозможно, а владение движком [releaseInternal] разрулит сам.
     *
     * ОГОВОРКА ПРО «в нуле» (docs/analysis_scrub_storm_click_risk.md R6/R7).
     * Нуль гарантирован рампой, но не расписанием: если VolumeShaper ЗАЛИП на
     * ненулевом множителе, `releaseNow()` снимает трек на полной амплитуде —
     * это ступенька, то есть настоящий щелчок. Поэтому прежде чем рубить,
     * проверяем [BinauralStream.isFadedToSilent] и, пока есть запас, ждём.
     * Ждать бесконечно нельзя (трек и пакет надо отдать), поэтому второй,
     * более длинный предел [OUTGOING_REAPER_HARD_TIMEOUT_MS] снимает поток
     * в любом состоянии — но уже с честным логом «шаг неизбежен».
     */
    private val outgoingReaper = object : Runnable {
        override fun run() {
            val s = outgoing ?: return     // уже освободился штатно — сторож угасает
            val waited = System.currentTimeMillis() - outgoingStartWallMs
            if (waited < OUTGOING_RELEASE_TIMEOUT_MS) {
                actor.postDelayed(this, OUTGOING_REAPER_PERIOD_MS)
                return
            }
            // Рампа ещё не дошла до нуля — снятие сейчас даст ступеньку. Ждём
            // до жёсткого предела: шейпер почти всегда доезжает.
            if (waited < OUTGOING_REAPER_HARD_TIMEOUT_MS && !s.isFadedToSilent()) {
                StreamLogger.w(TAG, "outgoingReaper: spec#${s.spec.serial} за ${waited}мс ещё " +
                    "не в нуле по рампе — жду, снятие сейчас дало бы щелчок")
                actor.postDelayed(this, OUTGOING_REAPER_PERIOD_MS)
                return
            }
            val stepped = !s.isFadedToSilent()
            StreamLogger.w(TAG, "outgoingReaper: spec#${s.spec.serial} не освободился за " +
                "${waited}мс — принудительный релиз" +
                if (stepped) " (рама НЕ дошла до нуля — шаг неизбежен)" else " (поток уже в нуле, щелчка нет)")
            outgoing = null
            val pending = pendingAfterOutgoing
            pendingAfterOutgoing = null
            current?.setPacketGrowthAllowed(true)
            s.releaseNow()
            if (pending != null) pending.invoke()
            else if (isActiveState()) tryAdvanceQueue()
        }
    }

    private fun scheduleOutgoingReaper() {
        actor.removeCallbacks(outgoingReaper)
        actor.postDelayed(outgoingReaper, OUTGOING_REAPER_PERIOD_MS)
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
        if (s === outgoing) {
            // Уходящий поток кроссфейда: автомат он не двигает — только закрывает
            // перекрытие. (Основной путь передаёт [onOutgoingReleased] прямо в
            // stop(); здесь — страховка на случай иного маршрута утилизации.)
            onOutgoingReleased(s)
            return
        }
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
                // ПОСЛЕДОВАТЕЛЬНЫЙ хэндофф — только аварийная ветвь
                // ([beginHandoffSequential]), когда переход не влез по памяти.
                // Штатная смена пресета сюда больше не приходит: там NEXT
                // готовится ДО fade-out CURRENT (см. [beginSilentSwitch]).
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
            ManagerState.HANDOFF -> {
                // КРОССФЕЙД ИДЁТ. Цель рампы менять НЕДОСТАТОЧНО: в этом
                // состоянии CURRENT — это уже NEXT, он РАЗЫГРЫВАЕТСЯ, и ничто
                // его не гасит (в последовательной схеме HANDOFF означал
                // «старый гаснет», и retargetFade действительно всё решал).
                // Гасим CURRENT явно; уходящий дойдёт до нуля своей рампой и
                // закроет перекрытие сам — через [onOutgoingReleased].
                queue.clear()
                pendingPlaySpec = null
                pendingHandoff = false
                resetContinuity()
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
        if (outgoing != null) {
            // Пауза застала незакрытый кроссфейд (pause во время HANDOFF). Уходящий
            // ещё держит свой пакет, а пересборка аллоцирует новый — два больших
            // буфера одновременно. Ждём релиза: он близко и на слухе не присутствует.
            StreamLogger.d(TAG, "resumeFromPaused: отложено до релиза уходящего " +
                "spec#${outgoing?.spec?.serial}")
            afterOutgoingReleased { resumeFromPaused() }
            return
        }
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
                resumeAnchor = CurveAnchor.NONE,
                resumeLeftPhase = null,
                resumeRightPhase = null
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

    // ---- Непрерывность сквозного переключения сегментов ----

    /**
     * Состояния, в которых CURRENT ещё ЖИВОЙ и его можно опрашивать.
     *
     * Захват из `PREPARING`, `IDLE`, `PAUSED` (без живого трека) и из окон
     * фейд-аута в стоп запрещён: там «текущая позиция» либо ещё не
     * определена, либо уже не имеет отношения к звуку, который услышит
     * пользователь. Окно «current жив, движок мёртв» отсекается отдельной
     * проверкой [BinauralStreamImpl.hasLiveEngine].
     */
    private fun continuityCaptureAllowed(): Boolean = when (state) {
        ManagerState.FADE_IN, ManagerState.RUNNING, ManagerState.HANDOFF, ManagerState.PAUSED -> true
        ManagerState.IDLE, ManagerState.PREPARING,
        ManagerState.FADE_OUT_PAUSE, ManagerState.FADE_OUT_STOP -> false
    }

    /**
     * Снять с CURRENT то, что нельзя вычислить заново: часы сессии и фазы
     * несущих. Позиция кривой НЕ снимается — следующий поток встанет на
     * «сейчас» (см. комментарий к [switchElapsedMs]).
     *
     * Позиция всё же читается, но только для ДИАГНОСТИКИ: штормовые прогоны
     * asserting'ом ловят «`curveTod=0` при `now ≫ 0`» — ровно тот признак, по
     * которому баг был найден. Ошибкой воспроизведения она больше не является:
     * поле в спеку не уезжает, поэтому рассинхрон исключён по построению.
     */
    /**
     * ДИАГНОСТИКА: насколько расстроены тоны CURRENT и NEXT (Δf по
     * неупорядоченной паре ушей). docs/analysis_scrub_storm_click_risk.md
     * (§4.1, §4.2, §4.4).
     *
     * С §4.4 метод НИЧЕГО не маршрутизирует: любой переход идёт без
     * перекрытия ([beginTransition]), какой бы ни была расстройка. Замер
     * оставлен затем, чтобы в логе каждого перехода было видно, чем грозил
     * бы кроссфейд на этом жесте, — иначе вердикт «а было ли нельзя»
     * невозможно проверить задним числом.
     *
     * Что даёт величина Δf:
     *
     *  - **Δf < [COHERENT_DETUNE_HZ]** — тоны КОГЕРЕНТНЫ: в перекрытии они
     *    сложились бы амплитудами, `√2·|cos(φ/2)|` со случайным φ (фаза
     *    фронтира генерации обгоняет слышимое на кольцо AudioTrack — 400–2200
     *    циклов). Итог перекрытия: от полной взаимной компенсации до +3 дБ.
     *  - **Δf ≫ порога** — энергия в перекрытии складывалась бы корректно,
     *    но звучать стали бы ДВА разных звука разом, а при перестановке
     *    каналов — с обменом ушей внутри окна (модуляция на |beat|).
     *
     * РАСКЛАДКА КАНАЛОВ (§4.3): сравнение идёт НЕУПОРЯДОЧЕННОЙ парой ушей
     * `{min(L,R), max(L,R)}`, а не «левый с левым, правый с правым».
     *
     * Причина. Частоты CURRENT приходят из живого движка и уже учитывают
     * перестановку каналов (там же их берут осцилляторы), а частоты NEXT — из
     * номинальной кривой, которая о раскладке не знает. Измерение третьей
     * волны сравнивало каналы поимённо, и каждая правка настройки давала
     * `Δf = |beat|` ровно (`L 409→391, R 391→409`) при НЕИЗМЕННОЙ кривой:
     * истинная расстройка нулевая, вердикт — «разные тоны». Слышимая цена —
     * на каждой правке настройки уши менялись местами ВНУТРИ перекрытия: оба
     * тона звучат в обоих ушах, и перекрытие модулируется на |beat| (18 Гц
     * × 250 мс = 4.5 цикла — слышимый «трепет», а не мягкое смешение).
     *
     * Перестановка — это то же МНОЖЕСТВО тонов в других ушах: неупорядоченная
     * пара инвариантна к перестановке ЛЮБОГО из потоков.
     */
    private fun logCoherenceVerdict(old: BinauralStreamImpl, spec: PlaybackSpec) {
        val live = old.getFrequenciesAtCurrentTime()
        if (live == null) {
            // Движка живого потока уже нет — читать нечего. Это не ошибка:
            // NEXT всё равно стартует с нуля.
            StreamLogger.d(TAG, "когерентность: ${spec.reason} частоты CURRENT недоступны " +
                "(движок spec#${old.spec.serial} разрушен) — замер пропущен")
            return
        }
        // Измерение идёт по КАНАЛАМ, а не по «несущая/биения»: гасить друг
        // друга будут тоны в каждом ухе по отдельности, а не их полусумма.
        // Каналы тут же СВОРАЧИВАЮТСЯ в неупорядоченную пару (см. KDoc):
        // «левый→левый, правый→правый» ломается на перестановке ушей.
        val (oldBeat, oldCarrier) = live
        val oldLeft = FrequencyMath.leftChannelFrequency(oldCarrier, oldBeat)
        val oldRight = FrequencyMath.rightChannelFrequency(oldCarrier, oldBeat)
        val (oldLo, oldHi) = earPair(oldLeft, oldRight)

        try {
            val (l0, r0) = spec.config.getChannelFrequenciesAt(localTimeOfDay(axisSecondsFor(spec)))
            // Секунда округляется вниз И вверх, берётся МЕНЬШАЯ расстройка:
            // если внутри этой секунды кривая делает скачок (STEP), консервативно
            // считаем, что «почти совпадает» (до §4.4 это склоняло вердикт к
            // нулевому перекрытию — более безопасной стороне).
            val (l1, r1) = spec.config.getChannelFrequenciesAt(
                localTimeOfDay(axisSecondsFor(spec) + 1f)
            )
            val pair0 = earPair(l0, r0)
            val pair1 = earPair(l1, r1)
            val detune = minOf(
                maxOf(abs(oldLo - pair0.first), abs(oldHi - pair0.second)),
                maxOf(abs(oldLo - pair1.first), abs(oldHi - pair1.second))
            )
            val coherent = detune < COHERENT_DETUNE_HZ
            // В лог пишутся и каналы, и свёрнутая пара: «Δf = |beat| ровно при
            // каналах-зеркалах» — это и есть признак перестановки ушей, его
            // надо видеть в логе, а не гадать (§4.2-2, §4.3).
            StreamLogger.d(TAG, "когерентность: ${spec.reason} Δf=${fmt(detune)}Гц " +
                "(уши {${fmt(oldLo)}; ${fmt(oldHi)}} → {${fmt(pair0.first)}; ${fmt(pair0.second)}}, " +
                "каналы L ${fmt(oldLeft)}→${fmt(l0)}, R ${fmt(oldRight)}→${fmt(r0)}) — " +
                (if (coherent) "когерентно (перекрытие дало бы провал до нуля)"
                 else "разные тоны (перекрытие дало бы смесь двух звуков)")
            )
        } catch (e: Exception) {
            // Оценка — диагностика, а не функциональность: любая ошибка здесь
            // не должна мешать переходу.
            StreamLogger.w(TAG, "когерентность: ${spec.reason} частоты NEXT не оценены " +
                "(${e.message}) — замер пропущен")
        }
    }

    /**
     * Неупорядоченная пара ушей: `{min(L,R), max(L,R)}`.
     *
     * Перестановка каналов — это те же два тона в других ушах. Для решения
     * «смешивать или приседать» важен НАБОР частот, а не то, какая из них в
     * каком ухе: см. KDoc [logCoherenceVerdict] (§4.3).
     */
    private fun earPair(left: Float, right: Float): Pair<Float, Float> =
        if (left <= right) left to right else right to left

    /** Герцы в лог: `Locale.US` обязателен — в ru-локаль это `83991,88`. */
    private fun fmt(hz: Float): String = String.format(Locale.US, "%.2f", hz)

    /**
     * Ось времени суток, на которую встанет NEXT по этой спеке: реальное
     * «сейчас» + её собственный сдвиг скраба.
     *
     * Ось — `Float` (см. правило в памяти проекта): дробная доля секунды здесь
     * не теряется, в целые секунды значение переводится только на входе в
     * `LocalTime`, то есть уже внутри оценки когерентности.
     */
    private fun axisSecondsFor(spec: PlaybackSpec): Float =
        normalizeTimeOfDay(baseTimeOfDaySeconds() + spec.scrubOffsetSec)

    /**
     * Секунды суток → `LocalTime` для оценки кривой.
     *
     * Дробная доля здесь теряется сознательно: дальше идёт сравнение с
     * порогом [COHERENT_DETUNE_HZ] (единицы герц), где ошибка в одну секунду
     * хода кривой неразличима. Сама ось при этом остаётся `Float` — см.
     * правило проекта (время суток — только `float`).
     */
    private fun localTimeOfDay(seconds: Float): LocalTime =
        LocalTime.fromSecondOfDay(normalizeTimeOfDay(seconds).toLong().toInt().coerceIn(0, 86_399))

    private fun captureContinuity() {
        val s = current ?: return
        if (!continuityCaptureAllowed()) {
            StreamLogger.d(TAG, "captureContinuity: состояние $state — захват запрещён, " +
                "наследуются только ранее снятые фазы")
            return
        }
        if (!s.hasLiveEngine()) {
            StreamLogger.w(TAG, "captureContinuity: движок spec#${s.spec.serial} уже разрушен — " +
                "координаты нечитаемы, NEXT якорится на «сейчас»")
            return
        }
        switchElapsedMs = s.getElapsedMs()
        // ФИКС RC-2: живые фазы несущих для бесшовного кроссфейда.
        s.getPhases()?.let { (l, r) ->
            switchLeftPhase = l
            switchRightPhase = r
        }
        val tod = s.getCurrentCurveTimeSeconds()
        when {
            tod == null -> StreamLogger.w(TAG, "captureContinuity: позиция кривой недоступна")
            // ЦЕЛЬ — ось СО СДВИГОМ: при активном скрабе позиция кривой
            // заведомо далека от РЕАЛЬНОГО now, и сравнение с ним дало бы
            // ложный WARN на каждом хэндоффе (диагностика перестала бы
            // отличать «звук уехал» от «слушаем другое время суток»).
            !CurveAnchorRules.isPlausible(tod, targetTimeOfDaySeconds()) ->
                StreamLogger.w(TAG, "captureContinuity: позиция кривой $tod далека от " +
                    "цели=${"%.1f".format(targetTimeOfDaySeconds())} (сдвиг скраба " +
                    "=${scrubOffsetSec} с) — как якорь она была бы " +
                    "отвергнута валидацией (сейчас это только диагностика)")
        }
        StreamLogger.d(TAG, "captureContinuity spec#${s.spec.serial}: curveTod=$tod " +
            "(диагностика, якорем не становится), elapsed=${switchElapsedMs}ms, " +
            "phase=$switchLeftPhase/$switchRightPhase")
    }

    /**
     * Обогатить спеку наследуемой непрерывностью.
     *
     * ЯКОРЯ КРИВОЙ ЗДЕСЬ НЕТ И БЫТЬ НЕ ДОЛЖНО: `resumeAnchor` остаётся
     * [CurveAnchor.NONE], и `prepare()` сам возьмёт «сейчас». Этим убирается
     * весь канал протухшего захвата — а с ним и защёлкивание 0 → 0.
     */
    private fun enrichForContinuity(spec: PlaybackSpec): PlaybackSpec {
        // Отладочный скраб — ЯВНАЯ установка времени оператором, непрерывность
        // её не перебивает: prepare() и так якорится по оси самого движка,
        // которую скраб уже переставил.
        //
        // ПРОДАКШЕН-СКРАБ (предпросмотр из редактора) сюда НЕ попадает и
        // попадать не должен: это ДРУГАЯ переменная. Ему наследование фаз
        // нужно — иначе NEXT стартует с фазы 0 и интерферирует с уходящим
        // потоком (провал огибающей в кроссфейде).
        if (debugScrubPending != null) return spec
        return spec.copy(
            resumeElapsedMs = switchElapsedMs,
            resumeLeftPhase = switchLeftPhase,
            resumeRightPhase = switchRightPhase
        )
    }

    private fun resetContinuity() {
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

    /**
     * Занять слот current и запустить поток.
     *
     * @return true — трек стартовал; false — старт не удался, поток утилизирован.
     *
     * ВАЖНО для [beginSilentSwitch]: при неудаче слот current зануляется и
     * автомат уходит в IDLE. Если до вызова в слоте был ЖИВОЙ поток, вызывающий
     * обязан вернуть его на место — иначе старый поток
     * останется без владельца и будет звучать вечно.
     *
     * @param fadeInShape форма рампы входа. LINEAR по умолчанию — для
     *   одиночного запуска (PLAY/RESUME) второго потока нет, и equal-power
     *   нечего делить (см. KDoc [FadeShape.EQUAL_POWER]). [beginSilentSwitch]
     *   передаёт [FadeShape.EQUAL_POWER]: плечо входа согласовано с cos-плечом
     *   ухода, набор и спад симметричны по мощности. Раньше форма сюда не
     *   передавалась вовсе, и NEXT поднимался ЛИНЕЙНО — при перекрытии пара
     *   «cos-уход + линейный вход» давала провал энергии до ~1.5 дБ около
     *   p≈0.75 (R4 документа).
     */
    private fun launchStream(
        stream: BinauralStreamImpl,
        fadeInShape: FadeShape = FadeShape.LINEAR,
        fadeInMsOverride: Long = 0L
    ): Boolean {
        StreamLogger.d(TAG, "launchStream spec#${stream.spec.serial} sr=${stream.spec.sampleRate} beat=${stream.spec.config.frequencyCurve.getBeatFrequencyAt(kotlinx.datetime.LocalTime(0, 0))} shape=$fadeInShape")
        // Не-handoff запуск (PLAY/RESUME) — новый сегмент без непрерывности; сбрасываем
        // якорь. При handoff (PRESET_SWITCH/SETTINGS) якорь захвачен в beginHandoff и
        // должен дожить до старта NEXT — continuity применится в enrichForContinuity.
        if (pendingHandoff) {
            pendingHandoff = false
        } else {
            resetContinuity()
        }
        // Пока уходящий поток кроссфейда не отдал свой пакет, новый НЕ
        // доращивает свой: два больших буфера одновременно — это ровно тот
        // отказ PacketMemoryBudget, ради которого и введён флаг. Разрешение
        // раздаёт только [onOutgoingReleased]; здесь — ТОЛЬКО сужение, иначе
        // этот вызов отменил бы явный запрет, поставленный [beginSilentSwitch].
        if (outgoing != null) stream.setPacketGrowthAllowed(false)
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
        if (!stream.start(
                onFullyStarted = { setState(ManagerState.RUNNING) },
                shape = fadeInShape,
                fadeInMsOverride = fadeInMsOverride
            )
        ) {
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
        if (outgoing === stream) {
            // Ошибка УХОДЯЩЕГО потока. Он уже в нуле по громкости и в эфире не
            // присутствует — провал его писателя не имеет слышимых последствий.
            // Всё, что нужно: закрыть перекрытие немедленно, не трогая автомат
            // (иначе сбой хвоста уходящего кроссфейда глушил бы живой CURRENT).
            StreamLogger.w(TAG, "handleRuntimeError: сбой уходящего spec#${stream.spec.serial} — " +
                "закрываем перекрытие принудительно")
            stream.releaseNow()
            onOutgoingReleased(stream)
            return
        }
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
        pendingHandoff = false
        // СКРАБ: потока не стало — возвращать на реальную ось больше нечего.
        scrubNeedsRealignment = false
        resumeInFlight = false
        lastResumeAccuracy = null
        resetContinuity()
    }
}
