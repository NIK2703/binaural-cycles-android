package com.binauralcycles

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.binauralcycles.service.BinauralPlaybackService
import com.binauralcycles.ui.navigation.BinauralNavigation
import com.binaural.core.ui.theme.BinauralTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_EXIT = "com.binauralcycles.action.EXIT_APP"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Проверяем, нужно ли закрыть приложение
        if (intent?.action == ACTION_EXIT) {
            finishAndRemoveTask()
            return
        }
        
        // Запускаем сервис для воспроизведения в фоне
        val serviceIntent = Intent(this, BinauralPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        enableEdgeToEdge()
        setContent {
            BinauralTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    BinauralNavigation(navController = navController)
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Обрабатываем запрос на выход, если он пришёл во время работы Activity
        if (intent.action == ACTION_EXIT) {
            finishAndRemoveTask()
        }
    }
}
