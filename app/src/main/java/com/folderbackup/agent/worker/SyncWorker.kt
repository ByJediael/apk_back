package com.folderbackup.agent.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.folderbackup.agent.sync.SyncCoordinator

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val outcome = SyncCoordinator(applicationContext).runOnce()
        return if (outcome.isSuccess) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
