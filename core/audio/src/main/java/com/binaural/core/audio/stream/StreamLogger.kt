package com.binaural.core.audio.stream

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import com.binaural.core.audio.BuildConfig
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Файловый логгер для ДЕБАГ-сборки.
 *
 * Пишет подробные логи работы подсистемы бинауральных потоков (создание/фейды/
 * handoff/очередь/ошибки) в файл в папке Загрузки (Downloads), а также дублирует
 * их в Logcat. В релиз-сборке [BuildConfig.DEBUG] == false — логгер молчит и
 * ничего не пишет на диск.
 *
 * Запись идёт в собственном фоновом потоке (HandlerThread), чтобы не блокировать
 * аудио-актор и writer. Каждое сообщение сбрасывается на диск (flush) сразу.
 *
 * Ограничений размера/частоты НЕТ — за чистоту лога отвечает отсутствие
 * «мусорных» (высокочастотных) вызовов StreamLogger в коде, а не подавление.
 */
object StreamLogger {

    private const val TAG = "StreamLogger"
    private const val FILE_NAME = "binaural_stream.log"

    /** Включён только в debug-сборке. */
    private val enabled = BuildConfig.DEBUG

    @Volatile private var appContext: Context? = null
    @Volatile private var writer: BufferedWriter? = null

    private val logThread = HandlerThread("StreamLogFile").apply { start() }
    private val logHandler = Handler(logThread.looper)
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (!enabled) return
        appContext = context.applicationContext
        logHandler.post { openFile() }
    }

    fun flush() {
        if (!enabled) return
        logHandler.post { synchronized(this) { runCatching { writer?.flush() } } }
    }

    private fun openFile() {
        if (writer != null) return
        writer = openMediaStore() ?: openFallback()
        if (writer == null) {
            Log.e(TAG, "не удалось открыть файл лога (Downloads и fallback недоступны)")
            return
        }
        val line = "==== StreamLogger session @ ${dateFmt.format(Date())} (DEBUG) ====\n"
        runCatching { writer?.write(line); writer?.flush() }
    }

    /** Публичные Загрузки через MediaStore (API 29+, без WRITE_EXTERNAL_STORAGE). */
    private fun openMediaStore(): BufferedWriter? {
        val ctx = appContext ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = ctx.contentResolver
            val uri = findExistingMediaStore(resolver) ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } ?: return null
            BufferedWriter(OutputStreamWriter(resolver.openOutputStream(uri, "wa"), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore open failed: ${e.message}")
            null
        }
    }

    private fun findExistingMediaStore(resolver: android.content.ContentResolver): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val sel = "${MediaStore.Downloads.DISPLAY_NAME}=?"
        runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, sel, arrayOf(FILE_NAME), null
            )
        }.getOrNull()?.use { c ->
            if (c.moveToFirst()) {
                return android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                )
            }
        }
        return null
    }

    /** Фолбэк: приватная папка приложения .../files/Download/ (всегда доступна). */
    private fun openFallback(): BufferedWriter? = runCatching {
        val ctx = appContext ?: return null
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        dir.mkdirs()
        val f = java.io.File(dir, FILE_NAME)
        java.io.BufferedWriter(java.io.FileWriter(f, true))
    }.getOrNull()

    private fun emit(level: String, tag: String, msg: String) {
        if (!enabled) return
        val ts = dateFmt.format(Date())
        val line = "$ts [$level] $tag: $msg\n"
        Log.println(levelToPriority(level), tag, msg)
        logHandler.post {
            synchronized(this) {
                if (writer == null) openFile()
                runCatching {
                    writer?.write(line)
                    writer?.flush()
                }
            }
        }
    }

    private fun levelToPriority(level: String): Int = when (level) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        "E" -> Log.ERROR
        else -> Log.DEBUG
    }

    fun d(tag: String, msg: String) = emit("D", tag, msg)
    fun i(tag: String, msg: String) = emit("I", tag, msg)
    fun w(tag: String, msg: String) = emit("W", tag, msg)
    fun e(tag: String, msg: String) = emit("E", tag, msg)
}
