# Рекомендации по архитектуре Android-приложения

Этот документ содержит лучшие и самые современные подходы к архитектуре Android-приложений, которые рекомендуется внедрить в проект Binaural Cycles.

---

## 1. Модульная архитектура

### Текущее состояние

Проект уже имеет модульную структуру:
- `app` — основной модуль с UI и навигацией
- `core/domain` — бизнес-логика, модели, use cases, интерфейсы
- `core/audio` — аудио-движок (Kotlin + NDK)
- `core/ui` — общие UI-компоненты и тема
- `data/preferences` — хранение настроек

### Рекомендации

#### Выделение Domain-слоя

Рекомендуется создать отдельный модуль `domain` для бизнес-логики:

**Структура:**
```
domain/
├── model/           # Domain entities (BinauralPreset, FrequencyCurve, etc.)
├── repository/      # Repository interfaces
├── usecase/         # Use cases / Interactors
└── di/              # Domain-уровень DI (optional)
```

**Преимущества:**
- Полная изоляция бизнес-логики от Android Framework
- Возможность переиспользования в других контекстах (Kotlin Multiplatform)
- Упрощение unit-тестирования (не нужен Robolectric/Android Test)
- Чёткие границы ответственности

#### Feature Modules vs Layer Modules

**Текущий подход:** Layer modules (core, data по слоям)

**Альтернатива:** Feature modules для крупномасштабных приложений:
```
features/
├── preset/
│   ├── api/         # Публичный API фичи
│   ├── impl/        # Реализация (UI, ViewModel, UseCases)
│   └── test/        # Тесты фичи
├── playback/
└── settings/
```

**Рекомендация:** Для текущего размера проекта достаточно layer modules с последующим переходом на feature modules при масштабировании.

---

## 2. Clean Architecture

### Принципы

Clean Architecture разделяет приложение на три слоя с严格的 правилами зависимости:

1. **Presentation Layer** — UI, ViewModels, Navigation
2. **Domain Layer** — Use Cases, Business Logic, Entities
3. **Data Layer** — Repositories, Data Sources, API, Database

**Dependency Rule:** Внутренние слои не зависят от внешних. Domain не знает о Data и Presentation.

### Domain Layer

#### Entities

Бизнес-объекты, не зависящие от фреймворка:

**Текущее состояние:** Модели находятся в `core/audio/model/` и содержат бизнес-логику (методы `getCarrierFrequencyAt()`, `getBeatFrequencyAt()`).

**Рекомендация:** 
- Вынести модели в `domain/model/`
- Модели должны быть Kotlin data classes без Android-зависимостей
- Бизнес-логика моделей (расчёт частот) допустима внутри entities

#### Repository Interfaces

Интерфейсы определяются в Domain, реализуются в Data:

```
// domain/repository/PresetRepository.kt
interface PresetRepository {
    fun getPresets(): Flow<List<BinauralPreset>>
    suspend fun savePreset(preset: BinauralPreset)
    suspend fun deletePreset(id: String)
    fun getActivePreset(): Flow<BinauralPreset?>
}
```

#### Use Cases

Инкапсуляция бизнес-операций:

```
// domain/usecase/PlayPresetUseCase.kt
class PlayPresetUseCase(
    private val presetRepository: PresetRepository,
    private val audioEngine: AudioEngine
) {
    suspend operator fun invoke(presetId: String) { ... }
}
```

**Преимущества Use Cases:**
- Single Responsibility Principle
- Переиспользование логики между разными ViewModels
- Упрощение тестирования
- Документирование бизнес-операций через命名

### Data Layer

#### Repository Implementation

**Текущее состояние:** `BinauralPreferencesRepository` совмещает интерфейс и реализацию, напрямую работает с DataStore.

**Рекомендация:**
- Создать интерфейс `PresetRepository` в domain
- `BinauralPreferencesRepository` переименовать в `PresetRepositoryImpl`
- Добавить mapping между DTO (SerializablePreset) и Domain entities

#### Data Sources Pattern

Разделение источников данных:

```
data/
├── repository/
│   └── PresetRepositoryImpl.kt
├── datasource/
│   ├── local/
│   │   ├── PreferencesDataSource.kt    # DataStore
│   │   └── DatabaseDataSource.kt       # Room (future)
│   └── remote/
│       └── CloudSyncDataSource.kt      # Future backup/sync
└── mapper/
    └── PresetMapper.kt
```

---

## 3. Dependency Injection

### Текущее состояние

Используется Hilt с модулями:
- `AudioModule` в core/audio
- `PreferencesModule` в data/preferences

### Рекомендации

#### Module Organization

Организация модулей по слоям:

```
di/
├── AppModule.kt           # Application-wide dependencies
├── DomainModule.kt        # UseCases
├── DataModule.kt          # Repository implementations
└── PresentationModule.kt  # ViewModels
```

#### Scoping Strategy

- **@Singleton** — Repository, DataStore, AudioEngine
- **@ActivityScoped** — ViewModel (через Hilt NavGraph)
- **@ViewModelScoped** — UseCases, shared state

#### Interface Binding

Привязка реализаций к интерфейсам:

```
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindPresetRepository(
        impl: PresetRepositoryImpl
    ): PresetRepository
}
```

---

## 4. Unidirectional Data Flow (UDF)

### Принцип

UI — это функция от State. State изменяется только через Intent/Action. Один источник истины — ViewModel.

### Текущее состояние

`BinauralViewModel` использует `MutableStateFlow<UiState>` для state management.

### Рекомендации

#### State Modeling

Чёткое моделирование UI State:

```
data class PresetListUiState(
    val presets: List<PresetItemState> = emptyList(),
    val activePresetId: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class PresetItemState(
    val id: String,
    val name: String,
    val currentCarrierFrequency: Float,
    val currentBeatFrequency: Float,
    val isActive: Boolean
)
```

#### Events vs State

**Проблема:** Одноразовые события (show Toast, navigate) не должны быть частью State.

**Решение:** Channel/SharedFlow для событий:

```
sealed interface PresetListEvent {
    data class ShowError(val message: String) : PresetListEvent
    data class NavigateToEdit(val presetId: String) : PresetListEvent
    object PresetDeleted : PresetListEvent
}

class BinauralViewModel : ViewModel() {
    private val _events = Channel<PresetListEvent>()
    val events: ReceiveChannel<PresetListEvent> = _events
}
```

#### State Hoisting в Compose

Подъём состояния к родительским компонентам для переиспользования и тестирования:

**Плохо:** Состояние внутри переиспользуемого компонента
**Хорошо:** Состояние в caller, компонент — stateless

---

## 5. Repository Pattern

### Текущее состояние

`BinauralPreferencesRepository` реализует Repository pattern, но:
- Совмещает интерфейс и реализацию
- Хранит сериализованные пресеты как JSON-строку в DataStore
- Нет разделения на data sources

### Рекомендации

#### Repository Interface

```
interface PresetRepository {
    // Flow для реактивного наблюдения
    fun observePresets(): Flow<List<BinauralPreset>>
    fun observeActivePreset(): Flow<BinauralPreset?>
    
    // One-shot operations
    suspend fun getPreset(id: String): BinauralPreset?
    suspend fun savePreset(preset: BinauralPreset)
    suspend fun deletePreset(id: String)
    suspend fun setActivePreset(id: String?)
}
```

#### Offline-First Approach

Архитектура, ориентированная на работу без сети:

1. **Single Source of Truth:** Локальная БД — главный источник данных
2. **Sync Strategy:** Фоновая синхронизация при появлении сети
3. **Conflict Resolution:** Стратегия разрешения конфликтов

#### Data Source Abstraction

```
interface PresetLocalDataSource {
    fun observePresets(): Flow<List<PresetEntity>>
    suspend fun savePreset(entity: PresetEntity)
    suspend fun deletePreset(id: String)
}
```

---

## 6. Service Architecture

### Текущее состояние

`BinauralPlaybackService` — Foreground Service для воспроизведения аудио в фоне. Связь с ViewModel через:
- Статические StateFlow в companion object
- `serviceInstance` ссылка
- Binder для прямого вызова методов

### Проблемы

1. **Tight Coupling:** ViewModel напрямую зависит от Service
2. **Static State:** StateFlows в companion object нарушают принципы DI
3. **Testing:** Сложно тестировать из-за статических зависимостей

### Рекомендации

#### Service Abstraction

```
// domain/service/PlaybackController.kt
interface PlaybackController {
    val playbackState: StateFlow<PlaybackState>
    val isPlaying: StateFlow<Boolean>
    val connectionState: StateFlow<Boolean>
    
    fun play()
    fun stop()
    fun stopWithFade()
    fun pauseWithFade()
    fun resumeWithFade()
    
    fun updateConfig(config: BinauralConfig, relaxationSettings: RelaxationModeSettings)
    fun setVolume(volume: Float)
    fun setCurrentPresetName(name: String?)
    fun setCurrentPresetId(id: String?)
    
    fun connect()
    fun disconnect()
    fun setOnPresetSwitchCallback(callback: (String) -> Unit)
    fun onAppForeground()
    fun onAppBackground()
}
```

**Примечание:** PlaybackController уже перемещён из `domain/repository/` в `domain/service/` для правильной категоризации.

#### Repository for Playback State

```
interface PlaybackStateRepository {
    val playbackState: StateFlow<PlaybackState>
    suspend fun updatePlaybackState(state: PlaybackState)
}
```

#### Media3 Library

Рекомендуется миграция на Media3 для медиа-воспроизведения:
- `MediaSessionService` вместо `Service`
- `MediaController` для управления из UI
- Автоматическая интеграция с system media controls

---

## 7. Navigation Architecture

### Текущее состояние

Навигация реализована через Compose Navigation в `Navigation.kt`.

### Рекомендации

#### Type-Safe Navigation

Использование type-safe аргументов:

```
// Определение destination
sealed class Destination {
    @Serializable data object PresetList : Destination()
    @Serializable data class PresetEdit(val presetId: String) : Destination()
    @Serializable data object Settings : Destination()
}

// Навигация
navController.navigate(Destination.PresetEdit(presetId = "123"))
```

#### Navigation State Management

Навигация как часть UI State:
- ViewModel определяет, куда навигировать
- UI выполняет навигацию через callback
- Deep links обрабатываются централизованно

---

## 8. State Management

### StateFlow vs SharedFlow

| StateFlow | SharedFlow |
|-----------|------------|
| Имеет начальное значение | Без начального значения |
| Replay = 1 | Configurable replay |
| Для состояния UI | Для событий |
| Всегда есть значение | Может быть "пустым" |

**Рекомендация:**
- `StateFlow` для UI state
- `SharedFlow` с `replay = 0` для событий
- `Channel` для one-shot событий

### UI State Modeling

#### Loading States

```
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
```

#### Partial State Updates

Для сложных экранов — partial state updates:

```
sealed interface PresetEditIntent {
    data class UpdateName(val name: String) : PresetEditIntent
    data class UpdateCarrierFrequency(val frequency: Float) : PresetEditIntent
    data object SavePreset : PresetEditIntent
}

fun reducer(state: PresetEditState, intent: PresetEditIntent): PresetEditState {
    return when (intent) {
        is PresetEditIntent.UpdateName -> state.copy(name = intent.name)
        is PresetEditIntent.UpdateCarrierFrequency -> state.copy(carrierFrequency = intent.frequency)
        is PresetEditIntent.SavePreset -> state.copy(isSaving = true)
    }
}
```

---

## 9. Миграция на Room Database

### Почему Room вместо DataStore для пресетов

**Текущее:** Пресеты хранятся как JSON-строка в DataStore.

**Проблемы:**
- Весь список загружается в память
- Нет возможности query по отдельным пресетам
- Нет миграций при изменении схемы

**Room преимущества:**
- Type-safe queries
- Migration support
- Reactive queries (Flow)
- Partial updates

### Рекомендуемая структура

```
@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "frequency_points", 
        foreignKeys = [ForeignKey(entity = PresetEntity::class, parentColumns = ["id"], childColumns = ["presetId"])])
data class FrequencyPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val presetId: String,
    val hour: Int,
    val minute: Int,
    val carrierFrequency: Float,
    val beatFrequency: Float
)
```

---

## 10. Тестирование архитектуры

### Unit Tests

**Domain Layer:** Чистый Kotlin, не требует Android:
- UseCase tests
- Repository interface tests (mock implementations)
- Entity logic tests

### Integration Tests

**Data Layer:** Android Instrumentation tests:
- Repository implementation tests
- DataStore/Room tests

### UI Tests

**Presentation Layer:** Compose Testing:
- ViewModel tests with Turbine
- UI tests with Compose TestRule

### Test Doubles

```
// Test implementation
class FakePresetRepository : PresetRepository {
    private val presets = MutableStateFlow<List<BinauralPreset>>(emptyList())
    
    override fun observePresets() = presets.asStateFlow()
    
    // ... реализация для тестов
}

// Hilt test module
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RepositoryModule::class])
abstract class TestRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindPresetRepository(
        fake: FakePresetRepository
    ): PresetRepository
}
```

---

## Итоговая рекомендуемая структура

```
app/
├── di/                          # App-level DI
├── navigation/                  # Navigation graph
├── ui/                          # UI screens
│   ├── presetlist/
│   │   ├── PresetListScreen.kt
│   │   ├── PresetListViewModel.kt
│   │   └── PresetListState.kt
│   ├── presetedit/
│   └── settings/
└── service/                     # Android Services

domain/
├── model/                       # Business entities
├── repository/                  # Repository interfaces
├── usecase/                     # Use cases
└── service/                     # Service interfaces

data/
├── repository/                  # Repository implementations
├── datasource/
│   ├── local/
│   │   ├── DataStoreDataSource.kt
│   │   └── RoomDataSource.kt
│   └── remote/                  # Future: cloud sync
├── mapper/                      # Entity ↔ Model mapping
└── di/                          # Data DI modules

core/
├── audio/                       # Audio engine (NDK)
│   ├── engine/
│   ├── model/
│   └── di/
└── ui/                          # Shared UI components
    ├── theme/
    └── components/
```

---

## Приоритет внедрения

1. **Высокий приоритет:**
   - Выделение domain-слоя с моделями и интерфейсами
   - Repository pattern с интерфейсами
   - Use cases для ключевых операций

2. **Средний приоритет:**
   - Миграция на Room Database
   - Service abstraction
   - Type-safe navigation

3. **Низкий приоритет (при масштабировании):**
   - Feature modules
   - Offline-first с sync
