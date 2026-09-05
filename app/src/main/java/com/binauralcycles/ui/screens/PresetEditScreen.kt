package com.binauralcycles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.RelaxationModeSettings
import com.binauralcycles.ui.components.*
import com.binauralcycles.ui.theme.Spacing
import com.binauralcycles.viewmodel.BinauralViewModel
import com.binauralcycles.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PresetEditScreen(
    viewModel: BinauralViewModel,
    presetId: String?,  // null для нового пресета
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    // Телеметрия отдельным потоком: частоты/время тикают каждую секунду и
    // не должны перекомпоновывать весь экран редактирования.
    val telemetry by viewModel.telemetry.collectAsState()
    val newPresetName = stringResource(R.string.new_preset)
    
    // Находим пресет для редактирования
    val editingPreset = remember(presetId, uiState.presets) {
        if (presetId == null) null
        else uiState.presets.find { it.id == presetId }
    }
    
    // Локальное состояние для редактирования
    var presetName by remember(editingPreset) { 
        mutableStateOf(editingPreset?.name ?: newPresetName) 
    }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    // Справка по жестам — по кнопке рядом с сохранением. Состояние здесь,
    // а не внутри графика: справка описывает весь редактор (график, окно
    // точки, метки границ), а не один его элемент.
    var showGesturesHelp by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    
    // Флаг для предотвращения повторных навигаций (локальный debounce)
    var isNavigating by remember { mutableStateOf(false) }
    
    // Инициализируем редактируемый пресет в ViewModel
    LaunchedEffect(presetId) {
        if (presetId != null) {
            viewModel.startEditingPreset(presetId)
        } else {
            viewModel.startNewPreset()
        }
    }

    // Автопоказ справки по управлению при первом входе в редактор.
    // Ключ эффекта — сам флаг: пока он null (ещё не прочитан из DataStore) или
    // уже true, окно не трогаем; как только прочиталось значение false —
    // открываем ровно один раз. После закрытия флаг становится true, эффект
    // перезапускается, но повторно не открывает.
    LaunchedEffect(uiState.gesturesHelpShown) {
        if (uiState.gesturesHelpShown == false) {
            showGesturesHelp = true
        }
    }
    
    // Проверяем наличие изменений
    hasChanges = editingPreset?.let { preset ->
        preset.name != presetName || 
        uiState.editingFrequencyCurve != preset.frequencyCurve ||
        uiState.editingRelaxationModeSettings != preset.relaxationModeSettings
    } ?: run {
        // Новый пресет: сравниваем с исходным шаблоном, а не с «не-null».
        // Если пользователь ничего не поменял в шаблоне, изменений нет —
        // диалог «несохранённые изменения» при выходе не показываем.
        val baseline = uiState.newPresetBaselineCurve
        if (baseline == null) {
            // Шаблон ещё не инициализирован (первый кадр до startNewPreset):
            // трактуем как «без изменений», чтобы диалог не мелькал ложно.
            false
        } else {
            presetName != newPresetName ||
            uiState.editingFrequencyCurve != baseline ||
            uiState.editingRelaxationModeSettings != RelaxationModeSettings()
        }
    }
    
    fun saveAndNavigateBack() {
        // Предотвращаем повторный вызов во время навигации
        if (isNavigating) return
        isNavigating = true

        val curve = uiState.editingFrequencyCurve ?: return
        // СНАЧАЛА запускаем анимацию выхода. Сохранение в сервис/БД и обновление
        // состояния перенесены ПОСЛЕ навигации, чтобы главный поток был свободен
        // в первые кадры перехода — иначе shared-анимация «съедается» этой работой.
        onNavigateBack()
        // Порядок: сначала анимация, потом вся работа выхода, и ПОСЛЕДНИМ —
        // возврат оси прослушивания. releaseEditorScrub обязана идти в конце:
        // хэндофф от сохранения уже унёс обнулённый сдвиг (один кроссфейд
        // вместо двух), а вызов добирает только случай, когда работа выхода
        // не породила хэндоффа вовсе.
        if (presetId == null) {
            // Создаём новый пресет
            viewModel.createPreset(
                name = presetName,
                curve = curve,
                relaxationModeSettings = uiState.editingRelaxationModeSettings
            )
        } else {
            // Обновляем существующий
            viewModel.saveEditingPreset(
                presetId = presetId,
                name = presetName,
                curve = curve,
                relaxationModeSettings = uiState.editingRelaxationModeSettings
            )
        }
        // editingFrequencyCurve намеренно НЕ очищаем: это состояние перезапишется
        // при следующем входе в редактор, а сейчас оно не мешает списку.
        viewModel.releaseEditorScrub()
    }

    fun navigateBackWithCheck() {
        // Предотвращаем повторный вызов во время навигации
        if (isNavigating) return
        isNavigating = true

        if (hasChanges) {
            showUnsavedDialog = true
            isNavigating = false  // Сбрасываем если показываем диалог
        } else {
            // СНАЧАЛА анимация, потом восстановление кривой активного пресета
            // в сервисе — чтобы работа на главном потоке не откладывала старт
            // shared-перехода. Возврат оси — последним (см. saveAndNavigateBack).
            onNavigateBack()
            viewModel.cancelEditingInService()
            viewModel.releaseEditorScrub()
        }
    }
    
    // Очистка фокуса при скрытии клавиатуры
    val focusManager = LocalFocusManager.current
    
    // Обработка системной кнопки "назад".
    //
    // Порядок важен: сначала закрывается то, что лежит ПОВЕРХ экрана, и
    // только потом сам экран. Открытое контекстное окно точки — первый
    // кандидат: «назад» закрывает его и оставляет пользователя в редакторе,
    // а не выкидывает на список пресетов (и тем более не показывает диалог
    // несохранённых изменений — окно точки ничего не сохраняет и не отменяет).
    // Снятие выделения идёт тем же путём, что касание мимо окна и повторное
    // нажатие на точку: набранное в полях применится по уходу окна.
    BackHandler(enabled = true) {
        if (uiState.selectedPointIndex != null) {
            focusManager.clearFocus()
            viewModel.deselectPoint()
        } else {
            focusManager.clearFocus()
            navigateBackWithCheck()
        }
    }

    // СКРАБ: страховка на случай ухода экрана, который не прошёл ни через один
    // штатный выход (cancelEditingInService / saveEditingPreset). Штатные пути
    // сами вызывают releaseEditorScrub — здесь только добор «непредусмотренного».
    //
    // ПОЧЕМУ НЕЛЬЗЯ СБРАСЫВАТЬ БЕЗ ПРОВЕРКИ (так было раньше). onDispose здесь
    // срабатывает НЕ только при закрытии редактора, а ещё и когда:
    //   • поворот / смена темы / локали / размера шрифта — Activity
    //     пересоздаётся, но редактор остаётся на экране, а сессия
    //     восстанавливается из SavedStateHandle (maybeRestoreEditingSession);
    //   • приложение свёрнуто и система уничтожила Activity — при возврате она
    //     будет воссоздана вместе с редактором;
    //   • пользователь ушёл в «режим разделённого экрана» и т.п.
    // Во всех этих случаях сдвинутая ось — осознанный выбор, который
    // пользователь продолжает слушать, и стирать её нельзя. Поэтому сбрасываем
    // только когда Activity действительно ЗАВЕРШАЕТСЯ (isFinishing) и не
    // пересоздаётся ради новой конфигурации (isChangingConfigurations).
    val activity = LocalContext.current.findActivity()
    val activityRef by rememberUpdatedState(activity)
    DisposableEffect(Unit) {
        onDispose {
            val a = activityRef
            val finishing = a != null && a.isFinishing && !a.isChangingConfigurations
            android.util.Log.d(
                "ScrubLifecycle",
                "editor disposed: isFinishing=${a?.isFinishing}, " +
                    "isChangingConfigurations=${a?.isChangingConfigurations} -> release=$finishing"
            )
            if (finishing) viewModel.releaseEditorScrub()
        }
    }

    with(sharedTransitionScope) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(if (presetId == null) stringResource(R.string.new_preset) else stringResource(R.string.edit_preset)) 
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            focusManager.clearFocus()
                            navigateBackWithCheck() 
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        // Справка по жестам — СЛЕВА от сохранения: сохранить
                        // остаётся крайней правой, то есть «дальней» от центра
                        // и ближайшей к большому пальцу, а подсказка не
                        // отодвигает её от привычного места.
                        IconButton(onClick = { showGesturesHelp = true }) {
                            // AutoMirrored-версия: в RTL иконка разворачивается,
                            // а заодно это не-deprecated вариант (обычный
                            // Icons.Default.Help помечен устаревшим).
                            Icon(
                                Icons.AutoMirrored.Filled.Help,
                                contentDescription = stringResource(R.string.gestures_help)
                            )
                        }
                        IconButton(
                            onClick = { saveAndNavigateBack() },
                            enabled = presetName.isNotBlank()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = "preset-$presetId"
                        ),
                        animatedVisibilityScope = animatedVisibilityScope,
                        // Симметричный клип с карточкой: на списке
                        // clipInOverlayDuringTransition = OverlayClip(shapes.large),
                        // здесь дефолтный ParentClip давал перескок картинки при
                        // передаче между концами перехода.
                        clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.large)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                            // Касание вне контекстного окна точки закрывает его
                            if (uiState.selectedPointIndex != null) {
                                viewModel.deselectPoint()
                            }
                        })
                    }
                    // То же самое, но не только по «чистому» тапу: свайп и
                    // прокрутка за пределами графика тоже закрывают окно.
                    // Смотрим DOWN в проходе Final — если касание пришлось на
                    // кнопку, слайдер, текстовое поле или само окно, они
                    // успели съесть событие, и мы его не трогаем. Сами не
                    // съедаем ничего, поэтому прокрутка и жесты работают.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val down = event.changes.firstOrNull { it.changedToDown() }
                                    ?: continue
                                if (!down.isConsumed && uiState.selectedPointIndex != null) {
                                    viewModel.deselectPoint()
                                }
                            }
                        }
                    }
                    .padding(horizontal = Spacing.lg)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                // Название пресета
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.preset_name)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    singleLine = true
                )
                
                // График частот (используем редактируемую кривую)
                val editingCurve = uiState.editingFrequencyCurve
                // Показываем указатель текущей частоты только если редактируется активный пресет
                val isEditingActivePreset = presetId != null && uiState.activePreset?.id == presetId

                if (editingCurve != null) {
                    FrequencyGraph(
                        points = editingCurve.points,
                        selectedPointIndex = uiState.selectedPointIndex,
                        currentCarrierFrequency = telemetry.currentCarrierFrequency,
                        currentBeatFrequency = telemetry.currentBeatFrequency,
                        carrierRange = editingCurve.carrierRange,
                        beatRange = editingCurve.beatRange,
                        interpolationType = editingCurve.interpolationType,
                        splineTension = editingCurve.splineTension,
                        // Показываем указатель только если редактируется активный пресет
                        isPlaying = isEditingActivePreset && telemetry.isPlaying,
                        relaxationModeSettings = uiState.editingRelaxationModeSettings,
                        // НОВОЕ: единое время (реальное/виртуальное) для указателя на графике
                        externalCurrentTime = telemetry.currentTime,
                        // СКРАБ: ручка прослушивания другого времени суток.
                        // Появляется только для АКТИВНОГО пресета — это уже
                        // заложено в [isPlaying] выше: сдвинутая ось —
                        // осознанная ложь о времени ради прослушивания правки,
                        // и для чужого пресета она бессмысленна.
                        // СКРАБ: реальное «сейчас» для серой линии — парой к
                        // оси, из того же источника (§14.7 плана). График по
                        // нему же решает, показывать ли кнопку сброса (§14.10).
                        realTimeOfDay = telemetry.realTime,
                        onScrubTo = { time -> viewModel.scrubTo(time) },
                        onScrubReset = { viewModel.scrubReset() },
                        // Параметры точки редактируются во всплывающем окне прямо
                        // на графике, отдельного раздела в списке опций больше нет.
                        autoExpandGraphRange = uiState.autoExpandGraphRange,
                        onPointSelected = { index ->
                            // Повторное нажатие на уже выбранную точку закрывает окно
                            if (uiState.selectedPointIndex == index) {
                                viewModel.deselectPoint()
                            } else {
                                viewModel.selectPoint(index)
                            }
                        },
                        onPointTimeChanged = { index, newTime ->
                            viewModel.updateEditingPointTimeDirect(index, newTime)
                        },
                        onPointCarrierChanged = { index, newCarrier ->
                            viewModel.updateEditingPointCarrierFrequencyDirect(index, newCarrier)
                        },
                        onPointBeatChanged = { index, newBeat ->
                            viewModel.updateEditingPointBeatFrequencyDirect(index, newBeat)
                        },
                        onAddPoint = { time, carrier, beat ->
                            viewModel.addEditingPoint(time, carrier, beat)
                        },
                        onRemovePoint = { index -> viewModel.removeEditingPoint(index) },
                        onDismissPopup = { viewModel.deselectPoint() },
                        onCarrierRangeChange = { min, max -> 
                            viewModel.updateEditingCarrierRange(min, max) 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 300.dp)
                    )
                }
                
                // Настройки интерполяции пресета
                PresetSettingsCard(
                    interpolationType = editingCurve?.interpolationType ?: com.binaural.core.audio.model.InterpolationType.LINEAR,
                    onInterpolationTypeChange = { viewModel.setInterpolationType(it) }
                )
                
                // Режим расслабления
                RelaxationModeCard(
                    relaxationModeSettings = uiState.editingRelaxationModeSettings,
                    onRelaxationModeEnabledChange = { viewModel.setEditingRelaxationModeEnabled(it) },
                    onCarrierReductionChange = { viewModel.setEditingCarrierReductionPercent(it) },
                    onBeatReductionChange = { viewModel.setEditingBeatReductionPercent(it) },
                    onRelaxationGapChange = { viewModel.setEditingRelaxationGapMinutes(it) },
                    onTransitionPeriodChange = { viewModel.setEditingTransitionPeriodMinutes(it) },
                    onRelaxationDurationChange = { viewModel.setEditingRelaxationDurationMinutes(it) }
                )
                
            }
        }
    }
    
    // Справка по жестам редактора. Диапазон несущей передаётся текущий:
    // пункт про метки границ подставляет их живые значения («600 Гц и
    // 100 Гц»), а не абстрактные «граничные метки».
    if (showGesturesHelp) {
        GesturesHelpDialog(
            carrierRange = uiState.editingFrequencyCurve?.carrierRange
                ?: FrequencyRange.DEFAULT_CARRIER,
            onDismiss = {
                showGesturesHelp = false
                // Закрытие любым способом (Понятно / назад / мимо) фиксирует
                // флаг: окно больше не откроется автоматически при входе.
                viewModel.markGesturesHelpShown()
            }
        )
    }

    // Диалог несохранённых изменений
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        saveAndNavigateBack()
                    },
                    enabled = !isNavigating
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Предотвращаем повторный вызов
                        if (isNavigating) return@TextButton
                        isNavigating = true

                        showUnsavedDialog = false
                        // СНАЧАЛА анимация, потом восстановление кривой в сервисе
                        onNavigateBack()
                        viewModel.cancelEditingInService()
                        viewModel.releaseEditorScrub()
                    },
                    enabled = !isNavigating
                ) {
                    Text(stringResource(R.string.do_not_save))
                }
            }
        )
    }
}

/**
 * Ближайшая Activity по цепочке контекстов.
 *
 * Нужна ровно для одного решения: экран ушёл из композиции потому, что
 * закрылся редактор, или потому, что пересоздаётся Activity (поворот) либо
 * уничтожается системой в фоне. По самому факту ухода экрана эти случаи не
 * различить — только по состоянию Activity.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}