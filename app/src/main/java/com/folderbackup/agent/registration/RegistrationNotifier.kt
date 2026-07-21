package com.folderbackup.agent.registration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.folderbackup.agent.FolderBackupApplication
import com.folderbackup.agent.MainActivity
import com.folderbackup.agent.R

object RegistrationNotifier {
    private const val CHANNEL_ID = "wa_registration"
    private const val NOTIF_ID = 2001
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(context: Context, title: String, message: String) {
        val appContext = context.applicationContext
        mainHandler.post { showOnMain(appContext, title, message) }
    }

    private fun showOnMain(context: Context, title: String, message: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
