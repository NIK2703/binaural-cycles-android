package com.binauralcycles.service.helpers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.binaural.core.domain.repository.PlaybackStateRepository
import com.binauralcycles.MainActivity
import com.binauralcycles.R
import com.binauralcycles.service.BinauralPlaybackService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper-класс для управления уведомлениями сервиса воспроизведения.
 * Отвечает за создание notification channel и создание/обновление уведомлений.
 */
@Singleton
class NotificationHelper @Inject constructor(
    private val playbackStateRepository: PlaybackStateRepository
) {
    companion object {
        const val CHANNEL_ID = "binaural_playback_channel"
        const val NOTIFICATION_ID = 1001
    }

    /**
     * Создаёт notification channel для сервиса воспроизведения.
     * Должен вызываться один раз при создании сервиса.
     */
    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Создаёт уведомление для foreground service.
     * @param context Контекст для создания PendingIntent
     * @param serviceClass Класс сервиса для Intent
     * @param mediaSessionToken Token MediaSession для MediaStyle (опционально)
     */
    fun createNotification(
        context: Context,
        serviceClass: Class<out Service>,
        mediaSessionToken: Any? = null
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Toggle action (Play/Pause)
        val toggleIntent = Intent(context, serviceClass).apply {
            action = BinauralPlaybackService.ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            context, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Exit action
        val exitIntent = Intent(context, serviceClass).apply {
            action = BinauralPlaybackService.ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            context, 2, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val currentState = playbackStateRepository.playbackState.value
        val title = currentState.currentPresetName ?: context.getString(R.string.notification_playing)

        val content = if (currentState.isPlaying) {
            // Показываем частоты только если они установлены (не 0)
            if (currentState.currentBeatFrequency > 0 && currentState.currentCarrierFrequency > 0) {
                context.getString(
                    R.string.notification_title,
                    currentState.currentBeatFrequency,
                    currentState.currentCarrierFrequency
                )
            } else {
                // Если частоты ещё не установлены - показываем название пресета
                currentState.currentPresetName ?: context.getString(R.string.notification_playing)
            }
        } else {
            context.getString(R.string.notification_paused)
        }

        val playPauseIcon = if (currentState.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseText = if (currentState.isPlaying) 
            context.getString(R.string.action_pause) 
        else 
            context.getString(R.string.action_play)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(currentState.isPlaying)
            .setOnlyAlertOnce(true) // Предотвращает мерцание иконки при обновлении
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(playPauseIcon, playPauseText, togglePendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_exit),
                exitPendingIntent
            )

        // Добавляем MediaStyle если есть token
        @Suppress("UNCHECKED_CAST")
        val token = mediaSessionToken as? android.support.v4.media.session.MediaSessionCompat.Token
        if (token != null) {
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1) // Показываем play/pause и выход в компактном виде
            )
        }

        return builder.build()
    }

    /**
     * Немедленное обновление notification.
     */
    fun updateNotification(context: Context, notificationManager: NotificationManager) {
        try {
            val notification = createNotification(
                context,
                BinauralPlaybackService::class.java,
                null // token будет установлен позже
            )
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            android.util.Log.e("NotificationHelper", "Failed to update notification", e)
        }
    }

    /**
     * Тихое обновление notification (только текст, без мерцания иконки).
     */
    fun updateNotificationSilently(
        context: Context,
        notificationManager: NotificationManager,
        mediaSessionToken: Any? = null
    ) {
        try {
            val notification = createNotification(
                context,
                BinauralPlaybackService::class.java,
                mediaSessionToken
            )
            // Используем флаг для предотвращения визуального мерцания
            val silentNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                notification.extras.putBoolean("android.silent", true)
                notification
            } else {
                notification
            }
            notificationManager.notify(NOTIFICATION_ID, silentNotification)
        } catch (e: Exception) {
            android.util.Log.e("NotificationHelper", "Failed to update notification silently", e)
        }
    }
}