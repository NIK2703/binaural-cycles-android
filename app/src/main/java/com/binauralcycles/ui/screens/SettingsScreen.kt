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
import com.binaural.data.preferences.NotificationStyle
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
            
            // Раздел: Энергопотребление
            Text(
                text = stringResource(R.string.settings_section_power),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Настройки энергопотребления
            PowerSettingsCard(
                sampleRate = uiState.sampleRate,
                frequencyUpdateIntervalMs = uiState.frequencyUpdateIntervalMs,
                onSampleRateChange = { viewModel.setSampleRate(it) },
                onFrequencyUpdateIntervalChange = { viewModel.setFrequencyUpdateInterval(it) }
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            
            // Раздел: Интерфейс
            Text(
                text = stringResource(R.string.settings_section_interface),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // Настройка стиля уведомления
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.notification_style_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = stringResource(R.string.notification_style_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Выбор стиля уведомления
                    NotificationStyleSelector(
                        currentStyle = uiState.notificationStyle,
                        onStyleChange = { viewModel.setNotificationStyle(it) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Настройка возобновления при подключении гарнитуры
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.resume_on_headset_connect),
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = stringResource(R.string.resume_on_headset_connect_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Switch(
                        checked = uiState.resumeOnHeadsetConnect,
                        onCheckedChange = { viewModel.setResumeOnHeadsetConnect(it) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Селектор для выбора стиля уведомления
 */
@Composable
private fun NotificationStyleSelector(
    currentStyle: NotificationStyle,
    onStyleChange: (NotificationStyle) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        NotificationStyle.values().forEach { style ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = currentStyle == style,
                    onClick = { onStyleChange(style) }
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column {
                    Text(
                        text = when (style) {
                            NotificationStyle.MEDIA -> stringResource(R.string.notification_style_media)
                            NotificationStyle.SIMPLE -> stringResource(R.string.notification_style_simple)
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = when (style) {
                            NotificationStyle.MEDIA -> stringResource(R.string.notification_style_media_desc)
                            NotificationStyle.SIMPLE -> stringResource(R.string.notification_style_simple_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
