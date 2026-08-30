package com.binauralcycles.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Приёмник adb-команд. Существует ТОЛЬКО в debug-сборке: класс лежит в source
 * set `app/src/debug`, а регистрация — в `app/src/debug/AndroidManifest.xml`,
 * поэтому ни класса, ни упоминания о нём в release-манифесте нет.
 *
 * Отправка:
 * ```
 * adb shell am broadcast -a com.binauralcycles.debug.COMMAND \
 *     -p com.binauralcycles.debug --es cmd "'status'"
 *
 * Форма с явным компонентом (`-n com.binauralcycles.debug/.DebugCommandReceiver`)
 * на этом устройстве доставку не выполняет — работает только явный action
 * с указанием пакета. Готовый хелпер: tools/dbgcmd.sh <команда>.
 * ```
 *
 * Результат пишется в logcat под тегом [DEBUG_LOG_TAG]: `am broadcast` отдаёт
 * `resultData` только для упорядоченных рассылок и обрезает длинные ответы,
 * поэтому logcat — основной канал. Дублирование в `resultData` оставлено на
 * случай, если шелл всё-таки его напечатает.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val output = DebugCommandBus.dispatch(command)
        val failed = output.startsWith("ОШИБКА")

        // logcat режет сообщение примерно на 4000 байт — длинные ответы
        // (export, presets, logtail) делим на куски, иначе хвост теряется.
        ("cmd: $command\n$output").lineSequence().forEach { line ->
            if (line.length <= MAX_LOG_CHUNK) {
                Log.i(DEBUG_LOG_TAG, line)
            } else {
                val chunks = (line.length + MAX_LOG_CHUNK - 1) / MAX_LOG_CHUNK
                for (i in 0 until chunks) {
                    val from = i * MAX_LOG_CHUNK
                    val to = minOf(from + MAX_LOG_CHUNK, line.length)
                    Log.i(DEBUG_LOG_TAG, "[$i/$chunks] ${line.substring(from, to)}")
                }
            }
        }

        if (isOrderedBroadcast) {
            resultCode = if (failed) RESULT_ERROR else RESULT_OK
            resultData = output
        }
    }

    companion object {
        const val ACTION_COMMAND = "com.binauralcycles.debug.COMMAND"
        const val EXTRA_COMMAND = "cmd"

        private const val RESULT_OK = 0
        private const val RESULT_ERROR = 1

        // С запасом: в сообщении помимо текста есть служебные поля logcat.
        private const val MAX_LOG_CHUNK = 3000
    }
}
