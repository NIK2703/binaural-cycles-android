package com.binauralcycles.debug

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.binaural.core.audio.debug.DebugClock
import com.binaural.core.audio.debug.DebugNativeClock
import com.binaural.core.audio.stream.BinauralStreamImpl
import com.binaural.core.audio.stream.PacketMemoryBudget
import com.binaural.core.audio.model.SampleRate
import com.binaural.core.audio.model.BinauralPreset
import com.binaural.core.audio.model.ChannelSwapMode
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyMath
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.NormalizationType
import com.binaural.core.audio.model.RelaxationModeSettings
import com.binaural.core.audio.model.VolumeNormalizationSettings
import com.binaural.data.preferences.BinauralPreferencesRepository
import com.binauralcycles.MainActivity
import com.binauralcycles.service.BinauralPlaybackService
import com.binauralcycles.viewmodel.buildPlaybackConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/**
 * Исполнитель текстовых команд поверх СЕРВИСА и репозитория, а не ViewModel.
 *
 * Первая версия жила в `BinauralViewModel` и потому молча умирала вместе с
 * активити: стоило экрану погаснуть, как любая команда отвечала «ViewModel не
 * подключён». Проверять кроссфейд при выключенном экране нельзя было никак,
 * а будить устройство ради каждого замера — значит мешать и жечь батарею.
 *
 * Теперь исполнитель создаётся в `Application` и работает, пока жив процесс:
 *  - воспроизведение — через [BinauralPlaybackService] (он сам себя останавливает
 *    при остановке звука, поэтому экземпляр может быть `null` — это не ошибка);
 *  - пресеты и настройки — через [BinauralPreferencesRepository] из Hilt.
 *
 * Комады исполняются на главной нити (приёмник получает broadcast на ней).
 * Чтение DataStore блокирующее, но короткое: это отладочный путь, а
 * асинхронный ответ через `am broadcast` пришлось бы собирать вручную.
 */
class DebugCommandExecutor(private val app: Application) : DebugCommandTarget {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var switchRunnable: Runnable? = null

    @Volatile
    private var swapWatchRunnable: Runnable? = null

    /**
     * Черновик редактора — headless-зеркало `BinauralUiState.editingFrequencyCurve`.
     *
     * Нужен, потому что фантомная смена каналов в TREND-режиме воспроизводится
     * только на пути редактора (`startDraft` + `updateEditingCurve`), а тыкать
     * пальцем в Compose-график через `input tap` нельзя: координаты точки
     * зависят от масштаба, и «добавить вторую точку» вслепую не выходит.
     * Здесь тот же самый набор вызовов сервиса, что делает ViewModel, но без UI.
     * docs/analysis_trend_swap_editor_phantom.md
     */
    @Volatile
    private var draftCurve: FrequencyCurve? = null

    /** Режим расслабления черновика (зеркало `editingRelaxationModeSettings`). */
    @Volatile
    private var draftRelaxation: RelaxationModeSettings = RelaxationModeSettings()

    private val repo: BinauralPreferencesRepository by lazy {
        EntryPointAccessors.fromApplication(app, DebugRepositoryEntryPoint::class.java)
            .preferencesRepository()
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Живой сервис или `null` — сервис живёт только во время воспроизведения. */
    private val service: BinauralPlaybackService?
        get() = BinauralPlaybackService.liveInstance

    // ============= Разбор команд =============

    override fun execute(command: String): String {
        // Первый токен — команда, остаток строки — её аргумент (может содержать
        // пробелы, это нужно для `import <json>`).
        val split = command.trim().split(Regex("\\s+"), limit = 2)
        val cmd = split[0].lowercase(Locale.US)
        val arg = split.getOrNull(1)?.trim().orEmpty()
        return when (cmd) {
            "help", "?" -> DEBUG_HELP
            "ui" -> openUi()
            "exit" -> exitApp()
            "status", "st" -> status()
            "presets" -> presets()
            "play" -> play()
            "stop" -> fadeStop()
            // Мягкая пауза: поток замирает, но НЕ утилизируется (буфер и фазы живы).
            // Отдельная команда, потому что это другой путь автомата, и проверять
            // кроссфейд надо в обоих.
            "pause" -> softPause()
            "resume" -> resume()
            "toggle" -> togglePlayback()
            "state" -> managerState()
            "audible" -> audibleTime()
            "audibleraw" -> audibleRawTime()
            "resumesnap" -> resumeSnapshot()
            "preset" -> selectPreset(arg)
            "next" -> stepPreset(+1)
            "prev" -> stepPreset(-1)
            "switch" -> switchLoop(arg)
            "volume", "vol" -> setVolume(arg)
            "samplerate", "sr" -> setSampleRate(arg)
            "buffer" -> setBuffer(arg)
            "norm" -> setNorm(arg)
            "tnorm" -> setTemporalNorm(parseBool(arg))
            "swap" -> setSwap(arg)
            "swapinterval" -> setSwapInterval(arg)
            "swapfade" -> setSwapFade(arg)
            "vtime" -> setVirtualTime(arg)
            "scrub" -> scrub(arg)
            // СКРАБ ПРЕДПРОСМОТРА — продакшен-путь (то же, что ручка в редакторе).
            "pscrub" -> productionScrub(arg)
            "pscrubreset" -> productionScrubReset()
            // Выход из редактора пресета: точная последовательность вызовов UI.
            "editexit" -> editorExit()
            // --- ЧЕРНОВИК РЕДАКТОРА (headless-зеркало пути BinauralViewModel) ---
            // docs/analysis_trend_swap_editor_phantom.md
            "dn" -> draftInit()
            "dstart" -> draftStart()
            "dadd" -> draftAddPoint(arg)
            "drelax" -> draftRelaxation(arg)
            "dsave" -> draftSave(arg)
            "dshow" -> draftShow()
            "dstop" -> draftStop()
            "swapwatch" -> swapWatch(arg)
            "scale" -> setScale(arg)
            "vrun" -> setVirtualRunning(arg)
            "realtime" -> resetToRealTime()
            // Виртуальное настенное время: проверка паузы БЕЗ реального ожидания.
            "warp" -> warpTime(arg)
            "totime" -> setTimeOfDay(arg)
            "clock" -> clockInfo()
            "clockreset" -> resetWallClock()
            "vsnap" -> verifySnapshot()
            "invcheck" -> invariantCheck()
            "delete", "del" -> withPreset(arg) { deletePreset(it) }
            "duplicate", "dup" -> withPreset(arg) { duplicatePreset(it) }
            "export" -> withPreset(arg) { exportPreset(it) }
            "import" -> importPreset(arg)
            "mem" -> memory()
            "gc" -> System.gc().let { "System.gc() выполнен — смотрите `mem`" }
            "packetmax" -> setPacketMax(arg)
            "packetpct" -> setPacketPct(arg)
            "packetgpct" -> setGlobalPct(arg)
            "bufstat" -> PacketMemoryBudget.report()
            "alloc" -> alloc(arg)
            "pkstat" -> packetStats()
            "pcreset" -> BinauralStreamImpl.resetPacketStats()
                .let { "Пики счётчиков пакетной памяти обнулены — смотрите `pkstat`" }
            "logtail" -> logTail(arg.toIntOrNull() ?: 40)
            else -> "Неизвестная команда: \"$cmd\". Отправьте \"help\"."
        }
    }

    // ============= Воспроизведение =============

    private fun play(): String {
        if (BinauralPlaybackService.isPlaying.value) return "Уже воспроизводится"
        val s = service ?: return startService()
        s.play()
        return "Старт запрошен (состояние обновится асинхронно — смотрите `status`)"
    }

    /** Полная остановка: фейд-аут, затем утилизация потока. */
    private fun fadeStop(): String {
        if (!BinauralPlaybackService.isPlaying.value) return "Уже остановлено"
        service?.stopWithFade() ?: return "Сервис не запущен"
        return "Остановка с затуханием"
    }

    /** Мягкая пауза: фейд-аут, затем заморозка потока (ресурсы живы). */
    private fun softPause(): String {
        if (!BinauralPlaybackService.isPlaying.value) return "Уже остановлено"
        service?.pauseWithFade() ?: return "Сервис не запущен"
        return "Мягкая пауза (поток заморожен, ресурсы живы)"
    }

    private fun resume(): String {
        if (BinauralPlaybackService.isPlaying.value) return "Уже воспроизводится"
        service?.resumeWithFade() ?: return "Сервис не запущен"
        return "Возобновление запрошено"
    }

    /** Состояние актёра менеджера — чтобы видеть, в какой фазе кроссфейда мы сейчас. */
    private fun managerState(): String {
        val s = service ?: return "Сервис не запущен"
        return "state=${s.managerState()} playing=${BinauralPlaybackService.isPlaying.value}"
    }

    /** СЛЫШИМАЯ позиция кривой (секунды суток) — для проверки, что пауза/продолжение
     *  не сбрасывают отметку времени в 0:00, и что возобновление играет ритм
     *  для ТЕКУЩЕГО момента суток (см. docs/analysis_resume_from_0_position.md).
     *
     *  Печатает три величины:
     *    audible  — где звук реально остановился (левая граница окна);
     *    frontier — конец уже сгенерированного аудио (правая граница окна);
     *    now      — реальный текущий момент суток.
     *  После возобновления audible (через R секунд кольца трека) и now обязаны
     *  сойтись — именно это и есть суть приложения. */
    private fun audibleTime(): String {
        val s = service ?: return "Сервис не запущен"
        val audible = s.audibleTimeOfDaySeconds()
        val frontier = s.frontierTimeOfDaySeconds()
        val now = nowTodSeconds()
        return "audible=${audible}s (${LocalTime.fromSecondOfDay(audible.coerceIn(0, 86399))}) " +
            "frontier=${frontier}s now=${"%05.2f".format(now)}s"
    }

    /**
     * СЛЫШИМАЯ позиция БЕЗ компенсации пропуска — РЕАЛЬНОЕ то, что звучит сейчас.
     *
     * Отличается от `audible` ровно на переходную задержку кольца трека. На
     * нестареющем пути возобновления (пакет переиспользуется с пропуском Δ·rate
     * кадров) `audible` сразу равен `now` (компенсация skippedFrames), а эта
     * команда показывает подлинную слышимую позицию, которая отстаёт от `now`
     * на Δ (длительность паузы) до тех пор, пока замороженное кольцо трека не
     * доиграет свой хвост. Разница `now − audibleraw` и есть измерение точности
     * привязки к текущему моменту суток (см. docs/analysis_resume_from_0_position.md).
     */
    private fun audibleRawTime(): String {
        val s = service ?: return "Сервис не запущен"
        val raw = s.audibleTimeOfDaySecondsRaw()
        val frontier = s.frontierTimeOfDaySeconds()
        val now = nowTodSeconds()
        // Нормализованная разница круглых суток: сколько слышимая отстаёт от now.
        val lag = circularDelta(now, raw.toDouble())
        return "audibleraw=${raw}s (${LocalTime.fromSecondOfDay(raw.coerceIn(0, 86399))}) " +
            "frontier=${frontier}s now=${"%05.2f".format(now)}s " +
            "lag(now-raw)=${"%.2f".format(lag)}s"
    }

    /**
     * Снимок решателя последнего возобновления из PAUSED: какой путь выбран
     * (SOFT — мягкое продолжение нестареющего пакета, или REBUILD_* —
     * пересборка), окно актуальности lead = F0 − A0, Δ паузы, пропущенные
     * кадры и точность квантования. Без аргументов печатает последний снимок;
     * после каждого pause→resume снимок обновляется.
     */
    private fun resumeSnapshot(): String {
        val s = service ?: return "Сервис не запущен"
        return s.resumeAccuracyReport()
            ?: "Нет снимка — сделайте pause, затем resume, и повторите `resumesnap`"
    }

    // ============= Виртуальное время (без реального ожидания) =============
    //
    // Проверять «возобновление играет ТЕКУЩИЙ момент суток» реальным ожиданием
    // нельзя: чтобы дойти до границы окна (десятки секунд), пришлось бы сидеть
    // минутами, а чтобы проверить переход через полночь — часами. VirtualClock
    // тут не помощник: его носитель времени — сгенерированные сэмплы, на паузе
    // генерация стоит, значит стоит и «now», и решатель (Δ = now − A0) не
    // испытывается вовсе. Нужен сдвиг НАСТЕННЫХ часов: тогда «сейчас» уезжает
    // мгновенно, а пакет, голова трека и фронтир остаются замороженными —
    // ровно как при настоящей паузе (см. DebugWallClock.h).

    /** Текущий момент суток по виртуальным часам (в release == реальные часы). */
    private fun nowTodSeconds(): Double = DebugClock.realTimeOfDaySeconds().toDouble()

    /** Разница времён суток по кругу: сколько [to] отстаёт от [from]. */
    private fun circularDelta(from: Double, to: Double): Double {
        var d = from - to
        if (d < 0) d += 86400.0
        return d
    }

    /**
     * `warp <дельта>` — мгновенно прокрутить настенные часы.
     *
     * Понимает `5` (секунды), `5s`, `250ms`, `2m`, `1h`, `-3`, `-0.5s`.
     * Всё, что связано со звуком, остаётся на месте: пакет, голова трека,
     * фронтир генерации. Меняется только «сейчас» — то есть Δ паузы.
     */
    private fun warpTime(arg: String): String {
        val ms = parseDurationMs(arg) ?: return "Не понял длительность \"$arg\". Примеры: 5, 5s, 250ms, 2m, 1h, -3"
        val before = nowTodSeconds()
        val totalOffset = DebugClock.warp(ms)
        val after = nowTodSeconds()
        return "warp ${ms}мс: now ${DebugClock.formatTimeOfDay(before.toFloat())} -> " +
            "${DebugClock.formatTimeOfDay(after.toFloat())} " +
            "(суммарный сдвиг ${totalOffset}мс = ${"%.2f".format(totalOffset / 1000.0)}с)"
    }

    /**
     * `totime <сек|HH:MM[:SS]>` — поставить часы на конкретный момент суток.
     * Берётся ближайший переход (вперёд или назад), поэтому `totime 23:59:55`
     * аккуратно ставит нас в пяти секундах от полуночи.
     */
    private fun setTimeOfDay(arg: String): String {
        val seconds = parseTimeOfDay(arg)
            ?: return "Не понял время суток \"$arg\". Примеры: 3600, 01:00, 23:59:55"
        DebugClock.setTimeOfDay(seconds)
        return "now=${DebugClock.formatTimeOfDay(nowTodSeconds().toFloat())} " +
            "(сдвиг ${DebugClock.offsetMs()}мс)"
    }

    /**
     * `clock` — состояние виртуальных часов ОБЕИХ сторон (Kotlin и C++).
     *
     * `drift` — расхождение Kotlin- и нативного «сейчас». Ненулевой drift
     * означает, что решатель и якорь свежего потока читают разное время, и
     * ANY вывод верификации ненадёжен: сначала `clockreset`.
     */
    private fun clockInfo(): String {
        val kOffset = DebugClock.offsetMs()
        val kTod = nowTodSeconds()
        val nOffset = DebugNativeClock.getOffsetMs()
        val nTod = DebugNativeClock.getTimeOfDaySeconds().toDouble()
        val drift = if (DebugNativeClock.available) {
            var d = kTod - nTod
            if (d > 43200.0) d -= 86400.0
            if (d < -43200.0) d += 86400.0
            d
        } else Double.NaN
        // СКРАБ: со сдвинутой осью «now» часов и «now» звука РАЗНЫЕ по
        // замыслу, а не из-за рассинхрона. Без этой строки любой `clock` при
        // активном скрабе выглядел бы как поломка привязки к времени.
        val scrub = BinauralPlaybackService.scrubOffsetSeconds.value
        val scrubLine = if (scrub == 0) "scrub=0 (ось = реальное сейчас)" else
            "scrub=${scrub}с — ось звука сдвинута на ${LocalTime.fromSecondOfDay(scrub)}; " +
                "audible должен быть ~${LocalTime.fromSecondOfDay(((kTod.toInt() + scrub) % 86400))}"
        return "kotlin: offset=${kOffset}мс now=${DebugClock.formatTimeOfDay(kTod.toFloat())}\n" +
            "native: offset=${nOffset}мс now=${DebugClock.formatTimeOfDay(nTod.toFloat())} " +
            "(available=${DebugNativeClock.available})\n" +
            "$scrubLine\n" +
            "drift=${"%.3f".format(drift)}с ${if (!drift.isNaN() && kotlin.math.abs(drift) < 1.0) "OK" else "РАСХОЖДЕНИЕ"}"
    }

    /** `clockreset` — вернуть реальное время (обе стороны). */
    private fun resetWallClock(): String {
        DebugClock.reset()
        return "Часы возвращены к реальному времени: offset=${DebugClock.offsetMs()}мс " +
            "now=${DebugClock.formatTimeOfDay(nowTodSeconds().toFloat())}"
    }

    /**
     * `vsnap` — ОДНОСТРОЧНЫЙ машинный снимок всего, что нужно верификации.
     *
     * Одна строка, потому что его разбирает shell: `grep -o 'lag=[0-9.]*'`.
     * Поля:
     *   state      — фаза актёра (RUNNING / PAUSED / HANDOFF / …);
     *   playing    — флаг воспроизведения;
     *   now        — текущий момент суток (по виртуальным часам), сек;
     *   audible    — слышимая позиция С компенсацией пропуска, сек;
     *   audibleraw — REAL'ная слышимая позиция (что звучит прямо сейчас), сек;
     *   frontier   — F0, конец сгенерированного аудио, сек;
     *   lag        — now − audibleraw по кругу: главная метрика (должен → 0);
     *   window     — F0 − audible по кругу: окно, внутри которого пакет ещё жив;
     *   warped     — сдвиг часов, мс (0 == реальное время);
     *   scrub      — сдвиг оси времени суток, сек (0 == обычный режим).
     *
     * ЧИСЛА — СТРОГО `Locale.US`. `String.format` без локали берёт системную,
     * и на русской локали снимок печатал `now=83991,88` с ЗАПЯТОЙ. awk (а
     * значит все стенды) читает такое как 83991, то есть молча теряет дробь:
     * машинный снимок становится на секунду грубее, чем claiming его формат.
     */
    private fun verifySnapshot(): String {
        val s = service
        val now = nowTodSeconds()
        // Разделитель — точка ВСЕГДА, независимо от локали устройства.
        fun f2(v: Double): String = String.format(Locale.US, "%.2f", v)
        if (s == null) {
            return "state=NONE playing=0 now=${f2(now)} audible=0 audibleraw=0 " +
                "frontier=0 lag=0.00 window=0.00 warped=${DebugClock.offsetMs()} scrub=0"
        }
        val audible = s.audibleTimeOfDaySeconds().toDouble()
        val raw = s.audibleTimeOfDaySecondsRaw().toDouble()
        val frontier = s.frontierTimeOfDaySeconds().toDouble()
        // СКРАБ: без поля сдвига снимок неотличим от «звук уехал от часов» —
        // обе ситуации выглядят как audible != now. Стенд tools/dbgscrub.sh
        // проверяет именно audible ≈ now + scrub, поэтому сдвиг обязан быть
        // в машинной строке, а не только в человекочитаемом `clock`.
        val scrub = BinauralPlaybackService.scrubOffsetSeconds.value
        return "state=${s.managerState()} playing=${if (BinauralPlaybackService.isPlaying.value) 1 else 0} " +
            "now=${f2(now)} audible=${f2(audible)} " +
            "audibleraw=${f2(raw)} frontier=${f2(frontier)} " +
            "lag=${f2(circularDelta(now, raw))} " +
            "window=${f2(circularDelta(frontier, audible))} " +
            "warped=${DebugClock.offsetMs()} scrub=$scrub"
    }

    /**
     * Разовая проверка инварианта «слышимая позиция == сейчас».
     *
     * Фоновый сторож делает то же каждые 500 мс и пишет в лог, если расхождение
     * держится дольше 3 с; эта команда — для ручного разбора прямо в момент,
     * когда «что-то не так слышно».
     */
    private fun invariantCheck(): String {
        val s = service ?: return "Сервис не запущен"
        return s.invariantCheck()
    }

    /** Разбор длительности: 5 | 5s | 250ms | 2m | 1h | -0.5s. Возвращает мс. */
    private fun parseDurationMs(raw: String): Long? {
        val t = raw.trim().lowercase(Locale.US)
        val m = Regex("^(-?[0-9]*\\.?[0-9]+)\\s*(ms|s|m|h)?$").matchEntire(t) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2]) {
            "ms" -> 1.0
            "m" -> 60_000.0
            "h" -> 3_600_000.0
            else -> 1000.0 // "s" или пусто — по умолчанию секунды
        }
        return (value * mult).toLong()
    }

    /** Разбор момента суток: секунды или HH:MM[:SS]. */
    private fun parseTimeOfDay(raw: String): Double? {
        val t = raw.trim()
        if (t.contains(":")) {
            val parts = t.split(":")
            if (parts.size !in 2..3) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val sec = if (parts.size == 3) {
                parts[2].toDoubleOrNull() ?: return null
            } else {
                0.0
            }
            if (h !in 0..23 || m !in 0..59) return null
            return (h * 3600 + m * 60).toDouble() + sec
        }
        return t.toDoubleOrNull()
    }

    private fun togglePlayback(): String =
        if (BinauralPlaybackService.isPlaying.value) fadeStop() else play()

    /**
     * Запуск сервиса «из фона». Android 12+ запрещает `startForegroundService`
     * из фона для большинства ситуаций, поэтому честно сообщаем, если старт
     * заблокирован системой, — а не делаем вид, что команда сработала.
     */
    private fun startService(): String {
        return try {
            val intent = Intent(app, BinauralPlaybackService::class.java)
                .setAction(BinauralPlaybackService.ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            "Сервис запускается (ACTION_START). Если система заблокировала " +
                "старт из фона — откройте приложение и повторите."
        } catch (e: Throwable) {
            "Не удалось запустить сервис: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ============= Пресеты =============

    private fun selectPreset(arg: String): String {
        // Настройки читаются ОДИН раз: settings() — это runBlocking по семи
        // DataStore-флоу, и два таких чтения на команду давали 1-2,5 с «задержки»
        // до начала кроссфейда, которую легко принять за задержку движка.
        val st = settings()
        val preset = resolvePreset(arg, st) ?: return "Пресет не найден: \"$arg\". Список: presets"
        return applyPreset(preset, st)
    }

    private fun stepPreset(direction: Int): String {
        val st = settings()
        if (st.presets.isEmpty()) return "Список пресетов пуст"
        val current = st.presets.indexOfFirst { it.id == st.activeId }
        // current == -1 (ничего не активно) -> с 0-го при +1 и с последнего при -1
        val index = if (current < 0) {
            if (direction >= 0) 0 else st.presets.lastIndex
        } else {
            (current + direction + st.presets.size) % st.presets.size
        }
        val preset = st.presets[index]
        val result = applyPreset(preset, st)
        return "Пресет ${index + 1}/${st.presets.size}: $result"
    }

    /**
     * Смена пресета — ровно тот же путь, что в UI: `updateConfig()` уходит в
     * `requestHandoff()` и поднимает NEXT параллельно с fade-out CURRENT.
     *
     * СКРАБ: повторяем `resetScrub()` из `BinauralViewModel.playPreset()`.
     * Без него CLI-смена пресета расходилась бы с реальным UI: там сдвиг оси
     * стирается, здесь выживал бы и на новом пресете превращался бы из
     * предпросмотра в просто ложные часы. Эта же разница делала бы
     * невоспроизводимым сценарий V9 стенда tools/dbgscrub.sh.
     */
    private fun applyPreset(preset: BinauralPreset, st: GlobalSettings): String {
        val s = service
            ?: return "Сервис не запущен — воспроизведение не активно. Сначала `play`"
        s.resetScrub()
        s.setPresetIds(st.presets.map { it.id })
        s.setCurrentPresetName(preset.name)
        s.setCurrentPresetId(preset.id)
        s.updateConfig(
            buildPlaybackConfig(
                frequencyCurve = preset.frequencyCurve,
                volume = st.volume,
                channelSwap = st.swap,
                normalization = st.normalization
            ),
            preset.relaxationModeSettings
        )
        if (!BinauralPlaybackService.isPlaying.value) s.play()
        io { repo.saveActivePresetId(preset.id) }
        return "${preset.name} (${preset.id})"
    }

    /**
     * Серия быстрых смен пресета — воспроизведение падения при переключении во
     * время игры (`switch 20 250`). Работает по главной нити через Handler.
     *
     * Настройки и список пресетов читаются ОДИН раз: внутри серии идёт только
     * `updateConfig()`, иначе каждый шаг платил бы за чтение DataStore и серия
     * перестала быть «быстрой».
     */
    private fun switchLoop(arg: String): String {
        stopSwitch()
        val tokens = arg.split(Regex("\\s+"))
        if (tokens.firstOrNull()?.lowercase(Locale.US) == "stop") {
            return "Серия смен прервана"
        }
        val count = tokens.getOrNull(0)?.toIntOrNull()
            ?: return "Формат: switch <количество> [задержкаМс] | switch stop"
        val delayMs = tokens.getOrNull(1)?.toLongOrNull()?.coerceIn(0, 10000)
            ?: DEFAULT_SWITCH_DELAY_MS

        val s = service
            ?: return "Сервис не запущен — воспроизведение не активно. Сначала `play`"
        val st = settings()
        if (st.presets.size < 2) return "Нужно минимум 2 пресета, сейчас ${st.presets.size}"

        s.setPresetIds(st.presets.map { it.id })
        val total = count.coerceIn(1, 1000)
        var remaining = total
        var index = st.presets.indexOfFirst { it.id == st.activeId }
        val startedAt = System.currentTimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                if (remaining <= 0) {
                    switchRunnable = null
                    Log.i(
                        DEBUG_LOG_TAG,
                        "switch: серия из $total смен завершена за " +
                            "${System.currentTimeMillis() - startedAt} мс"
                    )
                    return
                }
                index = (index + 1) % st.presets.size
                val preset = st.presets[index]
                val live = service
                if (live == null) {
                    switchRunnable = null
                    Log.w(DEBUG_LOG_TAG, "switch: сервис умер на #${total - remaining + 1}, серия прервана")
                    return
                }
                val n = total - remaining + 1
                val at = System.currentTimeMillis() - startedAt
                Log.i(DEBUG_LOG_TAG, "switch #$n/$total (+${at}мс) -> ${preset.name}")
                live.setCurrentPresetName(preset.name)
                live.setCurrentPresetId(preset.id)
                live.updateConfig(
                    buildPlaybackConfig(
                        frequencyCurve = preset.frequencyCurve,
                        volume = st.volume,
                        channelSwap = st.swap,
                        normalization = st.normalization
                    ),
                    preset.relaxationModeSettings
                )
                remaining--
                handler.postDelayed(this, delayMs)
            }
        }
        switchRunnable = runnable
        handler.post(runnable)
        return "Смена пресета: $total смен с интервалом $delayMs мс запущена " +
            "(ход серии — в logcat, тег $DEBUG_LOG_TAG)"
    }

    private fun stopSwitch() {
        switchRunnable?.let { handler.removeCallbacks(it) }
        switchRunnable = null
    }

    private fun deletePreset(preset: BinauralPreset): String {
        io {
            if (repo.getActivePresetId().first() == preset.id) {
                repo.saveActivePresetId(null)
            }
            repo.deletePreset(preset.id)
        }
        return "Пресет удалён: ${preset.name}"
    }

    private fun duplicatePreset(preset: BinauralPreset): String {
        val st = settings()
        val copy = preset.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = uniqueName(st.presets, preset.name),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        io { repo.addPreset(copy) }
        return "Пресет скопирован: ${copy.name}"
    }

    private fun exportPreset(preset: BinauralPreset): String = try {
        json.encodeToString(preset)
    } catch (e: Throwable) {
        "Не удалось экспортировать: ${e.javaClass.simpleName}: ${e.message}"
    }

    private fun importPreset(arg: String): String {
        if (arg.isEmpty()) return "Нужен JSON: import {…}"
        return try {
            val preset = json.decodeFromString<BinauralPreset>(arg)
            val st = settings()
            val imported = preset.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = uniqueName(st.presets, preset.name),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            io { repo.addPreset(imported) }
            "Пресет импортирован, id=${imported.id}"
        } catch (e: Throwable) {
            "Импорт не удался: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ============= Черновик редактора (headless) =============
    //
    // Зеркало пути `PresetEditScreen → BinauralViewModel` для расследования
    // фантомной смены каналов в TREND-режиме (docs/analysis_trend_swap_editor_phantom.md).
    // Повторяются вызовы СЕРВИСА один в один; состояние UI не эмулируется —
    // оно в этом баге и так одинаковое (одна и та же кривая, одни и те же
    // глобальные channelSwapSettings).

    /** `dn` — новая предустановка: ровно то, что даёт `FrequencyCurve.newPresetCurve()`. */
    private fun draftInit(): String {
        draftCurve = FrequencyCurve.newPresetCurve()
        draftRelaxation = RelaxationModeSettings()
        val c = draftCurve!!
        return "Черновик создан: ${c.points.size} т., ${c.interpolationType}, " +
            "carrier=${c.carrierRange}, beat=${c.beatRange}, " +
            "relaxation=${onOff(draftRelaxation.enabled)}"
    }

    /**
     * `dstart` — зеркало `BinauralViewModel.startDraft()`: зазвучать черновиком.
     *
     * Отличие от сохранённого пресета ровно два: `presetId = __draft__` и
     * позже — минутный интервал буфера предпросмотра. Конфиг собирается той же
     * `buildPlaybackConfig()`, что и для сохранённого пресета.
     */
    private fun draftStart(): String {
        val curve = draftCurve ?: return "Черновика нет — сначала `dn`"
        val s = service
            ?: return "Сервис не запущен: ${startService()}. Повторите `dstart` после старта."
        val st = settings()
        // Как в startDraft: старт явный, интервал буфера — пользовательский.
        s.setFrequencyUpdateInterval(st.bufferMinutes * 60 * 1000)
        s.setCurrentPresetName("Черновик (debug)")
        s.setCurrentPresetId(DRAFT_PRESET_ID)
        s.updateConfig(
            buildPlaybackConfig(
                frequencyCurve = curve,
                volume = st.volume,
                channelSwap = st.swap,
                normalization = st.normalization
            ),
            draftRelaxation
        )
        if (!BinauralPlaybackService.isPlaying.value) s.play()
        return "Черновик звучит: ${curve.points.size} т., swap=${st.swap.mode}, " +
            "interval=${st.swap.intervalSeconds}с, relaxation=${onOff(draftRelaxation.enabled)}. " +
            "Смотрите logcat SWAPCFG"
    }

    /**
     * `dadd <сек> <несущая> <биения>` — зеркало `addEditingPoint()` +
     * `updateEditingCurve()`: кламп → сортировка → живой пуш в звучащий поток.
     *
     * Живой пуш — ключевая деталь бага: каждое движение точки в редакторе
     * уходит как `updateFrequencyCurve()` = полный zero-overlap хэндофф.
     */
    private fun draftAddPoint(arg: String): String {
        val curve = draftCurve ?: return "Черновика нет — сначала `dn`"
        val t = arg.split(Regex("\\s+"))
        val sec = t.getOrNull(0)?.toIntOrNull()
            ?: return "Формат: dadd <секСуток|HH:MM> <несущая> <биения>"
        val carrier = t.getOrNull(1)?.toFloatOrNull()
            ?: return "Формат: dadd <сек> <несущая> <биения>"
        val beat = t.getOrNull(2)?.toFloatOrNull()
            ?: return "Формат: dadd <сек> <несущая> <биения>"
        val time = LocalTime.fromSecondOfDay(((sec % 86400) + 86400) % 86400)

        val clampedCarrier = curve.carrierRange.clamp(carrier)
        val clampedBeat = FrequencyMath.clampBeat(
            clampedCarrier, beat, carrierRange = curve.carrierRange
        )

        val points = (curve.points + FrequencyPoint(time, clampedCarrier, clampedBeat))
            .sortedBy { it.time.toSecondOfDay() }
        val newCurve = FrequencyCurve(
            points = points,
            carrierRange = curve.carrierRange,
            beatRange = curve.beatRange,
            interpolationType = curve.interpolationType,
            splineTension = curve.splineTension,
            stepFadeDurationMs = curve.stepFadeDurationMs
        )
        draftCurve = newCurve

        // Пуш в звук — только если черновик звучит (гейт `isEditingSounding`).
        val s = service
        if (s == null || !BinauralPlaybackService.isPlaying.value) {
            return "Точка добавлена (звук не звучит): ${points.size} т., " +
                "запрошено c=$carrier b=$beat → c=$clampedCarrier b=$clampedBeat"
        }
        // armEditorPreviewBufferInterval(): минутный буфер на время правки.
        s.setFrequencyUpdateInterval(EDITOR_PREVIEW_BUFFER_INTERVAL_MS)
        s.updateFrequencyCurve(newCurve)
        return "Точка ${LocalTime.fromSecondOfDay(((sec % 86400) + 86400) % 86400)} " +
            "c=${fmt(clampedCarrier, 1)} b=${fmt(clampedBeat, 2)} → " +
            "push в звук (буфер ${EDITOR_PREVIEW_BUFFER_INTERVAL_MS / 1000} с): ${points.size} т. " +
            "Смотрите logcat SWAPCFG"
    }

    /**
     * `drelax <on|off>` — включить режим расслабления у черновика.
     *
     * Проверка гипотезы №1: `NativeAudioEngine.updateConfig()` подменяет точки
     * кривой виртуальными при `relaxation.enabled && points.size >= 2` — ровно
     * на второй точке. Виртуальные точки создают регулярные провалы биений,
     * а значит — десятки экстремумов в сутки и частые смены каналов в TREND.
     */
    private fun draftRelaxation(arg: String): String {
        val on = parseBool(arg)
        draftRelaxation = draftRelaxation.copy(enabled = on)
        val curve = draftCurve ?: return "Режим расслабления: ${onOff(on)} (черновика нет)"
        val s = service ?: return "Режим расслабления черновика: ${onOff(on)} (в звук не ушло)"
        val st = settings()
        // Как `updateRelaxationModeSettings` в редакторе: конфиг целиком,
        // потому что relaxation входит в `onSpecChanged` и пересобирает поток.
        s.updateConfig(
            buildPlaybackConfig(
                frequencyCurve = curve,
                volume = st.volume,
                channelSwap = st.swap,
                normalization = st.normalization
            ),
            draftRelaxation
        )
        return "Режим расслабления черновика: ${onOff(on)} (${curve.points.size} т.). " +
            "Смотрите logcat SWAPCFG"
    }

    /**
     * `dsave <имя>` — зеркало `createPreset(activate = true)`.
     *
     * Конфиг тот же самый (кривая — тот же объект), меняются только имя и id:
     * именно это отличие пользователь называет «после сохранения — правильно».
     * Плюс возвращается пользовательский интервал буфера.
     */
    private fun draftSave(arg: String): String {
        val curve = draftCurve ?: return "Черновика нет — сначала `dn`"
        val name = arg.ifEmpty { "Черновик" }
        val preset = BinauralPreset(
            name = name,
            frequencyCurve = curve,
            relaxationModeSettings = draftRelaxation
        )
        io {
            repo.addPreset(preset)
            repo.saveActivePresetId(preset.id)
        }
        val s = service
            ?: return "Пресет сохранён: ${preset.name} (${preset.id}), но в звук не ушло"
        val st = settings()
        s.setCurrentPresetName(preset.name)
        s.setCurrentPresetId(preset.id)
        s.setFrequencyUpdateInterval(st.bufferMinutes * 60 * 1000)
        s.updateConfig(
            buildPlaybackConfig(
                frequencyCurve = curve,
                volume = st.volume,
                channelSwap = st.swap,
                normalization = st.normalization
            ),
            draftRelaxation
        )
        return "Сохранён и активирован: ${preset.name} (${preset.id}), ${curve.points.size} т. " +
            "Смотрите logcat SWAPCFG и сравните с черновиком"
    }

    /** `dshow` — что сейчас в черновике и с какими настройками он ушёл бы в звук. */
    private fun draftShow(): String {
        val curve = draftCurve ?: return "Черновика нет — сначала `dn`"
        val st = settings()
        return buildString {
            append("Черновик: ${curve.points.size} т., ${curve.interpolationType}, ")
            append("carrier=[${fmt(curve.carrierRange.min, 1)};${fmt(curve.carrierRange.max, 1)}] ")
            append("beat=[${fmt(curve.beatRange.min, 1)};${fmt(curve.beatRange.max, 1)}]\n")
            curve.points.forEachIndexed { i, p ->
                append("  ${i + 1}. ${p.time} c=${fmt(p.carrierFrequency, 1)} ")
                append("b=${fmt(p.beatFrequency, 2)} (L=${fmt(p.leftChannelFrequency, 1)} ")
                append("R=${fmt(p.rightChannelFrequency, 1)})\n")
            }
            append("relaxation=${onOff(draftRelaxation.enabled)}\n")
            append("swap=${st.swap.enabled} mode=${st.swap.mode} ")
            append("interval=${st.swap.intervalSeconds}с trendPoints=${st.swap.trendPoints} ")
            append("fade=${st.swap.fadeEnabled}\n")
            append("нормализация=${st.normalization.type} буфер=${st.bufferMinutes} мин")
        }
    }

    /** `dstop` — остановить звук (черновик при этом остаётся в памяти). */
    private fun draftStop(): String {
        stopSwapWatch()
        return fadeStop()
    }

    /**
     * `swapwatch <сек>` — прямое измерение «каналы меняются каждые N секунд».
     *
     * Опрашивает знак эффективной частоты биений (sign = фактическая раскладка
     * каналов) каждые 200 мс и печатает ВСЕ переходы в logcat тегом SWAPWATCH:
     * время, знак, `isChannelsSwapped`. Это и есть разница между «расписание
     * пересечений корректно» и «звук действительно меняет раскладку» —
     * SWAPCFG показывает первое, swapwatch — второе.
     */
    private fun swapWatch(arg: String): String {
        stopSwapWatch()
        val seconds = arg.toIntOrNull()?.coerceIn(1, 600) ?: 30
        if (!BinauralPlaybackService.isPlaying.value) {
            return "Воспроизведение не идёт — считать нечего"
        }
        val deadline = System.currentTimeMillis() + seconds * 1000L
        var lastSign = 0
        var flips = 0
        var samples = 0
        val startedAt = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val beat = BinauralPlaybackService.currentBeatFrequency.value
                val swapped = BinauralPlaybackService.isChannelsSwapped.value
                val sign = when {
                    beat > 0.0001f -> 1
                    beat < -0.0001f -> -1
                    else -> 0
                }
                samples++
                if (sign != 0) {
                    if (lastSign == 0) {
                        lastSign = sign
                        Log.i("SWAPWATCH", "старт: знак=$sign swapped=$swapped beat=${
                            fmt(beat, 3)} (t=${System.currentTimeMillis() - startedAt} мс)")
                    } else if (sign != lastSign) {
                        flips++
                        lastSign = sign
                        Log.i("SWAPWATCH",
                            "СМЕНА #$flips: знак=$sign swapped=$swapped beat=${fmt(beat, 3)} " +
                                "(t=${System.currentTimeMillis() - startedAt} мс)")
                    }
                }
                if (System.currentTimeMillis() >= deadline) {
                    swapWatchRunnable = null
                    Log.i("SWAPWATCH",
                        "итог за ${System.currentTimeMillis() - startedAt} мс: " +
                            "смен=$flips, опросов=$samples, знак=$sign, swapped=$swapped")
                    return
                }
                handler.postDelayed(this, 200L)
            }
        }
        swapWatchRunnable = runnable
        handler.post(runnable)
        return "swapwatch: $seconds с, опрос 200 мс, лог — logcat SWAPWATCH"
    }

    private fun stopSwapWatch() {
        swapWatchRunnable?.let { handler.removeCallbacks(it) }
        swapWatchRunnable = null
    }

    // ============= Настройки =============

    private fun setVolume(arg: String): String {
        val value = arg.toFloatOrNull() ?: return "Формат: volume <0..1>"
        val clamped = value.coerceIn(0f, 1f)
        service?.setVolume(clamped) ?: return "Сервис не запущен"
        io { repo.saveVolume(clamped) }
        return "Громкость: %.2f".format(Locale.US, clamped)
    }

    private fun setSampleRate(arg: String): String {
        val hz = arg.toIntOrNull() ?: return "Формат: samplerate <8000|16000|22050|44100|48000>"
        val rate = SampleRate.entries.find { it.value == hz }
            ?: return "Нет такого SampleRate: $hz. Допустимо: " +
                SampleRate.entries.joinToString { it.value.toString() }
        service?.setSampleRate(rate) ?: return "Сервис не запущен"
        io { repo.saveSampleRate(rate.value) }
        return "Частота дискретизации: ${rate.value} Гц (применится со следующим пакетом)"
    }

    private fun setBuffer(arg: String): String {
        val minutes = arg.toIntOrNull() ?: return "Формат: buffer <минуты 1..10>"
        val clamped = minutes.coerceIn(1, 10)
        service?.setFrequencyUpdateInterval(clamped * 60 * 1000)
            ?: return "Сервис не запущен"
        io { repo.saveBufferGenerationMinutes(clamped) }
        return "Интервал генерации: $clamped мин"
    }

    private fun setNorm(arg: String): String {
        val on = parseBool(arg)
        val type = if (on) NormalizationType.TEMPORAL else NormalizationType.CHANNEL
        val st = settings()
        val new = st.normalization.copy(type = type)
        io { repo.saveVolumeNormalizationSettings(new) }
        return applyConfig("Нормализация громкости: ${onOff(on)} ($type)")
    }

    private fun setTemporalNorm(on: Boolean): String {
        val st = settings()
        val type = if (on) NormalizationType.TEMPORAL else NormalizationType.CHANNEL
        val new = st.normalization.copy(type = type)
        io { repo.saveVolumeNormalizationSettings(new) }
        return applyConfig("Временная нормализация: ${onOff(on)} ($type)")
    }

    private fun setSwap(arg: String): String {
        val mode = when (arg.lowercase(Locale.US)) {
            "off", "none", "0" -> null
            "timer" -> ChannelSwapMode.TIMER
            "trend" -> ChannelSwapMode.TREND
            else -> return "Формат: swap <off|timer|trend>"
        }
        val st = settings()
        val new = st.swap.copy(enabled = mode != null, mode = mode ?: st.swap.mode)
        io { repo.saveChannelSwapSettings(new) }
        return applyConfig("Перестановка каналов: ${mode?.name ?: "off"}")
    }

    private fun setSwapInterval(arg: String): String {
        val sec = (arg.toIntOrNull() ?: 0).coerceIn(5, 3600)
        val st = settings()
        io { repo.saveChannelSwapSettings(st.swap.copy(intervalSeconds = sec)) }
        return applyConfig("Интервал смены каналов: $sec с")
    }

    private fun setSwapFade(arg: String): String {
        val on = parseBool(arg)
        val st = settings()
        io { repo.saveChannelSwapSettings(st.swap.copy(fadeEnabled = on)) }
        return applyConfig("Плавная перестановка: ${onOff(on)}")
    }

    private fun setVirtualTime(arg: String): String {
        val on = parseBool(arg)
        service?.debugSetVirtualTimeEnabled(on) ?: return "Сервис не запущен"
        return "Виртуальное время: ${onOff(on)}"
    }

    private fun scrub(arg: String): String {
        val sec = (arg.toIntOrNull() ?: 0).coerceIn(0, 86399)
        service?.debugScrub(sec) ?: return "Сервис не запущен"
        return "Перемотка на $sec с (${LocalTime.fromSecondOfDay(sec)})"
    }

    /**
     * `pscrub <сек|HH:MM>` — СКРАБ ПРЕДПРОСМОТРА: сдвиг оси времени суток,
     * ровно тот путь, который в редакторе делает ручка ◀|▶.
     *
     * Отличается от `scrub`: тот — перемотка ВИРТУАЛЬНОГО времени (только
     * debug, без пересборки потока), этот — продакшен-маршрут через
     * `SpecReason.SCRUB` с полным хэндоффом. Проверять им надо то же, что
     * слышит пользователь (см. §12 docs/plan_playback_scrub_handle.md).
     */
    private fun productionScrub(arg: String): String {
        val raw = parseTimeOfDay(arg)
            ?: return "Не понял время суток \"$arg\". Примеры: 3600, 01:00, 23:55"
        val sec = ((raw.toInt() % 86400) + 86400) % 86400
        val s = service ?: return "Сервис не запущен — скраб без звука бессмысленен"
        s.scrubTo(sec)
        val now = nowTodSeconds().toInt()
        return "pscrub: цель=${LocalTime.fromSecondOfDay(sec)} (${sec} с), " +
            "now=${LocalTime.fromSecondOfDay(now.coerceIn(0, 86399))}. " +
            "Проверьте `audible` через 1-2 с (время кроссфейда) и `invcheck`."
    }

    /** `pscrubreset` — вернуть прослушивание к реальному текущему моменту. */
    private fun productionScrubReset(): String {
        val s = service ?: return "Сервис не запущен"
        s.scrubReset()
        return "pscrubreset: сдвиг оси снят, ждите кроссфейд (~1 с) и проверьте `audible`"
    }

    /**
     * `editexit` — ВЫХОД ИЗ РЕДАКТОРА ПРЕСЕТА: ровно та последовательность
     * вызовов, которую делает UI (BinauralViewModel + PresetEditScreen):
     *
     *   resetScrub()   — тихо снимает заданный сдвиг (его унесёт попутный
     *                    хэндофф сохранения/восстановления кривой);
     *   scrubReset()   — слышимый добор: если попутного хэндоффа не было,
     *                    именно он возвращает ЗВУК на реальное «сейчас».
     *
     * Команда существует потому, что ключевая ошибка этого пути была НЕ в UI:
     * тихий сброс стирал `scrubOffsetSec`, и сторож слышимого сброса решал,
     * что делать нечего. Линия возвращалась на «сейчас», а воспроизведение
     * оставалось на времени предпросмотра (docs/plan_playback_scrub_handle.md).
     * Проверять это стендом обязательно: значение в StateFlow сходится и без
     * звука.
     */
    private fun editorExit(): String {
        val s = service ?: return "Сервис не запущен"
        s.resetScrub()
        s.scrubReset()
        return "editexit: сдвиг снят + запрошен слышимый возврат. Ждите кроссфейд (~1 с) " +
            "и проверьте `vsnap`: audible == now, scrub=0"
    }

    private fun setScale(arg: String): String {
        val scale = (arg.toFloatOrNull() ?: 1f).coerceIn(1f, 60f)
        service?.debugSetTimeScale(scale) ?: return "Сервис не запущен"
        return "Масштаб времени: $scale"
    }

    private fun setVirtualRunning(arg: String): String {
        val on = parseBool(arg)
        service?.debugSetRunning(on) ?: return "Сервис не запущен"
        return "Виртуальное время идёт: ${onOff(on)}"
    }

    private fun resetToRealTime(): String {
        service?.debugResetToRealTime() ?: return "Сервис не запущен"
        return "Возврат к реальному времени"
    }

    /**
     * Пересобрать конфиг из активного пресета и новых настроек — зеркало
     * `BinauralViewModel.updateAudioConfig()`. Меняет звук кроссфейдом,
     * а не мгновенной подменой частот в звучащем потоке.
     */
    private fun applyConfig(report: String): String {
        val s = service ?: return "$report (сервис не запущен — в звук не ушло)"
        val st = settings()
        val preset = st.presets.find { it.id == st.activeId } ?: st.presets.firstOrNull()
            ?: return "$report (нет пресетов)"
        s.updateConfig(
            buildPlaybackConfig(
                frequencyCurve = preset.frequencyCurve,
                volume = st.volume,
                channelSwap = st.swap,
                normalization = st.normalization
            ),
            preset.relaxationModeSettings
        )
        return report
    }

    // ============= Состояние =============

    private fun status(): String {
        val st = settings()
        val playing = BinauralPlaybackService.isPlaying.value
        val tod = BinauralPlaybackService.currentTimeOfDaySeconds.value
        val active = st.presets.find { it.id == st.activeId }
        return buildString {
            append("playing=$playing ")
            append("service=${service != null} ui=${uiVisible()}\n")
            append("preset=${active?.name ?: "<нет>"} id=${st.activeId ?: "-"}\n")
            append("beat=${fmt(BinauralPlaybackService.currentBeatFrequency.value, 2)} Гц ")
            append("carrier=${fmt(BinauralPlaybackService.currentCarrierFrequency.value, 1)} Гц ")
            append("swapped=${BinauralPlaybackService.isChannelsSwapped.value}\n")
            append("time=${LocalTime.fromSecondOfDay(tod.coerceIn(0, 86399))}\n")
            append("volume=${fmt(st.volume, 2)} rate=${st.sampleRate.value} Гц ")
            append("bufferMin=${st.bufferMinutes}\n")
            append("norm=${st.normalization.type} ")
            append("strength=${fmt(st.normalization.strength, 2)}\n")
            append("swap=${st.swap.enabled} mode=${st.swap.mode} ")
            append("interval=${st.swap.intervalSeconds}с ")
            append("fade=${st.swap.fadeEnabled}\n")
            append("presets=${st.presets.size}")
            if (switchRunnable != null) append(" (идёт серия смен, `switch stop`)")
        }
    }

    private fun presets(): String {
        val st = settings()
        if (st.presets.isEmpty()) return "Список пресетов пуст"
        return st.presets.mapIndexed { i, p ->
            val mark = if (p.id == st.activeId) "*" else " "
            val points = p.frequencyCurve.points.size
            "$mark${i + 1}. ${p.name}  [${points} т., ${p.frequencyCurve.interpolationType}] ${p.id}"
        }.joinToString("\n")
    }

    private fun memory(): String {
        val rt = Runtime.getRuntime()
        val maxMb = rt.maxMemory() / MB
        val totalMb = rt.totalMemory() / MB
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / MB
        val dmi = Debug.MemoryInfo()
        Debug.getMemoryInfo(dmi)
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return buildString {
            append("java heap: ${usedMb}MB занято / ${totalMb}MB выделено / ${maxMb}MB максимум\n")
            append("pss: total=${dmi.totalPss / 1024}MB dalvik=${dmi.dalvikPss / 1024}MB ")
            append("native=${dmi.nativePss / 1024}MB other=${dmi.otherPss / 1024}MB\n")
            append("system: avail=${mi.availMem / MB}MB lowMemory=${mi.lowMemory} ")
            append("threshold=${mi.threshold / MB}MB\n")
            append(BinauralStreamImpl.packetStats().replace("\n", "\n"))
        }
    }

    /**
     * Потолок ОДНОГО пакетного буфера на ходу, в мегабайтах.
     *
     * Единственный способ искать предел аллокации без пересборки: перебор
     * 32/48/64/… МБ под switch-штормом делается за одну установку. 0 — вернуть
     * константу сборки. Применяется к СЛЕДУЮЩЕЙ аллокации: живые буферы не
     * переаллоцируются, поэтому после смены предела разумен `switch` или
     * `stop`+`play`.
     */
    private fun setPacketMax(arg: String): String {
        val mb = (arg.toIntOrNull() ?: return "Формат: packetmax <МБ 4..256> | packetmax 0 (константа)")
            .coerceIn(0, 256)
        BinauralStreamImpl.setPacketMaxBytes(mb.toLong() * MB)
        return if (mb == 0) {
            "Потолок пакета: константа сборки (${BinauralStreamImpl.packetMaxBytesEffective() / MB} МБ)\n" +
                BinauralStreamImpl.packetStats()
        } else {
            "Потолок пакета: $mb МБ (применится к следующей аллокации)\n" +
                BinauralStreamImpl.packetStats()
        }
    }

    /**
     * Доля кучи под ОДИН пакет в процентах (0 = константа сборки, 86%).
     *
     * Нужна, чтобы искать предел на устройстве за одну установку: поднял
     * `packetpct 100` и перебирай `packetmax`. В проде не используется —
     * 86% подобраны замером (см. PacketMemoryBudget), а процент — предохранитель
     * против устройств с неожиданно маленькой кучей.
     */
    private fun setPacketPct(arg: String): String {
        val p = (arg.toIntOrNull()
            ?: return "Формат: packetpct <10..95> | packetpct 0 (константа 86%)")
            .coerceIn(0, 100)
        PacketMemoryBudget.setHeapPercent(p)
        return "Доля кучи под пакет: ${if (p == 0) "константа 86%" else "$p%"} " +
            "(применится к следующей аллокации)\n" + PacketMemoryBudget.report()
    }

    /**
     * Доля кучи под СУММУ пакетов всех потоков в процентах (0 = как packetpct).
     *
     * Без неё потолки выше бюджета проверять нельзя: бюджет урежет пакет
     * раньше, чем сработает `packetmax`, и прогон покажет предел бюджета, а
     * не аллокации.
     */
    private fun setGlobalPct(arg: String): String {
        val p = (arg.toIntOrNull()
            ?: return "Формат: packetgpct <10..95> | packetgpct 0 (как packetpct)")
            .coerceIn(0, 100)
        PacketMemoryBudget.setGlobalHeapPercent(p)
        return "Доля кучи под сумму пакетов: ${if (p == 0) "как packetpct" else "$p%"} " +
            "(применится к следующему доращиванию)\n" + PacketMemoryBudget.report()
    }

    private fun packetStats(): String = BinauralStreamImpl.packetStats()

    // ---- alloc: прямой тест аллокатора минуя стрим ----
    //
    // Нужен, чтобы отделить «ART не возвращает память от allocateDirect» от
    // «мы её не отпускаем». Поток проверяет доращивание СВОИМ предохранителем
    // (growPacketBuffer смотрит на свободное место и не пытается аллоцировать),
    // поэтому по одному стриму нельзя понять, течёт память или просто не
    // спрашивали. Здесь аллокация делается вслепую, как она и делалась бы
    // без предохранителя.
    private val heldBuffers = mutableListOf<java.nio.ByteBuffer>()
    private val heldArrays = mutableListOf<ByteArray>()

    private fun heapUsedMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / MB
    }

    private fun alloc(arg: String): String {
        val p = arg.split(Regex("\\s+"))
        when (p.getOrNull(0)?.lowercase(Locale.US)) {
            "free" -> {
                val n = heldBuffers.size
                heldBuffers.clear()
                System.gc()
                return "Отпущено $n буферов, System.gc(): занято ${heapUsedMb()}МБ"
            }
            "list" -> return if (heldBuffers.isEmpty()) "Удержанных буферов нет"
            else buildString {
                append("Удержано ${heldBuffers.size}: ")
                append(heldBuffers.joinToString { "${it.capacity() / MB}МБ" })
                append("\nзанято ${heapUsedMb()}МБ")
            }
            "cycle" -> {
                val times = p.getOrNull(1)?.toIntOrNull() ?: return "Формат: alloc cycle <повторов> <МБ>"
                val mb = p.getOrNull(2)?.toLongOrNull() ?: return "Формат: alloc cycle <повторов> <МБ>"
                return allocCycle(times.coerceIn(1, 20), mb.coerceIn(1, 512))
            }
            // Контроль к `cycle`: обычный byte[] на куче вместо прямого буфера.
            // Если он возвращается, а прямой — нет, причина в DirectByteBuffer
            // (Cleaner/неперемещаемый массив), а не в System.gc() вообще.
            "hcycle" -> {
                val times = p.getOrNull(1)?.toIntOrNull() ?: return "Формат: alloc hcycle <повторов> <МБ>"
                val mb = p.getOrNull(2)?.toLongOrNull() ?: return "Формат: alloc hcycle <повторов> <МБ>"
                return heapCycle(times.coerceIn(1, 20), mb.coerceIn(1, 512))
            }
            else -> {
                val mb = p.getOrNull(0)?.toLongOrNull() ?: return "Формат: alloc <МБ> | alloc free | alloc list | alloc cycle <N> <МБ>"
                val before = heapUsedMb()
                return try {
                    val b = java.nio.ByteBuffer.allocateDirect((mb * MB).toInt())
                    heldBuffers.add(b)
                    "allocateDirect($mb МБ) → capacity=${b.capacity() / MB}МБ, " +
                        "занято $before → ${heapUsedMb()}МБ"
                } catch (o: OutOfMemoryError) {
                    "allocateDirect($mb МБ) → OutOfMemoryError, занято ${heapUsedMb()}МБ"
                }
            }
        }
    }

    /** Выделить–отпустить N раз: возвращает ли ART память от прямого буфера. */
    private fun allocCycle(times: Int, mb: Long): String {
        heldBuffers.clear()
        System.gc()
        val base = heapUsedMb()
        val sb = StringBuilder("цикл allocateDirect($mb МБ) x$times, база ${base}МБ\n")
        for (i in 1..times) {
            var got: Long
            try {
                val b = java.nio.ByteBuffer.allocateDirect((mb * MB).toInt())
                got = b.capacity() / MB
                // Записать в каждый байт: без этого ART может отдать виртуальную
                // память, не замапив её, и замер окажется враньём.
                for (off in 0 until b.capacity() step 4096) b.put(off, 1.toByte())
            } catch (o: OutOfMemoryError) {
                sb.append("  $i: OutOfMemoryError, занято ${heapUsedMb()}МБ\n")
                break
            }
            val peak = heapUsedMb()
            heldBuffers.clear()
            // Две сборки с паузой: первая доставляет DirectByteBuffer в очередь
            // фантомных ссылок, вторая — после того как Cleaner обнулил byte[].
            System.gc()
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            System.gc()
            sb.append("  $i: выделено ${got}МБ, пик ${peak}МБ → после возврата ${heapUsedMb()}МБ\n")
        }
        return sb.toString()
    }

    /** То же, что [allocCycle], но обычный byte[] на куче (контроль). */
    private fun heapCycle(times: Int, mb: Long): String {
        heldArrays.clear()
        System.gc()
        val base = heapUsedMb()
        val sb = StringBuilder("цикл ByteArray($mb МБ) x$times, база ${base}МБ\n")
        for (i in 1..times) {
            val arr = try {
                ByteArray((mb * MB).toInt())
            } catch (o: OutOfMemoryError) {
                sb.append("  $i: OutOfMemoryError, занято ${heapUsedMb()}МБ\n")
                break
            }
            for (off in arr.indices step 4096) arr[off] = 1
            heldArrays.add(arr)
            val peak = heapUsedMb()
            heldArrays.clear()
            System.gc()
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
            System.gc()
            sb.append("  $i: выделено ${mb}МБ, пик ${peak}МБ → после возврата ${heapUsedMb()}МБ\n")
        }
        return sb.toString()
    }

    /** Хвост файлового лога потока — он пишется только в debug-сборке. */
    private fun logTail(lines: Int): String {
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        val file = File(dir, STREAM_LOG_NAME)
        if (!file.exists()) return "Лог не найден: ${file.absolutePath}"
        val take = lines.coerceIn(1, 500)
        val all = file.readLines()
        if (all.isEmpty()) return "Лог пуст: ${file.absolutePath}"
        val tail = all.takeLast(take)
        return "${file.absolutePath} (${all.size} строк, показано ${tail.size})\n" +
            tail.joinToString("\n")
    }

    // ============= UI =============

    private fun openUi(): String {
        return try {
            val intent = Intent(app, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            app.startActivity(intent)
            "MainActivity: startActivity вызван (Android может заблокировать старт из фона)"
        } catch (e: Throwable) {
            "Не удалось открыть MainActivity: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun exitApp(): String {
        return try {
            val intent = Intent(app, MainActivity::class.java).apply {
                action = MainActivity.ACTION_EXIT
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            app.startActivity(intent)
            "MainActivity: выход запрошен (ACTION_EXIT)"
        } catch (e: Throwable) {
            "Не удалось закрыть MainActivity: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ============= Вспомогательное =============

    /** Снимок всего, что нужно командам: один проход по DataStore. */
    private fun settings(): GlobalSettings = io {
        GlobalSettings(
            presets = repo.getPresets().first(),
            activeId = repo.getActivePresetId().first(),
            volume = repo.getVolume().first(),
            sampleRate = SampleRate.entries.find { it.value == repo.getSampleRate().first() }
                ?: SampleRate.LOW,
            bufferMinutes = repo.getBufferGenerationMinutes().first(),
            normalization = repo.getVolumeNormalizationSettings().first(),
            swap = repo.getChannelSwapSettings().first()
        )
    }

    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    /**
     * Есть ли у приложения задача на переднем плане. Нужен только для справки
     * в `status`: если UI на экране, часть команд дублируется в нём.
     * `getAppTasks()` на Android 13 кидает `SecurityException` для чужих
     * задач — поэтому через `runCatching`.
     */
    private fun uiVisible(): Boolean = runCatching {
        (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.appTasks?.any { it.taskInfo?.topActivity?.packageName == app.packageName } == true
    }.getOrDefault(false)

    private fun uniqueName(presets: List<BinauralPreset>, base: String): String {
        if (presets.none { it.name == base }) return base
        var i = 2
        while (presets.any { it.name == "$base ($i)" }) i++
        return "$base ($i)"
    }

    private fun resolvePreset(token: String): BinauralPreset? = resolvePreset(token, settings())

    private fun resolvePreset(token: String, st: GlobalSettings): BinauralPreset? {
        if (st.presets.isEmpty()) return null
        if (token.isEmpty()) return st.presets.find { it.id == st.activeId }
        token.toIntOrNull()?.let { i ->
            if (i in 1..st.presets.size) return st.presets[i - 1]
        }
        st.presets.find { it.id.equals(token, ignoreCase = true) }?.let { return it }
        return st.presets.find { it.name.contains(token, ignoreCase = true) }
    }

    private inline fun withPreset(token: String, block: (BinauralPreset) -> String): String {
        val preset = resolvePreset(token)
            ?: return "Пресет не найден: \"$token\". Список: presets"
        return block(preset)
    }

    private fun onOff(value: Boolean): String = if (value) "вкл" else "выкл"

    private fun parseBool(arg: String): Boolean = when (arg.lowercase(Locale.US)) {
        "1", "on", "true", "yes", "вкл" -> true
        "0", "off", "false", "no", "выкл" -> false
        else -> arg.toBoolean()
    }

    private fun fmt(value: Float, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value)

    private class GlobalSettings(
        val presets: List<BinauralPreset>,
        val activeId: String?,
        val volume: Float,
        val sampleRate: SampleRate,
        val bufferMinutes: Int,
        val normalization: VolumeNormalizationSettings,
        val swap: ChannelSwapSettings
    )

    private companion object {
        const val MB = 1024L * 1024L
        const val DEFAULT_SWITCH_DELAY_MS = 400L
        const val STREAM_LOG_NAME = "binaural_stream.log"
        // Те же значения, что в BinauralViewModel (там они приватные).
        const val DRAFT_PRESET_ID = "__draft__"
        const val EDITOR_PREVIEW_BUFFER_INTERVAL_MS = 60_000
    }
}
