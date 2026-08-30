package com.binauralcycles.debug

import com.binauralcycles.BuildConfig

/** Тег, под которым и результаты команд, и их прогресс попадают в logcat. */
internal const val DEBUG_LOG_TAG = "BinauralDebug"

/**
 * Список команд. Живёт здесь, а не в исполнителе, чтобы `help` отвечал даже
 * когда ViewModel не подключён — именно в этот момент подсказка нужнее всего.
 */
internal val DEBUG_HELP = """
    Командный интерфейс (debug).

    Отправка (готовый хелпер): bash tools/dbgcmd.sh <команда>
    Вручную:  adb shell am broadcast -a com.binauralcycles.debug.COMMAND -p com.binauralcycles.debug --es cmd "'<команда>'"
              (внутренние кавычки обязательны: adb shell склеивает аргументы
              пробелом, и команда из двух слов развалилась бы)
    Ответ:    печатается в resultData вызова am broadcast; то же самое
              дублируется в logcat (тег BinauralDebug).
    Требование: MainActivity на экране — исполнитель команд живёт
              в BinauralViewModel.

    help                     этот список
    ui                       открыть главный экран
    exit                     закрыть приложение
    status | st              сводка состояния
    presets                  список пресетов (* — активный)
    play | pause | toggle    воспроизведение
    preset <n|id|имя>        выбрать и запустить пресет (n — с 1)
    next | prev              соседний пресет
    switch <n> [мс]          n быстрых смен подряд (стресс кроссфейда)
    switch stop              прервать серию
    volume <0..1>            громкость
    samplerate <Гц>          8000|16000|22050|44100|48000
    buffer <мин>             интервал генерации, 1..10
    norm <on|off>            нормализация громкости
    tnorm <on|off>           временная нормализация
    swap <off|timer|trend>   автоперестановка каналов
    swapinterval <сек>       интервал перестановки, 5..3600
    swapfade <on|off>        плавная перестановка
    vtime <on|off>           виртуальное время
    scrub <сек>              перемотка (секунды от полуночи)
    scale <1..60>            масштаб виртуального времени
    vrun <on|off>            пауза/пуск виртуального времени
    realtime                 вернуться к реальному времени
    delete <n|id|имя>        удалить пресет
    duplicate <n|id|имя>     скопировать пресет
    export <n|id|имя>        JSON пресета
    import <json>            импортировать пресет из JSON
    mem                      память процесса
    gc                       System.gc()
    logtail [строк]          хвост лога потока (binaural_stream.log)
""".trimIndent()

/**
 * Точка, умеющая выполнять одну текстовую команду и возвращать результат.
 *
 * Единственная реализация — [DebugCommandExecutor] поверх `BinauralViewModel`.
 */
interface DebugCommandTarget {
    fun execute(command: String): String
}

/**
 * Процессный «почтовый ящик» для adb-команд (только debug).
 *
 * Живёт в `main`, потому что исполнителю нужен доступ к `BinauralViewModel`,
 * который лежит там же. В release это ничего не стоит: `BuildConfig.DEBUG`
 * — константа `false`, R8 вырезает и саму регистрацию, и тела методов.
 *
 * Зачем шина, а не прямой вызов из приёмника: приёмник объявлен в source set
 * `debug` и потому не может ссылаться на типы из `main`, не будучи втянутым
 * в release. Шина — единственная деталь, которую видит приёмник; вся логика
 * остаётся в `main` и тестируется обычной сборкой `:app:compileDebugKotlin`.
 */
object DebugCommandBus {

    @Volatile
    private var target: DebugCommandTarget? = null

    /** Регистрируется в `BinauralViewModel.init`, снимается в `onCleared`. */
    fun attach(target: DebugCommandTarget) {
        if (BuildConfig.DEBUG) this.target = target
    }

    fun detach(target: DebugCommandTarget) {
        if (this.target === target) this.target = null
    }

    fun isAttached(): Boolean = BuildConfig.DEBUG && target != null

    /**
     * Выполнить команду. Никогда не бросает: падение внутри команды
     * возвращается текстом, иначе `am broadcast` молча теряет результат.
     */
    fun dispatch(command: String): String {
        if (!BuildConfig.DEBUG) {
            return "Командный интерфейс доступен только в debug-сборке"
        }
        val line = command.trim()
        if (line.isEmpty()) {
            return "Пустая команда. Отправьте \"help\" для списка команд."
        }
        // help отвечает всегда: подсказка нужнее всего именно когда UI не запущен.
        if (line.equals("help", ignoreCase = true) || line == "?") return DEBUG_HELP
        val t = target ?: return "ViewModel не подключён (UI не на экране). " +
            "Запустите приложение и повторите команду."
        return try {
            t.execute(line)
        } catch (e: Throwable) {
            "ОШИБКА: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
