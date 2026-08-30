package com.binauralcycles.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Работа с системным списком исключений фонового энергосбережения (Doze / App Standby).
 *
 * Приложение играет бинауральные ритмы часами в фоновом сервисе. Если система
 * сочтёт приложение неактивным, она урежет ему окна CPU и WakeLock'и, генерация
 * буфера начнёт опаздывать и звук будет прерываться. Исключение
 * (`isIgnoringBatteryOptimizations`) снимает эти ограничения.
 *
 * Важно: выдать исключение программно нельзя — это делает только пользователь
 * в системном интерфейсе. Поэтому переключатель в настройках не «включает»
 * исключение сам, а открывает системный диалог; фактическое состояние потом
 * перечитывается через [isIgnoringBatteryOptimizations] (см. ViewModel).
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimization"

    /**
     * true — приложение уже добавлено в исключения фонового энергосбережения.
     *
     * При сбое системы считаем, что исключение получено: иначе пользователь
     * получал бы напоминание, которое невозможно закрыть результатом.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return try {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось проверить исключение энергосбережения", e)
            true
        }
    }

    /**
     * Открыть системный диалог подтверждения: «Разрешить приложению работать
     * в фоне без ограничений». Это прямой запрос — пользователю остаётся только
     * нажать «Разрешить» в системном окне.
     *
     * Часть OEM-оболочек подменяет диалог экраном настроек, а некоторые его
     * вообще не отдают, поэтому есть цепочка фолбэков: список приложений с
     * оптимизацией → экран сведений о приложении.
     *
     * @return true — системный интерфейс удалось открыть
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, requestIntent)) return true
        return openBatteryOptimizationSettings(context)
    }

    /**
     * Открыть список «Приложения с оптимизацией энергопотребления».
     * Отсюда же исключение можно отозвать — поэтому именно этот экран
     * используется для выключения переключателя в настройках.
     *
     * @return true — системный интерфейс удалось открыть
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, listIntent)) return true

        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startSafely(context, detailsIntent)
    }

    private fun startSafely(context: Context, intent: Intent): Boolean {
        return try {
            // resolveActivity нужен, чтобы отличить «прошивка не отдаёт диалог»
            // от краша startActivity. Видимость пакета настроек обеспечена
            // блоком <queries> в манифесте (иначе Android 11+ вернул бы null).
            if (intent.resolveActivity(context.packageManager) == null) {
                Log.w(TAG, "Системный экран недоступен: $intent")
                return false
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось открыть системный экран: $intent", e)
            false
        }
    }
}
