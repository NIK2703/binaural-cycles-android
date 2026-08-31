package com.binaural.core.audio.stream

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.VolumeShaper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.binaural.core.audio.engine.NativeAudioEngine
import java.nio.ByteBuffer

import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BinauralStreamImpl(
    private val context: Context,
    override val spec: PlaybackSpec,
    /** Нить актёра менеджера: все таймеры фейдов и колбэки исполняются здесь. */
    private val controlHandler: Handler,
    /** Интервал генерации буфера (энергосбережение), мс. */
    private val bufferIntervalMs: Int,
    private val fadeInMs: Long = DEFAULT_FADE_MS,
    private val fadeOutMs: Long = DEFAULT_FADE_MS,
    /** Хук применения debug-состояния к свежему нативному движку. */
    private val nativeCustomizer: ((NativeAudioEngine) -> Unit)? = null,
    /** Ошибка генерации/записи в рантайме; вызывается на нити актёра. */
    private val onRuntimeError: (BinauralStreamImpl, String) -> Unit = { _, _ -> }
) : BinauralStream {

    companion object {
        private const val TAG = "BinauralStream"
        const val DEFAULT_FADE_MS = 250L
        private const val FADE_GUARD_MS = 60L
        /** Байт в кадре: стерео × ENCODING_PCM_FLOAT. */
        private const val frameBytes = 2 * 4
        /**
         * Внутренний буфер (кольцо) AudioTrack — сколько PCM может лежать в треке
         * впереди слышимой позиции.
         *
         * Ограничен сверху и по времени, и по байтам (см. [MAX_TRACK_BUFFER_BYTES]):
         * кроссфейд держит ДВА живых трека (CURRENT+NEXT), а разделяемая память
         * треков выделяется из одной кучи клиента в AudioFlinger (MemoryDealer).
         * На части устройств она ~7 МБ: при 10 с на 48 кГц кольцо весит 3.84 МБ,
         * и второй трек просто не создаётся —
         * `createTrack_l() initCheck failed -12` (NO_MEMORY).
         */
        private const val TRACK_BUFFER_MS = 10000         // внутренний буфер AudioTrack
        /**
         * Потолок кольца в байтах — на КАЖДЫЙ поток, а не на все сразу.
         *
         * Откуда число. Разделяемая память треков выделяется из ОДНОЙ кучи
         * клиента в AudioFlinger (`dumpsys media.audio_flinger` → Clients:
         * heap_size, типовое значение 7 MiB). Кроссфейд держит ДВА живых трека,
         * и оба обязаны влезть в эту кучу — иначе второй трек не создаётся
         * вовсе.
         *
         * Замер на устройстве (POCO 23049PCD8G, Android 13, 48 кГц): запрос
         * кольца 3 MiB = 393216 кадров оборачивается аллокацией 4 MiB —
         *
         *     E AF::TrackBase: TrackBase(58): not enough memory for AudioTrack
         *                     size=4194536
         *     E AudioFlinger: createTrack_l() initCheck failed -12
         *
         * Итог при потолке 3 МБ: beginHandoff гарантированно проваливался на
         * prepare() NEXT — переключение пресета не происходило вообще, старый
         * поток продолжал играть. 2 MiB на поток → двое влезают с запасом
         * (проверено на устройстве: оба трека создаются).
         *
         * Побочный эффект: на 48 кГц кольцо ~5.5 с вместо 10 с (на 22.05 кГц
         * потолок не работает — 10 с = 1.76 МБ, меньше предела). Чанк записи
         * подстраивается сам: writerLoop() считает его от ФАКТИЧЕСКОГО размера
         * кольца минус [MIN_WRITE_MARGIN_MS], то есть запас до underrun
         * сохраняется, а частота пробуждений писателя растёт (~800/час вместо
         * ~500/час). minBuffer — нижняя граница HAL, её не роняем.
         */
        private const val MAX_TRACK_BUFFER_BYTES = 2L * 1024 * 1024
        /**
         * Гранулярность записи — и она же определяет, как часто просыпается
         * писатель. write(WRITE_BLOCKING) возвращается, только когда в кольце
         * трека есть место под ВЕСЬ чанк, то есть заполненность упала до
         * `buffer - chunk`; в установившемся режиме период между записями
         * равен ровно длительности чанка:
         *
         *     пробуждений в час = 3_600_000 / реальный_чанк
         *     реальный_чанк = min(WRITE_CHUNK_MS, кольцо_трека − MIN_WRITE_MARGIN_MS)
         *
         * Было 500 мс (7200/час) → 2000 мс (1800/час) → 8000 мс.
         *
         * ВАЖНО: формула «3_600_000 / 8000 = 450/час», которая здесь стояла,
         * верна только если кольцо трека дотягивает до 9 с. На 48 кГц float
         * оно НЕ дотягивает: MAX_TRACK_BUFFER_BYTES = 2 МиБ урезает запрошенные
         * 10 с до 5.46 с, минус маржа 1 с ⇒ чанк 4.46 с ⇒ **807/час** (на
         * 44.1 кГц — 728/час). Полные 450/час дают только SR <= 22050 Гц, где
         * 10-секундное кольцо влезает в 2 МиБ целиком. Иными словами, выигрыш
         * от 8-секундного чанка на максимальном SR реализуется лишь наполовину
         * — и это структурное следствие предела кольца, а не баг.
         *
         * Проверено замером: стоимость самой генерации от этого не зависит
         * (6.4 нс/кадр при любом размере пакета), так что это и есть основной
         * рычаг энергопотребления писателя — не DSP.
         *
         * Отзывчивость на stop() от размера чанка не зависит: писателя
         * разблокируют track.pause()/stop()/release() в releaseInternal(),
         * а пауза/фейд идут через VolumeShaper на нити актёра.
         *
         * Ограничение: чанк ОБЯЗАН быть меньше буфера трека минимум на
         * MIN_WRITE_MARGIN_MS, иначе подпитка не гарантирована. См. writerLoop().
         */
        private const val WRITE_CHUNK_MS = 8000           // гранулярность записи/реакции
        /**
         * Гарантированный запас аудио в кольце трека в момент разблокировки
         * write(). Именно столько остаётся проиграть, если писатель встанет
         * (GC, конкуренция за CPU, смена частоты) — то есть это и есть запас
         * до underrun: `buffer - chunk`. Держим его явно, чтобы фактический
         * размер буфера, урезанный HAL, не съел запас молча.
         *
         * 1000 мс — историческая величина (при кольце 3000 мс и чанке 2000 мс
         * запас был ровно таким). Её задача — перекрыть задержку пробуждения
         * писателя, а не держать полсекунды про запас: при урезанном по
         * [MAX_TRACK_BUFFER_BYTES] кольце лишние 1000 мс напрямую вычитаются из
         * чанка и тем самым удваивают число пробуждений.
         */
        private const val MIN_WRITE_MARGIN_MS = 1000

        /**
         * Длина стартового пакета в секундах. Именно столько (768 КБ при 48 кГц,
         * стерео float) выделяет prepare() — а не весь bufferIntervalMs.
         * Полный интервал доращивает писатель. См. комментарий в prepare().
         */
        private const val STARTUP_PACKET_SECONDS = 2

        /**
         * Сколько раз писатель вправе пытаться дорастить пакет до полного
         * интервала. Ограничение обязательно: каждая попытка — это
         * allocateDirect на сотни мегабайт с уполовиниванием, и без счётчика
         * неудачная попытка повторялась бы на каждом витке цикла вечно.
         */
        private const val MAX_GROW_ATTEMPTS = 3
        /**
         * Опрос парковки писателя на паузе. wait() с таймаутом, а не вечное
         * ожидание: пропущенный notify не превращается в зависший поток —
         * writer сам проверит паузу и выход из цикла не позже чем через это
         * время. 200 мс — компромисс: просыпаний почти нет, реакция на
         * stop()/release() из паузы — не дольше одного тика.
         */
        private const val PARK_POLL_MS = 200L
        private const val SECONDS_PER_DAY = 86400

        /**
         * Потолок пакета, подменяемый НА ХОДУ (debug-команда `packetmax`).
         *
         * Состояние живёт в [PacketMemoryBudget.setPacketMaxBytes], здесь
         * только делегация — чтобы debug-команда не знала о бюджете. Почему
         * состояние там: ручной потолок обязан двигать и слайдер, иначе он
         * урезает выбранную пользователем стопу молча.
         */
        @JvmStatic
        fun setPacketMaxBytes(bytes: Long) = PacketMemoryBudget.setPacketMaxBytes(bytes)

        /** Действующий ручной потолок пакета: override или «потолка нет». */
        @JvmStatic
        fun packetMaxBytesEffective(): Long = PacketMemoryBudget.packetMaxBytesEffective()

        /**
         * Какая доля кучи в СУММЕ отдана под пакеты всех живых потоков.
         *
         * Считается как процент кучи в [PacketMemoryBudget.globalBudgetBytes],
         * а не как константа в мегабайтах: раньше здесь стояло 96 МБ («ровно
         * три полных буфера по 32 МБ») — число, привязанное к тогдашнему
         * потолку пакета. Подняв потолок, такой бюджет можно было обойти и
         * вернуть ту же ловушку, от которой он ставился.
         *
         * Это ВТОРАЯ линия обороны. Первая — инвариант одного потока: новый
         * поток создаётся только после полной утилизации старого
         * (см. BinauralStreamManager.beginHandoff), поэтому двух живых пакетов
         * не существует вовсе (два — лишь на время копирования при доращивании).
         * Бюджет срабатывает только при поломке инварианта (например, писатель
         * застрял и старый поток не успел отдать буфер до роста следующего).
         *
         * Стоимость отказа дорастить — малая: поток остаётся на стартовом
         * пакете ([STARTUP_PACKET_SECONDS]) и генерирует чаще. CPU/час от длины
         * пакета НЕ зависит (замер: 1.02 с/час и при 2 с, и при 190 с), так что
         * платим только числом вызовов планировщика, а не процессором.
         */
        @JvmStatic
        fun globalPacketBudgetBytes(): Long = PacketMemoryBudget.globalBudgetBytes()

        /** Сколько байт пакетных буферов учтено за всеми потоками сейчас. */
        private val packetsBudgetUsed = java.util.concurrent.atomic.AtomicLong(0)

        /** Пик [packetsBudgetUsed] за время жизни процесса. */
        private val peakPacketsBudgetUsed = java.util.concurrent.atomic.AtomicLong(0)

        /** Сколько потоков сейчас держат пакетный буфер (учёт — в [commitPacketBudget]). */
        private val livePacketHolders = java.util.concurrent.atomic.AtomicInteger(0)

        /** Пик [livePacketHolders] — прямая проверка инварианта «не больше двух». */
        private val peakPacketHolders = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Сколько раз [allocateDirect] поймал OutOfMemoryError и уполовинил
         * запрос. Ноль — обязательное условие «предел найден»: каждый провал
         * это не только потерянные миллисекунды, но и поднятый GC.
         */
        private val oomHalvings = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Сколько раз доращивание ОТЛОЖЕНО предохранителем (резерв или общий
         * бюджет). Отдельный счётчик, потому что отказ молчаливый: поток просто
         * остаётся на стартовом пакете, звук не рвётся, и заметить деградацию
         * можно только по нему. Ненулевой значение — повод смотреть `packetmax`.
         */
        private val growDeferredTotal = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Дедлайн опроса выхода писателя в finalizeStop(): сколько максимум
         * держим утилизацию потока, прежде чем отдать её releaseInternal().
         * Покрывает один полный чанк записи (WRITE_CHUNK_MS) — иначе таймаут
         * наступил бы, пока писатель штатно стоит в track.write().
         *
         * Опрос, а не await: нить актёра НЕ блокируется, на ней висят таймеры
         * фейдов и onStreamFullyStopped.
         */
        private const val WRITER_EXIT_WAIT_MS = WRITE_CHUNK_MS + 1500L
        /**
         * Короткая грейс-фаза в releaseInternal(): сколько даём писателю на
         * выход ПОСЛЕ того, как трек уже снят (pause() разблокировал
         * write()) — писателю нужно лишь дойти до проверки lifecycle или
         * вернуться из отладочной генерации.
         *
         * Это предел БЛОКИРУЮЩЕГО ожидания на нити актёра, поэтому он короткий
         * [WRITER_EXIT_WAIT_MS] (9.5 с) здесь ждать нельзя: пока актёр стоит,
         * не исполняется ни один таймер фейда и не стартует следующий поток —
         * ровно оттуда и росли многосекундные провалы при переключении.
         * Не вышел за грейс — движок остаётся писателю (он освободит его в
         * своём finally, см. engineOwnedByWriter), утилизация идёт дальше.
         */
        private const val WRITER_HANDOFF_GRACE_MS = 250L

        /**
         * Диагностика пакетной памяти — для debug-команды `pkstat`.
         *
         * Главные цифры: `holders peak` (прямая проверка инварианта «с пакетом
         * не больше одного потока») и `oomHalvings` (сколько раз allocateDirect
         * делил запрос пополам — при правильно найденном пределе ноль).
         */
        @JvmStatic
        fun packetStats(): String {
            val rt = Runtime.getRuntime()
            val mb = 1024L * 1024L
            val pct = PacketMemoryBudget.heapPercentEffective()
            val gpct = PacketMemoryBudget.globalHeapPercentEffective()
            return buildString {
                append("heap=${rt.maxMemory() / mb}МБ доля=${pct}% " +
                    "резервПриложению=${PacketMemoryBudget.appReserveBytes() / mb}МБ\n")
                // ЕДИНАЯ цифра потолка — та же, по которой считает слайдер.
                append("perStreamCap=${packetBudgetBytes() / mb}МБ " +
                    "(abi=${PacketMemoryBudget.ABI_CAP_BYTES / mb}МБ)\n")
                append("holders=${livePacketHolders.get()} peak=${peakPacketHolders.get()} (инвариант: <=1)\n")
                append("budget=${packetsBudgetUsed.get() / mb}МБ " +
                    "peak=${peakPacketsBudgetUsed.get() / mb}МБ " +
                    "limit=${globalPacketBudgetBytes() / mb}МБ (общий ${gpct}%)\n")
                append("oomHalvings=${oomHalvings.get()} " +
                    "отказовРоста=${growDeferredTotal.get()}\n")
                // Вложенные кавычки внутри шаблона Kotlin не разбирает —
                // значение считаем отдельно.
                val learned = PacketMemoryBudget.adaptiveCeilingBytes()
                val learnedText = if (learned == Long.MAX_VALUE) "нет" else "${learned / mb}МБ"
                append("выученныйПотолок=$learnedText")
            }
        }

        /** Сколько потоков прямо сейчас держат пакетный буфер (инвариант: 0 или 1). */
        @JvmStatic
        fun livePacketHolders(): Int = livePacketHolders.get()

        /** Обнулить накопленные пики — чтобы замерять каждый прогон с чистого листа. */
        @JvmStatic
        fun resetPacketStats() {
            peakPacketsBudgetUsed.set(packetsBudgetUsed.get())
            peakPacketHolders.set(livePacketHolders.get())
            oomHalvings.set(0)
            growDeferredTotal.set(0)
            PacketMemoryBudget.resetAdaptiveCeiling()
        }

        /**
         * Сколько байт можно отдать под пакет ОДНОГО потока.
         *
         * Прямой буфер — `ByteBuffer.allocateDirect`, а на Android он
         * выделяется как non-movable `byte[]` НА ЯВА-КУЧЕ (libcore →
         * `VMRuntime.newNonMovableArray`), то есть конкурирует за кучу и с
         * приложением, и САМ С СОБОЙ: во время кроссфейда живут CURRENT и
         * NEXT, а при быстрой смене пресетов ещё и догорающий старый поток.
         * Поэтому предел считаем от кучи, а не от объёма RAM в устройстве.
         *
         * Вся арифметика — процент кучи, резерв приложению, ABI-потолок,
         * выученный после OOM, ручной `packetmax` — собрана ровно в одном
         * месте, в [PacketMemoryBudget.engineCeilingBytes], и этот класс её не
         * дублирует. Слайдер и prepare() обязаны спрашивать одну и ту же
         * цифру: иначе слайдер предлагает стопу, которую движок молча урежет.
         *
         * История, из-за которой предел вообще понадобился. На 23049PCD8G
         * `bufferGenerationMinutes=10` давал цель 600 с = 230 МБ на поток.
         * Два-три потока доращивали пакет одновременно, `allocateDirect`
         * уполовинивал 230→115→57.6→28.8, и КАЖДАЯ неудачная попытка сама
         * поднимала GC. Итог — ART/Xiaomi сторож:
         *   kill process: ... reason: memory leaks occurred.
         *   current heap memory: 268314168
         * До процента здесь стоял делитель кучи: 1/8 (32 МБ ≈ 87 с при 48 кГц),
         * потом 1/4 (64 МБ ≈ 174 с). Делитель — плохая единица: он не
         * выражается в секундах звука и потому не объясняет слайдеру, сколько
         * можно. Процент выражается, и он же подобран замером.
         */
        @JvmStatic
        private fun packetBudgetBytes(): Long = PacketMemoryBudget.engineCeilingBytes()
    }

    private val lifecycleRef = AtomicReference(StreamLifecycle.CREATED)
    override val lifecycle: StreamLifecycle get() = lifecycleRef.get()

    @Volatile private var fadeMode = FadeMode.NONE
    @Volatile private var userVolume = spec.volume

    private var nativeEngine: NativeAudioEngine? = null
    private var audioTrack: AudioTrack? = null
    /**
     * Пакетный буфер генерации. Volatile: его подменяет НИТЬ ПИСАТЕЛЯ
     * ([maybeGrowPacketBuffer]), а читает и обнуляет нить управления
     * ([releaseInternal]) — без volatile релиз мог бы обнулить свежий буфер
     * и тот утек бы.
     */
    @Volatile private var directBuffer: ByteBuffer? = null
    private var writerThread: HandlerThread? = null
    private var writerHandler: Handler? = null
    private var volumeShaper: VolumeShaper? = null
    /** Ёмкость текущего буфера в сэмплах на канал. Растёт от стартовой к целевой. */
    @Volatile private var samplesPerChannel = 0
    /**
     * Целевая ёмкость пакета по настройке bufferIntervalMs (с капами).
     * Достигается НЕ сразу: prepare() берёт только стартовый пакет, а до
     * полного интервала буфер доращивает писатель уже под звук.
     */
    private var targetSamplesPerChannel = 0
    private var packetBufferGrown = false
    private var growAttempts = 0
    /**
     * Отложенный рост уже logged? Писатель доходит сюда КАЖДЫЙ пакет (раз в 2 с
     * на стартовом размере), поэтому без флага две строки в секунду захламили
     * бы файловый лог потока и утопили в нём всё остальное.
     */
    private var growDeferredLogged = false
    /**
     * Сколько байт этого потока учтено в [packetsBudgetUsed]. Atomic, а не
     * обычное поле: бюджет правят две нити — писатель (доращивание) и актёр
     * (prepare/releaseInternal).
     */
    private val packetBudgetCommitted = AtomicLong(0)
    /**
     * Сколько неудачных попыток (OutOfMemoryError) понадобилось [allocateDirect]
     * перед успехом. Только для диагностики: каждый провал — это реальные
     * потраченные миллисекунды на актёрской нити во время кроссфейда.
     */
    private var directAllocateAttempts = 0
    private var audioTrackBufferSize = 0
    private var preparedPacketBytes = 0
    private val writerExitLatch = CountDownLatch(1)
    @Volatile private var writerStarted = false
    @Volatile private var preparedPrefilled = false
    /**
     * Писатель забрал владение нативным движком (вызвал его release() в своём
     * finally). Устанавливается ДО writerExitLatch.countDown() — память
     * упорядочена: наблюдатель, увидевший латч==0, видит и этот флаг.
     * После таймаута ожидания писателя в releaseInternal этот флаг решает,
     * кто удаляет движок: если писатель его уже «несёт» — менеджер НЕ
     * удаляет (иначе use-after-free внутри generateAudioBuffer → SIGSEGV).
     */
    @Volatile private var writerConsumedEngine = false

    /** Runnable завершения фейда: хранится, чтобы "разворот рампы" мог его отменить. */
    @Volatile private var fadeCompletion: Runnable? = null

    /**
     * Утилизация (setVolume(0) + pause + release) отложена на [FADE_GUARD_MS]
     * после конца рампы: VolumeShaper стартует не мгновенно, а на следующем
     * цикле микшера, поэтому в момент «рампа дошла до нуля» множитель ещё
     * ≈0.31. Снять трек там — значит оборвать звук на 31 % громкости, щелчком.
     *
     * Раньше здесь же висел второй колбэк ровно в конце рампы («точка
     * тишины») — из него менеджер повышал заранее подготовленный NEXT и
     * получал бесшовный кроссфейд. В схеме «загружен не более одного потока»
     * поднимать нечего: следующий поток создаётся только ПОСЛЕ [fadeCompletion],
     * и [FADE_GUARD_MS] — это ровно та величина, на которую разрыв звука
     * длиннее самой рампы.
     */
    private fun cancelFadeCallbacks() {
        fadeCompletion?.let { controlHandler.removeCallbacks(it) }
        fadeCompletion = null
    }

    // -------------------------------------------------- мягкая пауза (состояние)
    /**
     * true — поток заморожен: трек на паузе, писатель припаркован, пакет и
     * смещение в [directBuffer] сохранены. Ресурсы НЕ освобождены.
     * Пишется только нитью актёра, читается писателем.
     */
    @Volatile private var paused = false
    override val isPaused: Boolean get() = paused

    /**
     * Кадры на канал, сгенерированные этим движком с начала потока.
     * Пара к AudioTrack.playbackHeadPosition даёт слышимую позицию кривой:
     * сколько сэмплов «впереди звука» — столько и отнимаем от фронтира.
     * Пишет писатель (и prepare() до его старта), читает актёр.
     */
    @Volatile private var generatedFrames = 0L

    /** Монитор парковки писателя: parkWriter/wakeWriter. */
    private val parkLock = Object()

    // ------------------------------------------------------------------ prepare

    override fun prepare(): Boolean {
        if (!lifecycleRef.compareAndSet(StreamLifecycle.CREATED, StreamLifecycle.PREPARED)) {
            StreamLogger.w(TAG, "prepare spec#${spec.serial}: уже не CREATED (lc=${lifecycleRef.get()})")
            return false
        }
        StreamLogger.d(TAG, "prepare spec#${spec.serial} sr=${spec.sampleRate.value} reason=${spec.reason} resume=${spec.resumeAnchorMs > 0} curveTime=${spec.resumeCurveTimeSeconds}")
        return try {
            val rate = spec.sampleRate.value

            // 1. Отдельный писатель: генерация/запись НЕ занимает нить управления.
            val thread = HandlerThread("BinauralWriter-${spec.serial}", android.os.Process.THREAD_PRIORITY_AUDIO)
            thread.start()
            writerThread = thread
            writerHandler = Handler(thread.looper)

            // 2. Нативный движок (свежий экземпляр на каждый поток).
            val engine = NativeAudioEngine()
            engine.initialize()
            engine.setSampleRate(rate)
            engine.updateConfig(spec.config, spec.relaxation)
            nativeCustomizer?.invoke(engine)

            // ФИКС 1.4: движок ВСЕГДА свежий — состояние обязано быть детерминированным.
            // resetState ДО любого play: фазы синусоид = 0 => первый сэмпл = 0.
            engine.resetState()
            // ФИКС RC-2: продолжение (кроссфейд смены SR/настроек) — восстанавливаем
            // фазу несущих, иначе NEXT стартует с фазой 0 и интерферирует со старым
            // (провал/всплеск огибающей в EQUAL_POWER). СТРОГО после resetState().
            val rl = spec.resumeLeftPhase
            val rr = spec.resumeRightPhase
            if (rl != null && rr != null) {
                engine.setPhases(rl, rr)
            }

            if (spec.resumeCurveTimeSeconds >= 0) {
                // Продолжение (пауза/handoff): позиция кривой задаётся явно,
                // иначе свежий движок генерировал бы с 00:00.
                engine.setCurveTime(spec.resumeCurveTimeSeconds)
            }
            if (spec.resumeAnchorMs > 0) {
                engine.setPlaybackStartTime(spec.resumeAnchorMs)
                engine.play(preserveTimeline = true)     // не переякоряет таймлайн
            } else if (spec.resumeElapsedMs > 0) {
                // ФИКС 3. Продолжение БЕЗ явного wall-якоря (сквозное переключение
                // сегментов): обязаны играть с preserveTimeline=true, иначе play()
                // переякорит кривую к настенным часам и сбросит setCurveTime() —
                // частота/фаза прыгнут (слышимый щелчок/шаг). Держим позицию кривой,
                // заданную выше, и продолжаем elapsed-часы с resumeElapsedMs.
                engine.setPlaybackStartTime(System.currentTimeMillis() - spec.resumeElapsedMs)
                engine.play(preserveTimeline = true)
            } else {
                engine.play()                            // свежий старт от 00:00
            }
            nativeEngine = engine

            // 3. AudioTrack создаётся ДО буфера — ПОРЯДОК ВАЖЕН.
            //    allocateDirect() держит нижний предел OOM-уполовинивания как
            //    maxOf(audioTrackBufferSize, rate*2*4), а audioTrackBufferSize
            //    заполняется именно внутри createAudioTrack(). При обратном
            //    порядке предел равнялся НУЛЮ: на устройстве с нехваткой памяти
            //    буфер схлопывался до 1 с аудио при 10-секундном внутреннем
            //    буфере трека — писатель физически не успевал подпитывать трек,
            //    и каждый такой просадкой давался underrun (щелчок).
            //    Трек создан, но НЕ запущен — поток ещё беззвучен.
            createAudioTrack(rate)

            // 4. Целевая ёмкость пакета.
            //
            //    Потолок берётся ровно из одного места — [PacketMemoryBudget.
            //    engineCeilingBytes] — то есть ИЗ ТОЙ ЖЕ цифры, по которой
            //    слайдер строил стопы и по которой менеджер клампил интервал.
            //    Раньше здесь стояли ещё два потолка, о которых слайдер не знал
            //    (ABI-константа и выученный после OOM), и на устройствах с
            //    кучей ≥ 341 МБ они молча урезали выбранную длину: 40 мин
            //    @16 кГц превращались в 35, 15 мин @48 кГц — в 11.6. Теперь
            //    усечение возможно только по запрошенному интервалу, и оно
            //    По-прежнему не молчит.
            val ceilingSamples = packetBudgetBytes() / PacketMemoryBudget.BYTES_PER_FRAME
            // Структурный предел длительности (не память, а здравый смысл:
            // пакет длиннее часа — это час, в течение которого правка графика
            // дойдёт до звука только на следующей генерации).
            val maxSamplesBySeconds = rate.toLong() * PacketMemoryBudget.MAX_SECONDS
            val requestedSamples = rate.toLong() * bufferIntervalMs / 1000L
            val targetSamples = minOf(
                requestedSamples,
                maxSamplesBySeconds,
                ceilingSamples
            ).toInt()
            targetSamplesPerChannel = targetSamples
            // Потолок — это предел устройства, а не рабочий предел. Дефолтный
            // 600 с влезает целиком при любом SR (максимум 230 МБ на HIGH);
            // урезается только то, что физически не держится (напр. 3600 с
            // @44.1 кГц = 1.27 ГБ/поток). Усечение не молчит: пишем
            // запрошенный и реальный интервал в лог.
            if (targetSamples < requestedSamples) {
                StreamLogger.w(TAG, "prepare spec#${spec.serial}: buffer interval clamped " +
                    "(ceiling ${packetBudgetBytes() / (1024 * 1024)}MB " +
                    "[abi=${PacketMemoryBudget.ABI_CAP_BYTES / (1024 * 1024)}MB] " +
                    "on heap ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB): " +
                    "requested ${bufferIntervalMs}ms -> effective ${targetSamples * 1000L / rate}ms @ ${rate}Hz")
            }

            // 5. Стартовый буфер — ТОЛЬКО под первый пакет, НЕ под весь интервал.
            //    Причина не скорость, а ПАМЯТЬ. Полный интервал 600 с — это 230 МБ
            //    на поток; allocateDirect их не даёт и уполовинивает до ~29 МБ,
            //    но при БЫСТРОЙ смене пресетов одновременно живёт несколько
            //    потоков, и эти буферы складываются. Замер на устройстве
            //    (23049PCD8G): куча 223/256 МБ, каждый хэндофф добавлял 28.8 МБ,
            //    два подряд её переполняли — GC-трэш (15 сборок за 600 мс, каждая
            //    освобождает 16-48 КБ) и сторож Xiaomi убивал процесс:
            //      kill process: ... reason: memory leaks occurred.
            //      current heap memory: 263993728
            //    Стартовый пакет — 768 КБ при 48 кГц, то есть в 37 раз меньше.
            //    До полного интервала буфер доращивает ПИСАТЕЛЬ уже под звук
            //    (см. maybeGrowPacketBuffer): к тому моменту поток один, старые
            //    уже отпущены и память есть. Интервал генерации (а значит и
            //    частота пробуждений писателя) в установившемся режиме
            //    сохраняется — батарейная оптимизация не пострадала.
            val startupSamples = minOf(targetSamples, rate * STARTUP_PACKET_SECONDS)
            directBuffer = allocateDirect(startupSamples * 2 * 4, rate)
                ?: throw OutOfMemoryError("direct buffer unavailable")
            // Реальная ёмкость может оказаться меньше запрошенной: при OOM
            // allocateDirect() делит размер пополам. Урезаем длину пакета по
            // факту — иначе JNI вернёт 0 («buffer too small») и звук встанет.
            val capacitySamples = directBuffer!!.capacity() / 8
            samplesPerChannel = maxOf(1, minOf(startupSamples, capacitySamples))
            // Стартовый буфер тоже входит в общий бюджет: в шторме смен пресетов
            // подряд создаётся много потоков, и 768 КБ × N — уже не мелочь.
            commitPacketBudget(capacitySamples.toLong() * 8)
            if (capacitySamples < startupSamples) {
                // Единицы — МИЛЛИСЕКУНДЫ: samples*1000/rate, а не samples/rate.
                // Раньше здесь стояло «...${...}s», из-за чего реальное 75 с
                // читалось как 75000 с и диагноз уезжал в космос.
                StreamLogger.w(TAG, "prepare spec#${spec.serial}: allocateDirect урезал буфер до " +
                    "${capacitySamples * 1000L / rate}мс @ ${rate}Hz (запрошено " +
                    "${requestedSamples * 1000L / rate}мс, провалов аллокации: " +
                    "$directAllocateAttempts) — недостаточно RAM")
            }
            // Запас по времени (буфер не меньше внутреннего буфера трека)
            // проверяется НЕ здесь, а при доращивании: стартовый пакет заведомо
            // меньше TRACK_BUFFER_MS, и требовать обратное значит опять просить
            // десятки мегабайт на prepare(). См. maybeGrowPacketBuffer().

            // 6. Первый пакет — КОРОТКИЙ (до 2 с): подготовка быстрая и не блокирует
            // актёр надолго; полный интервал сгенерирует писатель, пока трек уже играет.
            val prepareSamples = minOf(samplesPerChannel, rate * STARTUP_PACKET_SECONDS)
            val buf = directBuffer!!
            buf.clear()
            val generated = engine.generateBufferDirect(buf, prepareSamples)
            if (generated <= 0) throw IllegalStateException("first packet generation failed")
            preparedPacketBytes = generated * 2 * 4
            generatedFrames = generated.toLong()
            StreamLogger.d(TAG, "prepare OK spec#${spec.serial} firstPacketBytes=$preparedPacketBytes")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "prepare() failed spec#${spec.serial}: ${t.message}")
            StreamLogger.e(TAG, "prepare FAILED spec#${spec.serial}: ${t.message}")
            lifecycleRef.set(StreamLifecycle.FAILED)
            releaseInternal()
            false
        }
    }

    private fun createAudioTrack(rate: Int) {
        val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        // Long на всём пути: rate * 8 байт/кадр * TRACK_BUFFER_MS(10 с) = 3.5e9 —
        // в Int уже не влезает (на 3 с влезало, на 10 — нет).
        //
        // Потолок в байтах режет только старшие SR: два живых трека (кроссфейд)
        // обязаны влезть в одну кучу клиента AudioFlinger, иначе второй трек не
        // создаётся (-12 NO_MEMORY). minBuffer — нижняя граница HAL, её не роняем.
        val requested = maxOf(minBuffer.toLong(), rate.toLong() * frameBytes * TRACK_BUFFER_MS / 1000)
        val size = maxOf(minBuffer.toLong(), minOf(requested, MAX_TRACK_BUFFER_BYTES)).toInt()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Читаем ФАКТИЧЕСКИЙ размер кольца, а не запрошенный: HAL вправе его
        // урезать, и тогда чанк записи, посчитанный от запрошенного, оказался бы
        // больше реального зазора. write(WRITE_BLOCKING) разблокируется при
        // заполненности `buffer - chunk`, то есть запас до underrun равен
        // ровно `buffer - chunk`; если считать от завышенного buffer, запас
        // молча уезжает в ноль. minSdk 26 — getBufferSizeInFrames() доступен
        // (API 23) без проверки версии.
        val track = audioTrack
        val actualBytes = if (track != null) {
            (track.bufferSizeInFrames.toLong() * 2 * 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }
        audioTrackBufferSize = if (actualBytes > 0) actualBytes else size
        StreamLogger.d(TAG, "createAudioTrack spec#${spec.serial}: кольцо запрошено $size б "
            + "(${size * 1000L / (rate.toLong() * frameBytes)} мс), выделено $audioTrackBufferSize б "
            + "(${audioTrackBufferSize * 1000L / (rate.toLong() * frameBytes)} мс) @${rate}Гц")
        track?.setVolume(userVolume)   // база; VolumeShaper — множитель поверх
    }

    /**
     * Дорастить пакетный буфер от стартового до [targetSamplesPerChannel].
     *
     * ТОЛЬКО с нити писателя, перед генерацией. prepare() берёт лишь стартовый
     * пакет ([STARTUP_PACKET_SECONDS]) — иначе быстрая смена пресетов держит в
     * памяти несколько буферов полного интервала одновременно и переполняет
     * кучу (замер и цифры — в комментарии к prepare()). Здесь поток уже звучит,
     * он один, старые отпущены — память под полный интервал находится.
     *
     * Публикуем буфер, только если поток ещё PLAYING: releaseInternal()
     * обнуляет [directBuffer], и буфер, опубликованный после релиза, утёк бы.
     * Порядок в releaseInternal() это допускает: stop() переводит lifecycle в
     * STOPPING ещё до обнуления, поэтому проверка надёжна.
     */
    private fun maybeGrowPacketBuffer(rate: Int) {
        if (packetBufferGrown) return
        val target = targetSamplesPerChannel
        if (target <= samplesPerChannel) {
            packetBufferGrown = true          // стартовый и есть целевой
            return
        }
        if (growAttempts >= MAX_GROW_ATTEMPTS) return
        // Поток уже уходит (фейд-аут/стоп) — полный интервал ему не нужен:
        // это экономит десятки мегабайт на каждом прерванном хэндоффе.
        if (lifecycleRef.get() != StreamLifecycle.PLAYING || fadeMode == FadeMode.OUT) return

        // Предохранитель. НЕ смотрим на Runtime.freeMemory(), и вот почему.
        //
        // На ART крупные массивы живут в large object space и по System.gc()
        // НЕ возвращаются: замер на 23049PCD8G — 6 циклов «выделить 32 МБ,
        // отпустить, 2× System.gc() с паузой» дали 34→66→98→130 МБ и ни одного
        // возврата, пока куча не упёрлась в потолок. Обычный byte[] ведёт себя
        // так же, это не особенность allocateDirect. Итого «занято» завышено на
        // десятки МБ уже НЕЖИВОГО мусора, и прежняя проверка
        // (maxMemory − (total − free)) после шторма смен лгала: «нужно 64 МБ,
        // свободно 50 МБ» при 200 МБ мусора. Рост откладывался НАВСЕГДА —
        // поток не дорастал даже со стартовых 2 с, и писатель просыпался
        // ~1800 раз/час вместо ~800. Предохранитель от OOM сам себя запирал.
        //
        // Считаем поэтому по СВОЕЙ бухгалтерии: сколько держат НАШИ потоки,
        // известно точно, а крупные аллокации в приложении — только наши.
        // Мусор ART вернёт под давлением аллокации; не сможет — allocateDirect
        // уполовинит запрос сам (вторая линия обороны, она осталась).
        //
        // ЕДИНСТВЕННАЯ проверка — влазит ли нужное в общий бюджет сверх того,
        // что держат другие потоки. Отдельного «резерва приложению» здесь нет,
        // и это не упущение: резерв уже заложен в сам бюджет. Пакету отводится
        // 86% кучи, остальные 14% (36 МБ на 256 МБ) — это и есть резерв на
        // приложение. Раньше резерв отмерялся сверху (heap/8 = 32 МБ) и при
        // пакете в 86% кучи складывался с ним: 220 + 32 = 252 из 256 МБ, то
        // есть предохранитель молча запрещал ровно ту длину, которую сам же
        // разрешил потолок. Один предел вместо двух — и противоречие снято.
        val neededBytes = target.toLong() * 8
        val heldByOthers = packetsBudgetUsed.get() - packetBudgetCommitted.get()
        if (heldByOthers + neededBytes > globalPacketBudgetBytes()) {
            if (!growDeferredLogged) {
                growDeferredLogged = true
                growDeferredTotal.incrementAndGet()
                StreamLogger.d(TAG, "growPacketBuffer spec#${spec.serial}: отложено — " +
                    "нужно ${neededBytes / (1024 * 1024)}МБ, чужие потоки держат " +
                    "${heldByOthers / (1024 * 1024)}МБ, бюджет пакетов " +
                    "${globalPacketBudgetBytes() / (1024 * 1024)}МБ")
            }
            return
        }
        // Неудачу не считаем попыткой ([growAttempts] не растёт): отпустится
        // чужой буфер — дорастим на следующем пакете.
        growAttempts++
        val grown = allocateDirect(target * 2 * 4, rate) ?: return
        // Между аллокацией и публикацией мог успеть пройти releaseInternal().
        if (lifecycleRef.get() != StreamLifecycle.PLAYING) {
            StreamLogger.d(TAG, "growPacketBuffer spec#${spec.serial}: поток утилизирован " +
                "до публикации — буфер не опубликован (не течёт)")
            return
        }
        val capacitySamples = grown.capacity() / 8
        val minSafeSamples = rate * TRACK_BUFFER_MS / 1000
        if (capacitySamples < minSafeSamples) {
            // Меньше внутреннего буфера трека — не публикуем: писатель не успевал
            // бы подпитывать трек (гарантированный underrun). Остаёмся на
            // стартовом пакете: просыпаемся чаще, но звучим без щелчков.
            packetBufferGrown = true
            StreamLogger.w(TAG, "growPacketBuffer spec#${spec.serial}: дорастить не удалось " +
                "(${capacitySamples * 1000L / rate}мс < ${TRACK_BUFFER_MS}мс) — остаёмся на " +
                "стартовом пакете ${samplesPerChannel * 1000L / rate}мс")
            return
        }
        directBuffer = grown
        samplesPerChannel = capacitySamples
        packetBufferGrown = true
        // Учесть ФАКТ. Важно сделать это именно в момент публикации, а не сразу
        // после allocateDirect(): буфер, выделенный, но не опубликованный
        // (поток успели утилизировать), ничьей памяти не занимает — за него
        // заплатит GC, и в бюджет он не входит.
        commitPacketBudget(grown.capacity().toLong())
        StreamLogger.d(TAG, "growPacketBuffer spec#${spec.serial}: буфер доращен до " +
            "${capacitySamples * 1000L / rate}мс (цель ${target * 1000L / rate}мс, " +
            "провалов аллокации $directAllocateAttempts)")
    }

    /**
     * Записать в общий бюджет фактический объём буфера ЭТОГО потока.
     *
     * Считаем по ФАКТУ ([ByteBuffer.capacity]), а не по запросу: [allocateDirect]
     * при OOM уполовинивает запрос, и учёт по запросу разошёлся бы с реальностью.
     * Идемпотентно: повтор с тем же значением ничего не меняет.
     *
     * Здесь же ведётся счётчик ЖИВЫХ держателей пакета — это и есть измеритель
     * инварианта «не больше двух потоков с пакетом одновременно».
     *
     * И здесь же отпускается храповик выученного потолка: как только под
     * пакеты не занято ничего, предел можно перепроверить — но лишь в том
     * случае, если прошлое опускание было вызвано конкуренцией потоков, а не
     * абсолютным пределом устройства (см. [PacketMemoryBudget.
     * releaseAdaptiveCeilingIfSafe]).
     */
    private fun commitPacketBudget(actualBytes: Long) {
        val prev = packetBudgetCommitted.getAndSet(actualBytes)
        val delta = actualBytes - prev
        if (delta != 0L) {
            val used = packetsBudgetUsed.addAndGet(delta)
            peakPacketsBudgetUsed.getAndUpdate { p -> maxOf(p, used) }
            if (used == 0L) PacketMemoryBudget.releaseAdaptiveCeilingIfSafe()
        }
        if (prev == 0L && actualBytes > 0L) {
            val n = livePacketHolders.incrementAndGet()
            peakPacketHolders.getAndUpdate { p -> maxOf(p, n) }
        } else if (prev > 0L && actualBytes == 0L) {
            livePacketHolders.decrementAndGet()
        }
    }

    private fun allocateDirect(sizeBytes: Int, rateHz: Int): ByteBuffer? {
        val minSize = maxOf(audioTrackBufferSize, rateHz * 2 * 4)
        var size = sizeBytes
        directAllocateAttempts = 0
        while (true) {
            try {
                return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            } catch (e: OutOfMemoryError) {
                directAllocateAttempts++
                // Глобальный счётчик — главный критерий «предел не превышен»:
                // каждый провал означает, что куча ушла в потолок и GC поднят
                // принудительно, даже если в итоге буфер удалось получить.
                oomHalvings.incrementAndGet()
                // Запомнить потолок: `size` не дали, значит просить столько и
                // больше бессмысленно, пока пакетов не останется вовсе.
                //
                // Второй аргумент — сколько пакетной памяти держат ДРУГИЕ
                // потоки: по нему бюджет решает, был провал пределом
                // устройства или конкуренцией. Своё уже закоммиченное
                // вычитаем, иначе при доращивании единственного потока его же
                // собственный стартовый пакет сойдёт за чужую память.
                // См. [PacketMemoryBudget.noteAllocationFailure].
                val otherHeld =
                    (packetsBudgetUsed.get() - packetBudgetCommitted.get()).coerceAtLeast(0L)
                PacketMemoryBudget.noteAllocationFailure(size.toLong(), otherHeld)
                if (size <= minSize) return null
                size = maxOf(minSize, size / 2)
            }
        }
    }

    // ------------------------------------------------------------------ start

    override fun start(onFullyStarted: () -> Unit, shape: FadeShape): Boolean {
        if (!lifecycleRef.compareAndSet(StreamLifecycle.PREPARED, StreamLifecycle.PLAYING)) {
            StreamLogger.w(TAG, "start spec#${spec.serial}: не PREPARED (lc=${lifecycleRef.get()})")
            return false
        }
        val track = audioTrack ?: return false
        fadeMode = FadeMode.IN
        StreamLogger.d(TAG, "start spec#${spec.serial}: shaper($shape) -> play -> writer (именно в этом порядке)")
        return try {
            // ФИКС RC-1. Новый порядок (устраняет стартовый щелчок на 44.1/48 кГц):
            // 1) праймим трек УЖЕ СГЕНЕРИРОВАННЫМ пакетом ДО старта — микшеру
            //    сразу есть данные, окна underrun/пустоты нет;
            // 2) активируем шейпер (множитель ~0) ДО первого рендера;
            // 3) стартуем трек — первый цикл микшера читает УЖЕ ЗАПИСАННЫЕ данные
            //    под нулевой рампой;
            // 4) писатель последним — продолжает со смещения preparedPacketBytes.
            val tApply = System.nanoTime()
            val underrunBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                track.underrunCount else -1
            directBuffer?.let { buf ->
                buf.position(0)
                buf.limit(preparedPacketBytes)
                val written = track.write(buf, preparedPacketBytes, AudioTrack.WRITE_BLOCKING)
                if (written < preparedPacketBytes) {
                    StreamLogger.w(TAG, "start spec#${spec.serial}: prefill $written/$preparedPacketBytes")
                }
            }
            preparedPrefilled = true

            val dur = applyShaper(from = 0f, to = 1f, durationMs = fadeInMs, shape = shape)
            track.play()
            // §E: верификация RC-1 без осциллографа (underrun-окно + позиция).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val du = track.underrunCount - underrunBefore
                val head = track.playbackHeadPosition
                StreamLogger.d(TAG, "start spec#${spec.serial}: RC1 underrunDelta=$du headPos=$head " +
                    "applyToPlayUs=${(System.nanoTime() - tApply) / 1000}")
            }
            writerStarted = true
            writerHandler?.post(::writerLoop)

            val completion = Runnable {
                if (lifecycleRef.get() == StreamLifecycle.PLAYING && fadeMode == FadeMode.IN) {
                    StreamLogger.d(TAG, "start spec#${spec.serial}: fade-in завершён, поток играет")
                    closeShaper()   // кривая на 1.0; возврат к базе userVolume непрерывен
                    fadeMode = FadeMode.NONE
                    onFullyStarted()
                }
            }
            fadeCompletion = completion
            controlHandler.postDelayed(completion, dur + FADE_GUARD_MS)
            true
        } catch (e: Exception) {
            Log.e(TAG, "start() failed: ${e.message}")
            StreamLogger.e(TAG, "start FAILED spec#${spec.serial}: ${e.message}")
            lifecycleRef.set(StreamLifecycle.FAILED)
            return false
        }
    }

    // ------------------------------------------------------------------ stop

    override fun stop(onFullyStopped: () -> Unit, shape: FadeShape) {
        when (lifecycleRef.get()) {
            StreamLifecycle.RELEASED, StreamLifecycle.FAILED -> {
                StreamLogger.d(TAG, "stop spec#${spec.serial}: уже RELEASED/FAILED — мгновенный колбэк")
                onFullyStopped(); return
            }
            // Поток ещё НЕ звучал — утилизация бесшумна (кейс "остановлен в очереди").
            StreamLifecycle.CREATED, StreamLifecycle.PREPARED -> {
                StreamLogger.d(TAG, "stop spec#${spec.serial}: не играл — тихий abort")
                abort(); onFullyStopped(); return
            }
            StreamLifecycle.STOPPING -> { StreamLogger.d(TAG, "stop spec#${spec.serial}: уже STOPPING (идемпотентно)"); return }
            else -> {}
        }
        if (fadeMode == FadeMode.OUT) {
            StreamLogger.d(TAG, "stop spec#${spec.serial}: fade-out уже идёт (идемпотентно)")
            return     // идемпотентность
        }
        if (paused) {
            // МЯГКАЯ ПАУЗА: трек уже остановлен, громкость в нуле, шейпер снят —
            // рампа не нужна (шейпер на приостановленном треке и не пошёл бы).
            // Утилизируем сразу, не растягивая stop на длительность фейда.
            StreamLogger.d(TAG, "stop spec#${spec.serial}: на мягкой паузе — утилизация без рампы")
            cancelFadeCallbacks()
            fadeMode = FadeMode.OUT
            finalizeStop(onFullyStopped)
            return
        }
        fadeMode = FadeMode.OUT
        cancelFadeCallbacks()   // снять фейд-ин, если был

        // Текущее значение рампы: если остановили посреди fade-in — фейд-аут короткий.
        val cur = currentMultiplier()
        val dur = if (cur <= 0.001f) 0L else (fadeOutMs * cur).toLong().coerceAtLeast(40L)
        StreamLogger.d(TAG, "stop spec#${spec.serial}: fade-out($shape) cur=$cur dur=${dur}ms")
        if (dur > 0) {
            applyShaper(from = cur, to = 0f, durationMs = dur, shape = shape)
        } else {
            // Уже в нуле: гасим базу и снимаем активный фейд-ин-шейпер (тишина).
            try { audioTrack?.setVolume(0f) } catch (_: Exception) {}
            closeShaper()
        }
        // Утилизация — через FADE_GUARD_MS после конца рампы, а не ровно в её
        // конце: VolumeShaper отстаёт от расписания (~50 мс), и в «точке тишины»
        // множитель ещё ≈0.31. Снимать трек там — это щелчок на 31 % громкости.
        //
        // РАЗРЫВ ЗВУКА при смене пресета начинается ровно здесь и длится до
        // start() следующего потока: менеджер не создаёт новый поток, пока
        // этот не утилизирован (инвариант «загружен не более одного потока»).
        val completion = Runnable { finalizeStop(onFullyStopped) }
        fadeCompletion = completion
        controlHandler.postDelayed(completion, dur + FADE_GUARD_MS)
    }

    private fun finalizeStop(onFullyStopped: () -> Unit) {
        // ФИКС 1.2. Рампа дошла до 0. ДО закрытия шейпера и паузы гасим базовую
        // громкость: закрытие/окончание шейпера возвращает громкость к базе, а во
        // внутреннем буфере трека ещё до TRACK_BUFFER_MS (10 с) полноамплитудного
        // PCM. Без этого шага
        // финал звучит вспышкой — тот самый хлопок в конце пресета.
        try { audioTrack?.setVolume(0f) } catch (_: Exception) {}

        if (!lifecycleRef.compareAndSet(StreamLifecycle.PLAYING, StreamLifecycle.STOPPING)) {
            onFullyStopped(); return
        }

        // Снимаем трек СРАЗУ, а не в releaseInternal() после опроса латча.
        //
        // pause() прерывает заблокированный write(WRITE_BLOCKING)
        // (mProxy->interrupt): писатель возвращается из записи немедленно, а не
        // доигрывает остаток чанка. WRITE_CHUNK_MS = 8000 мс, а write()
        // разблокируется лишь когда в кольце есть место под ВЕСЬ чанк, то есть
        // писатель может провисеть в нём до ~4.5 с (кольцо 2 МБ ≈ 5.5 с @48 кГц
        // минус маржа 1 с). Всё это время трек живёт в AudioFlinger, а менеджер
        // не создаёт следующий поток до полного релиза — отсюда и «случайная»
        // задержка смены пресета (замер на устройстве: 1.7–4.9 с).
        //
        // Неслышно: к этому моменту громкость уже в нуле (setVolume(0) выше,
        // плюс множитель шейпера), остаток кольца не нужен. pause() идемпотентен,
        // повторный вызов в releaseInternal() безвреден.
        try { audioTrack?.pause() } catch (_: Exception) {}

        // Писатель выходит не дольше одного чанка; ждём неблокирующим опросом на актёре.
        // Всё это время трек рендерит тишину (база 0 и/или множитель 0).
        // Дедлайн чуть больше полного ожидания в releaseInternal (WRITER_EXIT_WAIT_MS):
        // при нормальном выходе латч снимается здесь, и releaseInternal не блокирует
        // актёр повторным await.
        val deadline = System.currentTimeMillis() + WRITER_EXIT_WAIT_MS + 500L
        val poll = object : Runnable {
            override fun run() {
                if (writerExitLatch.count == 0L || System.currentTimeMillis() > deadline) {
                    releaseInternal()
                    onFullyStopped()
                } else {
                    controlHandler.postDelayed(this, 60L)
                }
            }
        }
        controlHandler.post(poll)
    }

    // ------------------------------------------------------------------ reverse (pause→resume)

    override fun reverseFadeToPlaying(onFullyStarted: () -> Unit): Boolean {
        if (lifecycleRef.get() != StreamLifecycle.PLAYING || fadeMode != FadeMode.OUT) {
            StreamLogger.w(TAG, "reverseFadeToPlaying spec#${spec.serial}: неприменимо (lc=${lifecycleRef.get()}, fadeMode=$fadeMode)")
            return false
        }
        val cur = currentMultiplier()
        fadeMode = FadeMode.IN
        cancelFadeCallbacks()   // отменить утилизацию и точку тишины
        val dur = (fadeInMs * (1f - cur)).toLong().coerceAtLeast(40L)
        StreamLogger.d(TAG, "reverseFadeToPlaying spec#${spec.serial}: разворот cur=$cur dur=${dur}ms")
        applyShaper(from = cur, to = 1f, durationMs = dur)
        val completion = Runnable {
            if (lifecycleRef.get() == StreamLifecycle.PLAYING && fadeMode == FadeMode.IN) {
                StreamLogger.d(TAG, "reverseFadeToPlaying spec#${spec.serial}: возобновлено")
                closeShaper(); fadeMode = FadeMode.NONE; onFullyStarted()
            }
        }
        fadeCompletion = completion
        controlHandler.postDelayed(completion, dur + FADE_GUARD_MS)
        return true
    }

    // ------------------------------------------------------------------ мягкая пауза

    override fun pause(onPaused: () -> Unit, shape: FadeShape): Boolean {
        if (lifecycleRef.get() != StreamLifecycle.PLAYING) {
            StreamLogger.w(TAG, "pause spec#${spec.serial}: не PLAYING (lc=${lifecycleRef.get()})")
            return false
        }
        if (paused) {
            StreamLogger.d(TAG, "pause spec#${spec.serial}: уже на паузе (идемпотентно)")
            return true
        }
        StreamLogger.d(TAG, "pause spec#${spec.serial}: мягкая пауза, буфер сохраняется (fadeMode=$fadeMode)")

        // Идущий фейд (например кроссфейд переключения) перехватываем: его
        // финалом была утилизация, теперь — заморозка. Отменять рампу НЕЛЬЗЯ:
        // пауза посреди громкого участка дала бы щелчок.
        cancelFadeCallbacks()

        if (fadeMode != FadeMode.OUT) {
            fadeMode = FadeMode.OUT
            val cur = currentMultiplier()
            val dur = if (cur <= 0.001f) 0L else (fadeOutMs * cur).toLong().coerceAtLeast(40L)
            if (dur > 0) {
                applyShaper(from = cur, to = 0f, durationMs = dur, shape = shape)
            } else {
                // Уже в нуле: гасим базу и снимаем шейпер (тишина без рампы).
                try { audioTrack?.setVolume(0f) } catch (_: Exception) {}
                closeShaper()
            }
        }
        val completion = Runnable { finalizePause(onPaused) }
        fadeCompletion = completion
        controlHandler.postDelayed(completion, fadeOutMs + FADE_GUARD_MS)
        return true
    }

    /**
     * Финал мягкой паузы. ПОРЯДОК КРИТИЧЕН:
     *   1) флаг парковки ДО pause() трека;
     *   2) pause() трека — прерывает заблокированный write() (mProxy->interrupt),
     *      поэтому писатель гарантированно выйдет из записи с сохранённым
     *      смещением, а недописанный остаток пакета НЕ теряется;
     *   3) тишина фиксируется базой 0 + снятым шейпером;
     *   4) указатель графика замирает на слышимой позиции.
     * Ресурсы остаются живы: буфер, движок, трек, фазы.
     */
    private fun finalizePause(onPaused: () -> Unit) {
        if (lifecycleRef.get() != StreamLifecycle.PLAYING) {
            StreamLogger.d(TAG, "finalizePause spec#${spec.serial}: поток уже не PLAYING — resources released")
            onPaused()
            return
        }
        paused = true
        try { audioTrack?.pause() } catch (e: Exception) {
            StreamLogger.e(TAG, "finalizePause spec#${spec.serial}: pause failed: ${e.message}")
        }
        wakeWriter()

        // База в нуль ДО закрытия шейпера: во внутреннем буфере трека ещё до
        // TRACK_BUFFER_MS (10 с) полноамплитудного PCM, и закрытие вернуло бы
        // их на полную. Мера та же, что и при 3 с, — просто запас больше.
        try { audioTrack?.setVolume(0f) } catch (_: Exception) {}
        closeShaper()
        fadeMode = FadeMode.NONE
        cancelFadeCallbacks()

        // Слышимая позиция — по голове воспроизведения, а не по UI-часам:
        // пауза любой длительности не сдвинет ни график, ни точку возобновления.
        val audible = audibleCurveSeconds()
        audible?.let { nativeEngine?.freezeUiTimelineAt(it) }

        StreamLogger.d(TAG, "finalizePause spec#${spec.serial}: ЗАМОРОЖЕН audible=$audible " +
            "generatedFrames=$generatedFrames head=${audioTrack?.playbackHeadPosition}")
        onPaused()
    }

    override fun resume(onFullyStarted: () -> Unit, shape: FadeShape): Boolean {
        if (lifecycleRef.get() != StreamLifecycle.PLAYING) {
            StreamLogger.w(TAG, "resume spec#${spec.serial}: не PLAYING (lc=${lifecycleRef.get()})")
            return false
        }
        if (!paused) {
            StreamLogger.w(TAG, "resume spec#${spec.serial}: не на паузе — нечего возобновлять")
            return false
        }
        cancelFadeCallbacks()
        fadeMode = FadeMode.IN

        // Позиция графика размораживается ДО play(): указатель продолжает ровно
        // с замороженной слышимой точки и идёт по wall-clock до фронтира пакета.
        val audible = audibleCurveSeconds()
        audible?.let { nativeEngine?.resumeUiTimelineFrom(it) }

        // Порядок как при старте (фикс RC-1): рампа → play → писатель. Первый
        // кадр после play() уходит под нулевым множителем — сохранённый
        // полноамплитудный остаток не даёт щелчка.
        applyShaper(from = 0f, to = 1f, durationMs = fadeInMs, shape = shape)
        try {
            audioTrack?.play()
        } catch (e: Exception) {
            StreamLogger.e(TAG, "resume spec#${spec.serial}: play failed: ${e.message}")
            fadeMode = FadeMode.NONE
            return false
        }
        paused = false
        wakeWriter()

        StreamLogger.d(TAG, "resume spec#${spec.serial}: ПРОДОЛЖЕН с audible=$audible " +
            "generatedFrames=$generatedFrames")
        val completion = Runnable {
            if (lifecycleRef.get() == StreamLifecycle.PLAYING && fadeMode == FadeMode.IN) {
                closeShaper()
                fadeMode = FadeMode.NONE
                onFullyStarted()
            }
        }
        fadeCompletion = completion
        controlHandler.postDelayed(completion, fadeInMs + FADE_GUARD_MS)
        return true
    }

    /**
     * Слышимая позиция кривой (секунды суток) по позиции головы воспроизведения.
     *
     * Это мост между осью AudioTrack (кадры) и осью кривой (секунды суток).
     * UI-экстраполяция для этой цели не годится: она ограничена концом
     * сгенерированного пакета и обновляется только по опросу, поэтому на
     * паузе (когда опрос остановлен) даёт устаревшее значение.
     */
    private fun audibleCurveSeconds(): Float? {
        val eng = nativeEngine ?: return null
        val track = audioTrack ?: return null
        val head = try { track.playbackHeadPosition } catch (_: Exception) { -1 }
        if (head < 0) return null
        return eng.getAudibleTimeSeconds(head.toLong(), generatedFrames)
    }

    override fun getAudibleTimeOfDaySeconds(): Int {
        val audible = audibleCurveSeconds()?.toInt() ?: getCurrentTimeOfDay()
        // Нативная сторона уже нормализовала время в [0, 86400); страхуем
        // диапазон на случай гонки счётчиков кадров.
        return ((audible % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY
    }

    override fun setPlaybackStartTime(anchorMs: Long) {
        nativeEngine?.setPlaybackStartTime(anchorMs)
    }

    // ------------------------------------------------------------------ volume / shaper

    override fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        StreamLogger.d(TAG, "setVolume spec#${spec.serial} -> $userVolume")
        // ФИКС: во время fade-out база зафиксирована (в финале принудительно 0).
        // Движение слайдера не должно поднимать громкость затухающего потока.
        if (fadeMode != FadeMode.OUT && lifecycleRef.get() == StreamLifecycle.PLAYING) {
            try { audioTrack?.setVolume(userVolume) } catch (_: Exception) {}
        }
    }

    /**
     * Создать и запустить шейпер БЕЗ разрыва громкости (фикс 1.3).
     *
     * Если активного шейпера нет (старт/стоп из RUNNING): рампа [from]->[to] по базе
     * userVolume. Если активен старый (стоп посреди fade-in): ДО закрытия старого
     * шейпера база приводится к ТЕКУЩЕЙ эффективной громкости (userVolume*from) —
     * закрытие возвращает громкость к базе без скачка, а новая рампа 1 -> to/from
     * стартует ровно с достигнутого уровня. Итоговая громкость непрерывна.
     */
    /**
     * Создать и запустить шейпер БЕЗ разрыва громкости (фикс 1.3).
     *
     * Если активного шейпера нет: рампа [from]->[to] формы [shape] по базе userVolume.
     * Если активен старый (стоп посреди fade-in): ФИКС 1.3 — база приводится к текущей
     * эффективной громкости, закрытие без скачка, новая ЛИНЕЙНАЯ рампа 1 -> to/from
     * (замена шейпера всегда линейна, чтобы не ломать непрерывность при прерывании
     * кроссфейда).
     */
    private fun applyShaper(from: Float, to: Float, durationMs: Long, shape: FadeShape = FadeShape.LINEAR): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            audioTrack?.setVolume(if (to > 0f) userVolume else 0f)
            return 0L
        }
        // Координаты аварийной рампы. Заполняются по ходу основного пути, чтобы
        // fallback (см. catch) стартовал ровно с той же базы и той же пары
        // значений — иначе аварийный путь сделал бы скачок там, где основной
        // путь его старательно избежал.
        var fbFrom = from.coerceIn(0f, 1f)
        var fbTo = to.coerceIn(0f, 1f)
        return try {
            val old = volumeShaper
            if (old != null) {
                // --- ФИКС 1.3: замена без разрыва, форма согласована с [shape] ---
                // База снижается ДО close() до текущей эффективной громкости, поэтому
                // возврат к базе при закрытии не даёт вспышки на полную громкость.
                // Новый шейпер сидит на редуцированной базе (base = from*userVolume),
                // поэтому его ординаты = огибающая / from — гасит в запрошенной форме
                // (для EQUAL_POWER и to=0 это from*cos(p*π/2), согласованно с sin у NEXT).
                // Уплотнение: живой множитель читаем максимально близко к close() —
                // пока старый шейпер ещё растёт (стоп посреди fade-in), переданный
                // [from] устаревает, а база = userVolume*from дала бы микропровал ε.
                val live = try { old.volume } catch (_: Exception) { from }
                val fromC = live.coerceIn(0f, 1f)
                val base = userVolume * fromC
                audioTrack?.setVolume(base)
                try { old.close() } catch (_: Exception) {}
                volumeShaper = null

                val (times, envVols) = buildCurve(fromC, to, shape)
                val shaperVols = FloatArray(envVols.size) { i ->
                    if (fromC > 0.001f) (envVols[i] / fromC).coerceAtLeast(0f)
                    else envVols[i].coerceAtLeast(0f)
                }
                fbFrom = shaperVols[0]
                fbTo = shaperVols[shaperVols.size - 1]

                val cfg = VolumeShaper.Configuration.Builder()
                    .setDuration(durationMs)
                    .setCurve(times, shaperVols)
                    .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                    .build()
                volumeShaper = audioTrack?.createVolumeShaper(cfg)
                volumeShaper?.apply(VolumeShaper.Operation.PLAY)
            } else {
                audioTrack?.setVolume(userVolume)
                val (times, vols) = buildCurve(from, to, shape)
                val cfg = VolumeShaper.Configuration.Builder()
                    .setDuration(durationMs)
                    .setCurve(times, vols)
                    .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                    .build()
                volumeShaper = audioTrack?.createVolumeShaper(cfg)
                volumeShaper?.apply(VolumeShaper.Operation.PLAY)
            }
            durationMs
        } catch (e: Exception) {
            // ФИКС. Раньше любая ошибка шейпера означала мгновенный
            // audioTrack.setVolume(to > 0 ? userVolume : 0) — то есть СКАЧОК
            // громкости посреди кроссфейда, тот самый щелчок, который слышен
            // как «прерывание». Теперь сначала пробуем минимальную 2-точечную
            // линейную рампу: её обязана принимать любая реализация с API 26,
            // и она сохраняет непрерывность (терпим только форму, не разрыв).
            // Жёсткая установка громкости — последний резерв.
            Log.e(TAG, "VolumeShaper failed: ${e.message}")
            StreamLogger.e(TAG, "VolumeShaper failed: ${e.message} (from=$from to=$to shape=$shape)")
            if (tryLinearFallback(fbFrom, fbTo, durationMs)) {
                StreamLogger.w(TAG, "VolumeShaper: аварийная линейная рампа ${fbFrom}->${fbTo} за ${durationMs}мс")
                durationMs
            } else {
                Log.e(TAG, "VolumeShaper: fallback тоже отказал — жёсткая установка громкости")
                StreamLogger.e(TAG, "VolumeShaper: fallback отказал — жёсткая установка громкости (скачок неизбежен)")
                audioTrack?.setVolume(if (to > 0f) userVolume else 0f)
                0L
            }
        }
    }

    /**
     * Аварийная рампа: 2-точечная ЛИНЕЙНАЯ кривая на УЖЕ установленной базе.
     *
     * Значения [fbFrom]/[fbTo] берутся из системы координат шейпера, который
     * не удалось создать, — поэтому базу НЕ трогаем: эффективная громкость
     * остаётся непрерывной, меняется только форма (sin/cos → прямая).
     */
    private fun tryLinearFallback(fbFrom: Float, fbTo: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (durationMs <= 0L) return false
        return try {
            closeShaper()
            val cfg = VolumeShaper.Configuration.Builder()
                .setDuration(durationMs)
                .setCurve(
                    floatArrayOf(0f, 1f),
                    floatArrayOf(fbFrom.coerceIn(0f, 1f), fbTo.coerceIn(0f, 1f))
                )
                .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                .build()
            volumeShaper = audioTrack?.createVolumeShaper(cfg)
            volumeShaper?.apply(VolumeShaper.Operation.PLAY)
            volumeShaper != null
        } catch (e: Exception) {
            StreamLogger.e(TAG, "VolumeShaper linear fallback failed: ${e.message}")
            false
        }
    }

    /**
     * Кривые огибающей. EQUAL_POWER: нарастание = sin(p·π/2), затухание = cos(p·π/2);
     * sin²+cos² = 1 — постоянная энергия в окне кроссфейда двух потоков.
     *
     * n = 15, то есть 16 точек: это потолок, который AudioFlinger принимает без
     * отказа на всех известных реализациях (17 точек уже упиралось в лимит и
     * бросало из createVolumeShaper — отказ означал мгновенный setVolume, то
     * есть щелчок посреди кроссфейда). Между соседними точками шейпер
     * интерполирует линейно (~17 мс при 250 мс) — на слух неотличимо.
     */
    private fun buildCurve(from: Float, to: Float, shape: FadeShape): Pair<FloatArray, FloatArray> {
        val f = from.coerceIn(0f, 1f)
        val t = to.coerceIn(0f, 1f)
        if (shape == FadeShape.LINEAR) {
            return floatArrayOf(0f, 1f) to floatArrayOf(f, t)
        }
        val n = 15
        val times = FloatArray(n + 1)
        val vols = FloatArray(n + 1)
        val rampUp = t > f
        for (i in 0..n) {
            val p = i.toFloat() / n
            times[i] = p
            // Кламп ОБЯЗАТЕЛЕН, а не «на всякий случай»: cos(π/2) в одинарной
            // точности равен -4.37e-8, а не ровно 0. VolumeShaper принимает
            // кривую только целиком и только из [0,1] — одна отрицательная
            // точка роняет createVolumeShaper с
            //   "volumes for linear scale must be between 0.f and 1.f"
            // (на устройстве: check index 15 — последняя точка затухания).
            // Раньше это молча уводило equal-power кроссфейд на аварийную
            // линейную рампу, то есть на провал ~3 дБ в середине перекрытия.
            // Погрешность 4e-8 не влияет ни на слух, ни на энергию
            // (sin²+cos²=1 сохраняется), поэтому клампим, а не округляем.
            vols[i] = if (rampUp) {
                t * kotlin.math.sin(p * (Math.PI.toFloat() / 2f))
            } else {
                f * kotlin.math.cos(p * (Math.PI.toFloat() / 2f))
            }.coerceIn(0f, 1f)
        }
        return times to vols
    }

    private fun currentMultiplier(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { volumeShaper?.volume?.let { return it.coerceIn(0f, 1f) } } catch (_: Exception) {}
        }
        return if (fadeMode == FadeMode.IN) 0f else 1f
    }

    private fun closeShaper() {
        try { volumeShaper?.close() } catch (_: Exception) {}
        volumeShaper = null
    }

    // ------------------------------------------------------------------ abort / release

    override fun abort() {
        val lc = lifecycleRef.get()
        if (lc == StreamLifecycle.CREATED || lc == StreamLifecycle.PREPARED || lc == StreamLifecycle.FAILED) {
            StreamLogger.d(TAG, "abort spec#${spec.serial} lc=$lc (ни разу не играл — тихо)")
            releaseInternal()   // трек ни разу не играл — тишина гарантирована
        }
    }

    private fun releaseInternal() {
        StreamLogger.d(TAG, "releaseInternal spec#${spec.serial} lc=${lifecycleRef.get()} paused=$paused")

        // Писатель мог быть припаркован паузой: без побудки он не заметит ни
        // смены lifecycle, ни освобождения трека и провисит до первого polling.
        paused = false
        wakeWriter()

        // Снятие трека — ДО ожидания писателя, а не после.
        //
        // pause() прерывает заблокированный write(WRITE_BLOCKING)
        // (mProxy->interrupt): писатель возвращается из write() немедленно, а не
        // доигрывает остаток WRITE_CHUNK_MS (до 8 с). Порядок критичен: при
        // прежнем порядке (await -> pause/stop/release) латч дёргался только по
        // остатку чанка, и releaseInternal растягивался на 8-9.5 с, удерживая на
        // нити актёра и onStreamFullyStopped, и старт следующего потока — ровно
        // та самая «пауза» при переключении пресета. Попутно это освобождает
        // разделяемую память трека в AudioFlinger до создания следующего: без
        // этого на 48 кГц второй трек не создавался (createTrack_l -12).
        //
        // Неслышно: к этому моменту громкость уже в нуле (setVolume(0) в
        // finalizeStop, база и множитель шейпера), остаток кольца не нужен.
        try { audioTrack?.pause() } catch (_: Exception) {}

        // ФИКС №1 (старый краш, SIGABRT destroyed mutex): движок разрешено
        // уничтожать только после выхода писателя.
        //
        // ФИКС №2 (новый краш, SIGSEGV в vector::__assign_with_size внутри
        // generateAudioBuffer): если писатель НЕ вышел за таймаут, удалять
        // движок ЗДЕСЬ НЕЛЬЗЯ — писатель всё ещё внутри него (застрял в
        // track.write(WRITE_BLOCKING)). Владение передаётся писателю: он
        // гарантированно освободит движок в своём finally, и только потом
        // задёрнет латч. Любой наш нативный релиз здесь был бы гонкой с живым
        // потоком внутри движка → use-after-free.
        var engineOwnedByWriter = false
        if (writerStarted && writerExitLatch.count > 0L) {
            // Грейс-фаза, а не полный WRITER_EXIT_WAIT_MS: трек уже снят
            // (pause() выше разблокировал write()), поэтому писателю нужно
            // только дойти до проверки lifecycle или вернуться из генерации
            // пакета. Блокировать нить актёра на секунды нельзя — на ней висят
            // таймеры фейдов, повышение NEXT и старт следующего потока.
            // Нормальный путь сюда вообще не заходит: finalizeStop() опрашивает
            // латч заранее и зовёт releaseInternal уже по нулевому счётчику.
            try {
                writerExitLatch.await(WRITER_HANDOFF_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (writerExitLatch.count > 0L) {
                // Писатель жив: не трогаем движок — он освободит его сам при
                // выходе (pause/stop/release трека ниже разблокируют write()).
                engineOwnedByWriter = true
                StreamLogger.w(TAG, "releaseInternal spec#${spec.serial}: писатель не вышел " +
                    "за ${WRITER_HANDOFF_GRACE_MS}мс — движок освобождает сам писатель " +
                    "(consumed=$writerConsumedEngine)")
            }
        }

        closeShaper()
        // pause() уже выполнен выше (до await) — именно он разблокировал писателя.
        // stop()/release() снимают трек окончательно; оба идемпотентны.
        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null

        if (!engineOwnedByWriter) {
            // Нормальный путь: писатель вышел (или не стартовал) — движок
            // никто не использует. stop() идемпотентен; релиз атомарен
            // (getAndSet(0) в Kotlin-обёртке → ровно одна деструкция).
            try { nativeEngine?.stop() } catch (_: Exception) {}
            try { nativeEngine?.release() } catch (_: Exception) {}
            nativeEngine = null
        } else {
            // Писатель жив и владеет движком. Свою Kotlin-ссылку оставляем
            // нетронутой (объект-обёртка доживёт у писателя в локальной
            // переменной), нативный релиз НЕ вызываем — иначе UAF.
            StreamLogger.d(TAG, "releaseInternal spec#${spec.serial}: нативный релиз " +
                "отложен до выхода писателя")
        }
        writerThread?.quitSafely()
        writerThread = null
        directBuffer = null
        // Вернуть долю в общий бюджет ВМЕСТЕ с обнулением буфера. Без этого
        // счётчик [packetsBudgetUsed] только рос: после нескольких хэндоффов
        // бюджет считался бы выбранным навсегда и ни один поток больше не
        // доращивал бы пакет.
        commitPacketBudget(0)
        lifecycleRef.set(StreamLifecycle.RELEASED)
    }

    // ------------------------------------------------------------------ writer

    /**
     * Припарковать писателя на паузе. Ожидание с таймаутом: даже пропущенный
     * notify не подвесит поток — он сам проверит паузу и выход из цикла.
     */
    private fun parkWriter() {
        synchronized(parkLock) {
            if (!paused || lifecycleRef.get() != StreamLifecycle.PLAYING) return
            try {
                parkLock.wait(PARK_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Разбудить писателя (снятие паузы, stop, release). */
    private fun wakeWriter() {
        synchronized(parkLock) { parkLock.notifyAll() }
    }

    private fun writerLoop() {
        StreamLogger.d(TAG, "writerLoop start spec#${spec.serial} sr=${spec.sampleRate.value} intervalMs=$bufferIntervalMs")
        // ВНЕШНИЙ finally гарантирует взвод латча ЛЮБЫМ путём выхода
        // (включая ранние return при нулевых ссылках) — иначе релиз такого
        // стрима всегда выгорал бы полный таймаут.
        try {
            try {
                val track = audioTrack ?: return
                val engine = nativeEngine ?: return
                var packetBytes = preparedPacketBytes
                // ФИКС RC-1: если пакет уже записан в start() (прайминг), стартуем
                // со смещения, чтобы не дублировать и не оставлять трек без данных.
                var offset = if (preparedPrefilled) preparedPacketBytes else 0

                // Верхняя граница чанка записи — инвариант подпитки.
                // write(WRITE_BLOCKING) разблокируется, когда в кольце трека
                // есть место под ВЕСЬ чанк, то есть заполненность упала до
                // `buffer - chunk`. Значит ровно столько аудио и остаётся
                // проиграть, если писатель встанет: это и есть запас до underrun.
                // Держим его не меньше MIN_WRITE_MARGIN_MS, считая от
                // ФАКТИЧЕСКОГО размера кольца (HAL вправе урезать запрошенный).
                // Период пробуждений = длительность чанка, поэтому этот же
                // предел задаёт и частоту wakeups: 3600/8 = 450 в час.
                val rate = spec.sampleRate.value.toLong()
                val targetChunk = rate * frameBytes * WRITE_CHUNK_MS / 1000
                val marginBytes = rate * frameBytes * MIN_WRITE_MARGIN_MS / 1000
                val byMargin = audioTrackBufferSize.toLong() - marginBytes
                val maxChunkBytes =
                    if (byMargin >= frameBytes.toLong()) minOf(targetChunk, byMargin)
                    // Вырожденное кольцо (меньше маржи + одного кадра): пишем
                    // половиной кольца — иначе write() не разблокируется вовсе.
                    else maxOf(audioTrackBufferSize.toLong() / 2, frameBytes.toLong())
                while (lifecycleRef.get() == StreamLifecycle.PLAYING) {
                    // ПАУЗА: парковка ДО генерации и ДО записи.
                    //   * генерация запрещена — она продвинула бы фронтир кривой
                    //     вперёд относительно звучащего участка (и пакет, ради
                    //     которого всё затевалось, пришлось бы выбросить);
                    //   * запись в приостановленный трек заблокировала бы
                    //     WRITE_BLOCKING до снятия паузы.
                    // packetBytes/offset — локальные, поэтому остаток пакета и
                    // смещение в нём переживают паузу целиком: возобновление
                    // дописывает ровно тот же буфер с того же места.
                    if (paused) {
                        parkWriter()
                        continue
                    }
                    if (offset >= packetBytes) {
                        // Дорастить буфер до полного интервала генерации. Здесь,
                        // а не в prepare(): на prepare() это десятки мегабайт на
                        // каждый поток, а при быстрой смене пресетов потоков
                        // несколько — куча переполнялась и процесс убивался.
                        maybeGrowPacketBuffer(spec.sampleRate.value)
                        // Буфер мог быть отдан ДОСРОЧно ([releasePacketBuffer] —
                        // поток уже в тишине и утилизируется): тогда выходим,
                        // а не генерируем в освобождённую память.
                        val buf = directBuffer ?: break
                        val want = samplesPerChannel
                        if (want <= 0) break
                        buf.clear()
                        val generated = engine.generateBufferDirect(buf, want)
                        if (generated <= 0) {
                            StreamLogger.e(TAG, "writerLoop spec#${spec.serial}: generate failed=$generated")
                            onRuntimeError(this, "generate failed: $generated")
                            break
                        }
                        packetBytes = generated * 2 * 4
                        offset = 0
                        generatedFrames += generated.toLong()
                    }
                    val buf = directBuffer ?: break
                    val chunk = minOf((packetBytes - offset).toLong(), maxChunkBytes).toInt()
                    buf.position(offset)
                    buf.limit(offset + chunk)
                    val written = track.write(buf, chunk, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        // Плановая утилизация: releaseInternal() снимает трек
                        // (pause()) ДО ожидания писателя, и заблокированный
                        // write() возвращается с ошибкой. Это штатный выход, а не
                        // отказ трека — иначе любой stop во время чанка уводил бы
                        // автомат в handleRuntimeError и рвал воспроизведение.
                        // Проверка идёт ПЕРВОЙ: lifecycle уже STOPPING/RELEASED.
                        if (lifecycleRef.get() != StreamLifecycle.PLAYING) {
                            StreamLogger.d(TAG, "writerLoop spec#${spec.serial}: write прерван утилизацией " +
                                "(lc=${lifecycleRef.get()}, written=$written)")
                            break
                        }
                        // Мягкая пауза: track.pause() прерывает заблокированный
                        // write() (mProxy->interrupt): это НЕ ошибка и НЕ потеря
                        // данных — кадры, не принятые треком, остаются в пакете
                        // по offset.
                        if (paused) {
                            StreamLogger.d(TAG, "writerLoop spec#${spec.serial}: write прерван паузой " +
                                "(пакет сохранён, offset=$offset/$packetBytes)")
                            continue
                        }
                        StreamLogger.e(TAG, "writerLoop spec#${spec.serial}: write failed=$written")
                        onRuntimeError(this, "write failed: $written")
                        break
                    }
                    offset += written
                }
            } finally {
                // Писатель сам хоронит движок, которым пользовался.
                // ПОРЯДОК КРИТИЧЕН:
                //   1) релиз движка (атомарный getAndSet(0) → ровно одна деструкция);
                //   2) флаг передачи владения;
                //   3) взвод латча.
                // Наблюдатель, увидевший латч==0, гарантированно видит и
                // завершённый релиз, и флаг (volatile + happens-before латча).
                val eng = nativeEngine
                if (eng != null) {
                    try { eng.release() } catch (_: Exception) {}
                    writerConsumedEngine = true
                }
                StreamLogger.d(TAG, "writerLoop exit spec#${spec.serial} " +
                    "(lc=${lifecycleRef.get()}, engineConsumed=${eng != null})")
            }
        } finally {
            writerExitLatch.countDown()
        }
    }

    // ------------------------------------------------------------------ getters

    override fun getElapsedSeconds(): Int = nativeEngine?.getElapsedSeconds() ?: 0
    override fun getCurrentTimeOfDay(): Int = nativeEngine?.getCurrentTimeOfDay() ?: 0

    // ФИКС 3. Непрерывность при сквозном переключении: менеджер читает живые
    // координаты старого потока, чтобы новый стартовал ровно с того же места.
    fun getElapsedMs(): Long = (nativeEngine?.getElapsedSeconds() ?: 0) * 1000L
    fun getCurrentCurveTimeSeconds(): Float = nativeEngine?.getCurrentTimeOfDay()?.toFloat() ?: 0f
    // ФИКС RC-2: живые фазы несущих для бесшовного кроссфейда.
    fun getPhases(): Pair<Float, Float>? {
        val p = nativeEngine?.getCurrentPhases() ?: return null
        return if (p.size == 2) Pair(p[0], p[1]) else null
    }

    override fun isChannelsSwapped(): Boolean = nativeEngine?.isChannelsSwapped() ?: false
    override fun getFrequenciesAtCurrentTime(): Pair<Float, Float>? =
        nativeEngine?.getFrequenciesAtCurrentTime()
}
