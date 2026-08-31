# AGENT_HANDOFF — binaural-cycles-android

> ## СТАТУС: РАБОТА ЗАВЕРШЕНА (2026-08-31)
>
> Всё, что ниже, — **исторический контекст** отладки. Актуальное состояние
> кода — коммит **`19d9557`** (`feat: gapless sequential preset crossfade,
> immediate fade-out on stop`), запушен в `origin/feat/beat-frequency-trend-swap`.
>
> Решено:
> - случайная задержка переключения 1.7–4.9 с — устранена (`pause()` в
>   `finalizeStop()` до опроса латча писателя);
> - «прилипание» кроссфейда к следующему пресету — устранено;
> - дыра ~65 мс между фейд-аутом и фейд-ином — устранена отдельным колбэком
>   «точки тишины» ровно в конце рампы;
> - найден и починен баг: правка громкости внутри окна кроссфейда не доходила
>   до вооружённого NEXT.
>
> Проверено на POCO 23049PCD8G в 10 сценариях (`tools/dbgxfade.sh`), плюс
> 112/112 нативных тестов и зелёный `:core:audio:testDebugUnitTest`.
> Файл можно удалить.

---

> Назначение: вводный контекст для агента, который приходит продолжить работу.
> Состояние на **2026-08-30 (ветка `feat/beat-frequency-trend-swap`)**.
> Автор контекста — предыдущий агент; этот файл можно удалить/закоммитить
> по усмотрению следующего агента после завершения работы.

---

## 0. TL;DR для входящего агента

- **Что уже запушено:** коммит `8dcb4e0` — параллельный кроссфейд при
  переключении пресетов, фикс вылета при быстром свитче, adb-командный
  интерфейс (debug-only). Всё компилируется, тесты зелёные.
- **Что НЕ закоммичено (лежит в working tree):** рефакторинг командного
  интерфейса — исполнитель перенесён из `BinauralViewModel` в `Application`,
  чтобы команды работали при погашенном экране. Код собирается и проверен на
  устройстве в фоне, **но не закоммичен**.
- **Главная нерешённая проблема:** переключение пресетов происходит не сразу,
  а с *случайной* задержкой (1.7–4.9 с), и кроссфейд почти никогда не слышен;
  когда слышен — он «прилипает» к **следующему** пресету. Корень — старый
  трек не освобождается вовремя (`pause()` вызывается слишком поздно), и
  orphan-гейт менеджера откладывает следующий хэндофф ровно на это время.
- **Готовый фикс описан в разделе 4** (точное место + код). Он НЕ применён —
  примените, пересоберите debug-APK, проверьте headless-скриптом и
  закоммитьте вместе с рефакторингом интерфейса.

---

## 1. Что уже сделано и запушено (commit `8dcb4e0`)

Коммит `8dcb4e0` (предыдущий HEAD → `origin/feat/beat-frequency-trend-swap`):
«feat: parallel preset crossfade, fast-switch crash fixes, debug adb command
interface», 15 файлов, +2269/−310.

Туда вошло:
- **Параллельный кроссфейд** в `BinauralStreamManager` (`beginHandoff()` →
  `captureContinuity()` → `prepare()` → NEXT `.start(EQUAL_POWER)` →
  `fadeOutCurrent(SWITCH)`; повышение NEXT в `promoteNextToCurrent()` по
  `onStreamSilent` через `dur + FADE_GUARD_MS`).
- **Фикс вылета при быстром свитче** (SIGABRT/SIGSEGV в нативном движке):
  упорядочивание `setVolume(0f)` → `closeShaper()` → `pause()` → релиз
  движка только после выхода писателя; `MAX_TRACK_BUFFER_BYTES = 2 MiB`
  (защита от `createTrack_l -12` на 48 кГц).
- **adb-командный интерфейс** (debug-only): `DebugCommandBus`,
  `DebugCommandExecutor` (тогда ещё в `BinauralViewModel`),
  `DebugCommandReceiver` + `app/src/debug/AndroidManifest.xml`, хелперы
  `tools/dbgcmd.sh`, `tools/dbgsmoke.sh`, `tools/dbgswitch.sh`.
- Нативные + JVM-тесты, `buildPlaybackConfig` и прочее.

Проверено: `:core:audio:testDebugUnitTest :app:compileDebugKotlin --rerun` →
BUILD SUCCESSFUL, 35/35. Секреты (`keystore.properties`, `*.keystore`) в
`.gitignore`.

---

## 2. Что сейчас НЕ закоммичено (рабочее дерево)

`git status` на момент написания:

```
изменено:  app/src/main/java/com/binauralcycles/BinauralCyclesApp.kt
изменено:  app/src/main/java/com/binauralcycles/debug/DebugCommandBus.kt
изменено:  app/src/main/java/com/binauralcycles/debug/DebugCommandExecutor.kt
изменено:  app/src/main/java/com/binauralcycles/service/BinauralPlaybackService.kt
изменено:  app/src/main/java/com/binauralcycles/viewmodel/BinauralViewModel.kt
неотслеживаемые: app/src/main/java/com/binauralcycles/debug/DebugRepositoryEntryPoint.kt
                 tools/dbgxfade.sh
```

### Суть рефакторинга командного интерфейса (см. раздел 5 почему)
**Проблема:** первая версия `DebugCommandExecutor` создавалась в
`BinauralViewModel`. ViewModel живёт, пока на экране Activity, поэтому при
погашенном экране любая команда отвечала «ViewModel не подключён». Проверять
кроссфейд в фоне (не будя устройство) было невозможно.

**Решение (уже написано и собирается):**
- `BinauralCyclesApp.onCreate()` теперь подключает исполнитель:
  ```kotlin
  if (BuildConfig.DEBUG) {
      DebugCommandBus.attach(DebugCommandExecutor(this))
  }
  ```
  В release `BuildConfig.DEBUG == false` → R8 вырезает блок целиком.
- `DebugCommandExecutor` теперь принимает `Application`, а не `ViewModel`.
  Читает пресеты/настройки из `BinauralPreferencesRepository` через Hilt
  entry-point (`DebugRepositoryEntryPoint`), управляет воспроизведением через
  `BinauralPlaybackService.liveInstance` / статический `isPlaying`.
  Ключевые методы: `applyPreset(preset)`, `applyConfig(report)` (зеркало
  `updateAudioConfig`), `settings()` (один проход DataStore → `GlobalSettings`),
  `startService()` (учитывает запрет запуска foreground-сервиса из фона на
  Android 12+), `uiVisible()` (обёрнуто в `runCatching` — на Android 13
  `UsageStatsManager` бросает `SecurityException`).
- `app/src/main/java/com/binauralcycles/debug/DebugRepositoryEntryPoint.kt`
  (НОВЫЙ):
  ```kotlin
  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface DebugRepositoryEntryPoint {
      fun preferencesRepository(): BinauralPreferencesRepository
  }
  ```
- `BinauralPlaybackService.kt`: добавлен
  `internal val liveInstance: BinauralPlaybackService? get() = serviceInstance`.
  Сервис `START_STICKY`, но сам останавливается через `stopSelf()` при
  остановке звука — поэтому экземпляр может быть `null` (это не ошибка).
- `BinauralViewModel.kt`: вынесен файловый `internal fun buildPlaybackConfig(
  frequencyCurve, volume, channelSwap, normalization): BinauralConfig` (раньше
  дублировался в `playPreset` и `updateAudioConfig`); удалена привязка к
  `DebugCommandExecutor`.
- `DebugCommandBus.kt`: обновлены комментарии — исполнитель живёт в
  Application, работает при погашенном экране через сервис; сообщение
  «ViewModel не подключён» заменено на «Исполнитель не подключён — процесс
  приложения не запущен…».
- `tools/dbgxfade.sh` (НОВЫЙ): headless-эксперимент по таймингам кроссфейда,
  **не будит устройство** (читает файловый лог с точного байтового смещения
  начала эксперимента через `stat`).

**Статус:** `:app:compileDebugKotlin` и `:app:assembleDebug` → BUILD
SUCCESSFUL, APK (`app-arm64-v8a-debug.apk`) установлен и проверен headless:
`status`/`presets`/`play`/`preset`/`switch` отвечают корректно при
`ui=false`. **НЕ закоммичено.**

---

## 3. Главная нерешённая проблема: случайная задержка переключения

### 3.1 Симптомы (со слов пользователя)
- Пресеты переключаются **не сразу**, а с некоторой **случайной, каждый раз
  разной** задержкой.
- Кроссфейда **почти никогда нет**.
- Когда кроссфейд **есть** — он появляется **уже ПОСЛЕ** переключения на
  следующий пресет, во время его воспроизведения.

### 3.2 Что измерено (эксперимент 23:26, лог `binaural_stream.log`)
Все замеры при `sr=44100`. Чистое одиночное переключение (spec#1 → spec#2):
- `beginHandoff` spec#2 в 15.669
- fade-out CURRENT в 15.745 (**через 76 мс**)
- fade-in done в 16.053 → promote в 16.057
- **писатель OLD-трека (spec#1) вышел из `write(WRITE_BLOCKING)` только в
  20.962 → релиз 21.016.** **Orphan-release lag ≈ 4.9 с.**

Первый отложенный хэндофф появился в 23:26:44.315 («spec#6 ждёт освобождения
старого трека spec#4»), потому что spec#4 вышел в 23:26:46.003 — **откладывание
≈ 1.7 с**. Это и есть «случайная» задержка: она равна времени, оставшемуся
доигрывать писателю в текущем чанке.

При `switch 6 400` (6 свитчей с интервалом 400 мс): spec#5 (43.544) → spec#6
отложен до релиза spec#4 (46.030) → spec#7 начат 46.034 (≈2.5 с после тапа
43.953). Последующие свитчи coalesce через single-slot очередь.

### 3.3 Корень проблемы (два звена)

**Звено А — старый трек не освобождается вовремя (причина случайной
задержки).**
`finalizeStop()` (BinauralStreamImpl.kt ~734-769) гасит громкость, дёргает
`onSilent` (точка повышения NEXT), ставит lifecycle в STOPPING, затем
**опрашивает латч выхода писателя** (`WRITER_EXIT_WAIT_MS + 500` = 9.5 с)
60-мс поллингом, и только в `releaseInternal()` (вызванном из этого поллинга)
вызывается `audioTrack?.pause()`. А `pause()` — это то, что прерывает
заблокированный `write(WRITE_BLOCKING)` (`mProxy->interrupt`). Пока `pause()`
не вызван, писатель доигрывает **остаток чанка** `WRITE_CHUNK_MS = 8000 мс` —
до ~5-8 с после тишины. Весь это время трек ещё живёт в AudioFlinger, и
orphan-гейт менеджера держит следующий хэндофф.

Важно: комментарий в `releaseInternal()` (строки ~1154-1168) **уже описывает
этот инвариант** («Снятие трека — ДО ожидания писателя… pause() прерывает
заблокированный write»), но сам `pause()` вызывается именно в
`releaseInternal()` — то есть **слишком поздно**, после того как писатель
провисел в `write()` всю длину чанка.

**Звено Б — orphan-гейт откладывает СЛЕДУЮЩИЙ хэндофф (причина «кроссфейд на
следующем пресете»).**
`BinauralStreamManager` держит `orphanReleasing: BinauralStreamImpl?`
(строка 126). `handoffBlocked()` (455) возвращает true, пока `orphanReleasing`
жив; `beginHandoff()` (492) при заблокированном гейте **не стартует** новый
хэндофф, а спека остаётся в очереди. `ORPHAN_WAIT_MAX_MS = 2000` — жёсткий
предел, после которого менеджер перестаёт ждать (защита от залипания).

Очередь `PlaybackQueue` — **single-slot, latest-wins** (A→B→C coalesce в C).
Когда orphan наконец освобождается, `promoteNextToCurrent()` (663) видит в
очереди **самую свежую** спеку (ту, что натапали за время ожидания) и
запускает хэндофф для НЕЁ. Поэтому:
- быстрые повторные тапы «слипаются» в один;
- кроссфейд реализуется не для пресета, который пользователь переключал в
  момент задержки, а для **последнего** из натапанных — отсюда ощущение
  «кроссфейд появляется уже на следующем пресете».

### 3.4 Почему кроссфейда почти никогда нет
Из-за coalesce очереди и того, что хэндофф стартует только после релиза
orphan, окно EQUAL_POWER (ровно два одновременно звучащих трека) либо
схлопывается, либо приходится на уже играющий следующий трек — слышимого
перекрытия нет.

---

## 4. Готовый фикс (НЕ применён — примените и проверьте)

**Цель:** разблокировать писателя СРАЗУ в `finalizeStop()`, а не ждать
поллинга до `releaseInternal()`. Тогда orphan-трек освобождается через ~0 мс
после тишины, orphan-гейт перестаёт задерживать следующий хэндофф, и
кроссфейд стартует на **запрошенном** пресете.

**Файл:** `core/audio/src/main/java/com/binaural/core/audio/stream/BinauralStreamImpl.kt`
**Функция:** `finalizeStop(onFullyStopped: () -> Unit)` (начало ~734)
**Место вставки:** сразу ПОСЛЕ блока `compareAndSet(PLAYING, STOPPING)`
(строки 749-751) и ДО настройки writer-exit poll (комментарий «Писатель
выходит не дольше одного чанка…», строка 752).

**Вставить:**

```kotlin
        // ФИКС ЗАДЕРЖКИ ПЕРЕКЛЮЧЕНИЯ: разблокируем писателя СРАЗУ, а не в
        // releaseInternal() после опроса латча. pause() прерывает заблокированный
        // write(WRITE_BLOCKING) (mProxy->interrupt); иначе писатель доигрывает
        // остаток чанка — до ~5-8 с. Пока трек жив в AudioFlinger, orphan-гейт
        // менеджера (handoffBlocked/orphanReleasing) держит СЛЕДУЮЩИЙ хэндофф
        // ровно на это время => случайная задержка переключения пресета.
        // Неслышно: к этому моменту громкость уже в нуле (setVolume(0) выше,
        // база и множитель шейпера), остаток кольца не нужен.
        try { audioTrack?.pause() } catch (_: Exception) {}
```

**Почему безопасно:**
- Громкость уже в нуле (строка 740 `setVolume(0f)`), тишина гарантирована —
  пользователь не услышит щелчка.
- `pause()` идемпотентен; в `releaseInternal()` он будет вызван повторно
  (строка 1168) — это безвредно.
- Латч выхода писателя (`writerExitLatch`) после `pause()` снимется быстро,
  поэтому writer-exit poll в `finalizeStop()` завершится на ранней итерации,
  и `releaseInternal()` будет вызван уже по нулевому счётчику (штатный путь,
  см. комментарий ~1187).
- Не трогает порядок релиза движка (ФИКС №1/№2 в `releaseInternal`): движок
  по-прежнему освобождает только вышедший писатель.

**Чего НЕ менять (несовместимо с текущей архитектурой):**
- `TRACK_BUFFER_MS` (10000), `WRITE_CHUNK_MS` (8000), `MIN_WRITE_MARGIN_MS`
  (1000) — не трогать без отдельного обоснования.
- `MAX_TRACK_BUFFER_BYTES = 2 MiB` — защита от `createTrack_l -12` на 48 кГц.
- Логику EQUAL_POWER `buildCurve()` (обязан `coerceIn(0f,1f)` — иначе
  `cos(π/2)` float = `-4.37e-8` роняет весь кроссфейд).

**Проверка (после применения):** см. раздел 6. Ожидаем: при `switch 6 400`
каждый хэндофф стартует в пределах ~300 мс после тапа (а не через 1.7-4.9 с),
и в логе кроссфейд виден между запрошенным и следующим пресетом, а не
«на следующем».

---

## 5. Почему командный интерфейс переделан под «без экрана»

Пользователь явно потребовал: **не будить и не разблокировать устройство при
тестах** (проверять, не беспокоя его), и **если команды заточены только под
пробуждённый режим — переделать по-нормальному**.

Поэтому исполнитель перенесён в `Application` (живёт, пока жив процесс) и
работает через `BinauralPlaybackService` + `BinauralPreferencesRepository`,
минуя Activity/ViewModel. Проверено на устройстве: `status` при `ui=false`
возвращает `playing=false service=false ui=false`; `play` стартует
foreground-сервис из фона; повторный `status` → `playing=true service=true
ui=false`. Ни одна команда не будит экран.

---

## 6. Как проверять БЕЗ пробуждения устройства (headless)

**adb:** `/home/nikita/tools/android-sdk/platform-tools/adb`
**Устройство:** POCO 23049PCD8G, Android 13, arm64-v8a.
**Рабочий IP:** `192.168.162.9` (старый `192.168.30.119:5555` мёртв).
Если `adb devices` пуст — `adb connect 192.168.162.9:5555`.

**Команда (broadcast, debug-only):**
```bash
adb shell am broadcast -a com.binauralcycles.debug.COMMAND \
  -p com.binauralcycles.debug --es cmd "'<cmd>'"
# ответ — в logcat тега BinauralDebug
```
Примеры команд: `status`, `presets`, `play`, `pause`, `preset <id>`,
`next`, `prev`, `switch <n> <ms>`, `volume <0..1>`, `samplerate`, `buffer`,
`norm`, `tnorm`, `swap*`, `vtime`, `scrub`, `scale`, `vrun`, `realtime`,
`mem`, `gc`, `logtail`, `ui`, `exit`. Полный список — `DEBUG_HELP` в
`DebugCommandBus.kt`.

**Чтение ответа без логката (тише):**
`adb shell logcat -d -s BinauralDebug | tail -n 20`

**Хелперы (уже в `tools/`):**
- `tools/dbgcmd.sh <cmd>` — одна команда.
- `tools/dbgsmoke.sh` — дымовой прогон.
- `tools/dbgswitch.sh` — серия переключений.
- `tools/dbgxfade.sh` — **headless-замер таймингов кроссфейда**: не будит
  устройство; читает файловый лог с точного байтового смещения начала
  эксперимента.

**Файловый лог потока (только debug):**
`/sdcard/Android/data/com.binauralcycles.debug/files/Download/binaural_stream.log`
ВНИМАНИЕ: не путать с `/sdcard/Download/binaural_stream.log` — там застарелый
дубль. Для анализа таймингов тяните именно первый путь.

**Чтобы экран точно не проснулся:** при необходимости (экран выключен/AOD)
команды могут не дойти — но в НОВОЙ схеме (исполнитель в Application) это не
нужно. Если всё же нужно «дёрнуть» экран для доставки broadcast (старая
схема/другие приложения), делайте это ТОЛЬКО с явного согласия пользователя:
`input keyevent 224` → `82` → `am start -n <pkg>/MainActivity`.

---

## 7. Конвенции сборки / коммита / пуша

**Сборка (gradlew только `.bat`; Gradle 8.13 + JDK 17):**
fish требует явного PATH:
```bash
env PATH=/usr/lib/jvm/java-17-openjdk/bin:/usr/bin:/bin \
    ANDROID_HOME=/home/nikita/tools/android-sdk \
    /home/nikita/tools/gradle-8.13/gradle-8.13/bin/gradle \
    :app:compileDebugKotlin :core:audio:compileDebugKotlin --offline --no-daemon
```
Gradle пишет UP-TO-DATE на изменённых исходниках → добавляйте `--rerun`.
Каталог `build/tmp/kotlin-classes` удалять нельзя (safe-delete гард).

**Нативные тесты (без NDK):**
```
cmake -S core/audio/src/main/cpp/tests -B /tmp/bptest_build && \
cmake --build /tmp/bptest_build -j8 && /tmp/bptest_build/buffer_package_tests
```
**JVM-тесты:** `:core:audio:testDebugUnitTest`.

**Коммит:** сообщение — через файл (в fish нет heredoc):
```bash
# Write в .git/commit_msg.txt → git commit -F .git/commit_msg.txt → удалить файл
```
Перед `git add -A`: `git ls-files | grep -i keystore` (секреты не коммитить).
В дереве бывает **чужой WIP** — перед сборкой `git status`, чужое не
коммитьте.

**Пуш:** `env GIT_TERMINAL_PROMPT=0 git push origin feat/beat-frequency-trend-swap`
(PAT в `credential.helper store`). Remote —
`https://github.com/NIK2703/binaural-cycles-android`.

**Release (для справки):** `:app:assembleRelease` (~3 мин), подпись
`keystore.properties` (alias `NIK2703`); выход — ABI-сплиты
`app/build/outputs/apk/release/app-<abi>-release.apk`. **Текущий release 9.2
собран ДО рефакторинга интерфейса и ДО фикса задержки — пересоберите его
после применения фикса, если нужен свежий релиз.**

---

## 8. Где искать доп. контекст (memory)

- `.workbuddy-ai/memory/MEMORY.md` — сводка проектных соглашений (beat,
  PointIntentMemory, режим расслабления, инварианты UI-графика, время/пауза/
  кроссфейд, debug-командный интерфейс, сборка, релиз/устройство, git/fish).
- `.workbuddy-ai/memory/2026-08-30.md` — хронология дня (в т.ч. эксперимент
  23:26 по кроссфейду и корень задержки). **Допишите туда итог применения
  фикса и новый коммит.**
- `app/src/debug/AndroidManifest.xml` — receiver живёт только в debug; в
  release его нет (проверяйте по слитому манифесту).

---

## 9. Чеклист для следующего агента

1. **[Блокирующее]** Применить фикс из раздела 4 (`finalizeStop` ранний
   `pause()`).
2. Пересобрать debug-APK (`app-arm64-v8a-debug.apk`) и установить
   `adb install -r`.
3. Запустить `bash tools/dbgxfade.sh` headless; убедиться, что задержка
   переключения ушла (хэндофф ≤ ~300 мс после тапа) и кроссфейд стартует на
   запрошенном пресете.
4. Прогнать нативные + JVM-тесты (раздел 7).
5. Закоммитить рефакторинг интерфейса + фикс задержки одним коммитом на
   `feat/beat-frequency-trend-swap`, запушить.
6. **[Опционально, нужно решение пользователя]** Промежуточные пресеты при
   быстром свитче «слипаются» (single-slot очередь). Варианты: debounce на
   стороне UI, либо soft-mix в один трек, либо оставить coalesce как есть
   (фикс задержки уже снимает худшую часть — случайную паузу). Обсудить с
   пользователем.
7. Пересобрать release 9.2 с обоими фиксами (если нужен свежий релиз).
8. Дописать `.workbuddy-ai/memory/2026-08-30.md`.

---

## 10. Известные подводные камни

- **fish-shell:** нет `VAR=val` (→ `set VAR val`), нет heredoc, `for..do`/
  `if..then`; `grep --include` падает → используйте инструмент Grep. Скрипты —
  через Write + `bash <file>`.
- **Не будите устройство** без согласия пользователя — вся проверка кроссфейда
  идёт headless.
- **AudioFlinger client heap** на POCO = 7 MiB; кольцо округляется до степени
  двойки (3 MiB → 4 MiB) → при ≥4 треках `createTrack_l -12`. Поэтому
  `MAX_TRACK_BUFFER_BYTES = 2 MiB` — максимум для двух треков.
- **`ByteBuffer.allocateDirect` на Android живёт в Java-heap**, не в native RAM.
- **`BinauralAudioEngine.kt` — мёртвый код**; форматтер переписывает файлы при
  сохранении → перед Edit перечитывайте.
- Сообщение коммита и пуш — файлом; секреты в `.gitignore`.
