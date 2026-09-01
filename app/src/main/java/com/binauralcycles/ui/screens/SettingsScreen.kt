package com.binauralcycles.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.binauralcycles.ui.components.PowerSettingsCard
import com.binauralcycles.ui.components.ChannelSwapSettingsCard
import com.binauralcycles.ui.components.VolumeNormalizationSettingsCard
import com.binauralcycles.ui.components.DebugTimeControlPanel
import com.binauralcycles.ui.components.SettingsSwitchRow
import com.binauralcycles.ui.theme.Spacing
import com.binauralcycles.viewmodel.BinauralViewModel
import com.binauralcycles.BuildConfig
import com.binauralcycles.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BinauralViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
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
                .padding(horizontal = Spacing.lg)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.lg),
            // Ритм МЕЖДУ секциями. Внутри каждой секции — свой Column со
            // spacedBy(Spacing.lg), поэтому зазор «заголовок → первый элемент»
            // всегда 16 и не зависит от типа элемента (карточка-группа или
            // строка-переключатель). Ни у одного блока нет внутренних
            // вертикальных паддингов — иначе ритм разъезжается.
            verticalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            // Раздел: Комфорт прослушивания
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text(
                    text = stringResource(R.string.settings_section_comfort),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                ChannelSwapSettingsCard(
                    channelSwapSettings = uiState.channelSwapSettings,
                    onChannelSwapSelect = { viewModel.setChannelSwapSelection(it) },
                    onChannelSwapIntervalChange = { viewModel.setChannelSwapInterval(it) },
                    onChannelSwapFadeDurationChange = { viewModel.setChannelSwapFadeDuration(it) },
                    onChannelSwapPauseDurationChange = { viewModel.setChannelSwapPauseDuration(it) },
                    onChannelSwapTrendPointsChange = { viewModel.setChannelSwapTrendPoints(it) }
                )

                VolumeNormalizationSettingsCard(
                    volumeNormalizationSettings = uiState.volumeNormalizationSettings,
                    onVolumeNormalizationEnabledChange = { viewModel.setVolumeNormalizationEnabled(it) },
                    onVolumeNormalizationStrengthChange = { viewModel.setVolumeNormalizationStrength(it) },
                    onTemporalNormalizationEnabledChange = { viewModel.setTemporalNormalizationEnabled(it) }
                )
            }

            // Раздел: Интерфейс
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text(
                    text = stringResource(R.string.settings_section_interface),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.resume_on_headset_connect),
                    description = stringResource(R.string.resume_on_headset_connect_desc),
                    checked = uiState.resumeOnHeadsetConnect,
                    onCheckedChange = { viewModel.setResumeOnHeadsetConnect(it) }
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.auto_resume_on_app_start),
                    description = stringResource(R.string.auto_resume_on_app_start_desc),
                    checked = uiState.autoResumeOnAppStart,
                    onCheckedChange = { viewModel.setAutoResumeOnAppStart(it) }
                )
            }

            // Раздел: Энергопотребление
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Text(
                    text = stringResource(R.string.settings_section_power),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                // Исключение фонового энергосбережения.
                // Состояние переключателя = фактическое состояние системы: выдать или
                // отозвать исключение может только пользователь, поэтому оба
                // направления открывают системный экран, а не пишут локальный флаг
                SettingsSwitchRow(
                    title = stringResource(R.string.uninterrupted_background_playback),
                    description = stringResource(R.string.uninterrupted_background_playback_desc),
                    checked = uiState.isIgnoringBatteryOptimizations,
                    onCheckedChange = { viewModel.setBatteryOptimizationExemption(it) }
                )

                PowerSettingsCard(
                    sampleRate = uiState.sampleRate,
                    bufferGenerationMinutes = uiState.bufferGenerationMinutes,
                    onSampleRateChange = { viewModel.setSampleRate(it) },
                    onBufferGenerationMinutesChange = { viewModel.setBufferGenerationMinutes(it) }
                )
            }

            // НОВОЕ: DEBUG-панель виртуального времени (только debug-сборка)
            if (BuildConfig.DEBUG) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    Text(
                        text = stringResource(R.string.settings_section_debug),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    DebugTimeControlPanel(viewModel)
                }
            }
        }
    }
}
