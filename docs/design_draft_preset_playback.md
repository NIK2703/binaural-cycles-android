# Проигрывание несохранённой предустановки (черновика)

Статус: **реализовано 2026-09-06**; `gradle :app:compileDebugKotlin` — BUILD
SUCCESSFUL. Проверка на устройстве (§9) не проводилась.

Решения по открытым вопросам (подтверждены пользователем):
1. Семантика кнопки play не меняется: во время звучания первый тап — пауза,
   переключение на редактируемое происходит при старте из остановленного
   состояния.
2. Имя черновика — без перекомпозиции на каждый символ: редактор пишет имя в
   обычное (не StateFlow) поле ViewModel (`noteDraftName`), которое читается
   ровно один раз — в момент старта прослушивания (`draftDisplayName()`);
   пустое имя → строка `draft_preset` («Черновик»).

## 1. Что не так сейчас

В приложении две разные сессии редактирования, и прослушивание работает только
для одной из них.

| Сессия | Как открывается | Состояние | Прослушивание |
|---|---|---|---|
| Существующий пресет | `startEditingPreset(id)` | `editingPresetId != null`, пресет есть в списке | ✅ работает |
| Новый пресет | `startNewPreset()` | `editingPresetId == null`, кривая живёт только в `editingFrequencyCurve` | ❌ не работает |

Для существующего пресета всё уже сделано: `togglePlayback()` (BinauralViewModel.kt:1381)
видит открытый редактор и вместо «возобновить прежний звук» вызывает
`startPreset(editingId, curveOverride = editingFrequencyCurve, relaxationOverride = …)`.
Комментарий там же (строки 1367–1371) прямо говорит, что новый пресет
«переключать некуда — его просто нет в списке», и оставляет прежнее поведение.

Точные места, где новый пресет проигрывается:

1. **`togglePlayback()` → ветка 1391.** Условие `editingId != null` не выполняется,
   управление падает в `updateAudioConfig() + resumeWithFade()` — то есть звучит
   **прежний активный пресет**, а не то, что нарисовано на экране.
2. **`isEditingActivePreset()` (1743).** Требует `editingPresetId != null`, поэтому
   ни одна правка черновика не уходит в движок: даже если бы черновик зазвучал,
   править его «на слух» было бы нельзя.
3. **`updateAudioConfig()` (2279).** Источник кривой выбирается по
   `editingPresetId == activePreset?.id`; для черновика условие ложно всегда.
4. **`showBottomPanel` (Navigation.kt:111).** `= uiState.activePreset != null`.
   Если ничего раньше не звучало, панели нет вообще — **нажать некуда**.

Итого: новый пресет нельзя послушать до сохранения, а значит нельзя и подобрать
частоты на слух — ровно то, что для сохранённых предустановок уже работает.

## 2. Целевое поведение

Неизменное правило: **кнопка play в редакторе приводит прослушивание к тому,
что открыто на экране**. Оно уже выполняется для сохранённых пресетов — черновик
должен подчиняться ему так же.

Второе правило (тоже уже действующее): play — это «пауза/пуск»; переключение на
редактируемую кривую происходит на **старте** из остановленного/паузного
состояния. Пока что-то звучит, первый тап — пауза (как сейчас и для пресетов).

## 3. Модель: явный «источник звука»

Сейчас источник звука неявен: им считается `activePreset`. Для черновика это
непригодно, потому что `activePreset` — ещё и точка возврата (авторезюм,
восстановление после выхода из редактора, подсветка в списке, персистентный
`activePresetId`).

Отклонённые варианты:

- **Поддельный пресет в `activePreset`** (`BinauralPreset(id = "__draft__")`).
  Плюс: вся downstream-логика продолжает работать без правок. Минус: сущность,
  которой нет в хранилище, попадает во все проверки `presets.find { it.id == … }`,
  в сохранение/удаление/экспорт, а точку возврата всё равно пришлось бы хранить
  отдельным полем. Риск «утечь» в персистентность слишком велик.
- **Автосохранение черновика в хранилище при старте прослушивания.** Меняет
  модель данных и плодит мусорные пресеты, если пользователь передумал.

Принятый вариант — **разделить «что звучит» и «что сохранено»**:

- `activePreset` — последний **реальный** сохранённый пресет. Персистится,
  восстанавливается, используется авторезюмом, является точкой возврата при
  выходе из редактора. Черновик его не затирает.
- `draftSounding: Boolean` — источник звука — несохранённый черновик
  (возможно только при `editingPresetId == null`).
- `editingSessionActive: Boolean` — открыт экран редактора (нужен, чтобы панель
  была видна и чтобы «черновиковая» логика не утекла на экран списка).

Инвариант: **звучит ровно один источник.** Любой запуск реального пресета
(`startPreset`) сбрасывает `draftSounding`; любой выход из редактора — тоже.

## 4. Новые поля `BinauralUiState`

```kotlin
// Звучит несохранённая предустановка из редактора (editingPresetId == null).
val draftSounding: Boolean = false,
// Открыт экран редактора: есть что прослушать, даже если активного пресета нет.
val editingSessionActive: Boolean = false,
```

Константа-метка для диалога наушников и MediaSession:

```kotlin
private const val DRAFT_PRESET_ID = "__draft__"
```

## 5. Правки

### 5.1. `startDraft()` / `endDraftAudition()` — новые приватные методы

```kotlin
private fun startDraft() {
    val state = _uiState.value
    val curve = state.editingFrequencyCurve ?: return

    // Старт явный, как при переключении пресета: интервал буфера —
    // пользовательский. Минутный «редакторский» интервал ставится уже
    // на первой правке (armEditorPreviewBufferInterval).
    restoreUserBufferInterval()
    // СКРАБ: сдвиг НЕ сбрасываем. К моменту старта он всегда 0: выход из
    // редактора его стирает (releaseEditorScrub), а внутри сессии нового
    // пресета ручка скраба скрыта (маркер не показывается).

    _uiState.update {
        it.copy(draftSounding = true, carrierRange = curve.carrierRange, beatRange = curve.beatRange)
    }
    playbackService?.setCurrentPresetName(context.getString(R.string.draft_preset))
    playbackService?.setCurrentPresetId(DRAFT_PRESET_ID)
    playbackService?.updateConfig(
        buildPlaybackConfig(
            frequencyCurve = curve,
            volume = state.volume,
            channelSwap = state.channelSwapSettings,
            normalization = state.volumeNormalizationSettings
        ),
        state.editingRelaxationModeSettings
    )
    // updateConfig во время воспроизведения — это хэндофф (кроссфейд), старт
    // делает он сам; вне воспроизведения нужен явный play().
    if (!_telemetry.value.isPlaying) playbackService?.play()
}

/**
 * Черновик больше не источник звука: вернуть прослушивание к активному
 * пресету, а если его нет — остановить (возвращаться некуда).
 */
private fun endDraftAudition() {
    if (!_uiState.value.draftSounding) return
    _uiState.update { it.copy(draftSounding = false) }
    restoreUserBufferInterval()
    val active = _uiState.value.activePreset
    if (active != null) {
        playbackService?.setCurrentPresetName(active.name)
        playbackService?.setCurrentPresetId(active.id)
        updateAudioConfig()   // кроссфейд обратно к сохранённому пресету
    } else {
        playbackService?.stopWithFade()
        playbackService?.setCurrentPresetName(null)
        playbackService?.setCurrentPresetId(null)
    }
}
```

### 5.2. `togglePlayback()` — черновая ветка рядом с существующей (1381)

```kotlin
// Новый, ещё не сохранённый пресет: переключать некуда (в списке его нет),
// но слушать надо ровно то, что нарисовано на экране.
val draftSession = state.editingPresetId == null &&
    state.editingSessionActive && state.editingFrequencyCurve != null
if (draftSession) {
    if (state.draftSounding) {
        // Черновик уже источник звука — это просто «продолжить»:
        // перепушить конфиг, чтобы в движок ушли последние правки.
        updateAudioConfig()
        playbackService?.resumeWithFade()
    } else {
        startDraft()
    }
    return
}
```

### 5.3. Диалог «подключите наушники» (1343, 678, 852)

`pendingPresetId` — строка; для черновика используем `DRAFT_PRESET_ID`:

- в `togglePlayback()`:
  `val pendingId = state.editingPresetId ?: (if (draftSession) DRAFT_PRESET_ID else null)
   ?: state.activePreset?.id ?: lastActivePresetId`;
- общий исполнитель для двух точек подтверждения (`playPresetAnyway` и
  коллектор `hasHeadset` в `observePlaybackState`, 858):

```kotlin
private fun startPending(id: String) {
    if (id == DRAFT_PRESET_ID) startDraft() else startPreset(id, null, null)
}
```

### 5.4. `isEditingActivePreset()` → `isEditingSounding()` (1743)

```kotlin
private fun isEditingSounding(): Boolean {
    val s = _uiState.value
    if (s.editingPresetId != null) return s.editingPresetId == s.activePreset?.id
    return s.draftSounding && s.editingFrequencyCurve != null
}
```

Этого достаточно: через этот гейт проходят **все** пуши правок
(`updateEditingCurve`, `setInterpolationType`, `setStepFadeDurationMs`,
`setSplineTension`, `updateEditingCarrierRange`, `pushEditingRelaxationToService`
— вызовы на строках 1795, 1817, 1839, 1856, 1874). Правка черновика начнёт
пересобирать звучащий поток кроссфейдом, как у сохранённого пресета.

### 5.5. `updateAudioConfig()` (2285) — третий источник кривой

```kotlin
val (frequencyCurve, relaxationModeSettings) = when {
    state.draftSounding && state.editingPresetId == null ->
        (state.editingFrequencyCurve ?: FrequencyCurve.defaultCurve()) to state.editingRelaxationModeSettings
    isActivePresetEditing ->
        (state.editingFrequencyCurve ?: state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve()) to
            state.editingRelaxationModeSettings
    else ->
        (state.activePreset?.frequencyCurve ?: FrequencyCurve.defaultCurve()) to
            (state.activePreset?.relaxationModeSettings ?: RelaxationModeSettings())
}
```

Это единственное место, откуда кривая уходит в движок при смене глобальных
настроек (нормализация, перестановка каналов, частота дискретизации) — после
правки звук останется черновиком.

### 5.6. `startPreset()` (955) — сброс черновика

Первой строкой после поиска пресета:

```kotlin
// Звучит реальный пресет — черновик перестаёт быть источником звука.
if (_uiState.value.draftSounding) {
    _uiState.update { it.copy(draftSounding = false) }
}
```

Сюда же попадает переключение с гарнитуры / MediaSession (`onPresetSwitch`, 413):
пользователь листает пресеты — черновик молча уходит.

### 5.7. `createPreset()` (1222) — сохранение звучащего черновика

```kotlin
fun createPreset(
    name: String,
    curve: FrequencyCurve,
    relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    activate: Boolean = false
) {
    val preset = BinauralPreset(name = name, frequencyCurve = curve, relaxationModeSettings = relaxationModeSettings)
    if (activate) {
        // Сессия закончена, черновик больше не источник звука: теперь звучит
        // настоящий пресет с именем и id. Кривая та же, поэтому менеджер
        // дедуплицирует конфиг и хэндоффа не будет — звук не дёрнется.
        _uiState.update { it.copy(activePreset = preset, draftSounding = false, editingSessionActive = false) }
        playbackService?.setCurrentPresetName(preset.name)
        playbackService?.setCurrentPresetId(preset.id)
        restoreUserBufferInterval()
        updateAudioConfig()
        lastActivePresetId = preset.id
    }
    viewModelScope.launch {
        preferencesRepository.addPreset(preset)
        if (activate) preferencesRepository.saveActivePresetId(preset.id)
    }
}
```

`PresetEditScreen.saveAndNavigateBack()` для `presetId == null` вызывает
`createPreset(..., activate = true)`. Порядок относительно `releaseEditorScrub()`
не меняется (скраб снимается последним, как сейчас).

### 5.8. Выходы из редактора

- `cancelEditingInService()` (1130): после `resetScrub()` и
  `restoreUserBufferInterval()` — `editingSessionActive = false`, и если
  `draftSounding` — `endDraftAudition(); return` (иначе оставить существующее
  `updateFrequencyCurve(activePreset.frequencyCurve)`).
- `cancelEditing()` (1101): то же + очистка состояния.
- `saveEditingPreset()` (1241): `editingSessionActive = false`
  (черновика здесь быть не может, но флаг сессии обязателен).
- `startNewPreset()` / `startEditingPreset()`: `editingSessionActive = true`,
  `draftSounding = false` (новая сессия начинается без прослушивания).

**Сирота-черновик.** Черновик не переживает пересоздание ViewModel (поворот,
смерть процесса): `editingFrequencyCurve` восстанавливается шаблоном
(`startNewPreset` из `LaunchedEffect` экрана), а сервис со своим конфигом живёт
дальше. Нужен признак, переживающий ViewModel, — им стала метка
`SavedStateHandle[KEY_DRAFT_SOUNDING]`:

- `startDraft()` ставит её; все точки, где гаснет `draftSounding`
  (`endDraftAudition`, `startPreset`, `startEditingPreset`, `createPreset` с
  `activate`, `startNewPreset`), её снимают — `clearDraftSoundingMark()`;
- `startNewPreset()` читает метку и поднимает `orphanDraftPending = true`;
- разбор — в `updateAudioConfig()`, сразу после проверки «все настройки
  прочитаны»:

```kotlin
if (orphanDraftPending && playbackService != null) {
    orphanDraftPending = false
    if (stopOrphanSound()) return   // источника нет — гасим до всякого пуша
    // иначе звук вернётся к активному пресету этим же пушем конфига
}
```

Разбирать в `startNewPreset()` нельзя: `presets` грузятся асинхронно, и
`activePreset` там ещё может быть `null` просто от недогрузки — звук активного
пресета был бы погашен ошибочно. Ориентироваться на
`_telemetry.value.isPlaying` тоже нельзя: в `onServiceConnected` конфиг пушится
**до** подключения наблюдателей, то есть `isPlaying` ещё `false` при живом и
звучащем движке.

Итог: если активный пресет есть — звук кроссфейдом возвращается к нему; если его
нет — `stopWithFade()` и пустой заголовок уведомления. Дефолтная кривая в живой
движок не уходит никогда.

> Первая редакция ставила страховку в `maybeRestoreEditingSession()` на ветку
> `EDITING_TARGET_NEW`. Ветка недостижима: `startNewPreset()` пишет
> `KEY_EDITING_TARGET` и тут же его снимает (в отличие от `startEditingPreset`,
> где ранний `return` на недогруженном списке оставляет ключ живым). Отсюда и
> переезд на отдельную метку.

### 5.9. `Navigation.kt`

```kotlin
val showBottomPanel = uiState.activePreset != null || uiState.draftSounding || uiState.editingSessionActive
...
presetName = if (uiState.draftSounding) stringResource(R.string.draft_preset) else uiState.activePreset?.name,
```

Первое условие даёт панель (а значит и кнопку play) в редакторе, даже когда
ничего раньше не звучало. Флаг `editingSessionActive` обязателен: одного
`editingFrequencyCurve != null` мало — после сохранения/выхода кривая
намеренно не очищается (нужна для shared-анимации), и панель «уехала» бы на
экран списка.

### 5.10. `PresetEditScreen.kt`

Строка 324 — маркер указателя и ручка скраба должны появляться и для черновика:

```kotlin
val isEditingSounding = if (presetId == null) uiState.draftSounding
                        else uiState.activePreset?.id == presetId
```

(заменяет `isEditingActivePreset` в `isPlaying` на строке 337).

### 5.11. Строки

Новый `draft_preset`: ru «Черновик», en «Draft», es «Borrador»,
zh-rCN/zh-rTW «草稿».

## 6. Матрица сценариев

| Сценарий | Поведение |
|---|---|
| Новый пресет, ничего не звучало, тап play | Зазвучит черновик; панель показывает «Черновик» |
| Новый пресет, звучал пресет A, тап play (звук идёт) | Пауза (как сейчас для пресетов) |
| …то же, звук на паузе, тап play | Переключение на черновик (кроссфейд) |
| Правка точки при звучащем черновике | Кроссфейд на минутном буфере, слышно сразу |
| Скраб при звучащем черновике | Работает (ручка видна, ось сдвигается) |
| Выход «Не сохранять», до черновика звучал A | Возврат к A кроссфейдом |
| Выход «Не сохранять», до черновика ничего не звучало | `stopWithFade()`, имени в уведомлении нет |
| Сохранение черновика | Пресет становится активным и продолжает звучать без разрыва |
| Переключение пресета с гарнитуры | `startPreset` гасит `draftSounding` |
| Поворот экрана при звучащем черновике | Черновик не переживает ViewModel: есть активный пресет — звук кроссфейдом вернулся к нему; нет — `stopWithFade()` |
| Смерть процесса / авторезюм | Возобновляется прежний реальный пресет: `activePresetId` черновиком не перезаписывается |
| Диалог наушников при старте черновика | Показывается; «Запустить» идёт через `startPending(DRAFT_PRESET_ID)` |

## 7. Осознанно не делаем

- **Имя черновика в панели и уведомлении.** Имя живёт в локальном состоянии
  экрана (`presetName`); тащить его в `uiState` — значит перекомпоновывать всё
  дерево (включая тяжёлый график) на каждый символ. Показываем фиксированную
  метку «Черновик»: имя ещё не зафиксировано, и это честно.
- **Персистентность черновика.** Иначе придётся хранить кривую в
  `SavedStateHandle` и восстанавливать звук после поворота — отдельная фича,
  которая заодно спасла бы несохранённые правки. Сейчас правки при повороте
  всё равно теряются, так что и звук гасим (инвариант: звучать может только то,
  что есть в состоянии).
- **Переключение на редактируемое первым тапом во время звучания.** Сейчасplay
  во время воспроизведения — это пауза, и менять эту семантику (для пресетов
  тоже) стоит отдельным решением.

## 8. Риски

1. **Хэндофф на старте.** `updateConfig()` во время звучания — это
   `requestHandoff()`/`beginHandoff()`, старт делает он сам; `play()` нужен
   только вне воспроизведения. Ровно та же логика, что в `startPreset()`
   (1021–1026) — повторяем её, а не `switchPresetWithFade()`.
2. **Двойной пуш при сохранении.** `createPreset(activate = true)` вызывает
   `updateAudioConfig()`, а затем экран вызывает `releaseEditorScrub()`. Кривая
   та же, поэтому хэндофф дедуплицируется менеджером; скраб добавляет не более
   одного кроссфейда — как в уже существующем пути сохранения.
3. **`resetScrub()` не звать** при старте черновика: сдвиг принадлежит редактору
   и снимается только на выходе.
4. **`editingSessionActive` обязателен для `showBottomPanel`**: без него панель
   с «черновой» логикой остаётся на экране списка (кривая после выхода
   намеренно не очищается).
5. **`updateAudioConfig()` при `activePreset == null`** всё ещё подсовывает
   дефолтную кривую в движок — но только когда звук и так ничей (приложение
   стартовало без активного пресета, движок молчит). Живой осиротевший звук
   перехватывает страховка §5.8 до всякого пуша; при доработках это место стоит
   держать в уме.

## 9. Проверка на устройстве (POCO, `adb`)

Сценарии (`tools/dbg*.sh`, тег `SWAPGAIN` и `BinauralViewModel`):

1. Новый пресет → play → в логе `startDraft`, в уведомлении «Черновик»,
   частоты панели совпадают с графиком.
2. Правка точки при звучащем черновике → кроссфейд, частота панели поехала
   за графиком.
3. Скраб → ось сместилась, звук сменился, `releaseEditorScrub` на выходе вернул
   «сейчас».
4. Выход «Не сохранять» при звучавшем до этого пресете → звук вернулся к нему.
5. Сохранение → пресет в списке активен, звук не прерывался.
6. Поворот при звучащем черновике → в логе `stopOrphanSound:` (если активного
   пресета не было) либо кроссфейд обратно к нему; панель не показывает
   ложное имя, `defaultCurve` в движок не уходит.
7. Переключение с гарнитуры при звучащем черновике → звучит следующий пресет,
   `draftSounding` снят.

Регресс: прослушивание существующего пресета из редактора (ветка 1381) и
переключение пресетов из списка не должны измениться.

## 10. Прогресс реализации (2026-09-06, остановлено по запросу)

### Сделано — `BinauralViewModel.kt` (все правки из §5, кроме UI и строк)

- `BinauralUiState`: добавлены `draftSounding`, `editingSessionActive` (§4).
- `DRAFT_PRESET_ID = "__draft__"` в companion; поле `draftNameSnapshot` +
  `noteDraftName()` + `draftDisplayName()` (имя читается один раз при старте,
  без перекомпозиций).
- `startPending()` — единая точка запуска отложенного (диалог наушников):
  метка черновика → `startDraft()`, иначе `startPreset()`. Подключено в
  `playPresetAnyway` и в коллектор `hasHeadset`.
- `startDraft()` / `endDraftAudition()` / `endDraftAuditionIfNeeded()` (§5.1).
- `startPreset()`: первым делом сбрасывает `draftSounding` (§5.6).
- `togglePlayback()`: `draftSession`-ветка — старт черновика или «продолжить»
  при уже звучащем; метка черновика в `pendingId` для диалога наушников (§5.2,
  §5.3). Семантика паузы не тронута (решение 1).
- `isEditingActivePreset()` → `isEditingSounding()`: черновик — тоже
  «редактируется то, что звучит»; все 5 точек вызова переключены (§5.4).
- `updateAudioConfig()`: ветка `isDraftSounding` для выбора кривой/расслабления,
  флаг в diagnostic-лог (§5.5).
- `createPreset(..., activate: Boolean = false)`: сохранение звучащего черновика
  без разрыва звука, `lastActivePresetId` и `saveActivePresetId` (§5.7).
- Выходы из редактора: `cancelEditing`, `cancelEditingInService`,
  `saveEditingPreset`, `finishEditing` гасят `editingSessionActive`;
  `cancelEditing*` и `finishEditing` возвращают звук с черновика через
  `endDraftAuditionIfNeeded()` (§5.8).
- `startNewPreset()` / `startEditingPreset()`: сессия активна, `draftSounding`
  сброшен.
- `maybeRestoreEditingSession()` + `stopOrphanSound()`: страховка
  «черновика-сироты» после пересоздания ViewModel (§5.8).

### Доделано 2026-09-06 (второй заход)

1. **`Navigation.kt`** (§5.9):
   - `showBottomPanel = uiState.activePreset != null || uiState.draftSounding ||
     uiState.editingSessionActive`;
   - имя панели — `viewModel.soundingPresetName()` (новый публичный метод VM:
     черновик → `draftDisplayName()`, иначе имя `activePreset`).
2. **`PresetEditScreen.kt`** (§5.10):
   - `isEditingSounding = if (presetId == null) uiState.draftSounding else
     uiState.activePreset?.id == presetId` — вместо `isEditingActivePreset`,
     управляет и маркером, и ручкой скраба;
   - `createPreset(..., activate = uiState.draftSounding)` — активация только
     когда черновик реально звучит (иначе поведение сохранения прежнее);
   - `onValueChange` имени → `viewModel.noteDraftName(it)` (только для нового
     пресета), плюс стартовое имя уходит в VM в `LaunchedEffect(presetId)`.
4. **Сирота-черновик переделан** (§5.8): вместо недостижимой ветки в
   `maybeRestoreEditingSession` — метка `KEY_DRAFT_SOUNDING` в
   `SavedStateHandle` + `orphanDraftPending`, разбор в `updateAudioConfig()`.
   `stopOrphanSound()` больше не смотрит на `_telemetry.value.isPlaying` (в
   момент пересоздания он ещё `false`) и возвращает `Boolean` — «гасить ли
   пуш конфига». `gradle :app:compileDebugKotlin` — BUILD SUCCESSFUL.
5. **Строки** (§5.11): `draft_preset` добавлен во ВСЕ 8 локалей (values,
   -de, -es, -hi, -ja, -ru, -zh-rCN, -zh-rTW) — они велись синхронно, по 176
   строк в каждой, так что точечно пятью ограничиваться нельзя.

### Попутно исправлено (мешало сборке, к черновику не относится)

- `DebugTimeControlPanel.kt`: при замене захардкоженных строк на
  `stringResource` потерялся `import com.binauralcycles.R` — 8 ошибок
  «Unresolved reference 'R'».
- `Navigation.kt` (152, 272): `stringResource(...)` вызывался внутри
  `scope.launch`/`withContext` — «Composable invocations can only happen…».
  Строки подняты в композицию (`exportFailedReasonDataLost`,
  `exportFailedReasonPresetNotFound`).

### Что осталось

- Проверка на устройстве (§9) — сценарии 1–7 и регресс существующего
  пресета.

### Замечания для продолжающего

- `endDraftAuditionIfNeeded()` в `cancelEditing`/`finishEditing` вызывается
  ПОСЛЕ сброса состояния сессии, но `endDraftAudition()` сам читает только
  `draftSounding` и `activePreset` — порядок безопасен.
- В `togglePlayback` при `draftSession && draftSounding && !isPlaying`
  делается `updateAudioConfig()` + `resumeWithFade()` — конфиг перепушивается,
  чтобы пауза не «прятала» последние правки.
- `stopOrphanSound()` гасит звук только когда источника нет вовсе
  (`!draftSounding && activePreset == null`), поэтому реальный пресет,
  проигрывающийся при пересоздании VM, задет не будет.
