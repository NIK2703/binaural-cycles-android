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
import com.binauralcycles.ui.components.AppSettingsCard
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
            
            AppSettingsCard(
                sampleRate = uiState.sampleRate,
                frequencyUpdateIntervalMs = uiState.frequencyUpdateIntervalMs,
                autoExpandGraphRange = uiState.autoExpandGraphRange,
                onSampleRateChange = { viewModel.setSampleRate(it) },
                onFrequencyUpdateIntervalChange = { viewModel.setFrequencyUpdateInterval(it) },
                onAutoExpandGraphRangeChange = { viewModel.setAutoExpandGraphRange(it) }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}