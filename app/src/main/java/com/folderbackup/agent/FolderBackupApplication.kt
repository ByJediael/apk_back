package com.folderbackup.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.folderbackup.agent.BuildConfig
import com.folderbackup.agent.push.FcmTokenRegistrar
import com.folderbackup.agent.sync.SessionInventoryReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FolderBackupApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        appScope.launch {
            FcmTokenRegistrar.registerIfPossible(this@FolderBackupApplication)
            SessionInventoryReporter.syncIfConfigured(this@FolderBackupApplication)
        }
        if (BuildConfig.DEBUG) {
            runDebugHooks()
        }
    }

    private fun runDebugHooks() {
        try {
            val hooks = Class.forName("com.folderbackup.agent.debug.DebugAppHooks")
            hooks.getMethod("onCreate", Application::class.java).invoke(null, this)
        } catch (_: ReflectiveOperationException) {
            // debug source set ausente
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "backup_sync"
    }
}
