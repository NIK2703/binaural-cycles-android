# Анализ: экспорт пресета создаёт пустой файл

**Дата:** 2026-09-04
**Симптом:** «Экспорт» в меню пресета → SAF-пикер → «Сохранить» → файл создаётся, но 0 байт.
**Вердикт:** это НЕ баг сериализации. Это потеря состояния при пересоздании Activity
во время открытого SAF-пикера. **Хранить состояние экспорта нужно ВНЕ процесса —
в файле кэша приложения.**

---

## 1. Кратко (TL;DR)

Первая попытка фикса (состояние в `ViewModel`) не помогла: при открытии SAF-пикера
Activity уничтожается, `ViewModelStore` очищается, и при возврате создаётся новая
ViewModel. Поле исчезает. Колбэк ланчера срабатывает уже с новой ViewModel —
`pending == null`, блок записи пропускается, файл остаётся пустым, без сообщений.

Доказательство получено логом на устройстве (тег `PresetExport`):
```
prepare:  vm=145308842   ← ViewModel ДО открытия пикера
consume:  vm=201341784   ← ViewModel ПОСЛЕ — другая
```
PID процесса при этом тот же, то есть пересоздаётся именно Activity, а не процесс.

Решение: подготовленный JSON (имя пресета + его сериализация) кладётся в файл
`cacheDir/pending_export.json` ДО открытия пикера; колбэк лаунчера забирает файл
с диска. Это переживает и пересоздание Activity, и смерть процесса с потерей
saved-state (файл в `cacheDir` сохраняется до явного удаления или системной чистки).

---

## 2. Участки кода

| Файл | Роль |
|---|---|
| `app/src/main/java/com/binauralcycles/ui/navigation/Navigation.kt` | лаунчеры, snackbar (через SnackbarHost) |
| `app/src/main/java/com/binauralcycles/viewmodel/BinauralViewModel.kt` | `prepareExport` / `discardPendingExport` / `consumePendingExport`, `importPresetFromJson` / `importPresetFromUri` |
| `app/src/main/res/values/strings.xml` | тексты снэкбаров |
| `app/src/main/AndroidManifest.xml` | нет `android:configChanges` → recreate на любой config-change |

---

## 3. Механика сбоя, по шагам

```
[Пользователь] меню пресета → «Экспорт»
       │
       ▼
onExportPreset(presetId):
  scope.launch(IO) {                          ← запись JSON на диск ДО пикера
    prepareExport(id) -> File(cacheDir, pending_export.json)
    withContext(Main) { exportLauncher.launch(fileName) }
  }
       │
       ▼
[SAF открыт] MainActivity → onStop → onDestroy (always_finish_activities=1
  или реальный системный kill)
  ViewModelStore.clear() → новая ViewModel при возврате
  pending_export.json НА ДИСКЕ остаётся
       │
       ▼
[Пользователь] выбирает папку → «Сохранить»
  Android создаёт файл (0 байт) и возвращает URI
  Activity пересоздаётся → новая ViewModel
       │
       ▼
Колбэк exportLauncher (НОВАЯ ViewModel):
  consumePendingExport() -> читает файл с диска → JSON
  openOutputStream(uri).write(json)             ← теперь пишем
  snackbarHostState.showSnackbar("Предустановка <Имя> экспортирована")
```

`rememberLauncherForActivityResult` и его `rememberSaveable`-ключ доставляют
результат после пересоздания — но без внешнего носителя данных этот результат
становится бесполезен (см. логи `vm=...`).

---

## 4. Доказательства (эмпирика, 2026-09-04, устройство POCO 23049PCD8G / lineage_marble, Android 13)

1. **VM пересоздаётся — это и есть причина провала первой правки.**
   Логи `PresetExport` на свежей установке (debug arm64-v8a) при `always_finish_activities=1`:
   ```
   18:11:51 prepare:  id=preset-circadian-rhythm, 1539 симв., vm=145308842
   18:12:35 callback: uri=content://...primary%3ADocuments%2FЦиркадный_ритм.json
   18:12:35 consume:  1539 симв., vm=201341784
   18:12:35 callback: записано 1552 байт
   ```
   `vm=…` — `System.identityHashCode(this)`: до пикера и после — разные числа.
   PID процесса тот же. Значит, поле `ViewModel` гарантированно теряется при
   пересоздании Activity.

2. **Файл на диске (`cacheDir/pending_export.json`) живёт, пока открыт пикер.**
   `adb shell run-as com.binauralcycles.debug ls -l cache/` показывает файл
   1552 байт прямо во время диалога SAF, и его же содержимое попадает в итоговый
   `Циркадный_ритм.json`.

3. **Сериализация не виновата.** `json.encodeToString(preset)` отрабатывает и в
   debug-CLI, и в `prepareExport` (см. логи выше).

4. **Прошлая «улика» про `pointSeconds` несостоятельна.** В ранней редакции
   анализа утверждалось, что в JSON от старого `.debug` присутствует поле
   `pointSeconds`, и это якобы доказывало, что установленный APK собран из
   устаревших исходников. На самом деле `pointSeconds` **до сих пор есть** в
   актуальной модели (`core/audio/.../BinauralPreset.kt:144`, `private val` —
   поле тела класса, не параметр конструктора). По умолчанию kotlinx-сериализация
   берёт только свойства из primary constructor, так что в выходной JSON
   `pointSeconds` попадать не должен; его появление в старом выводе CLI —
   отдельная тема, к данному багу отношения не имеет.

---

## 5. Сопутствующие дефекты (заодно исправлены)

1. **Полная тишина при любом сбое.** Раньше `openOutputStream → null`,
   `IOException` в `write`, исключение в `encodeToString`, потеря состояния —
   всё глоталось без снэкбара. Теперь — `runCatching` + явный `snackbar.show`
   с `MaterialTheme.colorScheme.inverseSurface/inverseOnSurface` (стиль
   приложения в обеих темах).
2. **`catch (e: Exception)` в `exportPresetToJson` / `importPresetFromJson`**
   превращал любую ошибку в `null` без диагностики. Теперь — `Log.e(...)` +
   понятное сообщение пользователю.
3. **`encodeDefaults = false`** оставлен без изменений: он убирает дефолтные
   `carrierRange`/`beatRange`/`splineTension`, и round-trip на них проходит
   (дефолты восстанавливаются при decode). Менять — отдельная задача.

---

## 6. Воспроизведение (детерминированное)

```bash
adb shell settings put global always_finish_activities 1
# меню пресета → «Экспорт» → выбрать папку → «Сохранить»
# с фиксом: файл > 0 байт, в логе PresetExport «записано N байт»
adb shell settings put global always_finish_activities 0
```

---

## 7. Фикс (текущая, рабочая версия)

Принцип — готовить JSON до открытия пикера и хранить его там, где пересоздание
Activity не страшно. Сначала пробовали ViewModel — не помогло (см. §4.1). Помог
диск.

### ViewModel

```kotlin
@Serializable
private data class PendingExport(val presetName: String, val json: String)

/** Имя временного файла (в cacheDir) с JSON, подготовленным к экспорту. */
private const val KEY_PENDING_EXPORT = "pending_export.json"

private fun pendingExportFile(): File = File(context.cacheDir, KEY_PENDING_EXPORT)

fun prepareExport(presetId: String): String? {           // IO
    val preset = getPresetForExport(presetId) ?: return null
    val exportedJson = exportPresetToJson(presetId) ?: return null
    return try {
        val payload = json.encodeToString(PendingExport(preset.name, exportedJson))
        val tmp = File(context.cacheDir, KEY_PENDING_EXPORT + ".tmp")
        tmp.writeText(payload)
        val target = pendingExportFile()
        if (!tmp.renameTo(target)) { target.writeText(payload); tmp.delete() }
        "${preset.name.replace(" ", "_")}.json"
    } catch (e: Exception) { Log.e("PresetExport", "prepare: …", e); null }
}

fun discardPendingExport() {                              // IO (на отмене)
    pendingExportFile().delete()
}

fun consumePendingExport(): Pair<String, String>? {       // IO
    val file = pendingExportFile()
    if (!file.exists()) return null
    val payload = json.decodeFromString<PendingExport>(
        file.readText().also { file.delete() }
    )
    payload.presetName to payload.json
}
```

`importPresetFromUri` теперь возвращает `BinauralPreset?` (а не только id) — чтобы
снэкбар импорта мог показать имя.

### Navigation

```kotlin
val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json")
) { uri ->
    if (uri == null) {
        scope.launch(Dispatchers.IO) { viewModel.discardPendingExport() }
        return@rememberLauncherForActivityResult
    }
    scope.launch(Dispatchers.IO) {
        val pending = viewModel.consumePendingExport()
        if (pending == null) {
            snackbarHostState.showSnackbar(
                String.format(exportFailedMessage, "данные экспорта потеряны, повторите")
            )
            return@launch
        }
        val (presetName, presetJson) = pending
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("openOutputStream вернул null")
            stream.bufferedWriter().use { it.write(presetJson) }
        }.fold(
            onSuccess  = { snackbarHostState.showSnackbar(String.format(exportSuccessMessage, presetName)) },
            onFailure  = { e ->
                Log.e("PresetExport", "callback: запись не удалась", e)
                snackbarHostState.showSnackbar(String.format(exportFailedMessage, e.message ?: e.javaClass.simpleName))
            }
        )
    }
}

Scaffold(snackbarHost = {
    SnackbarHost(hostState = snackbarHostState, modifier = …) { data ->
        Snackbar(
            snackbarData = data,
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface
        )
    }
})
```

`importLauncher` устроен зеркально: `importPresetFromUri` на IO,
`String.format(importSuccessMessage, imported.name)` при успехе, статичный
`importFailedMessage` при ошибке, навигация — с Main.

### Почему этого достаточно

- Файл в `cacheDir` переживает смерть Activity (`ViewModelStore` чистится — файл
  остаётся) и смерть процесса (пока кэш не вычищен системой).
- JSON посчитан ДО открытия пикера — нет гонки с `_uiState.value.presets` после
  пересоздания, когда пресеты могут ещё не догрузиться из DataStore.
- На отмене пикера файл удаляется — нет «залежавшегося» экспорта.
- При невозможности забрать данные (кэш вычищен, что-то пошло не так) —
  пользователь видит снэкбар вместо тихого нуля.

---

## 8. Проверка фикса (чек-лист)

- [x] Обычный экспорт: файл > 0 байт, содержимое — валидный JSON пресета.
      `Суточный_цикл (3).json` 1543 байт, `Циркадный_ритм.json` 1552 байт — оба
      валидны, JSON декодируется.
- [x] `always_finish_activities 1`: экспорт всё равно пишет содержимое
      (главная регрессия, на которой погорела ViewModel-версия фикса).
- [x] Отмена в пикере: `discardPendingExport()` удаляет файл, лишнего не пишем.
- [x] Снэкбар показывается и на успех («Предустановка <Имя> экспортирована»),
      и на ошибку («Не удалось экспортировать предустановку: …»).
- [x] Оформление снэкбара привязано к `MaterialTheme.colorScheme.*` —
      автоматически вписывается в текущую палитру (сейчас приложение
      рендерится тёмным независимо от системной темы; в светлой палитре
      inverse-цвета Material 3 переключатся без правок).
