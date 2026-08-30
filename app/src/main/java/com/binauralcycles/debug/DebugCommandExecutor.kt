package com.binauralcycles.debug

import android.app.ActivityManager
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
import com.binauralcycles.MainActivity
import com.binauralcycles.viewmodel.BinauralViewModel
import kotlinx.datetime.LocalTime
import java.io.File
import java.util.Locale

/**
 * Исполнитель текстовых команд поверх [BinauralViewModel].
 *
 * Все методы ViewModel вызываются здесь же, на главной нити — приёмник
 * получает broadcast именно на ней, так что дополнительной переправки
 * не требуется. Ни одна команда не должна бросать: результат уходит в
 * `am broadcast`, где исключение просто потерялось бы.
 */
class DebugCommandExecutor(
    private val context: Context,
    private val vm: BinauralViewModel
) : DebugCommandTarget {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var switchRunnable: Runnable? = null

    override fun execute(command: String): String {
        // Первый токен — команда, остаток строки — её аргумент (может содержать пробелы,
        // это нужно для `import <json>`).
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
            "pause", "stop" -> pause()
            "toggle" -> vm.togglePlayback().let { status() }
            "preset" -> selectPreset(arg)
            "next" -> stepPreset(+1)
            "prev" -> stepPreset(-1)
            "switch" -> switchLoop(arg)
            "volume", "vol" -> setVolume(arg)
            "samplerate", "sr" -> setSampleRate(arg)
            "buffer" -> setBuffer(arg)
            "norm" -> {
                val on = parseBool(arg)
                vm.setVolumeNormalizationEnabled(on)
                debounced("Нормализация громкости: ${onOff(on)}")
            }
            "tnorm" -> {
                val on = parseBool(arg)
                vm.setTemporalNormalizationEnabled(on)
                debounced("Временная нормализация: ${onOff(on)}")
            }
            "swap" -> setSwap(arg)
            "swapinterval" -> {
                val sec = (arg.toIntOrNull() ?: 0).coerceIn(5, 3600)
                vm.setChannelSwapInterval(sec)
                debounced("Интервал перестановки: $sec с")
            }
            "swapfade" -> {
                val on = parseBool(arg)
                vm.setChannelSwapFadeEnabled(on)
                debounced("Плавная перестановка: ${onOff(on)}")
            }
            "vtime" -> vm.setDebugVirtualTimeEnabled(parseBool(arg))
                .let { "Виртуальное время: ${vm.uiState.value.debugVirtualTimeEnabled}" }
            "scrub" -> {
                val sec = (arg.toIntOrNull() ?: 0).coerceIn(0, 86399)
                vm.debugScrubTime(sec)
                debounced("Перемотка на $sec с (${LocalTime.fromSecondOfDay(sec)})")
            }
            "scale" -> {
                val scale = (arg.toFloatOrNull() ?: 1f).coerceIn(1f, 60f)
                vm.debugSetTimeScale(scale)
                debounced("Масштаб времени: $scale")
            }
            "vrun" -> vm.debugSetVirtualTimeRunning(parseBool(arg))
                .let { "Виртуальное время идёт: ${vm.uiState.value.debugVirtualTimeRunning}" }
            "realtime" -> vm.debugResetToRealTime().let { "Возврат к реальному времени" }
            "delete", "del" -> withPreset(arg) { vm.deletePreset(it.id); "Пресет удалён: ${it.name}" }
            "duplicate", "dup" -> withPreset(arg) { vm.duplicatePreset(it.id); "Пресет скопирован: ${it.name}" }
            "export" -> withPreset(arg) {
                vm.exportPresetToJson(it.id) ?: "Не удалось экспортировать: ${it.name}"
            }
            "import" -> {
                if (arg.isEmpty()) return "Нужен JSON: import {…}"
                val id = vm.importPresetFromJson(arg)
                id?.let { "Пресет импортирован, id=$it" } ?: "Импорт не удался (см. logcat)"
            }
            "mem" -> memory()
            "gc" -> System.gc().let { "System.gc() выполнен — смотрите `mem`" }
            "logtail" -> logTail(arg.toIntOrNull() ?: 40)
            else -> "Неизвестная команда: \"$cmd\". Отправьте \"help\"."
        }
    }

    // ============= Воспроизведение =============

    private fun play(): String {
        if (vm.telemetry.value.isPlaying) return "Уже воспроизводится"
        vm.togglePlayback()
        return "Старт: playing=${vm.telemetry.value.isPlaying}"
    }

    private fun pause(): String {
        if (!vm.telemetry.value.isPlaying) return "Уже остановлено"
        vm.togglePlayback()
        return "Стоп: playing=${vm.telemetry.value.isPlaying}"
    }

    private fun selectPreset(arg: String): String {
        val preset = resolvePreset(arg) ?: return "Пресет не найден: \"$arg\". Список: presets"
        vm.playPreset(preset.id)
        return "Выбран пресет: ${preset.name} (${preset.id})"
    }

    private fun stepPreset(direction: Int): String {
        val presets = vm.uiState.value.presets
        if (presets.isEmpty()) return "Список пресетов пуст"
        val current = presets.indexOfFirst { it.id == vm.uiState.value.activePreset?.id }
        // current == -1 (ничего не активно) -> с 0-го при +1 и с последнего при -1
        val index = if (current < 0) {
            if (direction >= 0) 0 else presets.lastIndex
        } else {
            (current + direction + presets.size) % presets.size
        }
        val preset = presets[index]
        vm.playPreset(preset.id)
        return "Пресет ${index + 1}/${presets.size}: ${preset.name}"
    }

    /**
     * Серия быстрых смен пресета — воспроизведение падения при переключении
     * во время игры (`switch 20 250`). Работает по главной нити через Handler:
     * корутины ViewModel здесь не нужны и только усложнили бы отмену.
     */
    private fun switchLoop(arg: String): String {
        stopSwitch()
        val tokens = arg.split(Regex("\\s+"))
        if (tokens.firstOrNull()?.lowercase(Locale.US) == "stop") {
            return "Серия смен прервана"
        }
        val count = tokens.getOrNull(0)?.toIntOrNull()
            ?: return "Формат: switch <количество> [задержкаМс] | switch stop"
        val delayMs = tokens.getOrNull(1)?.toLongOrNull()?.coerceIn(0, 10000) ?: DEFAULT_SWITCH_DELAY_MS

        val presets = vm.uiState.value.presets
        if (presets.size < 2) return "Нужно минимум 2 пресета, сейчас ${presets.size}"

        var remaining = count.coerceIn(1, 1000)
        var index = presets.indexOfFirst { it.id == vm.uiState.value.activePreset?.id }
        val total = remaining
        val startedAt = System.currentTimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                if (remaining <= 0) {
                    switchRunnable = null
                    Log.i(DEBUG_LOG_TAG, "switch: серия из $total смен завершена за " +
                        "${System.currentTimeMillis() - startedAt} мс")
                    return
                }
                index = (index + 1) % presets.size
                val preset = presets[index]
                val n = total - remaining + 1
                val at = System.currentTimeMillis() - startedAt
                Log.i(DEBUG_LOG_TAG, "switch #$n/$total (+${at}мс) -> ${preset.name}")
                vm.playPreset(preset.id)
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

    // ============= Настройки =============

    private fun setVolume(arg: String): String {
        val value = arg.toFloatOrNull() ?: return "Формат: volume <0..1>"
        val clamped = value.coerceIn(0f, 1f)
        vm.setVolumeImmediate(clamped)
        vm.saveVolume()
        return "Громкость: %.2f".format(Locale.US, clamped)
    }

    private fun setSampleRate(arg: String): String {
        val hz = arg.toIntOrNull() ?: return "Формат: samplerate <8000|16000|22050|44100|48000>"
        val rate = SampleRate.entries.find { it.value == hz }
            ?: return "Нет такого SampleRate: $hz. Допустимо: " +
                SampleRate.entries.joinToString { it.value.toString() }
        vm.setSampleRate(rate)
        return debounced("Частота дискретизации: ${rate.value} Гц")
    }

    private fun setBuffer(arg: String): String {
        val minutes = arg.toIntOrNull() ?: return "Формат: buffer <минуты 1..10>"
        vm.setBufferGenerationMinutes(minutes)
        return "Интервал генерации: ${vm.uiState.value.bufferGenerationMinutes} мин"
    }

    private fun setSwap(arg: String): String {
        val mode = when (arg.lowercase(Locale.US)) {
            "off", "none", "0" -> null
            "timer" -> ChannelSwapMode.TIMER
            "trend" -> ChannelSwapMode.TREND
            else -> return "Формат: swap <off|timer|trend>"
        }
        vm.setChannelSwapSelection(mode)
        return debounced("Перестановка каналов: ${mode?.name ?: "off"}")
    }

    /**
     * Настройки идут через `restartWithFadeIfNeeded()` — дебаунс 300 мс, поэтому
     * состояние сразу после вызова ЕЩЁ СТАРОЕ. Сообщаем запрошенное значение и
     * прямо говорим, что подтверждение — в `status` примерно через 300 мс: иначе
     * ответ команды выглядит как «ничего не применилось».
     */
    private fun debounced(text: String): String =
        "$text (дебаунс 300 мс — подтверждение в `status`)"

    // ============= Состояние =============

    private fun status(): String {
        val s = vm.uiState.value
        val t = vm.telemetry.value
        return buildString {
            append("playing=${t.isPlaying} service=${s.isServiceConnected}\n")
            append("preset=${s.activePreset?.name ?: "<нет>"} id=${s.activePreset?.id ?: "-"}\n")
            append("beat=${fmt(t.currentBeatFrequency, 2)} Гц ")
            append("carrier=${fmt(t.currentCarrierFrequency, 1)} Гц ")
            append("swapped=${t.isChannelsSwapped}\n")
            append("time=${t.currentTime}\n")
            append("volume=${fmt(s.volume, 2)} rate=${s.sampleRate.value} Гц ")
            append("bufferMin=${s.bufferGenerationMinutes}\n")
            append("norm=${s.volumeNormalizationSettings.type} ")
            append("strength=${fmt(s.volumeNormalizationSettings.strength, 2)}\n")
            append("swap=${s.channelSwapSettings.enabled} mode=${s.channelSwapSettings.mode} ")
            append("interval=${s.channelSwapSettings.intervalSeconds}с ")
            append("fade=${s.channelSwapSettings.fadeEnabled}\n")
            append("vtime=${s.debugVirtualTimeEnabled} scale=${fmt(s.debugTimeScale, 1)} ")
            append("vrun=${s.debugVirtualTimeRunning}\n")
            append("presets=${s.presets.size}")
            val running = switchRunnable != null
            if (running) append(" (идёт серия смен, `switch stop`)")
        }
    }

    private fun presets(): String {
        val list = vm.uiState.value.presets
        if (list.isEmpty()) return "Список пресетов пуст"
        val activeId = vm.uiState.value.activePreset?.id
        return list.mapIndexed { i, p ->
            val mark = if (p.id == activeId) "*" else " "
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
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
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
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
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
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            context.startActivity(intent)
            "MainActivity: startActivity вызван (Android может заблокировать старт из фона)"
        } catch (e: Throwable) {
            "Не удалось открыть MainActivity: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun exitApp(): String {
        return try {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_EXIT
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
            "MainActivity: выход запрошен (ACTION_EXIT)"
        } catch (e: Throwable) {
            "Не удалось закрыть MainActivity: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ============= Вспомогательное =============

    private fun resolvePreset(token: String): BinauralPreset? {
        val presets = vm.uiState.value.presets
        if (presets.isEmpty()) return null
        if (token.isEmpty()) return vm.uiState.value.activePreset
        token.toIntOrNull()?.let { i ->
            if (i in 1..presets.size) return presets[i - 1]
        }
        presets.find { it.id.equals(token, ignoreCase = true) }?.let { return it }
        return presets.find { it.name.contains(token, ignoreCase = true) }
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

    private companion object {
        const val MB = 1024L * 1024L
        const val DEFAULT_SWITCH_DELAY_MS = 400L
        const val STREAM_LOG_NAME = "binaural_stream.log"

    }
}
