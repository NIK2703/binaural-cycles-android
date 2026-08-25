package com.binauralcycles.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.binauralcycles.ui.components.ChannelSwapSettingsCard
import com.binauralcycles.ui.components.VolumeNormalizationSettingsCard
import com.binauralcycles.ui.components.PowerSettingsCard
import com.binauralcycles.ui.components.DebugTimeControlPanel
import com.binauralcycles.viewmodel.BinauralViewModel
import com.binauralcycles.BuildConfig
import com.binauralcycles.R
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsScreen(
    viewModel: BinauralViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Раздел: Комфорт прослушивания
            SmallTitle(
                text = stringResource(R.string.settings_section_comfort),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Глобальные настройки прослушивания: перестановка каналов + нормализация
            Card(modifier = Modifier.fillMaxWidth()) {
                ChannelSwapSettingsCard(
                    channelSwapSettings = uiState.channelSwapSettings,
                    isChannelsSwapped = uiState.isChannelsSwapped,
                    onChannelSwapSelect = { viewModel.setChannelSwapSelection(it) },
                    onChannelSwapIntervalChange = { viewModel.setChannelSwapInterval(it) },
                    onChannelSwapFadeDurationChange = { viewModel.setChannelSwapFadeDuration(it) },
                    onChannelSwapPauseDurationChange = { viewModel.setChannelSwapPauseDuration(it) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                VolumeNormalizationSettingsCard(
                    volumeNormalizationSettings = uiState.volumeNormalizationSettings,
                    onVolumeNormalizationEnabledChange = { viewModel.setVolumeNormalizationEnabled(it) },
                    onVolumeNormalizationStrengthChange = { viewModel.setVolumeNormalizationStrength(it) },
                    onTemporalNormalizationEnabledChange = { viewModel.setTemporalNormalizationEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Раздел: Интерфейс
            SmallTitle(
                text = stringResource(R.string.settings_section_interface),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                // Настройка возобновления при подключении гарнитуры
                SwitchPreference(
                    title = stringResource(R.string.resume_on_headset_connect),
                    summary = stringResource(R.string.resume_on_headset_connect_desc),
                    checked = uiState.resumeOnHeadsetConnect,
                    onCheckedChange = { viewModel.setResumeOnHeadsetConnect(it) }
                )

                // Настройка автовозобновления при запуске приложения
                SwitchPreference(
                    title = stringResource(R.string.auto_resume_on_app_start),
                    summary = stringResource(R.string.auto_resume_on_app_start_desc),
                    checked = uiState.autoResumeOnAppStart,
                    onCheckedChange = { viewModel.setAutoResumeOnAppStart(it) }
                )
            }

            // Раздел: Энергопотребление
            SmallTitle(
                text = stringResource(R.string.settings_section_power),
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Настройки энергопотребления
            Card(modifier = Modifier.fillMaxWidth()) {
                PowerSettingsCard(
                    sampleRate = uiState.sampleRate,
                    bufferGenerationMinutes = uiState.bufferGenerationMinutes,
                    onSampleRateChange = { viewModel.setSampleRate(it) },
                    onBufferGenerationMinutesChange = { viewModel.setBufferGenerationMinutes(it) }
                )
            }

            // DEBUG-панель виртуального времени (только debug-сборка)
            if (BuildConfig.DEBUG) {
                SmallTitle(
                    text = stringResource(R.string.settings_section_debug),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                DebugTimeControlPanel(viewModel)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
