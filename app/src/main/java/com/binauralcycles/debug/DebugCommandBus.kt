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
    Условие:  процесс приложения жив. Экран может быть выключен и активити
              уничтожена — исполнитель подключён в Application и работает
              через сервис, поэтому фоновые проверки никого не будят.
              Команды, меняющие звук, требуют запущенного воспроизведения
              (сервис останавливает сам себя при остановке).

    help                     этот список
    ui                       открыть главный экран
    exit                     закрыть приложение
    status | st              сводка состояния
    presets                  список пресетов (* — активный)
    state                    состояние автомата звука (HANDOFF/FADE_IN/…)
    play                     старт воспроизведения
    stop                     полная остановка с фейд-аутом (утилизация)
    pause                    мягкая пауза с фейд-аутом (поток жив)
    resume                   возобновление после мягкой паузы
    toggle                   play/stop
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
    pcreset                  обнулить пики счётчиков пакетной памяти
    packetmax <МБ|0>         потолок ОДНОГО пакетного буфера на ходу (0 = без потолка)
    packetpct <10..95|0>     доля кучи под ОДИН пакет в % (0 = 86%); чтобы искать
                             предел вместе с packetmax
    packetgpct <10..95|0>    то же для СУММЫ пакетов всех потоков (0 = как packetpct)
    bufstat                  бюджет памяти и диапазоны слайдера по всем частотам
    alloc <МБ>               выделить прямой буфер и удержать
    alloc list|free          список / отпустить удерживаемые буферы
    alloc cycle <N> <МБ>     выделить–отпустить N раз: возвращает ли ART память
    pkstat                   счётчики пакетной памяти (живые буферы, пик, OOM)
    logtail [строк]          хвост лога потока (binaural_stream.log)
""".trimIndent()

/**
 * Точка, умеющая выполнять одну текстовую команду и возвращать результат.
 *
 * Единственная реализация — [DebugCommandExecutor] поверх сервиса и
 * репозитория настроек.
 */
interface DebugCommandTarget {
    fun execute(command: String): String
}

/**
 * Процессный «почтовый ящик» для adb-команд (только debug).
 *
 * Живёт в `main`, потому что исполнителю нужны сервис воспроизведения и
 * репозиторий настроек, а они там же. В release это ничего не стоит: `BuildConfig.DEBUG`
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

    /** Регистрируется в `BinauralCyclesApp.onCreate` — живёт, пока жив процесс. */
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
        // help отвечает всегда: подсказка нужнее всего именно когда что-то не так.
        if (line.equals("help", ignoreCase = true) || line == "?") return DEBUG_HELP
        val t = target ?: return "Исполнитель не подключён — процесс приложения не запущен. " +
            "Откройте приложение один раз (или пошлите `ui`) и повторите команду."
        return try {
            t.execute(line)
        } catch (e: Throwable) {
            "ОШИБКА: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
