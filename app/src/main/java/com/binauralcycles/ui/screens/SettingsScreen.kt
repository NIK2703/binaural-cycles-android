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
import com.binauralcycles.viewmodel.BinauralViewModel
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // Раздел: Комфорт прослушивания
            Text(
                text = stringResource(R.string.settings_section_comfort),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Глобальные настройки перестановки каналов
            ChannelSwapSettingsCard(
                channelSwapSettings = uiState.channelSwapSettings,
                isChannelsSwapped = uiState.isChannelsSwapped,
                onChannelSwapEnabledChange = { viewModel.setChannelSwapEnabled(it) },
                onChannelSwapIntervalChange = { viewModel.setChannelSwapInterval(it) },
                onChannelSwapFadeDurationChange = { viewModel.setChannelSwapFadeDuration(it) },
                onChannelSwapPauseDurationChange = { viewModel.setChannelSwapPauseDuration(it) }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            
            // Глобальные настройки нормализации громкости
            VolumeNormalizationSettingsCard(
                volumeNormalizationSettings = uiState.volumeNormalizationSettings,
                onVolumeNormalizationEnabledChange = { viewModel.setVolumeNormalizationEnabled(it) },
                onVolumeNormalizationStrengthChange = { viewModel.setVolumeNormalizationStrength(it) },
                onTemporalNormalizationEnabledChange = { viewModel.setTemporalNormalizationEnabled(it) }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            // Раздел: Интерфейс
            Text(
                text = stringResource(R.string.settings_section_interface),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Настройка возобновления при подключении гарнитуры
            ListItem(
                headlineContent = { Text(stringResource(R.string.resume_on_headset_connect)) },
                supportingContent = { Text(stringResource(R.string.resume_on_headset_connect_desc)) },
                trailingContent = {
                    Switch(
                        checked = uiState.resumeOnHeadsetConnect,
                        onCheckedChange = { viewModel.setResumeOnHeadsetConnect(it) }
                    )
                }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            // Настройка автовозобновления при запуске приложения
            ListItem(
                headlineContent = { Text(stringResource(R.string.auto_resume_on_app_start)) },
                supportingContent = { Text(stringResource(R.string.auto_resume_on_app_start_desc)) },
                trailingContent = {
                    Switch(
                        checked = uiState.autoResumeOnAppStart,
                        onCheckedChange = { viewModel.setAutoResumeOnAppStart(it) }
                    )
                }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            // Раздел: Энергопотребление
            Text(
                text = stringResource(R.string.settings_section_power),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Настройки энергопотребления
            PowerSettingsCard(
                sampleRate = uiState.sampleRate,
                bufferGenerationMinutes = uiState.bufferGenerationMinutes,
                onSampleRateChange = { viewModel.setSampleRate(it) },
                onBufferGenerationMinutesChange = { viewModel.setBufferGenerationMinutes(it) }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
