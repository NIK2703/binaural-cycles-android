package com.binauralcycles.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.binauralcycles.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.binauralcycles.ui.components.BatteryOptimizationPromptDialog
import com.binauralcycles.ui.components.BottomPlaybackPanel
import com.binauralcycles.ui.components.HeadphoneRequiredDialog
import com.binauralcycles.ui.screens.PresetEditScreen
import com.binauralcycles.ui.screens.PresetListScreen
import com.binauralcycles.ui.screens.SettingsScreen
import com.binauralcycles.viewmodel.BinauralViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen(val route: String) {
    object PresetList : Screen("presets")
    object PresetEdit : Screen("preset/{presetId}") {
        fun createRoute(presetId: String) = "preset/$presetId"
    }
    object PresetNew : Screen("preset/new")
    object Settings : Screen("settings")
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BinauralNavigation(
    navController: NavHostController,
    viewModel: BinauralViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // Телеметрия намеренно отдельным потоком: частоты меняются 1-2 раза в секунду,
    // и подмешивать их в uiState значило бы перекомпоновывать весь NavHost.
    val telemetry by viewModel.telemetry.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Системный диалог добавления в исключения энергосбережения не возвращает
    // результата, поэтому состояние исключения перечитываем при каждом
    // возврате приложения на экран — так переключатель в настройках сразу
    // показывает, разрешил пользователь исключение или нет
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBatteryOptimizationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // СКРАБ: страховка возврата прослушивания к реальному «сейчас».
    //
    // Сдвинутая ось времени суток — принадлежность редактора: она обязана
    // жить ровно столько, сколько открыт экран редактора. Штатные выходы
    // (стрелка «назад», системный «назад», сохранение) снимают её сами, и для
    // них этот наблюдатель ничего не делает — releaseEditorScrub идемпотентна.
    // Он нужен для ВСЕГО остального: любого перехода, который уводит с
    // маршрута редактора, но не проходит через его собственные обработчики
    // выхода (сегодня таких нет, однако требования «предусмотреть все случаи»
    // ровно про них).
    //
    // ПОЧЕМУ НЕ УХОД ЭКРАНА ИЗ КОМПОЗИЦИИ. onDispose в PresetEditScreen
    // срабатывает и на повороте, и при уничтожении Activity в фоне — то есть
    // когда редактор НЕ закрыт и пользователь продолжает слушать выбранное
    // время. Состояние back-stack таких ложных срабатываний не даёт: пока
    // редактор в стеке, он остаётся текущим и после пересоздания Activity.
    val editorOnScreen = navController.currentBackStackEntryAsState().value
        ?.destination
        ?.route
        ?.let { route -> route == Screen.PresetEdit.route || route == Screen.PresetNew.route }
        ?: false
    LaunchedEffect(editorOnScreen) {
        if (!editorOnScreen) {
            android.util.Log.d("ScrubLifecycle", "nav: редактор не на экране -> releaseEditorScrub")
            viewModel.releaseEditorScrub()
        }
    }

    // Панель отображается только когда есть активный пресет
    val showBottomPanel = uiState.activePreset != null
    
    // Любой сбой экспорта/импорта раньше был полностью тихим: файл создавался
    // и оставался пустым без единого признака ошибки. Теперь результат всегда
    // виден пользователю.
    val snackbarHostState = remember { SnackbarHostState() }
    val exportSuccessMessage = stringResource(R.string.export_success)
    val exportFailedMessage = stringResource(R.string.export_failed)
    val importSuccessMessage = stringResource(R.string.import_success)
    val importFailedMessage = stringResource(R.string.import_failed)

    // Лаунчер для экспорта пресета (создание файла).
    //
    // К моменту колбэка Activity уже пересоздана (пока открыт SAF-пикер, она
    // уничтожается), поэтому ни на какое состояние в памяти — ни Compose, ни
    // ViewModel — опираться нельзя: JSON пресета лежит в файле кэша, который
    // ViewModel подготовила ДО открытия пикера.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            // Пользователь отменил выбор: файл не создан, но подготовленный
            // JSON надо убрать, чтобы не лежал в кэше до следующего экспорта
            scope.launch(Dispatchers.IO) { viewModel.discardPendingExport() }
            return@rememberLauncherForActivityResult
        }
        android.util.Log.d("PresetExport", "callback: uri=$uri")
        scope.launch(Dispatchers.IO) {
            val pending = viewModel.consumePendingExport()
            if (pending == null) {
                // Раньше эта ветка молча пропускалась — и файл оставался пустым
                android.util.Log.e("PresetExport", "callback: данные экспорта потеряны")
                snackbarHostState.showSnackbar(
                    String.format(exportFailedMessage, "данные экспорта потеряны, повторите")
                )
                return@launch
            }
            val (presetName, presetJson) = pending
            val message = runCatching {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("openOutputStream вернул null")
                stream.bufferedWriter().use { writer -> writer.write(presetJson) }
            }.fold(
                onSuccess = {
                    android.util.Log.d(
                        "PresetExport",
                        "callback: записано ${presetJson.toByteArray(Charsets.UTF_8).size} байт"
                    )
                    String.format(exportSuccessMessage, presetName)
                },
                onFailure = { error ->
                    android.util.Log.e("PresetExport", "callback: запись не удалась", error)
                    String.format(exportFailedMessage, error.message ?: error.javaClass.simpleName)
                }
            )
            snackbarHostState.showSnackbar(message)
        }
    }

    // Лаунчер для импорта пресета (открытие файла)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importUri ->
            scope.launch(Dispatchers.IO) {
                val imported = viewModel.importPresetFromUri(importUri)
                snackbarHostState.showSnackbar(
                    if (imported != null) String.format(importSuccessMessage, imported.name)
                    else importFailedMessage
                )
                // После успешного импорта возвращаемся к списку (навигация — с главного)
                if (imported != null) withContext(Dispatchers.Main) { navController.popBackStack() }
            }
        }
    }
    
    // Высота нижней панели воспроизведения (для компенсации в контенте)
    val bottomPanelHeight = 60.dp

    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                // Снэкбар поднимаем над нижней панелью воспроизведения, иначе
                // та перекрывает его полностью.
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(bottom = if (showBottomPanel) bottomPanelHeight else 0.dp)
                    ) { data ->
                        // Цвета — из темы, БЕЗ инверсии. Дефолт Material 3
                        // (inverseSurface/inverseOnSurface) специально зеркалит
                        // тему: в светлой теме подсказка тёмная, в тёмной —
                        // светлая. Это ломает требование «оформление в стиле
                        // приложения»: берём обычную пару surfaceContainerHigh/
                        // onSurface — подсказка следует той же теме, что и всё
                        // приложение, и остаётся различимой за счёт тона
                        // поверхности и тени.
                        Snackbar(
                            snackbarData = data,
                            shape = RoundedCornerShape(12.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            ) { paddingValues ->
                NavHost(
                    navController = navController,
                    startDestination = Screen.PresetList.route,
                    modifier = Modifier
                        .padding(paddingValues)
                        // Добавляем снизу место для панели воспроизведения
                        .padding(bottom = if (showBottomPanel) bottomPanelHeight else 0.dp)
                        // Добавляем padding для navigation bar
                        .navigationBarsPadding(),
                    // Переходы заданы явно: дефолт navigation-compose — fade 700 мс,
                    // из-за чего на pop оба экрана 700 мс держатся в композиции и
                    // анимация сворачивания «съедается» двойной перерисовкой.
                    // Для pop входящий список показываем мгновенно (EnterTransition.None)
                    // — его проявляет сам общий элемент, а фейд поверх него только мешает.
                    enterTransition = { fadeIn(animationSpec = tween(300)) },
                    exitTransition = { fadeOut(animationSpec = tween(300)) },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { fadeOut(animationSpec = tween(300)) }
                ) {
                composable(Screen.PresetList.route) {
                    PresetListScreen(
                        viewModel = viewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onPresetClick = { presetId ->
                            // При клике на пресет начинаем воспроизведение
                            viewModel.playPreset(presetId)
                        },
                        onEditPreset = { presetId ->
                            navController.navigate(Screen.PresetEdit.createRoute(presetId))
                        },
                        onCreatePreset = {
                            navController.navigate(Screen.PresetNew.route)
                        },
                        onExportPreset = { presetId ->
                            // JSON готовим ДО открытия пикера (запись в кэш — на IO)
                            scope.launch(Dispatchers.IO) {
                                val fileName = viewModel.prepareExport(presetId)
                                // лаунчер и снэкбар — только с главного потока
                                withContext(Dispatchers.Main) {
                                    if (fileName != null) {
                                        exportLauncher.launch(fileName)
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            String.format(exportFailedMessage, "пресет не найден")
                                        )
                                    }
                                }
                            }
                        },
                        onOpenSettings = {
                            navController.navigate(Screen.Settings.route)
                        },
                        onImportPreset = {
                            importLauncher.launch(arrayOf("application/json"))
                        }
                    )
                }
                
                composable(
                    route = Screen.PresetEdit.route,
                    arguments = listOf(
                        navArgument("presetId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val presetId = backStackEntry.arguments?.getString("presetId") ?: ""
                    PresetEditScreen(
                        viewModel = viewModel,
                        presetId = presetId,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                
                composable(Screen.PresetNew.route) {
                    PresetEditScreen(
                        viewModel = viewModel,
                        presetId = null,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                }
            }
        }
        
        // Нижняя панель воспроизведения поверх контента.
        // Автоматически скрывается при паузе (panelVisible == false) — за возобновление
        // воспроизведения отвечает круглая плавающая кнопка ниже.
        // navigationBarsPadding применяется внутри BottomPlaybackPanel только к контенту
        // чтобы фон Surface заходил под navigation bar
        if (showBottomPanel) {
            BottomPlaybackPanel(
                presetName = uiState.activePreset?.name,
                beatFrequency = telemetry.currentBeatFrequency,
                carrierFrequency = telemetry.currentCarrierFrequency,
                isPlaying = telemetry.isPlaying,
                volume = uiState.volume,
                onPlayClick = { viewModel.togglePlayback() },
                onVolumeChange = { viewModel.setVolumeImmediate(it) },
                onVolumeSave = { viewModel.saveVolume() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Стартовое напоминание об исключении фонового энергосбережения.
        // Лежит в корне, поэтому показывается поверх любого экрана
        if (uiState.showBatteryOptimizationPrompt) {
            BatteryOptimizationPromptDialog(
                onConfirm = { viewModel.requestBatteryOptimizationExemption() },
                onCancel = { viewModel.dismissBatteryOptimizationPrompt() }
            )
        }

        // Диалог «Подключите наушники» при попытке запуска без гарнитуры
        if (uiState.showHeadphoneDialog) {
            HeadphoneRequiredDialog(
                onPlayAnyway = { viewModel.playPresetAnyway() },
                onDismiss = { viewModel.dismissHeadphoneDialog() }
            )
        }
    }
}
