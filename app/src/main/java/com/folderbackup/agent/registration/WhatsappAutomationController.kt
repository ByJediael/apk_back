package com.folderbackup.agent.registration

import android.content.Context
import android.util.Log
import com.folderbackup.agent.service.BackupForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/** Uma automação por vez; cancelável ao parar serviço ou "liberar toque". */
object WhatsappAutomationController {
    private const val TAG = "WaAutomationCtrl"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeJob: Job? = null

    fun runAutomation(context: Context, block: suspend () -> Unit) {
        val app = context.applicationContext
        cancelAutomation(app, releaseStates = false)
        activeJob = scope.launch {
            try {
                BackupForegroundService.start(app)
                block()
            } finally {
                BackupForegroundService.stop(app)
                activeJob = null
            }
        }
    }

    fun cancelAutomation(context: Context, releaseStates: Boolean = true) {
        activeJob?.cancel()
        activeJob = null
        scope.coroutineContext.cancelChildren()
        BackupForegroundService.stop(context.applicationContext)
        if (releaseStates) {
            WhatsappAutomationGate.releaseAll()
            Log.i(TAG, "Automação cancelada — toque liberado")
        }
    }
}
