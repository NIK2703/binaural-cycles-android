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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.binaural.core.domain.service.PlaybackController
import com.binauralcycles.service.BinauralPlaybackService
import com.binauralcycles.service.ServiceLifecycleManager
import com.binauralcycles.ui.navigation.BinauralNavigation
import com.binaural.core.ui.theme.BinauralTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_EXIT = "com.binauralcycles.action.EXIT_APP"
    }
    
    @Inject
    lateinit var playbackController: PlaybackController

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
        
        // Отслеживаем состояние приложения (foreground/background)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Приложение на экране - обновляем частоты каждую секунду
                playbackController.onAppForeground()
            }
        }
        
        enableEdgeToEdge()
        // Явно устанавливаем прозрачный цвет navigation bar
        window.navigationBarColor = Color.Transparent.toArgb()
        // Отключаем принудительный контраст navigation bar (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        
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
    
    override fun onResume() {
        super.onResume()
        // Приложение на экране
        playbackController.onAppForeground()
    }
    
    override fun onPause() {
        super.onPause()
        // Приложение уходит в фон
        playbackController.onAppBackground()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Обрабатываем запрос на выход, если он пришёл во время работы Activity
        if (intent.action == ACTION_EXIT) {
            finishAndRemoveTask()
        }
    }
}