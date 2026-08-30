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
        private const val TRACK_BUFFER_MS = 10000         // внутренний буфер AudioTrack
        /**
         * Гранулярность записи — и она же определяет, как часто просыпается
         * писатель. write(WRITE_BLOCKING) возвращается, только когда в кольце
         * трека есть место под ВЕСЬ чанк, то есть заполненность упала до
         * `buffer - chunk`; в установившемся режиме период между записями
         * равен ровно длительности чанка:
         *
         *     пробуждений в час = 3_600_000 / WRITE_CHUNK_MS
         *
         * Было 500 мс (7200/час) → 2000 мс (1800/час) → 8000 мс (450/час).
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
         */
        private const val MIN_WRITE_MARGIN_MS = 2000
        /**
         * Опрос парковки писателя на паузе. wait() с таймаутом, а не вечное
         * ожидание: пропущенный notify не превращается в зависший поток —
         * writer сам проверит паузу и выход из цикла не позже чем через это
         * время. 200 мс — компромисс: просыпаний почти нет, реакция на
         * stop()/release() из паузы — не дольше одного тика.
         */
        private const val PARK_POLL_MS = 200L
        private const val SECONDS_PER_DAY = 86400
        private const val MAX_BUFFER_MINUTES = 60
        /**
         * Потолок размера direct-буфера НА ПОТОК (native-память, вне Java-heap,
         * но в пределах RSS-бюджета LMK).
         *
         * Буфер — сырые PCM_FLOAT: 8 байт/кадр × SR × interval. Это НЕ сжатие —
         * уменьшить нельзя, не поменяв формат. Отсюда пределы:
         *   600 с @48 кГц = 230 МБ/поток  — влезает на любом 64-битном устройстве;
         *   3600 с @48 кГц = 1.38 ГБ/поток (2.76 ГБ в кроссфейде CURRENT+NEXT) —
         *     физически невозможно: LMK убьёт процесс ещё до этого, а на 32-bit
         *     ABI allocateDirect не выделит смежные 1+ ГБ из-за узкого VA.
         * Поэтому потолок — не «жадность», а гарантия, что настройка bufferInterval
         * не уронит воспроизведение: он честно ограничивает то, что устройство
         * реально может держать, а реальное значение пишется в лог (см. prepare()).
         *
         * Выбор числа: 256 МБ на 64-бит — это ~11 мин при 48 кГц на ПОТОК, то есть
         * дефолтный 600 с (10 мин) честно влезает целиком при ЛЮБОМ SR, а
         * усекается только 3600 с (где физика не пускает). На 32-bit VA узок —
         * 96 МБ, иначе allocateDirect падает с OOM ещё до 200 МБ. При желании
         * поднять предел (например, до 512 МБ) — имейте в виду фоновый RSS-бюджет
         * LMK: часы воспроизведения с выключенным экраном не прощают лишней памяти.
         */
        private val maxBufferBytes: Long
            get() = if (android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
                256L * 1024 * 1024 else 96L * 1024 * 1024
        /**
         * Сколько ждём выхода писателя перед уничтожением нативного движка.
         * ОБЯЗАН покрывать один полный чанк записи (WRITE_CHUNK_MS), иначе
         * таймаут наступит, пока писатель штатно стоит в track.write(), и
         * владение движком передастся ему без необходимости.
         */
        private const val WRITER_EXIT_WAIT_MS = WRITE_CHUNK_MS + 1500L
        /**
         * Короткая грейс-фаза в releaseInternal(): сколько даём писателю на
         * выход ПОСЛЕ того, как трек уже снят и write() разблокирован.
         * Полный WRITER_EXIT_WAIT_MS (3.5 с) блокировать нить актёра нельзя —
         * на ней висят все таймеры фейдов менеджера.
         */
        private const val WRITER_HANDOFF_GRACE_MS = 250L
    }

    private val lifecycleRef = AtomicReference(StreamLifecycle.CREATED)
    override val lifecycle: StreamLifecycle get() = lifecycleRef.get()

    @Volatile private var fadeMode = FadeMode.NONE
    @Volatile private var userVolume = spec.volume

    /**
     * Поток звучит, но рампа fade-in ещё НЕ завершена.
     *
     * Менеджеру важно: повышать NEXT в этом состоянии нельзя. EQUAL_POWER
     * (sin²+cos²=1) корректен ровно для ДВУХ одновременно звучащих потоков;
     * повышение посреди fade-in даёт ТРИ живых трека — суммарная энергия
     * уезжает, и вместо кроссфейда слышен провал/щелчок. Повышение обязано
     * дождаться onSilent от CURRENT.
     */
    override val isFadingIn: Boolean
        get() = lifecycleRef.get() == StreamLifecycle.PLAYING && fadeMode == FadeMode.IN

    private var nativeEngine: NativeAudioEngine? = null
    private var audioTrack: AudioTrack? = null
    private var directBuffer: ByteBuffer? = null
    private var writerThread: HandlerThread? = null
    private var writerHandler: Handler? = null
    private var volumeShaper: VolumeShaper? = null
    private var samplesPerChannel = 0
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

    /** Колбэк «поток тих» (рампа в нуле, ДО релиза) — точка повышения NEXT при кроссфейде. */
    @Volatile private var pendingOnSilent: (() -> Unit)? = null

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

            // 4. Буферы с теми же капами, что и в старом движке.
            val maxSamplesByMinutes = rate.toLong() * 60 * MAX_BUFFER_MINUTES
            val maxSamplesByBytes = maxBufferBytes / 8L
            val requestedSamples = rate.toLong() * bufferIntervalMs / 1000L
            samplesPerChannel = minOf(
                requestedSamples,
                maxSamplesByMinutes,
                maxSamplesByBytes
            ).toInt()
            // Потолок по байтам — это предел устройства (см. maxBufferBytes),
            // а не рабочий предел. Дефолтный 600 с влезает целиком при любом SR
            // (максимум 230 МБ на HIGH); урезается только то, что физически не
            // держится (напр. 3600 с @44.1 кГц = 1.27 ГБ/поток). Усечение
            // не молчит: пишем запрошенный и реальный интервал в лог.
            if (samplesPerChannel < requestedSamples) {
                StreamLogger.w(TAG, "prepare spec#${spec.serial}: buffer interval clamped " +
                    "by device RAM limit ${maxBufferBytes / (1024 * 1024)}MB: " +
                    "requested ${bufferIntervalMs}ms -> effective ${samplesPerChannel * 1000L / rate}ms @ ${rate}Hz")
            }
            directBuffer = allocateDirect(samplesPerChannel * 2 * 4, rate)
                ?: throw OutOfMemoryError("direct buffer unavailable")
            // Реальная ёмкость может оказаться меньше запрошенной: при OOM
            // allocateDirect() делит размер пополам. Урезаем длину пакета по
            // факту — иначе JNI вернёт 0 («buffer too small») и звук встанет.
            val capacitySamples = directBuffer!!.capacity() / 8
            if (capacitySamples in 1 until samplesPerChannel) {
                samplesPerChannel = capacitySamples
                StreamLogger.w(TAG, "prepare spec#${spec.serial}: allocateDirect halved to " +
                    "${capacitySamples * 1000L / rate}s @ ${rate}Hz (requested " +
                    "${requestedSamples * 1000L / rate}s) — недостаточно RAM")
            }
            // Запас по времени: если буфер урезан OOM ниже внутреннего буфера
            // трека, писатель не успеет подпитывать трек — это гарантированный
            // underrun. Лучше упасть на prepare(), чем щёлкать весь сеанс.
            val minSafeSamples = rate * TRACK_BUFFER_MS / 1000
            if (samplesPerChannel < minSafeSamples) {
                throw OutOfMemoryError(
                    "buffer ${samplesPerChannel} samples < track buffer $minSafeSamples " +
                        "(rate=$rate) — underrun неизбежен"
                )
            }

            // 5. Первый пакет — КОРОТКИЙ (до 2 с): подготовка быстрая и не блокирует
            // актёр надолго; полный интервал сгенерирует писатель, пока трек уже играет.
            val prepareSamples = minOf(samplesPerChannel, rate * 2)
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
        val size = maxOf(minBuffer.toLong(), rate.toLong() * 2 * 4 * TRACK_BUFFER_MS / 1000).toInt()
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

    private fun allocateDirect(sizeBytes: Int, rateHz: Int): ByteBuffer? {
        val minSize = maxOf(audioTrackBufferSize, rateHz * 2 * 4)
        var size = sizeBytes
        while (true) {
            try {
                return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            } catch (e: OutOfMemoryError) {
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

    override fun stop(
        onFullyStopped: () -> Unit,
        onSilent: (() -> Unit)?,
        shape: FadeShape
    ) {
        when (lifecycleRef.get()) {
            StreamLifecycle.RELEASED, StreamLifecycle.FAILED -> {
                StreamLogger.d(TAG, "stop spec#${spec.serial}: уже RELEASED/FAILED — мгновенные колбэки")
                onSilent?.invoke(); onFullyStopped(); return
            }
            // Поток ещё НЕ звучал — утилизация бесшумна (кейс "остановлен в очереди").
            StreamLifecycle.CREATED, StreamLifecycle.PREPARED -> {
                StreamLogger.d(TAG, "stop spec#${spec.serial}: не играл — тихий abort")
                abort(); onSilent?.invoke(); onFullyStopped(); return
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
            fadeCompletion?.let { controlHandler.removeCallbacks(it) }
            fadeMode = FadeMode.OUT
            pendingOnSilent = onSilent
            finalizeStop(onFullyStopped)
            return
        }
        fadeMode = FadeMode.OUT
        pendingOnSilent = onSilent
        fadeCompletion?.let { controlHandler.removeCallbacks(it) }   // снять фейд-ин, если был

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

        // Поток гарантированно тих (рампа в нуле, база 0) — сообщаем менеджеру
        // ДО перехода в STOPPING: это точка повышения NEXT при кроссфейде.
        pendingOnSilent?.let {
            pendingOnSilent = null
            try { it() } catch (t: Throwable) { Log.e(TAG, "onSilent failed: ${t.message}") }
        }

        if (!lifecycleRef.compareAndSet(StreamLifecycle.PLAYING, StreamLifecycle.STOPPING)) {
            onFullyStopped(); return
        }
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
        fadeCompletion?.let { controlHandler.removeCallbacks(it) }   // отменить утилизацию
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
        fadeCompletion?.let { controlHandler.removeCallbacks(it) }
        fadeCompletion = null

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
        fadeCompletion = null

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
        fadeCompletion?.let { controlHandler.removeCallbacks(it) }
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
            vols[i] = if (rampUp) {
                t * kotlin.math.sin(p * (Math.PI.toFloat() / 2f))
            } else {
                f * kotlin.math.cos(p * (Math.PI.toFloat() / 2f))
            }
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
            try {
                writerExitLatch.await(WRITER_EXIT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (writerExitLatch.count > 0L) {
                // Писатель жив: не трогаем движок — он освободит его сам при
                // выходе (pause/stop/release трека ниже разблокируют write()).
                engineOwnedByWriter = true
                StreamLogger.w(TAG, "releaseInternal spec#${spec.serial}: писатель не вышел " +
                    "за ${WRITER_EXIT_WAIT_MS}мс — движок освобождает сам писатель " +
                    "(consumed=$writerConsumedEngine)")
            }
        }

        closeShaper()
        // pause/stop/release трека разблокируют писателя, если он застрял
        // в track.write(WRITE_BLOCKING) — он получит ошибку записи и выйдет.
        try { audioTrack?.pause() } catch (_: Exception) {}
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
                        val buf = directBuffer ?: break
                        buf.clear()
                        val generated = engine.generateBufferDirect(buf, samplesPerChannel)
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
                        // track.pause() прерывает заблокированный write()
                        // (mProxy->interrupt): это НЕ ошибка и НЕ потеря данных —
                        // кадры, не принятые треком, остаются в пакете по offset.
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
