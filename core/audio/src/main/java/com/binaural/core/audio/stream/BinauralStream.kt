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

    /**
     * МЯГКАЯ ПАУЗА: рампа 0 → тишина, затем track.pause().
     *
     * В отличие от [stop] ресурсы СОХРАНЯЮТСЯ: AudioTrack, нативный движок
     * (фазы, положение на кривой) и уже сгенерированный пакет вместе со
     * смещением недописанного остатка. Пауза — это заморозка, а не
     * утилизация: возобновление продолжает звук ровно с того же сэмпла.
     *
     * @param onPaused вызывается ОДИН раз, когда поток гарантированно тих и
     *        трек остановлен. Ресурсы остаются живы до [stop]/[abort].
     */
    fun pause(onPaused: () -> Unit, shape: FadeShape = FadeShape.LINEAR): Boolean

    /**
     * Возобновление после [pause]: track.play() + рампа 0→1, запись
     * продолжается с сохранённого смещения — без перегенерации и без сдвига
     * позиции на кривой.
     */
    fun resume(onFullyStarted: () -> Unit, shape: FadeShape = FadeShape.LINEAR): Boolean

    /** Мгновенное освобождение НИКОГДА не звучавшего потока (бесшумно). */
    fun abort()

    fun setVolume(volume: Float)
    fun getElapsedSeconds(): Int
    fun getCurrentTimeOfDay(): Int

    /**
     * Точная СЛЫШИМАЯ позиция кривой (секунды суток): по позиции головы
     * воспроизведения, а не по UI-экстраполяции. Место, где звук остановился
     * в момент паузы и откуда он продолжится при возобновлении.
     */
    fun getAudibleTimeOfDaySeconds(): Int

    /**
     * Переякорить часы сессии (elapsed). Возобновление после паузы обязано
     * сдвинуть якорь: нативный elapsed считается по wall-clock и иначе
     * включил бы в себя всю длительность паузы.
     */
    fun setPlaybackStartTime(anchorMs: Long)

    /** Поток стоит на мягкой паузе (звук заморожен, ресурсы живы). */
    val isPaused: Boolean

    /**
     * Поток звучит, но рампа fade-in ещё не завершена.
     *
     * Для менеджера это запрет на повышение NEXT: кроссфейд EQUAL_POWER
     * корректен ровно для двух звучащих потоков, третьего он не учитывает.
     */
    val isFadingIn: Boolean

    fun isChannelsSwapped(): Boolean
    fun getFrequenciesAtCurrentTime(): Pair<Float, Float>?
}
