package com.folderbackup.agent.sync

import android.content.Context
import com.folderbackup.agent.backup.JobExecutor
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.network.toDebugString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val api = BackupApiClient()
    private val executor = JobExecutor(appContext, api)

    suspend fun runOnce(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val config = preferences.getConfigSnapshot()
            if (config.apiToken.isBlank() || config.deviceId.isBlank()) {
                preferences.setLastStatus("Configure URL, token e device ID")
                return@withContext Result.success("missing_config")
            }
            if (config.watchedFolders.isEmpty()) {
                preferences.setLastStatus("Adicione ao menos uma pasta")
                return@withContext Result.success("missing_folders")
            }

            val jobs = api.fetchPendingJobs(config).getOrElse { err ->
                preferences.setLastStatus("Erro API: ${err.message}")
                return@withContext Result.failure(err)
            }

            if (jobs.isEmpty()) {
                preferences.setLastStatus("Nenhum comando pendente")
                return@withContext Result.success("idle")
            }

            preferences.setLastStatus("Executando: ${jobs.toDebugString()}")
            BackupForegroundService.start(appContext)

            var summary = ""
            for (job in jobs) {
                val result = executor.execute(config, job)
                summary = "${result.status}: ${result.message}"
                preferences.setLastStatus(summary)
            }

            BackupForegroundService.stop(appContext)
            Result.success(summary.ifBlank { "done" })
        } catch (err: Exception) {
            preferences.setLastStatus("Falha: ${err.message}")
            BackupForegroundService.stop(appContext)
            Result.failure(err)
        }
    }
}
