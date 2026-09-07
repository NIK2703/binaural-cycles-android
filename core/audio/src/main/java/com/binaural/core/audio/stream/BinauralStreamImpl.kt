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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BinauralStreamImpl(
    private val context: Context,
    spec: PlaybackSpec,
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

    /**
     * Спек потока МУТАБЕЛЕН — и это принципиально, а не небрежность.
     *
     * Раньше спек был неизменным снимком: любое изменение настроек означало
     * новый поток, то есть ВТОРОЙ AudioTrack, второй движок, второй пакет и
     * весь ритуал «приседания» вокруг них (включая мониторинг тишины, из-за
     * которого и начался этот разбор). Теперь смена настроек применяется НА
     * ЖИВОМ потоке ([retune]), и спек обязан отражать то, что звучит сейчас.
     *
     * Volatile: пишет нить писателя ([doRetuneOnWriter]), читает актёр.
     */
    @Volatile private var specState = spec
    override val spec: PlaybackSpec get() = specState

    companion object {
        private const val TAG = "BinauralStream"
        const val DEFAULT_FADE_MS = 250L
        /**
         * Режим фейд-ина старта: false = Решение A (VolumeShaper, наверху не
         * закрываем), true = Решение B (огибающая запечена в PCM первого
         * пакета, шейпер на старте не создаётся). Отладочный переключатель;
         * оба режима закрывают стык, B — страховка для патологических
         * реализаций шейпера.
         */
        private const val DATA_BAKED_FADE_IN = false
        /**
         * ГАРАНТИЯ, А НЕ ИЗМЕРЕНИЕ.
         *
         * Рампа VolumeShaper идёт в масштабе реального времени микшера, поэтому
         * через `длительность + RAMP_SETTLE_MARGIN_MS` после `apply()` она либо
         * дошла до цели и удерживает её, либо микшер подставлял тишину
         * (underrun) — а для fade-out это тот же ноль. Маржа покрывает только
         * задержку СТАРТА рампы (квант микшера), а не её ход.
         *
         * Отсюда главное: завершение рампы НЕ опрашивается. Прежний опрос
         * живого множителя каждые 20 мс с двумя эскалирующими пределами был
         * попыткой узнать то, что узнать точно нельзя: у VolumeShaper нет
         * колбэка завершения, а `getVolume()` отвечает с лагом, зависящим от
         * устройства. Всё, что решалось по результату опроса, теперь либо не
         * нужно вовсе (закрытие шейпера — см. [applyShaper]), либо отложено
         * ровно на эту константу.
         *
         * Цена маржи — несколько десятков мс лишней тишины на fade-out. Ошибка
         * в меньшую сторону при марже не меньше кванта микшера невозможна.
         */
        private const val RAMP_SETTLE_MARGIN_MS = 80L
        /**
         * Порог «уже в нуле» — только для выбора ДЛИТЕЛЬНОСТИ рампы, не для
         * вывода о её завершении.
         */
        private const val FADE_ZERO_EPSILON = 0.002f
        /** Байт в кадре: стерео × ENCODING_PCM_FLOAT. */
        private const val frameBytes = 2 * 4
        /**
         * Внутренний буфер (кольцо) AudioTrack — сколько PCM может лежать в треке
         * впереди слышимой позиции.
         *
         * Байтового потолка больше НЕТ. Он существовал
         * (`MAX_TRACK_BUFFER_BYTES = 2 МиБ`, снят в §5.4.5), потому что
         * одновременно жили ДВА трека (CURRENT+NEXT при кроссфейде), а
         * разделяемая память треков выделяется из ОДНОЙ кучи клиента в
         * AudioFlinger (MemoryDealer, `dumpsys media.audio_flinger` → Clients:
         * heap_size, типовое значение ~7 МиБ). Замер на POCO 23049PCD8G
         * (Android 13, 48 кГц): запрос кольца 3 МиБ оборачивался аллокацией
         * 4 МиБ, и второй трек не создавался —
         *
         *     E AF::TrackBase: TrackBase(58): not enough memory for AudioTrack
         *                     size=4194536
         *     E AudioFlinger: createTrack_l() initCheck failed -12
         *
         * С тех пор как смена пресета идёт через [retune] на ОДНОМ треке,
         * второго трека не существует: единственный оставшийся случай
         * пересоздания ([recreateTrack], смена SR) снимает старый трек ДО
         * создания нового. Значит ограничение можно снять: на 48 кГц кольцо
         * снова полные 10 с = 3.84 МиБ вместо урезанных 5.46 с.
         *
         * Что это даёт: чанк записи перестаёт коллапсировать в
         * `кольцо − UNDERRUN_HEADROOM_MS` и держится на WRITE_CHUNK_MS = 8 с,
         * то есть пробуждения писателя падают с ~1040/час до 450/час на 44.1 и
         * 48 кГц. На младших SR потолок и так не работал (10 с = 1.76 МиБ при
         * 22.05 кГц), поэтому там поведение не меняется.
         *
         * Риск: если куча клиента AudioFlinger на каком-то устройстве меньше
         * ~4 МиБ, трек не создаётся вовсе (prepare() ловит Throwable и
         * возвращает false). minBuffer — нижняя граница HAL, её не роняем.
         */
        private const val TRACK_BUFFER_MS = 10000         // внутренний буфер AudioTrack
        /**
         * Гранулярность записи — и она же определяет, как часто просыпается
         * писатель. write(WRITE_BLOCKING) возвращается, только когда в кольце
         * трека есть место под ВЕСЬ чанк, то есть заполненность упала до
         * `buffer - chunk`; в установившемся режиме период между записями
         * равен ровно длительности чанка:
         *
         *     пробуждений в час = 3_600_000 / реальный_чанк
         *     реальный_чанк = min(WRITE_CHUNK_MS, кольцо_трека − UNDERRUN_HEADROOM_MS)
         *
         * Было 500 мс (7200/час) → 2000 мс (1800/час) → 8000 мс.
         *
         * ВАЖНО: формула «3_600_000 / 8000 = 450/час» держится, только пока
         * кольцо дотягивает до `WRITE_CHUNK_MS + UNDERRUN_HEADROOM_MS` = 10 с.
         * С §5.4.5 (байтовый потолок кольца снят) это так на любой SR:
         * **450/час**. Пока потолок 2 МиБ работал, кольцо на 48 кГц урезалось
         * до 5.46 с и чанк коллапсировал в `кольцо − запас` = 3.46 с ⇒
         * 1040/час (на 44.1 кГц — 3.94 с ⇒ 913/час): выигрыш от 8-секундного
         * чанка реализовывался лишь наполовину. Если HAL урежет кольцо сильнее
         * ожидаемого, чанк снова сожмётся — writerLoop() считает его по
         * ФАКТИЧЕСКОМУ размеру кольца и пишет результат в лог.
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
         * UNDERRUN_HEADROOM_MS, иначе подпитка не гарантирована. См. writerLoop().
         */
        private const val WRITE_CHUNK_MS = 8000           // гранулярность записи/реакции
        /**
         * Отступ от края кольца в формуле ЦЕЛИ ПРЕДЗАПОЛНЕНИЯ при перемотке
         * ([SEEK_PREFILL_MARGIN_MS]) — и больше нигде: запас до underrun
         * задаёт [UNDERRUN_HEADROOM_MS], а чанк выводится из него.
         *
         * 1000 мс — историческая величина (при кольце 3000 мс и чанке 2000 мс
         * запас был ровно таким). Смысл тот же, что у [prefillGoal] в
         * writerLoop(): цель обязана быть ДОСТИЖИМОЙ, иначе латч всегда будет
         * выгорать по таймауту.
         */
        private const val MIN_WRITE_MARGIN_MS = 1000

        /**
         * ЗАПАС ДО UNDERRUN — ПЕРВАЯ величина, а не остаток от деления.
         *
         * Исторически запас был ПОБОЧНЫМ ЭФФЕКТОМ двух других чисел:
         * `кольцо − чанк`, где чанк = `min(WRITE_CHUNK_MS, кольцо − 1 с)`.
         * Пока WRITE_CHUNK_MS = 8 с меньше `кольца − 1 с`, запас равен 2 с; как
         * только байтовый потолок кольца (бывший [MAX_TRACK_BUFFER_BYTES],
         * снят в §5.4.5) урезает кольцо ниже 9 с, чанк коллапсирует в
         * `кольцо − 1 с` и запас СХЛОПЫВАЕТСЯ РОВНО В 1 с. На 8/16/22.05 кГц
         * запас 2 с, на 44.1/48 кГц — 1 с, и нигде в коде про это не сказано:
         * число 1000 в [MIN_WRITE_MARGIN_MS] внезапно начинает означать «запас
         * до underrun», хотя задумывалось как «сколько не занимать у края
         * кольца».
         *
         * Теперь запас задаётся ЯВНО и чанк выводится из него:
         *
         *     чанк = min(WRITE_CHUNK_MS, кольцо − UNDERRUN_HEADROOM_MS, пакет)
         *
         * Что это даёт на 48 кГц теперь (кольцо полные 10 с): чанк 8 с, запас
         * 2.0 с, 450 пробуждений писателя в час. Пока кольцо было урезано до
         * 5.46 с, тот же самый запас стоил 1040 пробуждений в час — то есть за
         * явность платили вдвое, и снятие потолка эту цену убирает, не жертвуя
         * ни запасом, ни длиной чанка. Сам запас того стоит: underrun — это
         * подстановка тишины
         * микшером, то есть шаг ПОЛНОЙ амплитуды, худший из доступных
         * артефактов, а стоимость генерации от длины пакета НЕ зависит
         * (замер: 1.02 с CPU на час звука и при 2 с, и при 190 с).
         */
        private const val UNDERRUN_HEADROOM_MS = 2000L

        /**
         * Нижняя граница чанка записи. Ниже неё дробить бессмысленно: стоимость
         * пробуждения писателя начинает превышать стоимость самой записи. Если
         * `кольцо − UNDERRUN_HEADROOM_MS` не дотягивает до этой величины,
         * кольцо считается вырожденным и чанк берётся его половиной.
         */
        private const val MIN_WRITE_CHUNK_MS = 500L

        /**
         * Длина стартового пакета в секундах. Именно столько (768 КБ при 48 кГц,
         * стерео float) выделяет prepare() — а не весь bufferIntervalMs.
         * Полный интервал доращивает писатель. См. комментарий в prepare().
         */
        private const val STARTUP_PACKET_SECONDS = 2

        /**
         * Плечо «приседания» при перенастройке живого потока ([retune]), мс.
         *
         * Ровно половина прежнего [ManagerState]-перехода: уход 125 мс + приход
         * 125 мс = те же 250 мс суммарной ямы, но БЕЗ второго трека.
         */
        private const val RETUNE_RAMP_MS = 125L

        /**
         * Длина пакета, генерируемого ВНУТРИ провала ([doRetuneOnWriter]).
         *
         * НЕ весь интервал генерации ([bufferIntervalMs], до 600 с): замер
         * стоимости — 1.02 с CPU на час звука, то есть полная регенерация
         * растянула бы провал до секунды. 30 с — это 11.5 МБ при 48 кГц и
         * ≈8.5 мс CPU: хватает наполнить кольцо (до 10 с) и сыграть сразу, а
         * полный интервал писатель дорастит на следующем витке существующим
         * [maybeGrowPacketBuffer] — регенерация начнётся СРАЗУ после записи, а
         * не через 30 с, поэтому underrun не возникает.
         */
        private const val RETUNE_PACKET_SECONDS = 30

        /**
         * Сколько актёр ждёт писателя, прежде чем признать провал неудавшимся и
         * отдать управление обратно менеджеру (тот пересоберёт поток).
         *
         * Запас большой: генерация 30 с звука — единицы миллисекунд, но писатель
         * мог быть в длинной записи или в GC.
         */
        private const val RETUNE_DEADLINE_MS = 1_000L

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
         * Признак «перемотка не заказана» для [pendingSeekFrame].
         *
         * -1, а не 0: кадр 0 — законная цель (возврат к началу потока), и
         * нулём её пришлось бы кодировать отдельным флагом.
         */
        private const val NO_SEEK = -1L

        /**
         * Ниже этой Δ (сек) перемотка не делается вовсе.
         *
         * Δ возникла за счёт нажатия «play» сразу после паузы, и ошибка в
         * пределах порога меньше одного периода биений — она неразличима.
         * А [android.media.AudioTrack.flush] ради 30 мс только вносил бы риск
         * разрыва: кольцо опустело бы и его пришлось бы наполнять заново.
         */
        private const val SEEK_EPSILON_SECONDS = 0.05f

        /**
         * Сколько максимум нить управления ждёт писателя, пока тот перемотает
         * пакет и наполнит кольцо трека ДО [android.media.AudioTrack.play].
         *
         * Ожидание ограничено ПОТОМУ, что ждущая нить — актёр менеджера: на
         * ней висят таймеры фейдов и обработка stop. Один чанк записи
         * (~8 с PCM) генерируется за десятки миллисекунд, так что 150 мс —
         * запас с двукратным превышением. По истечении play() всё равно
         * вызывается: худший случай — короткая тишина ПОД НУЛЕВОЙ ГРОМКОСТЬЮ
         * рампы fade-in, а не зависшее возобновление.
         */
        private const val SEEK_PREFILL_TIMEOUT_MS = 150L

        /**
         * Доля кольца трека, которую перемотка обязана наполнить до play().
         *
         * Считается от фактического размера кольца минус [MIN_WRITE_MARGIN_MS]:
         * писатель всё равно не пишет вплотную к краю, и требовать больше —
         * значит ждать заведомо невыполнимого.
         */
        private const val SEEK_PREFILL_MARGIN_MS = MIN_WRITE_MARGIN_MS

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
                // Инвариант: 1 в покое, 2 только на время кроссфейда —
                // уходящий поток ещё держит СВОЙ дорощенный пакет, входящий
                // живёт на стартовом (750 КБ). Третьего быть не может:
                // менеджер не поднимает NEXT, пока [outgoing] не пуст.
                append("holders=${livePacketHolders.get()} peak=${peakPacketHolders.get()} (инвариант: <=2)\n")
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

    // Завершение фейда висит на опросе живого множителя шейпера, а не на
    // фиксированной страже после конца рампы: VolumeShaper стартует не
    // мгновенно, а на следующем цикле микшера, поэтому в момент «рампа дошла
    // до нуля» множитель ещё ≈0.31. Величина этого лага зависит от загрузки
    // устройства, и угадывать её константой нельзя — см.
    // [scheduleFadeCompletion] и [FADE_POLL_MS].
    //
    // Раньше здесь же висел второй колбэк ровно в конце рампы («точка
    // тишины») — из него менеджер повышал заранее подготовленный NEXT и
    // получал бесшовный кроссфейд. Схема вернулась (см.
    // BinauralStreamManager.beginSilentSwitch), NEXT подготовлен ДО
    // фейд-аута: колбэк нужен только чтобы утилизировать уходящий поток, и
    // находится он уже ПОД звучащим новым.
    private fun cancelFadeCallbacks() {
        fadeCompletion?.let { controlHandler.removeCallbacks(it) }
        fadeCompletion = null
        // Провал перенастройки — тоже «завершение рампы»: снимаем его таймеры.
        // Задание ([pendingRetune]) НЕ трогаем: пауза обязана его сохранить,
        // чтобы припаркованный писатель применил конфиг без провала.
        cancelRetuneTimers()
    }

    /**
     * Отложить завершение рампы на момент, когда она ЗАВЕДОМО дошла.
     *
     * Опроса нет — только расписание. Рампа VolumeShaper идёт в масштабе
     * реального времени микшера, поэтому к моменту
     * `rampMs + RAMP_SETTLE_MARGIN_MS` она либо дошла до цели и удерживает её,
     * либо микшер подставлял тишину (underrun), а для fade-out это тот же
     * ноль. Обоснование маржи — в [RAMP_SETTLE_MARGIN_MS].
     *
     * @param toZero осталось только для диагностики в строке лога: раньше по
     *        нему выбиралось направление сравнения при опросе.
     */
    private fun scheduleFadeCompletion(rampMs: Long, toZero: Boolean, completion: Runnable) {
        val delay = if (rampMs <= 0L) 0L else rampMs + RAMP_SETTLE_MARGIN_MS
        StreamLogger.d(TAG, "ramp spec#${spec.serial}: completion через ${delay}мс " +
            "(рампа ${rampMs}мс + маржа ${RAMP_SETTLE_MARGIN_MS}мс, toZero=$toZero)")
        val wrapped = Runnable {
            fadeCompletion = null
            completion.run()
        }
        fadeCompletion = wrapped
        controlHandler.postDelayed(wrapped, delay)
    }

    /**
     * Живой множитель шейпера либо `null`, если шейпера нет (снят вручную,
     * API < 26, аварийный путь без рампы) — тогда опрашивать нечего.
     */
    private fun liveShaperVolume(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val shaper = volumeShaper ?: return null
        return try { shaper.volume.coerceIn(0f, 1f) } catch (_: Exception) { null }
    }

    /**
     * ТЕЛЕМЕТРИЯ СТЫКА: живой множитель шейпера в момент срабатывания
     * completion фейд-ина. Норма — 1.0000. Значимо меньше — лаг данного
     * устройства превышает RAMP_SETTLE_MARGIN_MS; с правилом «наверху не
     * закрываем» это безвредно (рампа доиграет сама), но полезно видеть.
     *
     * Значение читается ТОЛЬКО для лога: никакое решение от него не зависит —
     * опрос завершения не возвращается.
     */
    private fun logShaperSettle(where: String) {
        val live = liveShaperVolume()
        StreamLogger.d(TAG, "$where spec#${spec.serial}: fade-in completion, " +
            "shaper live=${live?.let { "%.4f".format(it) } ?: "нет"} (цель 1.0)")
    }

    /**
     * КРАЙНИЙ резерв огибающей без VolumeShaper: база маленькими ступенями.
     *
     * Вызывается только когда шейпер недоступен ВОВСЕ (ветка API < 26 — на
     * minSdk 26 мёртва — либо двойной отказ create/replace в [applyShaper]).
     * Прежде здесь стоял одиночный `setVolume(userVolume)` — полноценная
     * ступень, т.е. гарантированный щелчок аварийного пути. Теперь 10
     * ступеней по ≤10 % амплитуды: грубый, но фейд, а не клик.
     *
     * Каждый шаг охраняется lifecycle: утилизация/пауза гасят хвост своей
     * базой 0 (finalizeStop/finalizePause), и посты просто становятся пустыми.
     */
    private fun manualBaseRamp(from: Float, to: Float, durationMs: Long) {
        val steps = 10
        val stepMs = (durationMs.coerceAtLeast(50L) / steps).coerceAtLeast(5L)
        for (i in 0..steps) {
            val p = i.toFloat() / steps
            val target = ((from + (to - from) * p).coerceIn(0f, 1f)) * userVolume
            controlHandler.postDelayed({
                if (lifecycleRef.get() == StreamLifecycle.PLAYING) {
                    try { audioTrack?.setVolume(target) } catch (_: Exception) {}
                }
            }, i * stepMs)
        }
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
     * ОСЬ КАДРОВ ПАКЕТА: сколько кадров на канал движок выдал с начала потока.
     *
     * Сквозная нумерация, НЕ смещение внутри буфера: буфер переиспользуется
     * (по исчерпании дописывается заново), а эта величина только растёт.
     * Три точки на ней и решают задачу возобновления:
     *   * `F0 = generatedFrames` — ФРОНТИР, конец сгенерированного аудио;
     *   * `C  = (generatedFrames − кадров в буфере) + offset/frameBytes` —
     *     КУРСОР ЗАПИСИ, сколько PCM уже отдано треку;
     *   * `A0 = frameBias + playbackHeadPosition` — СЛЫШИМАЯ позиция, кадр,
     *     который сейчас в динамике.
     * Разность `C − A0` — это и есть кольцо AudioTrack (`R`, до
     * [TRACK_BUFFER_MS]): PCM, уже отданный треку, но ещё не отыгранный.
     *
     * Пишет писатель (и prepare() до его старта), читает актёр.
     */
    @Volatile private var generatedFrames = 0L

    /**
     * АБСОЛЮТНЫЙ кадр пакета, с которого писатель обязан продолжить запись
     * после возобновления; [NO_SEEK] — перемотка не заказана.
     *
     * СУТЬ ПРИЛОЖЕНИЯ: звук обязан соответствовать ТЕКУЩЕМУ моменту суток.
     * За паузу часы ушли на Δ, и цель — кадр `T = A0 + Δ·rate`: ровно тот,
     * чей отсчёт по кривой равен `now`.
     *
     * Почему АБСОЛЮТНЫЙ кадр, а не «сколько кадров выбросить»: от курсора
     * записи `C` до динамика лежит кольцо трека, и пропуск «Δ кадров от `C`»
     * ставит курсор в `C + Δ`, тогда как нужен `A0 + Δ`. Разница ровно `R` —
     * столько секунд после возобновления пользователь и слышит СТАРУЮ позицию.
     * Кольцо принадлежит треку, а не пакету, поэтому выбрасывается не
     * пропуском, а [android.media.AudioTrack.flush]; сам пакет цел.
     *
     * Пишет нить управления ([resume]), читает писатель.
     */
    private val pendingSeekFrame = AtomicLong(NO_SEEK)

    /**
     * Смещение оси AudioTrack к оси пакета: `кадр пакета = голова + frameBias`.
     *
     * Изначально 0 (кадры пакета уходят в трек подряд). Перемотка ПЕРЕЯКОРЯЕТ
     * его на `T − голова`, причём голова читается ПОСЛЕ flush — часть
     * реализаций обнуляет её, часть нет, и переякорка по фактическому
     * значению делает обе ветки эквивалентными.
     *
     * Прибавляется к голове в расчёте СЛЫШИМОЙ позиции: без него audible
     * отставал бы от продвинутого фронтира на всю величину пропуска.
     */
    private val frameBias = AtomicLong(0)

    /**
     * Латч готовности перемотки: писатель выполнил flush, поставил курсор и
     * НАПОЛНИЛ кольцо — можно звать play() без разрыва. Взводится нитью
     * управления перед [wakeWriter] и снимается ею же по таймауту
     * [SEEK_PREFILL_TIMEOUT_MS], поэтому писатель не обязан знать о дедлайне.
     */
    @Volatile private var seekReadyLatch: CountDownLatch? = null

    // ------------------------------------------------- перенастройка живого потока
    /**
     * Задание на перенастройку. Пишет актёр ([retune]), читает и обнуляет
     * писатель ([doRetuneOnWriter]). Один слот, latest-wins: шторм жестов
     * перезаписывает цель, не накапливая переходов.
     */
    private val pendingRetune = AtomicReference<PlaybackSpec?>(null)

    /** Провал в полёте: между «наложили спад» и «play() после перезаполнения». */
    @Volatile private var retuning = false

    /**
     * Актёр уже погасил трек и поставил паузу — писателю можно исполнять.
     *
     * Не нужен был бы, будь у VolumeShaper колбэк завершения; его нет, поэтому
     * момент готовности вычисляется как `длительность + маржа` (см.
     * [RAMP_SETTLE_MARGIN_MS]) и ровно в нём актёр снимает звук. До этого
     * флага писатель обязан продолжать ПИТАТЬ трек: пауза на середине рампы
     * заморозила бы шейпер на ненулевом множителе — то есть дала бы щелчок.
     */
    @Volatile private var retuneGo = false

    /** Колбэк завершения перенастройки. Ставится актёром, снимается им же. */
    private var retuneCallback: ((Boolean) -> Unit)? = null

    /**
     * Перенастройка прошла НА ПАУЗЕ: свежий пакет начинается ровно с «сейчас»,
     * и возобновление обязано встать в его НАЧАЛО, даже когда Δ меньше
     * [SEEK_EPSILON_SECONDS] — иначе кольцо доигрывает остаток старого звука.
     */
    @Volatile private var retuneStartAtZero = false

    /** Отложенный «приземлитель» провала; хранится, чтобы пауза/стоп сняли его. */
    @Volatile private var retuneFinish: Runnable? = null

    /** Страховка провала: писатель не отозвался — отдаём управление менеджеру. */
    @Volatile private var retuneDeadlineRunnable: Runnable? = null

    // ------------------------------------------------- состояние цикла писателя
    /**
     * Курсор записи в пакете и длина текущего пакета (байты).
     *
     * Поля, а не локальные переменные цикла, потому что их подменяет
     * [doRetuneOnWriter] — а он вызывается из цикла, но живёт отдельным
     * методом. Владелец по-прежнему одна нить — писатель.
     */
    private var writerOffset = 0
    private var writerPacketBytes = 0
    /** Ждём наполнения кольца до play() (после перемотки/перенастройки). */
    private var writerPrefillUntilPlay = false
    private var writerPrefillLatch: CountDownLatch? = null
    /** Верхняя граница одной записи, байты (см. [UNDERRUN_HEADROOM_MS]). */
    private var writerMaxChunkBytes = 0L

    /**
     * DEBUG: дамп записываемого PCM в файл (float32 interleaved stereo).
     * Пишется в [writerLoop] сразу после успешного write(). Верификация стыков
     * пакетов/ретюна численным анализом (tools/analyze_pcm_seams.py);
     * микшерные фейды в дампе НЕ видны — огибающая применяется в микшере.
     */
    @Volatile private var debugPcmDump: java.io.FileOutputStream? = null

    /** Включить дамп записываемого PCM (debug-верификация стыков). */
    fun debugStartPcmDump(file: java.io.File) {
        debugPcmDump = try {
            java.io.FileOutputStream(file)
        } catch (e: Exception) {
            StreamLogger.e(TAG, "debugStartPcmDump: ${e.message}")
            null
        }
    }

    /** Остановить и закрыть дамп PCM. */
    fun debugStopPcmDump() {
        try { debugPcmDump?.flush(); debugPcmDump?.close() } catch (_: Exception) {}
        debugPcmDump = null
    }

    /** Монитор парковки писателя: parkWriter/wakeWriter. */
    private val parkLock = Object()

    // ------------------------------------------------------------------ prepare

    override fun prepare(): Boolean {
        if (!lifecycleRef.compareAndSet(StreamLifecycle.CREATED, StreamLifecycle.PREPARED)) {
            StreamLogger.w(TAG, "prepare spec#${spec.serial}: уже не CREATED (lc=${lifecycleRef.get()})")
            return false
        }
        StreamLogger.d(TAG, "prepare spec#${spec.serial} sr=${spec.sampleRate.value} reason=${spec.reason} resume=${spec.resumeAnchorMs > 0} anchor=${spec.resumeAnchor}")
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
            // ФАЗЫ БОЛЬШЕ НЕ ПЕРЕНОСЯТСЯ. Раньше уходящий поток отдавал их
            // NEXT, чтобы тот не стартовал с фазы 0 и не интерферировал в
            // перекрытии. Перекрытия нет (docs/plan_handoff_single_track.md):
            // штатная смена настроек перенастраивает ЭТОТ же движок, а
            // пересоздание трека поднимается уже после полной тишины, где
            // фаза 0 ни с чем не интерферирует.

            // СУТЬ ПРИЛОЖЕНИЯ: звук обязан соответствовать ТЕКУЩЕМУ моменту
            // времени суток. Якорь кривой задаётся ЯВНО всегда — и тем самым
            // переживает resetState() и play().
            //
            // Раньше свежий старт полагался на play() без preserveTimeline,
            // который и сам якорит m_curveTimeSeconds на realTimeOfDaySeconds().
            // Но между resetState() и play() есть окно, где нативный таймлайн
            // стоит на 0; если в это окно успевал пересоздать поток хэндофф
            // (стоп→play), звук рождался с 00:00 — ровно тот баг, с которого
            // начался разбор (docs/analysis_resume_from_0_position.md).
            //
            // ЕДИНАЯ ТОЧКА ЯКОРЕНИЯ. Раньше сюда приходил уже готовый якорь,
            // захваченный в beginHandoff — то есть на длительность фейд-аута и
            // релиза старого потока (~0.3–1 с) старше «сейчас». За серию правок
            // пресета лаг накапливался, и звук систематически отставал от
            // настенных часов. Теперь решение принимается ЗДЕСЬ и СЕЙЧАС:
            //   * якоря нет        → «сейчас»;
            //   * якорь plausible  → применяется;
            //   * якорь далёк      → отвергается, берётся «сейчас» (WARN).
            // Легальная ПОЛНОЧЬ (0) валидацией не отбрасывается: сравнение
            // идёт по круговому расстоянию (см. CurveAnchorRules).
            //
            // `engine.getCurrentTimeOfDay()` (а не realTimeOfDaySeconds()) —
            // потому что на свежем движке это и есть реальное локальное время,
            // но в debug-сборке та же ось несёт виртуальное время или результат
            // `debugScrub`, и перемотку оператора надо уважать.
            val engineNow = engine.getCurrentTimeOfDay()
            val resolved = resolveCurveAnchor(
                anchor = spec.resumeAnchor,
                nowSec = realTimeOfDaySeconds(),
                engineNowSec = engineNow.toFloat()
            )
            if (resolved.source == AnchorSource.FALLBACK) {
                StreamLogger.w(TAG, "prepare spec#${spec.serial}: якорь ${spec.resumeAnchor} " +
                    "отвергнут (далеко от now=${"%.1f".format(realTimeOfDaySeconds())}) — " +
                    "беру текущий момент суток $engineNow")
            }
            // СКРАБ (docs/plan_playback_scrub_handle.md §3.3). Сдвиг оси
            // применяется К РАЗРЕШЁННОМУ ЯКОРЮ, а не вместо него: «сейчас»
            // всё ещё решает, откуда начинать, смещение лишь указывает, какое
            // время суток звучит. Нормализация обязательна — сдвиг свободно
            // уводит ось через полночь.
            val scrubOffset = spec.scrubOffsetSec
            val anchorSec = if (scrubOffset != 0) {
                normalizeTimeOfDay(resolved.valueSec + scrubOffset.toFloat()).toInt()
            } else {
                resolved.valueSec
            }
            StreamLogger.d(TAG, "prepare spec#${spec.serial}: якорь кривой=$anchorSec " +
                "(источник=${resolved.source}, заявленный=${spec.resumeAnchor}, сдвиг скраба=$scrubOffset)")
            engine.setCurveTime(anchorSec)
            if (scrubOffset != 0) {
                // Сдвинутая ось ОБЯЗАНА играть с preserveTimeline: обычный
                // play() вызывает generator.resetState() и переякоривает
                // m_curveTimeSeconds на realTimeOfDaySeconds(), стирая только
                // что поставленный setCurveTime — сдвиг молча исчез бы.
                // elapsed-часы наследуем, если они уже накоплены (пауза на
                // сдвинутой оси — легальный сценарий V8).
                engine.setPlaybackStartTime(System.currentTimeMillis() - spec.resumeElapsedMs)
                engine.play(preserveTimeline = true)
            } else if (spec.resumeAnchorMs > 0) {
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
                engine.play()   // якорь уже задан выше: текущее время суток
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
            // актёра надолго; полный интервал сгенерирует писатель, пока трек уже играет.
            // 5.5. Режим B: огибающая фейд-ина будет домножена на первые кадры
            // самим генератором. Ставится ДО первой генерации и ДО старта писателя —
            // владелец счётчика с этого момента только аудио-нить.
            if (DATA_BAKED_FADE_IN) {
                engine.setPendingFadeIn(fadeInMs.toInt())
            }
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
        // Байтового потолка кольца больше НЕТ (§5.4.5): пока одновременно жили
        // два трека (кроссфейд), запрос резался по 2 МиБ — иначе оба не влезали
        // в одну кучу клиента AudioFlinger (-12 NO_MEMORY). Теперь трек всегда
        // один (смена пресета идёт через retune, а recreateTrack снимает старый
        // трек до создания нового), и на 48 кГц выделяются полные 3.84 МиБ под
        // 10 с. minBuffer — нижняя граница HAL, её не роняем.
        val requested = maxOf(minBuffer.toLong(), rate.toLong() * frameBytes * TRACK_BUFFER_MS / 1000)
        val size = requested.toInt()
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

    override fun start(
        onFullyStarted: () -> Unit,
        shape: FadeShape,
        fadeInMsOverride: Long
    ): Boolean {
        if (!lifecycleRef.compareAndSet(StreamLifecycle.PREPARED, StreamLifecycle.PLAYING)) {
            StreamLogger.w(TAG, "start spec#${spec.serial}: не PREPARED (lc=${lifecycleRef.get()})")
            return false
        }
        val track = audioTrack ?: return false
        fadeMode = FadeMode.IN
        StreamLogger.d(TAG, "start spec#${spec.serial}: shaper -> play -> writer (именно в этом порядке)")
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

            val fadeMs = if (fadeInMsOverride > 0L) fadeInMsOverride else fadeInMs

            if (DATA_BAKED_FADE_IN) {
                // Режим B: в кольце уже лежит пакет с запечённой sin²-огибающей
                // (0 … ровно 1.0 на последнем кадре). Микшеру нечего рамповать и
                // нечего закрывать: «стык фейд-ина с полным звуком» — это сэмпл,
                // где g стало 1.0, и дальше идёт чистый PCM с усилением 1.0.
                // Непрерывность — по построению, лаг шейпера не участвует вообще.
                try { track.setVolume(userVolume) } catch (_: Exception) {}
                track.play()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val du = track.underrunCount - underrunBefore
                    val head = track.playbackHeadPosition
                    StreamLogger.d(TAG, "start spec#${spec.serial}: B-mode underrunDelta=$du " +
                        "headPos=$head applyToPlayUs=${(System.nanoTime() - tApply) / 1000}")
                }
                writerStarted = true
                writerHandler?.post(::writerLoop)
                val completion = Runnable {
                    if (lifecycleRef.get() == StreamLifecycle.PLAYING && fadeMode == FadeMode.IN) {
                        fadeMode = FadeMode.NONE
                        onFullyStarted()
                    }
                }
                fadeCompletion = completion
                controlHandler.postDelayed(completion, fadeMs)   // только колбэк, не звук
                return true
            }

            // ФИКС M2: форма фейд-ина ВСЕГДА EQUAL_POWER (sin-рампа),
            // независимо от переданной [shape]. Её терминальный наклон при 1.0
            // РАВЕН НУЛЮ: даже если какое-то устройство задержит рампу,
            // амплитудный недоход квадратичен по задержке, а не линеен.
            // LINEAR с наклоном 1.0 превращал 30 мс лага шейпера в ступень
            // −18 дБ — слышимый щелчок стыка. (Направление вниз остаётся
            // линейным там, где вызов уже передаёт LINEAR: нижний конец
            // защищён порядком «база 0 до close».)
            val dur = applyShaper(from = 0f, to = 1f, durationMs = fadeMs,
                                  shape = FadeShape.EQUAL_POWER)
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
                    // ФИКС M1 — ГЛАВНЫЙ. Здесь БЫЛО closeShaper().
                    //
                    // Почему закрыть ≠ безопасно: момент, когда фактический
                    // множитель микшера дошёл до 1.0, узнать нельзя (колбэка
                    // завершения нет, getVolume() с лагом). close() при живом
                    // множителе < 1.0 мгновенно возвращает громкость к базе —
                    // ступень (1 − live)·userVolume. Это и есть щелчок
                    // «конец фейд-ина → полный звук».
                    //
                    // Почему оставить шейпер живым = безопасно: при множителе
                    // 1.0 он аудиально тождественен своему отсутствию. Оба
                    // поведения реализаций после конца кривой (держать 1.0;
                    // само-закрыться к базе) дают ОДИН и тот же звук. Дальнейшая
                    // судьба шейпера:
                    //   * следующая рампа заберёт его через replace(join=true),
                    //     стартуя от живого значения (разрыва нет по построению);
                    //   * closeShaper() вызовется на нижнем конце фейда, где база
                    //     уже принудительно 0 (finalize*/finishRetune) — там
                    //     закрытие ничего не меняет.
                    logShaperSettle("start")
                    fadeMode = FadeMode.NONE
                    onFullyStarted()
                }
            }
            fadeCompletion = completion
            scheduleFadeCompletion(dur, toZero = false, completion = completion)
            true
        } catch (e: Exception) {
            Log.e(TAG, "start() failed: ${e.message}")
            StreamLogger.e(TAG, "start FAILED spec#${spec.serial}: ${e.message}")
            lifecycleRef.set(StreamLifecycle.FAILED)
            return false
        }
    }

    // ------------------------------------------------------------------ stop

    override fun stop(onFullyStopped: () -> Unit, shape: FadeShape, fadeOutMsOverride: Long) {
        var rampStartMs = System.currentTimeMillis()
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
        if (retuning) {
            // Провал перенастройки снимается: задание теряется (поток всё равно
            // утилизируется), колбэк никому не нужен. Рампу вниз не отменяем —
            // она и есть начало этого fade-out.
            StreamLogger.d(TAG, "stop spec#${spec.serial}: снимаю провал перенастройки " +
                "(задание потеряно, поток утилизируется)")
            pendingRetune.set(null)
            retuneCallback = null
            cancelRetuneTimers()
            fadeMode = FadeMode.NONE
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
        // Длительность берётся из переопределения, если оно задано: переходам с
        // нулевым перекрытием обе рампы (уход и приход) складываются в общую
        // длину «приседания», и штатные fadeOutMs на каждое плечо дают лишние
        // сотни миллисекунд ямы.
        val fadeMs = if (fadeOutMsOverride > 0L) fadeOutMsOverride else fadeOutMs
        val cur = currentMultiplier()
        val dur = if (cur <= 0.001f) 0L else (fadeMs * cur).toLong().coerceAtLeast(40L)
        rampStartMs = System.currentTimeMillis()
        StreamLogger.d(TAG, "stop spec#${spec.serial}: fade-out($shape) cur=$cur dur=${dur}ms " +
            "(штатные ${fadeOutMs}мс, переопределение ${fadeOutMsOverride}мс)")
        if (dur > 0) {
            applyShaper(from = cur, to = 0f, durationMs = dur, shape = shape)
        } else {
            // Уже в нуле: гасим базу и снимаем активный фейд-ин-шейпер (тишина).
            try { audioTrack?.setVolume(0f) } catch (_: Exception) {}
            closeShaper()
        }
        // Утилизация — когда шейпер ФАКТИЧЕСКИ дошёл до нуля, а не через
        // фиксированную стражу после конца рампы: VolumeShaper отстаёт от
        // расписания на величину, которая зависит от загрузки устройства, и
        // угадать её константой нельзя (см. [scheduleFadeCompletion]).
        //
        // Хук «дошли до нуля» больше не нужен: второй поток не поднимается
        // вовсе (штатная смена настроек перенастраивает этот же поток —
        // docs/plan_handoff_single_track.md), а пересоздание трека всегда
        // идёт ПОСЛЕ [onFullyStopped], то есть после паузы, выхода писателя
        // и разбора буфера. Разрыв там ожидаем и задокументирован.
        val completion = Runnable {
            finalizeStop(onFullyStopped)
        }
        fadeCompletion = completion
        scheduleFadeCompletion(dur, toZero = true, completion = completion)
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
        // писатель может провисеть в нём до WRITE_CHUNK_MS = 8 с (кольцо 10 с,
        // чанк больше не коллапсирует в «кольцо − запас»). Всё это время трек
        // живёт в AudioFlinger, а менеджер
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
                // наверху шейпер не закрывается — см. комментарий в start() (M1)
                logShaperSettle("reverse")
                fadeMode = FadeMode.NONE
                onFullyStarted()
            }
        }
        fadeCompletion = completion
        scheduleFadeCompletion(dur, toZero = false, completion = completion)
        return true
    }

    // ------------------------------------------------------------------ retune

    /**
     * ПЕРЕНАСТРОЙКА ЖИВОГО ПОТОКА — то, что replaces второй AudioTrack.
     *
     * ПОЧЕМУ ЭТО ВООБЩЕ ВОЗМОЖНО. Звук предрендерится пакетами до 60 минут,
     * поэтому «предугадать» в пакете момент, когда пользователь нажмёт паузу
     * или поменяет настройку, нельзя — но и не нужно. Любое изменение доводится
     * до эфира ОДНИМ и тем же единственным рычагом: микшером (громкость
     * AudioTrack плюсVolumeShaper) и сменой конфига на ГРАНИЦЕ пакета. Сам PCM,
     * уже лежащий в кольце трека, всё равно придётся выбросить — как это и
     * делает существующий путь перемотки ([applyResumeSeek]).
     *
     * ПОЧЕМУ ЭТО ЛУЧШЕ ВТОРОГО ТРЕКА. Второй трек тянул за собой: вторую
     * выделенную память из общей кучи клиента AudioFlinger (отказ -12),
     * второй пакет на сотни мегабайт, сторож утилизации уходящего потока,
     * передачу фаз и — главное — МОНИТОРИНГ ТИШИНЫ: без колбэка завершения
     * рампы оставалось только опрашивать живой множитель шейпера, чтобы узнать,
     * можно ли уже поднимать NEXT. Здесь второго потока нет, а значит и
     * «момент тишины» никому не нужен: мы просто продолжаем играть тем же
     * треком после короткого приседания.
     *
     * ДВА СЦЕНАРИЯ:
     *  * **пауза** — тишина уже есть и писатель припаркован. Провала НЕТ
     *    вообще: задание кладётся в слот, писатель применит его и снова
     *    встанет в парковку. Это самый частый случай (пользователь листает
     *    пресеты на паузе) и он полностью бесплатен.
     *  * **играем** — провал по построению: рампа в ноль ([RETUNE_RAMP_MS]),
     *    пауза трека, смена конфига и регенерация ОГРАНИЧЕННОГО пакета
     *    ([RETUNE_PACKET_SECONDS]), запись в кольцо, play() с рампой вверх.
     *
     * @param newSpec целевой спек. Частота дискретизации и формат меняться не
     *        могут — для них нужен новый трек, и метод вернёт `false`.
     * @param onApplied `true` — перенастройка применена; `false` — поток в
     *        несогласованном состоянии, менеджер обязан его пересобрать.
     *        Исполняется на нити актёра ровно один раз.
     */
    override fun retune(newSpec: PlaybackSpec, onApplied: (Boolean) -> Unit): Boolean {
        if (lifecycleRef.get() != StreamLifecycle.PLAYING) {
            StreamLogger.w(TAG, "retune spec#${spec.serial}: не PLAYING (lc=${lifecycleRef.get()})")
            return false
        }
        if (newSpec.sampleRate != spec.sampleRate) {
            StreamLogger.w(TAG, "retune spec#${spec.serial}: смена SR " +
                "${spec.sampleRate.value} -> ${newSpec.sampleRate.value} — нужен новый трек")
            return false
        }
        userVolume = newSpec.volume

        // ШТОРМ: пока провал в полёте, новый жест ТОЛЬКО перезаписывает цель.
        // Таймер не перезапускается и шейпер не трогается — иначе серия жестов
        // растянула бы «приседание» на свою длину и превратила его в заикание.
        if (retuning) {
            pendingRetune.set(newSpec)
            StreamLogger.d(TAG, "retune spec#${spec.serial}: провал уже идёт — цель " +
                "перезаписана на spec#${newSpec.serial} (${newSpec.reason})")
            return true
        }

        retuneCallback = onApplied
        pendingRetune.set(newSpec)

        if (paused) {
            // ПАУЗА: провал не нужен, тишина уже есть. Писатель припаркован —
            // будим, он применит конфиг и снова встанет в парковку.
            cancelFadeCallbacks()
            wakeWriter()
            StreamLogger.d(TAG, "retune spec#${spec.serial}: на паузе — применяется " +
                "spec#${newSpec.serial} (${newSpec.reason}) БЕЗ провала")
            return true
        }

        cancelFadeCallbacks()
        fadeMode = FadeMode.OUT
        val cur = currentMultiplier()
        val dur = if (cur <= 0.001f) 0L else (RETUNE_RAMP_MS * cur).toLong().coerceAtLeast(20L)
        if (dur > 0) {
            applyShaper(from = cur, to = 0f, durationMs = dur, shape = FadeShape.EQUAL_POWER)
        } else {
            // Уже в нуле: гасим базу и снимаем шейпер (тишина без рампы).
            try { audioTrack?.setVolume(0f) } catch (_: Exception) {}
            closeShaper()
        }
        retuning = true
        retuneGo = false
        StreamLogger.d(TAG, "retune spec#${spec.serial} -> spec#${newSpec.serial} " +
            "(${newSpec.reason}): приседание, рампа вниз ${dur}мс")
        val finish = Runnable { finishRetune() }
        retuneFinish = finish
        // Маржа — ГАРАНТИЯ, а не измерение: см. [RAMP_SETTLE_MARGIN_MS]. До
        // этого момента писатель обязан продолжать питать трек: пауза на
        // середине рампы заморозила бы шейпер на ненулевом множителе.
        controlHandler.postDelayed(finish, dur + RAMP_SETTLE_MARGIN_MS)
        return true
    }

    /** Точка «рампа дошла»: снять звук и отдать писателю команду на замену. */
    private fun finishRetune() {
        retuneFinish = null
        if (!retuning) return
        if (lifecycleRef.get() != StreamLifecycle.PLAYING) {
            abortRetune(false)
            return
        }
        // Тишина фиксируется БАЗОЙ, а не шейпером: во внутреннем кольце трека
        // ещё до TRACK_BUFFER_MS полноамплитудного PCM, и закрытие шейпера
        // вернуло бы их на полную (тот же мотив, что в [finalizeStop]).
        try { audioTrack?.setVolume(0f) } catch (_: Exception) {}
        closeShaper()
        // pause() и здесь нужен: он прерывает заблокированный
        // write(WRITE_BLOCKING), иначе писатель вышел бы из записи только через
        // весь чанк (до нескольких секунд).
        try { audioTrack?.pause() } catch (_: Exception) {}
        retuneGo = true
        wakeWriter()
        val deadline = Runnable {
            retuneDeadlineRunnable = null
            if (!retuning) return@Runnable
            StreamLogger.e(TAG, "retune spec#${spec.serial}: писатель не отозвался за " +
                "${RETUNE_DEADLINE_MS}мс — провал сорван, поток надо пересобрать")
            abortRetune(false)
        }
        retuneDeadlineRunnable = deadline
        controlHandler.postDelayed(deadline, RETUNE_DEADLINE_MS)
    }

    /** Писатель перестроил пакет — поднимаем звук тем же треком. */
    private fun onRetuneReady() {
        retuneFinish = null
        retuneDeadlineRunnable?.let { controlHandler.removeCallbacks(it) }
        retuneDeadlineRunnable = null
        if (!retuning) return
        retuning = false
        retuneGo = false
        val cb = retuneCallback
        retuneCallback = null
        if (lifecycleRef.get() != StreamLifecycle.PLAYING) {
            cb?.invoke(false)
            return
        }
        fadeMode = FadeMode.IN
        // Шейпера нет (снят в [finishRetune]), поэтому эта рампа поднимает и
        // базу трека до userVolume, и множитель — ровно как при [resume].
        val dur = applyShaper(from = 0f, to = 1f, durationMs = RETUNE_RAMP_MS, shape = FadeShape.EQUAL_POWER)
        try {
            audioTrack?.play()
        } catch (e: Exception) {
            StreamLogger.e(TAG, "retune spec#${spec.serial}: play failed: ${e.message}")
            fadeMode = FadeMode.NONE
            cb?.invoke(false)
            return
        }
        wakeWriter()
        StreamLogger.d(TAG, "retune spec#${spec.serial}: ПОДНЯТ на spec#${spec.serial} " +
            "(рампа вверх ${dur}мс, причина=${spec.reason}) — второго трека не было")
        val completion = Runnable {
            if (lifecycleRef.get() == StreamLifecycle.PLAYING && fadeMode == FadeMode.IN) {
                // Провал ретюна: подъём завершён. Шейпер НЕ закрывается —
                // см. комментарий в start() (M1). Это закрывает стык «конец
                // подъёма → полный звук нового пакета»: пакет перестроен в
                // провале тем же движком (фаза непрерывна), кольцо наполнено
                // до play() (writeOneChunk), множитель доигрывает до 1.0 сам,
                // и никто не обрывает его закрытием.
                logShaperSettle("retune-up")
                fadeMode = FadeMode.NONE
            }
        }
        fadeCompletion = completion
        scheduleFadeCompletion(dur, toZero = false, completion = completion)
        cb?.invoke(true)
    }

    /** Прервать провал, не применяя задание (стоп, пауза, сорванный дедлайн). */
    private fun abortRetune(applied: Boolean) {
        retuning = false
        retuneGo = false
        retuneFinish?.let { controlHandler.removeCallbacks(it) }
        retuneFinish = null
        retuneDeadlineRunnable?.let { controlHandler.removeCallbacks(it) }
        retuneDeadlineRunnable = null
        val cb = retuneCallback
        retuneCallback = null
        cb?.invoke(applied)
    }

    /** Снять только таймеры провала, сохранив задание и колбэк (пауза). */
    private fun cancelRetuneTimers() {
        retuneFinish?.let { controlHandler.removeCallbacks(it) }
        retuneFinish = null
        retuneDeadlineRunnable?.let { controlHandler.removeCallbacks(it) }
        retuneDeadlineRunnable = null
        retuning = false
        retuneGo = false
    }

    /**
     * Исполнить перенастройку. ТОЛЬКО с нити писателя.
     *
     * Писатель — это и есть аудио-нить, то есть ровно та, которой инвариант
     * (`BinauralEngine.h:264-279`) разрешает трогать движок. Никакой новой
     * синхронизации не нужно: актёр лишь оставляет задание в атомарном слоте.
     */
    private fun doRetuneOnWriter() {
        val wasPaused = paused
        // В игре ждём флага: он означает, что актёр уже погасил трек.
        if (!retuneGo && !wasPaused) return
        val newSpec = pendingRetune.getAndSet(null) ?: return
        // Прежний сдвиг оси — только для лога: новый якорь считается от
        // [realTimeOfDaySeconds()] (см. ниже), поэтому вычитать oldScrub не
        // нужно.
        val oldScrub = specState.scrubOffsetSec
        val engine = nativeEngine
        val track = audioTrack
        var applied = false
        if (engine != null && track != null) {
            try {
                // Кольцо выбрасываем ДО смены конфига: иначе старый PCM (до
                // TRACK_BUFFER_MS) доиграл бы уже после снятия провала.
                track.flush()
                engine.updateConfig(newSpec.config, newSpec.relaxation)
                // Ось времени суток: «сейчас» плюс сдвиг скраба НОВОЙ спеки.
                //
                // Базис — [realTimeOfDaySeconds()] (Kotlin), а НЕ
                // `engine.getCurrentTimeOfDay()`: нативный движок считает своё
                // время от `System.currentTimeMillis()` и не знает про debug-
                // часы (`totime`). При `totime` эти двое расходятся на весь
                // сдвиг, и якорь, посчитанный от движка, уводил бы звук на
                // реальное «сейчас» вместо виртуального (поймано V5/V9
                // tools/dbgscrub.sh: INVARIANT НАРУШЕН на величину сдвига).
                // `realTimeOfDaySeconds()` — тот же базис, что у инварианта
                // менеджера `normalizeTimeOfDay(realTimeOfDaySeconds()+scrub)`,
                // поэтому совмещение гарантировано при любом debug-часах.
                val base = realTimeOfDaySeconds()
                val anchor = normalizeTimeOfDay(base + newSpec.scrubOffsetSec.toFloat())
                engine.setCurveTime(anchor.toInt())
                val head = try { track.playbackHeadPosition } catch (_: Exception) { -1 }
                frameBias.set(if (head > 0) -head.toLong() else 0L)
                generatedFrames = 0
                val buf = directBuffer
                val rate = newSpec.sampleRate.value
                // ОГРАНИЧЕННЫЙ пакет, а не весь интервал: стоимость генерации
                // 1.02 с CPU на час звука, и полная регенерация растянула бы
                // провал до секунды. Полный интервал дорастит следующий виток.
                val want = minOf(samplesPerChannel, rate * RETUNE_PACKET_SECONDS)
                if (buf != null && want > 0) {
                    buf.clear()
                    val generated = engine.generateBufferDirect(buf, want)
                    if (generated > 0) {
                        writerPacketBytes = generated * 2 * 4
                        writerOffset = 0
                        generatedFrames = generated.toLong()
                        // Пакет теперь короткий: разрешаем дорастить заново.
                        packetBufferGrown = false
                        growAttempts = 0
                        applied = true
                        specState = newSpec
                        StreamLogger.d(TAG, "doRetuneOnWriter spec#${newSpec.serial}: пакет " +
                            "перестроен (${generated} кадров, якорь=${anchor.toInt()}, " +
                            "сдвиг скраба=${newSpec.scrubOffsetSec} (было $oldScrub)), " +
                            "кольцо сброшено (${if (wasPaused) "на паузе" else "в провале"})")
                    }
                }
                if (applied && !wasPaused) {
                    // Кольцо пусто после flush — наполняем ДО play(), иначе
                    // микшер подставит тишину (underrun) на самом старте.
                    writeOneChunk(track)
                }
            } catch (e: Exception) {
                StreamLogger.e(TAG, "doRetuneOnWriter spec#${newSpec.serial}: ${e.message}")
            }
        }
        val ok = applied
        controlHandler.post {
            if (!ok) {
                abortRetune(false)
            } else if (wasPaused) {
                // Свежий пакет начинается ровно с «сейчас» — возобновление
                // обязано встать в его начало, даже если Δ меньше порога.
                retuneStartAtZero = true
                val cb = retuneCallback
                retuneCallback = null
                cb?.invoke(true)
            } else {
                onRetuneReady()
            }
        }
    }

    /** Одна запись в кольцо трека (после перенастройки, до play()). */
    private fun writeOneChunk(track: AudioTrack) {
        val buf = directBuffer ?: return
        val chunkLimit = if (writerMaxChunkBytes > 0) writerMaxChunkBytes else writerPacketBytes.toLong()
        val chunk = minOf((writerPacketBytes - writerOffset).toLong(), chunkLimit).toInt()
        if (chunk <= 0) return
        buf.clear()
        buf.position(writerOffset)
        buf.limit(writerOffset + chunk)
        val written = track.write(buf, chunk, AudioTrack.WRITE_BLOCKING)
        if (written > 0) writerOffset += written
        else {
            StreamLogger.w(TAG, "writeOneChunk spec#${spec.serial}: written=$written " +
                "(кольцо после flush, трек на паузе)")
        }
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
        //
        // То же — провал перенастройки: его финалом был подъём звука, а нужен
        // выход в тишину. Рампа вниз УЖЕ идёт (её запустил retune), поэтому
        // новую не заводим, только снимаем таймеры подъёма. Задание при этом
        // остаётся в слоте: припаркованный писатель применит конфиг БЕЗ
        // провала — тишина для него и так уже есть.
        val wasRetuning = retuning
        cancelFadeCallbacks()
        if (wasRetuning) {
            fadeMode = FadeMode.OUT
            StreamLogger.d(TAG, "pause spec#${spec.serial}: провал перенастройки отменён — " +
                "задание сохранено, применится на парковке")
        }

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
        // fadeOutMs, а не [dur]: ветка `fadeMode == FadeMode.OUT` выше только
        // перехватывает уже идущую рампу и своей длительности не заводит.
        scheduleFadeCompletion(fadeOutMs, toZero = true, completion = completion)
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
        // Заморозка обнуляет любое неисполненное задание перемотки: цель
        // считалась от головы ПРЕДЫДУЩЕЙ паузы и к новой не имеет отношения.
        pendingSeekFrame.set(NO_SEEK)
        seekReadyLatch = null
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

    /**
     * Рассчитать АБСОЛЮТНЫЙ кадр пакета, чей отсчёт по кривой равен `now`.
     *
     * Это и есть «точное определение позиции внутри пакета», которого требует
     * инвариант приложения: `T = A0 + Δ·rate`, где A0 — слышимый кадр заморозки,
     * а Δ = normalize(now − A0).
     *
     * @param deltaSeconds Δ в секундах КРИВОЙ (считана менеджером).
     * @return целевой кадр на оси [generatedFrames] либо [NO_SEEK], если
     *         перемотка не нужна (Δ в пределах [SEEK_EPSILON_SECONDS]) или
     *         невозможна (нет трека/головы/остатка пакета).
     */
    private fun planResumeSeek(deltaSeconds: Float): Long {
        if (deltaSeconds <= SEEK_EPSILON_SECONDS) return NO_SEEK
        val track = audioTrack ?: return NO_SEEK
        val head = try { track.playbackHeadPosition } catch (_: Exception) { -1 }
        if (head < 0) return NO_SEEK

        // Слышимый кадр на оси пакета. Трек стоит, писатель припаркован, —
        // голова и [generatedFrames] заморожены, снимок согласован.
        val audibleFrame = head.toLong() + frameBias.get()
        val unplayed = generatedFrames - audibleFrame
        if (unplayed <= 0L) return NO_SEEK

        // Δ (секунды кривой) → кадры. Перевод идёт ЧЕРЕЗ ОКНО между слышимой
        // позицией и фронтиром, а не умножением на rate: в debug-режиме
        // виртуальных часов секунда аудио продвигает кривую на `scale`, и
        // масштаб входит ровно в обе величины — он сокращается. Никакого
        // дополнительного JNI-геттера масштаба не требуется.
        val a0 = audibleCurveSeconds() ?: return NO_SEEK
        val window = normalizeTimeOfDay(frontierCurveSeconds() - a0)
        if (window <= 0f) return NO_SEEK

        val skipFrames = (deltaSeconds / window * unplayed.toFloat()).toLong().coerceIn(0L, unplayed)
        val target = audibleFrame + skipFrames
        StreamLogger.d(TAG, "planResumeSeek spec#${spec.serial}: A0=$a0 окно=${window}s " +
            "Δ=${deltaSeconds}s → кадр $audibleFrame + $skipFrames = $target (фронтир=$generatedFrames)")
        return target
    }

    /**
     * Применить перемотку к пакету. Вызывается ТОЛЬКО писателем — он единственный
     * владелец и курсора записи, и буфера — и только пока трек стоит на паузе.
     *
     * Три шага:
     *   1) [android.media.AudioTrack.flush] выбрасывает PCM, УЖЕ ОТДАННЫЙ треку.
     *      Это единственный способ убрать кольцо: пропуск кадров пакета на него
     *      не влияет, потому что в кольце лежат КОПИИ этих кадров. Сам пакет
     *      при этом не трогается — он весь в [directBuffer];
     *   2) [frameBias] переякоривается по голове, прочитанной ПОСЛЕ flush:
     *      часть реализаций обнуляет её, часть — нет;
     *   3) курсор ставится ровно на целевой кадр — ВПЕРЁД или НАЗАД. Отмотка
     *      назад возможна именно потому, что пакет сохранён: PCM никуда не
     *      делся, его можно подать в трек повторно.
     *
     * @return новое смещение записи в пакете (байты). При неудачном flush
     *         деградирует к относительному пропуску — поведению до этой правки
     *         (кольцо доигрывает хвост, звук сходится с `now` через R секунд).
     */
    private fun applyResumeSeek(targetFrame: Long, offsetNow: Int, packetBytesNow: Int): Int {
        val track = audioTrack ?: return offsetNow
        val headBefore = try { track.playbackHeadPosition } catch (_: Exception) { -1 }
        val deltaFrames = if (headBefore >= 0) targetFrame - (headBefore + frameBias.get()) else 0L

        val flushed = try {
            track.flush()
            true
        } catch (e: Exception) {
            StreamLogger.w(TAG, "applyResumeSeek spec#${spec.serial}: flush не удался " +
                "(${e.message}) — деградация к относительному пропуску")
            false
        }

        if (!flushed) {
            if (deltaFrames <= 0L) return offsetNow
            frameBias.addAndGet(deltaFrames)
            val moved = (offsetNow + deltaFrames * frameBytes).coerceAtMost(packetBytesNow.toLong())
            StreamLogger.d(TAG, "applyResumeSeek spec#${spec.serial}: кольцо НЕ сброшено — " +
                "относительный пропуск $deltaFrames кадров (offset $offsetNow→$moved)")
            return moved.toInt()
        }

        val head = try { track.playbackHeadPosition } catch (_: Exception) { -1 }
        if (head < 0) return offsetNow

        // Кадр 0 текущего буфера на сквозной оси: буфер переиспользуется,
        // поэтому его начало = фронтир минус сколько кадров в нём помещается.
        val packetFrames = packetBytesNow.toLong() / frameBytes
        val firstFrame = generatedFrames - packetFrames
        val index = (targetFrame - firstFrame).coerceIn(0L, packetFrames)
        frameBias.set(targetFrame - head.toLong())

        val newOffset = (index * frameBytes).toInt()
        StreamLogger.d(TAG, "applyResumeSeek spec#${spec.serial}: кольцо сброшено, курсор " +
            "$offsetNow→$newOffset байт (кадр $targetFrame; буфер [$firstFrame, " +
            "${firstFrame + packetFrames}]; bias=${frameBias.get()})")
        return newOffset
    }

    override fun resume(onFullyStarted: () -> Unit, shape: FadeShape, skipSeconds: Float): Boolean {
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

        // ТОЧНАЯ ПЕРЕМОТКА ВНУТРЬ ПАКЕТА.
        //
        // Цель — абс. кадр T = A0 + Δ·rate, чей отсчёт по кривой ровно `now`.
        // Задание ставится ДО play(): писатель проснётся уже с ним.
        val a0 = audibleCurveSeconds()
        // Перенастройка на паузе перестроила пакет: его кадр 0 — это и есть
        // «сейчас», поэтому возобновление обязано встать в начало, даже когда Δ
        // меньше [SEEK_EPSILON_SECONDS]. Иначе кольцо доиграло бы остаток
        // СТАРОГО звука — а он уже не соответствует ни спеке, ни моменту суток.
        val forcedZero = retuneStartAtZero
        retuneStartAtZero = false
        val seekFrame = if (forcedZero) 0L else planResumeSeek(skipSeconds)
        var latch: CountDownLatch? = null
        if (seekFrame != NO_SEEK) {
            pendingSeekFrame.set(seekFrame)
            latch = CountDownLatch(1)
            seekReadyLatch = latch
            StreamLogger.d(TAG, "resume spec#${spec.serial}: перемотка на кадр $seekFrame " +
                "(Δ=${skipSeconds}s, A0=$a0) — звук продолжится с текущего момента суток")
        }

        // Позиция графика размораживается ДО play() — на КОНЕЧНУЮ точку
        // перемотки (A0 + Δ = now), а не на слышимую позицию заморозки.
        // Перенастройка на паузе уже пересадила пакет на «сейчас»: [a0] и есть
        // цель, прибавлять [skipSeconds] нельзя — Δ уже учтена якорем retune,
        // и сложение унесло бы указатель графика вперёд на длительность паузы.
        val anchor = when {
            a0 == null -> null
            forcedZero -> a0
            seekFrame == NO_SEEK -> a0
            else -> normalizeTimeOfDay(a0 + skipSeconds)
        }
        anchor?.let { nativeEngine?.resumeUiTimelineFrom(it) }

        // Порядок: снять паузу → дать писателю перемотать и НАПОЛНИТЬ кольцо →
        // рампа → play().
        //
        // Ожидание здесь, а не внутри писателя, потому что play() принадлежит
        // нити управления: после flush() кольцо пусто, и немедленный play()
        // означал бы тишину до первой записи. Зато сам трек ещё стоит на паузе,
        // поэтому писатель наполняет кольцо без гонки с микшером.
        paused = false
        wakeWriter()
        if (latch != null) {
            val ready = latch.await(SEEK_PREFILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            seekReadyLatch = null
            if (!ready) {
                StreamLogger.w(TAG, "resume spec#${spec.serial}: перемотка не уложилась в " +
                    "${SEEK_PREFILL_TIMEOUT_MS}мс — play с неполным кольцом")
            }
        }

        // Первый кадр после play() уходит под нулевым множителем — сохранённый
        // полноамплитудный остаток не даёт щелчка (фикс RC-1). Форма фейд-ина
        // ВСЕГДА EQUAL_POWER — нулевой терминальный наклон (фикс M2, см. start).
        applyShaper(from = 0f, to = 1f, durationMs = fadeInMs, shape = FadeShape.EQUAL_POWER)
        try {
            audioTrack?.play()
        } catch (e: Exception) {
            StreamLogger.e(TAG, "resume spec#${spec.serial}: play failed: ${e.message}")
            fadeMode = FadeMode.NONE
            // Задание перемотки снимаем: писатель его либо уже исполнил, либо
            // не исполнит вовсе, и к следующей паузе оно неприменимо.
            pendingSeekFrame.set(NO_SEEK)
            seekReadyLatch = null
            return false
        }
        // Будить писателя и после play(): если он заблокировался в write()
        // (кольцо заполнено, а трек ещё стоял), write() сам не вернётся.
        wakeWriter()

        StreamLogger.d(TAG, "resume spec#${spec.serial}: ПРОДОЛЖЕН audible=${audibleCurveSeconds()} " +
            "цель=$seekFrame generatedFrames=$generatedFrames")
        val completion = Runnable {
            if (lifecycleRef.get() == StreamLifecycle.PLAYING && fadeMode == FadeMode.IN) {
                // не закрываем шейпер наверху — см. комментарий в start() (M1)
                logShaperSettle("resume")
                fadeMode = FadeMode.NONE
                onFullyStarted()
            }
        }
        fadeCompletion = completion
        scheduleFadeCompletion(fadeInMs, toZero = false, completion = completion)
        return true
    }

    /**
     * Переякорить UI-указатель графика на заданную позицию кривой.
     *
     * Вызывается менеджером сразу после [resume] с ненулевым пропуском: звук
     * (после того как кольцо трека доиграет старый хвост) продолжается с
     * текущего момента суток, и индикатор обязан показывать ту же точку, а не
     * слышимое время по голове трека, которое ещё R секунд отстаёт.
     */
    fun reanchorUiTimeline(seconds: Float) {
        nativeEngine?.resumeUiTimelineFrom(seconds)
    }

    /**
     * Слышимая позиция кривой (секунды суток) по позиции головы воспроизведения.
     *
     * Это мост между осью AudioTrack (кадры) и осью кривой (секунды суток).
     * UI-экстраполяция для этой цели не годится: она ограничена концом
     * сгенерированного пакета и обновляется только по опросу, поэтому на
     * паузе (когда опрос остановлен) даёт устаревшее значение.
     *
     * Голова трека переводится на ось пакета через [frameBias]: `кадр = голова +
     * bias`. Смещение переякоривается перемоткой, поэтому слышимая позиция
     * после возобновления СРАЗУ равна текущему моменту суток, а не догоняет
     * его по мере доигрывания кольца.
     */
    fun audibleCurveSeconds(): Float? {
        val eng = nativeEngine ?: return null
        val track = audioTrack ?: return null
        val head = try { track.playbackHeadPosition } catch (_: Exception) { -1 }
        if (head < 0) return null
        return eng.getAudibleTimeSeconds(head.toLong() + frameBias.get(), generatedFrames)
    }

    /**
     * Слышимая позиция кривой БЕЗ оглядки на перемотку — «что в динамике».
     *
     * Раньше метод НЕ прибавлял смещение к голове и тем самым вскрывал
     * ПЕРЕХОДНУЮ ЗАДЕРЖКУ: после относительного пропуска компенсированная
     * [audibleCurveSeconds] мгновенно прыгала на `now`, а реальный звук ещё
     * R секунд доигрывал замороженное кольцо. Разница и была мерой точности.
     *
     * После перехода на абсолютную перемотку с flush() этой задержки НЕТ ПО
     * ПОСТРОЕНИЮ: кольцо сбрасывается, [frameBias] переякоривается по голове,
     * и компенсированная позиция равна реальной. Обе величины совпадают, а
     * расхождение `now − raw` остаётся честной end-to-end метрикой — оно
     * ненулевое ровно тогда, когда звук действительно отстал (underrun,
     * просадка писателя), а не «по расчёту должен был».
     *
     * Метод сохранён: на него опираются debug-команда `audibleraw` и
     * tools/dbgverify_resume.sh.
     */
    fun audibleCurveSecondsRaw(): Float? = audibleCurveSeconds()

    /** Смещение оси трека к оси пакета ([frameBias]) — для диагностики. */
    fun skippedFramesCount(): Long = frameBias.get()

    /**
     * ФРОНТИР ГЕНЕРАЦИИ (секунды суток): конец уже сгенерированного аудио.
     *
     * Правая граница окна актуальности замороженного пакета: пока текущий
     * момент суток лежит внутри [audible, frontier], звук для него УЖЕ
     * посчитан — его надо лишь дописать, выбросив устаревшую голову.
     */
    fun frontierCurveSeconds(): Float = nativeEngine?.getCurveTimeSeconds() ?: 0f

    /**
     * Виртуальное время суток (только debug): носитель времени при включённых
     * виртуальных часах — настенные часы там идут с масштабом и могут быть
     * перемотаны. 0 — виртуальное время выключено либо release-сборка.
     */
    fun virtualTimeOfDaySeconds(): Float = nativeEngine?.debugGetVirtualTime()?.toFloat() ?: 0f

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
    /**
     * Задать огибающую громкости.
     *
     * Шейпер ОДИН на всё время жизни трека и НИКОГДА не закрывается в рабочих
     * путях, а база трека всегда равна [userVolume]. Отсюда два следствия:
     *
     *  1. Ординаты кривой — АБСОЛЮТНАЯ огибающая (из [from] в [to]), а не
     *     нормированная на стартовое значение. Эффективная громкость =
     *     `userVolume · кривая`, как и раньше, но без возни с базой.
     *  2. «Момент, когда рампу можно закрыть» перестаёт существовать как
     *     понятие: закрывать нечего. Именно для него раньше и опрашивался
     *     живой множитель шейпера.
     *
     * @param from стартовое значение; ИГНОРИРУЕТСЯ, если шейпер уже жив —
     *        тогда берётся фактическая громкость, чтобы замена кривой не дала
     *        ступеньку. Передавать его всё равно полезно для читаемости вызова.
     */
    private fun applyShaper(from: Float, to: Float, durationMs: Long, shape: FadeShape = FadeShape.LINEAR): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // minSdk 26: ветка мёртвая, но даже здесь не ступенька, а рампа (M3).
            manualBaseRamp(from, to, durationMs)
            return durationMs
        }
        // ОДНО чтение текущего множителя — чтобы начать новую кривую ровно там,
        // где звучит сейчас. Это не «мониторинг тишины»: значение не определяет
        // никакого решения, оно лишь задаёт стартовую ординату. join=true ниже
        // страхует от дрейфа между этим чтением и apply()/replace().
        val live = liveShaperVolume()
        val f = (live ?: from).coerceIn(0f, 1f)
        val t = to.coerceIn(0f, 1f)
        // Нулевая длительность недопустима для конфигурации шейпера; 1 мс —
        // «мгновенно», но через шейпер, а не ступенькой базы.
        val dur = durationMs.coerceAtLeast(1L)
        return try {
            val (times, vols) = buildCurve(f, t, shape)
            val cfg = VolumeShaper.Configuration.Builder()
                .setDuration(dur)
                .setCurve(times, vols)
                .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                .build()
            val shaper = volumeShaper
            if (shaper != null) {
                // join = true: новая кривая приклеивается к текущей громкости,
                // поэтому разрыва нет даже если чтение выше чуть устарело.
                shaper.replace(cfg, VolumeShaper.Operation.PLAY, true)
            } else {
                audioTrack?.setVolume(userVolume)
                volumeShaper = audioTrack?.createVolumeShaper(cfg)
                volumeShaper?.apply(VolumeShaper.Operation.PLAY)
            }
            dur
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
            if (tryLinearFallback(f, t, dur)) {
                StreamLogger.w(TAG, "VolumeShaper: аварийная линейная рампа $f->$t за ${dur}мс")
                dur
            } else {
                // БЫЛО: audioTrack?.setVolume(if (to > 0f) userVolume else 0f)
                // — скачок на всю базу, гарантированный щелчок (M3). СТАЛО:
                // ступенчатая рампа базы. Не сэмпл-точно, но каждая ступень
                // ≤ 10 % амплитуды — стык полного звука остаётся мягким.
                Log.e(TAG, "VolumeShaper: fallback тоже отказал — ступенчатая рампа базы")
                StreamLogger.e(TAG, "VolumeShaper: fallback отказал — ступенчатая рампа базы")
                manualBaseRamp(f, t, dur)
                dur
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
        // Шейпер долгоживущий, поэтому f — НЕ всегда 0 или 1: рампа может
        // стартовать посреди незавершённой предыдущей (пауза поверх resume и
        // т.п.). Кривая обязана начинаться ровно в f, иначе join не спасёт —
        // первая же точка даст ступеньку. Обобщённая equal-power:
        //   v(p) = f·cos(θ) + t·sin(θ), θ = p·π/2
        // при (f,t) = (1,0) это чистое затухание cos, при (0,1) — нарастание
        // sin, как и раньше; при обоих ненулевых сумма квадратов сохраняет
        // постоянную мощность «старого» и «нового» материала.
        if (kotlin.math.abs(t - f) < 1e-4f) {
            return floatArrayOf(0f, 1f) to floatArrayOf(f, t)
        }
        val n = 15
        val times = FloatArray(n + 1)
        val vols = FloatArray(n + 1)
        for (i in 0..n) {
            val p = i.toFloat() / n
            times[i] = p
            val theta = p * (Math.PI.toFloat() / 2f)
            // Кламп ОБЯЗАТЕЛЕН, а не «на всякий случай»: cos(π/2) в одинарной
            // точности равен -4.37e-8, а не ровно 0. VolumeShaper принимает
            // кривую только целиком и только из [0,1] — одна отрицательная
            // точка роняет createVolumeShaper с
            //   "volumes for linear scale must be between 0.f and 1.f"
            // (на устройстве: check index 15 — последняя точка затухания).
            // Погрешность 4e-8 не влияет ни на слух, ни на энергию.
            vols[i] = (f * kotlin.math.cos(theta) + t * kotlin.math.sin(theta))
                .coerceIn(0f, 1f)
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

        // Провал перенастройки больше некому исполнять: трек утилизируется, и
        // писатель из цикла выйдет. Штатный [stop] снимает задание сам (там же
        // теряется и колбэк), поэтому сюда мы попадаем только с принудительной
        // утилизацией ([abort]/повторный stop) — и обязаны вернуть менеджеру
        // `false`, иначе он останется ждать завершения провала, которого не
        // будет.
        if (retuning || pendingRetune.get() != null || retuneCallback != null) {
            StreamLogger.d(TAG, "releaseInternal spec#${spec.serial}: снимаю провал " +
                "перенастройки (задание потеряно)")
            pendingRetune.set(null)
            cancelRetuneTimers()
            val cb = retuneCallback
            retuneCallback = null
            cb?.invoke(false)
        }

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
        // разделяемую память трека в AudioFlinger до создания следующего:
        // кольцо на 48 кГц весит 3.84 МиБ, и единственный оставшийся путь со
        // вторым треком (recreateTrack при смене SR) без этого не выделил бы
        // его на той же куче клиента (createTrack_l -12).
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
                // Курсор пакета и его длина — ПОЛЯ, а не локальные: их подменяет
                // [doRetuneOnWriter], который живёт отдельным методом (см.
                // комментарий к полям выше). Владелец по-прежнему одна нить —
                // писатель.
                writerPacketBytes = preparedPacketBytes
                // ФИКС RC-1: если пакет уже записан в start() (прайминг), стартуем
                // со смещения, чтобы не дублировать и не оставлять трек без данных.
                writerOffset = if (preparedPrefilled) preparedPacketBytes else 0
                // true — после перемотки кольцо сброшено и его надо наполнить
                // ДО play(); снимается, как только заполнение дойдёт до цели.
                writerPrefillUntilPlay = false
                // Латч именно этой перемотки: за время ожидания нить управления
                // успевает поставить следующий, и будить надо тот, что наш.
                writerPrefillLatch = null

                // Верхняя граница чанка записи — инвариант подпитки.
                // write(WRITE_BLOCKING) разблокируется, когда в кольце трека
                // есть место под ВЕСЬ чанк, то есть заполненность упала до
                // `buffer - chunk`. Значит ровно столько аудио и остаётся
                // проиграть, если писатель встанет: это и есть запас до underrun.
                // Держим его не меньше UNDERRUN_HEADROOM_MS, считая от
                // ФАКТИЧЕСКОГО размера кольца (HAL вправе урезать запрошенный).
                // Период пробуждений = длительность чанка, поэтому этот же
                // предел задаёт и частоту wakeups: 3600/8 = 450 в час.
                val rate = spec.sampleRate.value.toLong()
                val targetChunk = rate * frameBytes * WRITE_CHUNK_MS / 1000
                val headroomBytes = rate * frameBytes * UNDERRUN_HEADROOM_MS / 1000
                val minChunkBytes = rate * frameBytes * MIN_WRITE_CHUNK_MS / 1000
                // Чанк выводится из ЗАПАСА, а запас не возникает «из остатка».
                // См. [UNDERRUN_HEADROOM_MS]: раньше здесь было
                // `min(8 с, кольцо − 1 с)`, и на 44.1/48 кГц, где байтовый
                // потолок кольца урезал его ниже 9 с, запас схлопывался ровно
                // в 1 с. Потолок снят (§5.4.5) — ветка вырожденного кольца
                // теперь срабатывает только если HAL урезал кольцо сам.
                val byHeadroom = audioTrackBufferSize.toLong() - headroomBytes
                val degenerate = byHeadroom < minChunkBytes
                val maxChunkBytes =
                    if (!degenerate) minOf(targetChunk, byHeadroom)
                    // Вырожденное кольцо (запас + минимальный чанк в него не
                    // влезает): пишем половиной кольца — иначе write() не
                    // разблокируется вовсе.
                    else maxOf(audioTrackBufferSize.toLong() / 2, frameBytes.toLong())
                // Границу чанка видит и [writeOneChunk]: она пишет в кольцо ВНЕ
                // этого цикла — сразу после [doRetuneOnWriter], до play().
                writerMaxChunkBytes = maxChunkBytes
                StreamLogger.d(TAG, "writerLoop spec#${spec.serial}: кольцо " +
                    "${audioTrackBufferSize * 1000L / (rate * frameBytes)}мс, чанк " +
                    "${maxChunkBytes * 1000L / (rate * frameBytes)}мс, запас до underrun " +
                    "${(audioTrackBufferSize.toLong() - maxChunkBytes) * 1000L / (rate * frameBytes)}мс" +
                    (if (degenerate) " (КОЛЬЦО ВЫРОЖДЕНО — запас не выдержан)" else ""))
                // Сколько кадров обязано лежать в кольце, прежде чем перемотка
                // сочтёт себя готовой к play(): кольцо минус запас, который
                // писатель и так не занимает (тот же MIN_WRITE_MARGIN_MS, что
                // вычтен из maxChunkBytes выше) — иначе цель недостижима и
                // латч всегда выгорал бы по таймауту.
                val prefillFrames = maxOf(
                    1L,
                    audioTrackBufferSize.toLong() / frameBytes - rate * SEEK_PREFILL_MARGIN_MS / 1000
                )
                // Цель, ДОСТИЖИМАЯ ОДНОЙ записью. write(WRITE_BLOCKING)
                // разблокируется, лишь когда в кольце свободно `chunk`, поэтому
                // за один проход оно заполняется ровно до `кольцо − chunk`.
                // Без minOf латч стабильно выгорал по [SEEK_PREFILL_TIMEOUT_MS]:
                // возобновление начиналось с предупреждением и 150 мс на нити
                // актёра, а после [doRetuneOnWriter] пакет и вовсе короткий.
                val prefillGoal = minOf(prefillFrames, maxChunkBytes / frameBytes)
                while (lifecycleRef.get() == StreamLifecycle.PLAYING) {
                    // ПАУЗА: парковка ДО генерации и ДО записи.
                    //   * генерация запрещена — она продвинула бы фронтир кривой
                    //     вперёд относительно звучащего участка (и пакет, ради
                    //     которого всё затевалось, пришлось бы выбросить);
                    //   * запись в приостановленный трек заблокировала бы
                    //     WRITE_BLOCKING до снятия паузы.
                    // writerPacketBytes/writerOffset — поля, поэтому остаток
                    // пакета и смещение в нём переживают паузу целиком:
                    // возобновление дописывает ровно тот же буфер с того же
                    // места. Перенастройка ([doRetuneOnWriter]) подменяет их
                    // целиком — см. две ветки ниже.

                    // ПЕРЕНАСТРОЙКА НА ПАУЗЕ ([retune], §3.1 плана). Тишина уже
                    // есть, припаркованный писатель применяет конфиг и снова
                    // встаёт в парковку: провала нет вовсе.
                    if (paused && pendingRetune.get() != null) {
                        doRetuneOnWriter()
                        continue
                    }
                    if (paused) {
                        parkWriter()
                        continue
                    }
                    // ПЕРЕНАСТРОЙКА В ИГРЕ ([retune], §3.2 плана). Работаем
                    // только по флагу [retuneGo]: до него актёр ещё не погасил
                    // трек, и писатель обязан продолжать его питать — иначе
                    // пауза посреди рампы дала бы щелчок.
                    if (retuning && retuneGo) {
                        doRetuneOnWriter()
                        continue
                    }
                    // ПЕРЕМОТКА ВНУТРЬ ПАКЕТА (возобновление после паузы).
                    //
                    // СУТЬ ПРИЛОЖЕНИЯ: звук обязан соответствовать ТЕКУЩЕМУ
                    // моменту суток. Пакет считался от точки A0, где звук
                    // встал на паузу; за паузу часы ушли на Δ, и нужен кадр
                    // T = A0 + Δ·rate. Пересчитывать пакет ради этого незачем —
                    // аудио для `now` в нём уже есть, нужно лишь встать на
                    // нужное место.
                    //
                    // Здесь, а не в resume(): двигать [offset] вправе только
                    // писателю (он единственный владелец пакета), а нить
                    // управления лишь оставляет задание. Проверка стоит ДО
                    // генерации: если цель уперлась в конец пакета, следующая
                    // ветка дорастит его штатно.
                    //
                    // ОТНОСИТЕЛЬНЫЙ пропуск («Δ кадров от курсора») здесь не
                    // годится: между курсором и динамиком лежит кольцо трека
                    // (R, до TRACK_BUFFER_MS полноамплитудного PCM, записанного
                    // ДО паузы). Кольцо — это уже отданные треку КОПИИ, их не
                    // уберёшь пропуском по пакету, и ровно их пользователь
                    // слышит первые R секунд: «продолжение с той же позиции».
                    // Поэтому цель АБСОЛЮТНАЯ, а кольцо сбрасывается flush().
                    val seekTarget = pendingSeekFrame.getAndSet(NO_SEEK)
                    if (seekTarget != NO_SEEK) {
                        writerOffset = applyResumeSeek(seekTarget, writerOffset, writerPacketBytes)
                        writerPrefillUntilPlay = true
                        writerPrefillLatch = seekReadyLatch
                    }
                    if (writerOffset >= writerPacketBytes) {
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
                        writerPacketBytes = generated * 2 * 4
                        writerOffset = 0
                        generatedFrames += generated.toLong()
                    }
                    val buf = directBuffer ?: break
                    // Пауза прерывает track.write(WRITE_BLOCKING) посреди пакета и
                    // оставляет буфер с позицией/лимитом под ту порцию, что писалась
                    // в момент остановки, — лимит оказывается меньше текущего offset.
                    // А возобновление сразу сдвигает offset на Δ·rate кадров пропуска,
                    // и buf.position(offset) падал бы с IllegalArgumentException
                    // ("Bad position …"), роняя весь процесс. Возвращаем буферу
                    // полный вид (лимит = вместимость) перед повторной установкой
                    // позиции — тот же инвариант, что держит ветка доращивания
                    // (buf.clear() выше). Без этого SOFT-возобновление нежизнеспособно.
                    buf.clear()
                    val chunk = minOf((writerPacketBytes - writerOffset).toLong(), maxChunkBytes).toInt()
                    buf.position(writerOffset)
                    buf.limit(writerOffset + chunk)
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
                        // Плановая пауза ПЕРЕНАСТРОЙКИ: [finishRetune] снимает
                        // трек, чтобы писатель перестроил пакет. Это не ошибка —
                        // писатель обязан дойти до начала цикла, где его ждёт
                        // [doRetuneOnWriter].
                        if (retuning) {
                            StreamLogger.d(TAG, "writerLoop spec#${spec.serial}: write прерван " +
                                "перенастройкой (offset=$writerOffset/$writerPacketBytes)")
                            continue
                        }
                        if (paused) {
                            StreamLogger.d(TAG, "writerLoop spec#${spec.serial}: write прерван паузой " +
                                "(пакет сохранён, offset=$writerOffset/$writerPacketBytes)")
                            continue
                        }
                        StreamLogger.e(TAG, "writerLoop spec#${spec.serial}: write failed=$written")
                        onRuntimeError(this, "write failed: $written")
                        break
                    }
                    writerOffset += written
                    debugPcmDump?.let { out ->
                        try {
                            val d = buf.duplicate()
                            d.position(writerOffset - written)     // байты: буфер в байтовой позиции
                            d.limit(writerOffset)
                            val chunk = ByteArray(d.remaining())
                            d.get(chunk)
                            out.write(chunk)
                        } catch (_: Exception) {}
                    }

                    // Готовность перемотки: кольцо наполнено — можно звать
                    // play() без разрыва. Трек ещё на паузе, голова стоит,
                    // поэтому заполнение растёт от одной записи к другой без
                    // гонки с микшером.
                    if (writerPrefillUntilPlay) {
                        val h = try { track.playbackHeadPosition } catch (_: Exception) { -1 }
                        val inRing = if (h < 0) 0L else {
                            (generatedFrames - writerPacketBytes.toLong() / frameBytes + writerOffset / frameBytes) -
                                (frameBias.get() + h)
                        }
                        if (inRing >= prefillGoal) {
                            writerPrefillUntilPlay = false
                            writerPrefillLatch?.countDown()
                            writerPrefillLatch = null
                            StreamLogger.d(TAG, "writerLoop spec#${spec.serial}: перемотка готова " +
                                "(в кольце $inRing из $prefillGoal кадров; цель без учёта чанка — $prefillFrames)")
                        }
                    }
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

    // ФИКС 3. Часы сессии, переносимые на следующий поток при сквозном
    // переключении (целые секунды — в первую секунду жизни потока дают 0).
    fun getElapsedMs(): Long = (nativeEngine?.getElapsedSeconds() ?: 0) * 1000L

    /**
     * Позиция кривой уходящего потока (секунды суток) или `null`, если движок
     * уже разрушен.
     *
     * НИКАКИХ `?: 0f`. Раньше разрушенный движок молча отдавал **0**, а 0 — это
     * легальная полночь: отличить «ответа нет» от «полночь» было невозможно, и
     * подставленный ноль становился якорем следующего потока. Это был ВТОРОЙ
     * независимый источник защёлкивания на 00:00 (первый — протухший
     * нативный кэш UI-времени).
     *
     * `null` — честный ответ: «спрашивать нечего». Решение принимает вызывающая
     * сторона, и оно всегда одно — взять «сейчас».
     */
    fun getCurrentCurveTimeSeconds(): Float? = nativeEngine?.getCurrentTimeOfDay()?.toFloat()

    /** Жив ли нативный движок (можно ли вообще спрашивать координаты потока). */
    fun hasLiveEngine(): Boolean = nativeEngine != null

    override fun isChannelsSwapped(): Boolean = nativeEngine?.isChannelsSwapped() ?: false
    override fun getFrequenciesAtCurrentTime(): Pair<Float, Float>? =
        nativeEngine?.getFrequenciesAtCurrentTime()
}
