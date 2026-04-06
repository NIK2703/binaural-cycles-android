# Руководство по рефакторингу архитектуры Binaural Cycles

Этот документ содержит детальное руководство по доведению архитектуры проекта до полного соответствия принципам из `architecture_best_practices.md`.

---

## Текущее состояние архитектуры

Проект уже имеет хорошую архитектурную основу и соответствует многим рекомендациям. Ниже представлен детальный анализ.

---

## ✅ Что УЖЕ соответствует рекомендациям

### 1. Domain Layer (Полное соответствие)
- **Модели**: `core/domain/model/` — `BinauralPreset`, `FrequencyCurve`, `FrequencyPoint`, `PlaybackState`, `BinauralConfig` и др.
- **Repository interfaces**: `core/domain/repository/` — `PresetRepository`, `SettingsRepository`, `PlaybackStateRepository`
- **Use Cases**: `core/domain/usecase/` — 12 use cases для различных операций
- **Service interfaces**: `core/domain/service/` — `PlaybackController`, `AudioEngineInterface`, `FileStorageService`

### 2. Repository Pattern (Полное соответствие)
- Интерфейсы определены в domain слое
- Реализации находятся в data слое: `PresetRepositoryImpl`, `SettingsRepositoryImpl`, `PlaybackStateRepositoryImpl`
- Используется `@Binds` для привязки интерфейсов к реализациям

### 3. Room Database (Полное соответствие)
- Entity: `PresetEntity`, `FrequencyPointEntity`
- DAO: `PresetDao`, `FrequencyPointDao`
- Mapper: `PresetMapper` для конвертации Entity ↔ Domain Model
- Migration: `DataMigrationHelper`

### 4. Data Sources Pattern (Полное соответствие)
- `PresetLocalDataSource` → `PresetRoomDataSource` (Room)
- `PreferencesDataSource` → `PreferencesDataSourceImpl` (DataStore)

### 5. DI с Hilt (Полное соответствие)
- `RepositoryModule` с `@Binds` для интерфейсов
- `DataSourceModule` для data sources
- `PreferencesModule` для предоставления зависимостей
- `DomainModule` для Use Cases
- `ServiceModule` для сервисов

### 6. Type-Safe Navigation (Полное соответствие)
- `sealed interface Destination` с `@Serializable`
- Kotlin Serialization для аргументов навигации
- Использование `toRoute()` для получения аргументов

### 7. UDF — Unidirectional Data Flow (Полное соответствие)
- `StateFlow` для UI state
- `Channel` для one-shot events (`PresetListEvent`, `PresetEditEvent`, `SettingsEvent`)
- `update {}` для атомарных обновлений state

### 8. Service Abstraction (Полное соответствие)
- `PlaybackController` interface в domain
- `PlaybackControllerImpl` в app модуле
- `PlaybackStateRepository` для реактивного наблюдения за состоянием

---

## ⚠️ Что НЕ соответствует рекомендациям

### 1. Нарушение правил зависимости Domain слоя

**Проблема**: `FileStorageService` в domain зависит от Android API (`Uri`).

**Файл**: `core/domain/src/main/java/com/binaural/core/domain/service/FileStorageService.kt`

**Нарушение**: Domain слой не должен зависеть от Android Framework. Метод `fromUri(uri: String)` работает с Android Uri.

---

### 2. ViewModel содержит бизнес-логику вместо Use Cases

**Проблема**: `PresetListViewModel` содержит ~380 строк кода с прямой логикой.

**Файл**: `app/src/main/java/com/binauralcycles/viewmodel/PresetListViewModel.kt`

**Нарушения**:
- Метод `playPreset()` создаёт `BinauralConfig` и управляет playback — это бизнес-логика
- Метод `updateAudioConfig()` дублирует логику построения конфига
- Прямое обращение к `settingsRepository` вместо использования Use Cases
- Наблюдение за множеством Flow из разных источников

---

### 3. BinauralPlaybackService перегружен ответственностью

**Проблема**: Service содержит ~500 строк кода с множеством ответственностей.

**Файл**: `app/src/main/java/com/binauralcycles/service/BinauralPlaybackService.kt`

**Нарушения**:
- Управление notification, audio focus, headset, media session — все в одном классе
- Дублирование методов `PlaybackController` (play, stop, pause, resume)
- Создание `BinauralAudioEngine` внутри Service вместо injection

---

### 4. Отсутствие Media3

**Проблема**: Используется обычный `Service` вместо `MediaSessionService` из Media3.

**Рекомендация из документа**: Миграция на Media3 для медиа-воспроизведения.

---

### 5. Отсутствие @ViewModelScoped

**Проблема**: Use Cases помечены как `@Singleton`, но по рекомендациям должны быть `@ViewModelScoped` для разделения состояния между ViewModels.

---

### 6. Несоответствие naming convention для UiState

**Проблема**: Отсутствует унифицированный sealed interface `UiState<out T>` для loading/error/success состояний.

**Текущее**: `UiState.Success(data)` используется частично.

**Рекомендация**: 
```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
```

---

### 7. Отсутствие интеграционных тестов

**Проблема**: Есть только unit тесты в `core/domain/src/test/`. Отсутствуют:
- Integration tests для Data слоя (Room, DataStore)
- UI тесты с Compose Testing
- Hilt test modules для тестирования

---

# Руководство по доведению архитектуры до соответствия

Руководство разделено на **5 независимых частей**, которые могут выполняться параллельно разными разработчиками.

---

# ЧАСТЬ 1: Рефакторинг Domain слоя и FileStorageService

**Цель**: Устранить зависимость domain от Android Framework.

**Трудозатраты**: Низкие  
**Приоритет**: Высокий

## Действия:

### 1.1. Перенос FileStorageService в app слой
- Переместить интерфейс `FileStorageService` из `core/domain/service/` в пакет `app/src/main/java/com/binauralcycles/service/`
- Или создать абстрактный интерфейс в domain без Android-зависимостей

### 1.2. Создание domain-level интерфейса для экспорта/импорта
- Создать интерфейс `PresetExporter` в domain с методами:
  - `exportToJson(preset: BinauralPreset): String`
  - `importFromJson(json: String): BinauralPreset?`
- Оставить работу с Uri в `FileStorageServiceImpl` в app слое

### 1.3. Обновление Use Cases
- `ExportPresetUseCase` должен использовать новый `PresetExporter` интерфейс
- `ImportPresetUseCase` должен использовать domain-level интерфейс

### 1.4. Обновление DI
- Переместить биндинг `FileStorageService` из `DomainModule` в `ServiceModule`
- Добавить биндинг нового `PresetExporter` интерфейса

### 1.5. Обновление тестов
- Переместить `FakeFileStorageService` из `core/domain/test/` в `app/src/test/`
- Создать `FakePresetExporter` для domain тестов

---

# ЧАСТЬ 2: Рефакторинг ViewModel и Use Cases

**Цель**: Вынести бизнес-логику из ViewModel в Use Cases.

**Трудозатраты**: Средние  
**Приоритет**: Высокий

## Действия:

### 2.1. Создание PlayPresetConfigUseCase
- Создать Use Case, который принимает `presetId` и возвращает сконфигурированный `BinauralConfig`
- Use Case должен получать настройки из `SettingsRepository`
- Use Case должен возвращать готовый конфиг для воспроизведения

### 2.2. Создание ObservePlaybackStateUseCase
- Создать Use Case для объединения всех playback-related Flow в один `PlaybackUiState`
- Убрать множественные `collect` из ViewModel

### 2.3. Рефакторинг PresetListViewModel
- Удалить метод `updateAudioConfig()` — логика переносится в Use Case
- Метод `playPreset()` делегирует работу Use Case
- Использовать `ObservePlaybackStateUseCase` вместо прямого наблюдения за Flow

### 2.4. Добавление @ViewModelScoped
- Определить `@ViewModelScoped` annotation
- Создать `ViewModelComponent` для Hilt
- Использовать `@ViewModelScoped` для Use Cases, которые должны разделять состояние

### 2.5. Унификация UiState
- Создать sealed interface `UiState<out T>` с Loading/Success/Error
- Применить ко всем ViewModel
- Добавить helper extension для обработки состояний

---

# ЧАСТЬ 3: Рефакторинг BinauralPlaybackService

**Цель**: Разделить ответственности и упростить Service.

**Трудозатраты**: Высокие  
**Приоритет**: Средний

## Действия:

### 3.1. Создание ServiceCoordinator
- Вынести логику координации компонентов в отдельный класс
- Coordinator управляет: AudioFocus, Headset, MediaSession, Notification
- Service делегирует вызовы Coordinator

### 3.2. Инъекция BinauralAudioEngine
- Создать `AudioEngineFactory` для создания `BinauralAudioEngine`
- Инжектировать factory в Service вместо прямого создания
- Это упростит тестирование

### 3.3. Удаление дублирующих методов
- Методы `play()`, `stop()`, `pauseWithFade()`, `resumeWithFade()` вызывают `PlaybackController`
- Убрать дублирование — Service должен только управлять lifecycle
- Воспроизведением управляет `PlaybackControllerImpl`

### 3.4. Упрощение Service до lifecycle управления
- Service отвечает только за: startForeground, stopForeground, notification updates
- Все callback и observer переместить в Coordinator
- Service становится тонкой обёрткой

---

# ЧАСТЬ 4: Миграция на Media3 (Опционально)

**Цель**: Использовать современный Media3 library.

**Трудозатраты**: Высокие  
**Приоритет**: Низкий

## Действия:

### 4.1. Добавление зависимостей Media3
- Добавить `androidx.media3:media3-exoplayer`
- Добавить `androidx.media3:media3-session`

### 4.2. Создание PlaybackService на базе MediaSessionService
- Наследовать от `MediaSessionService` вместо `Service`
- Создать `MediaSession` с `Player.Listener`
- Использовать `MediaController` для управления из UI

### 4.3. Интеграция с system media controls
- Media3 автоматически обрабатывает Bluetooth, гарнитуру, media buttons
- Удалить custom `HeadsetManager` и `MediaSessionManager`
- Использовать встроенные возможности Media3

### 4.4. Обновление notification
- Использовать `MediaNotification.Builder` из Media3
- Упростить `NotificationHelper`

---

# ЧАСТЬ 5: Добавление тестов

**Цель**: Обеспечить покрытие тестами всех слоёв.

**Трудозатраты**: Средние  
**Приоритет**: Параллельно с другими частями

## Действия:

### 5.1. Integration Tests для Data слоя
- Создать `androidTest` для `PresetRepositoryImpl` с реальной Room
- Создать тесты для `SettingsRepositoryImpl` с реальным DataStore
- Использовать `@RunWith(AndroidJUnit4::class)`

### 5.2. Hilt Test Modules
- Создать `TestRepositoryModule` с fake реализациями
- Использовать `@UninstallModules` для замены production модулей
- Добавить `HiltAndroidRule` в тесты

### 5.3. UI Tests с Compose Testing
- Создать `androidTest` для экранов с `ComposeTestRule`
- Тестировать state rendering и user interactions
- Использовать `hiltAndroidRule` для injection

### 5.4. ViewModel Tests с Turbine
- Добавить library `app.cash.turbine:turbine` для тестирования Flow
- Тестировать state transitions и events
- Использовать `TestDispatcher` для корутин

---

## Приоритет внедрения

| Приоритет | Часть | Трудозатраты | Влияние |
|-----------|-------|--------------|---------|
| Высокий | Часть 2 | Средняя | Улучшение тестируемости ViewModel |
| Высокий | Часть 1 | Низкая | Clean Architecture compliance |
| Средний | Часть 3 | Высокая | Упрощение Service |
| Низкий | Часть 4 | Высокая | Современный media stack |
| Параллельно | Часть 5 | Средняя | Покрытие тестами |

---

## Рекомендуемый порядок выполнения

1. **Спринт 1**: Часть 1 + Часть 5.1 (Domain refactoring + Data tests)
2. **Спринт 2**: Часть 2 (ViewModel refactoring)
3. **Спринт 3**: Часть 3 (Service refactoring) + Часть 5.3 (UI tests)
4. **Спринт 4**: Часть 4 (Media3 migration) — опционально

---

## Параллельное выполнение

Следующие части могут выполняться параллельно разными разработчиками:

| Разработчик A | Разработчик B | Разработчик C |
|---------------|---------------|---------------|
| Часть 1 | Часть 5.1 | — |
| Часть 2 | Часть 5.3-5.4 | — |
| Часть 3 | Часть 5.2 | Часть 4 (опционально) |

---

## Критерии приёмки

### Часть 1
- [ ] `FileStorageService` не содержит Android-зависимостей в domain
- [ ] Все тесты domain слоя проходят без Robolectric
- [ ] DI модули корректно настроены

### Часть 2
- [ ] ViewModel не содержит бизнес-логики
- [ ] Все бизнес-операции выполняются через Use Cases
- [ ] UiState унифицирован для всех экранов

### Часть 3
- [ ] Service содержит только lifecycle управление
- [ ] Coordinator отвечает за координацию компонентов
- [ ] AudioEngine инжектируется через factory

### Часть 4
- [ ] Service наследуется от MediaSessionService
- [ ] System media controls работают корректно
- [ ] Notification упрощён

### Часть 5
- [ ] Data layer покрыт integration тестами
- [ ] UI покрыт Compose тестами
- [ ] ViewModel покрыт unit тестами с Turbine