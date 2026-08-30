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
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.BinauralPreset
import com.binaural.core.audio.model.ChannelSwapMode
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.NormalizationType
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
            "scale" -> setScale(arg)
            "vrun" -> setVirtualRunning(arg)
            "realtime" -> resetToRealTime()
            "delete", "del" -> withPreset(arg) { deletePreset(it) }
            "duplicate", "dup" -> withPreset(arg) { duplicatePreset(it) }
            "export" -> withPreset(arg) { exportPreset(it) }
            "import" -> importPreset(arg)
            "mem" -> memory()
            "gc" -> System.gc().let { "System.gc() выполнен — смотрите `mem`" }
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
     */
    private fun applyPreset(preset: BinauralPreset, st: GlobalSettings): String {
        val s = service
            ?: return "Сервис не запущен — воспроизведение не активно. Сначала `play`"
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
        return applyConfig("Интервал перестановки: $sec с")
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
            append("threshold=${mi.threshold / MB}MB")
        }
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
    }
}
