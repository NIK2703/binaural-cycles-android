package com.binaural.core.audio.stream

enum class StreamLifecycle { CREATED, PREPARED, PLAYING, STOPPING, RELEASED, FAILED }
enum class FadeMode { NONE, IN, OUT }

/**
 * Форма огибающей фейда.
 * LINEAR — обычные старт/стоп/пауза.
 * EQUAL_POWER — парные кривые sin/cos для кроссфейда (sin²+cos²=1):
 * суммарная энергия двух потоков в окне перекрытия постоянна, провала −3 дБ нет.
 */
enum class FadeShape { LINEAR, EQUAL_POWER }

/**
 * Одноразовый бинауральный поток. Контракт звука:
 *   либо поток никогда не звучал (тогда допустим только [abort]),
 *   либо он звучит строго как  0 --fade-in--> V --fade-out--> 0.
 */
interface BinauralStream {
    val spec: PlaybackSpec
    val lifecycle: StreamLifecycle

    /** Создать движок/трек/буфер и сгенерировать ПЕРВЫЙ пакет. Звука нет. Синхронно. */
    fun prepare(): Boolean

    /** play() трека + рампа 0→1 (фикс 1.1: шейпер до play и до первой записи). */
    fun start(onFullyStarted: () -> Unit, shape: FadeShape = FadeShape.LINEAR): Boolean

    /**
     * Рампа до 0, затем полная утилизация. Идемпотентен.
     * @param onSilent вызывается ОДИН раз в момент, когда рампа дошла до нуля:
     *        поток уже гарантированно тих, но релиз ещё может идти. Точка
     *        «повышения» NEXT при кроссфейде.
     * @param onFullyStopped вызывается после полного освобождения ресурсов.
     */
    fun stop(
        onFullyStopped: () -> Unit,
        onSilent: (() -> Unit)? = null,
        shape: FadeShape = FadeShape.LINEAR
    )

    /** Разворот рампы: идущий fade-out превращается в fade-in с текущего значения. */
    fun reverseFadeToPlaying(onFullyStarted: () -> Unit): Boolean

    /** Мгновенное освобождение НИКОГДА не звучавшего потока (бесшумно). */
    fun abort()

    fun setVolume(volume: Float)
    fun getElapsedSeconds(): Int
    fun getCurrentTimeOfDay(): Int
    fun isChannelsSwapped(): Boolean
    fun getFrequenciesAtCurrentTime(): Pair<Float, Float>?
}
