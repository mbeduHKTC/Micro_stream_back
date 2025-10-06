package com.example.hearingaidstreamer.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.example.hearingaidstreamer.R
import com.example.hearingaidstreamer.ui.MainActivity

/**
 * Builds and updates the media-style notification shown while the stream is active or paused.
 */
class StreamNotificationManager(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        ensureChannel()
    }

    fun show(mediaSessionToken: MediaSessionCompat.Token, isPlaying: Boolean) {
        if (!notificationManager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled; skipping media notification")
            return
        }

        val contentIntent = TaskStackBuilder.create(context).run {
            addNextIntent(Intent(context, MainActivity::class.java))
            getPendingIntent(REQUEST_CODE_CONTENT, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notification_pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                context.getString(R.string.notification_play),
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY)
            )
        }

        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            context.getString(R.string.notification_stop),
            MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP)
        )

        val style = MediaNotificationCompat.MediaStyle()
            .setMediaSession(mediaSessionToken)
            .setShowActionsInCompactView(0, 1)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                context.getString(if (isPlaying) R.string.call_active else R.string.call_idle)
            )
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setStyle(style)
            .addAction(playPauseAction)
            .addAction(stopAction)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun dismiss() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun areEnabled(): Boolean = notificationManager.areNotificationsEnabled()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.call_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.call_notification_channel_desc)
        }
        val systemManager = context.getSystemService(NotificationManager::class.java)
        systemManager?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "StreamNotification"
        private const val CHANNEL_ID = "stream_media_channel"
        private const val NOTIFICATION_ID = 0x534d
        private const val REQUEST_CODE_CONTENT = 0x100
    }
}
