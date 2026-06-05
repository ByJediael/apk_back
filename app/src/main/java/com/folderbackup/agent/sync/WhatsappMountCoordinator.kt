package com.folderbackup.agent.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import com.folderbackup.agent.backup.WhatsappSessionExporter
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.registration.AccessibilityHelper
import com.folderbackup.agent.registration.RegistrationNotifier
import com.folderbackup.agent.registration.WhatsappRegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WhatsappMountCoordinator(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val api = BackupApiClient()

    suspend fun submitPairingCode(
        requestId: String?,
        pairingCode: String,
        evolutionInstance: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!AccessibilityHelper.isServiceEnabled(context)) {
            val msg = "Ative Acessibilidade → WhatsApp Backup"
            report(requestId, "submit_pairing_code", "failed", msg, evolutionInstance)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val code = pairingCode.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        if (code.length < 4) {
            return@withContext Result.failure(IllegalStateException("pairing_code inválido"))
        }

        report(requestId, "submit_pairing_code", "running", "Abrindo WA para vincular…", evolutionInstance)
        RegistrationNotifier.show(context, "Evolution", "Código: $code")
        WhatsappRegistrationState.beginPairing(code)
        launchWhatsapp()
        delay(2000)
        openLinkedDevices()

        val ok = withTimeoutOrNull(PAIRING_TIMEOUT_MS) {
            while (true) {
                when (WhatsappRegistrationState.phase) {
                    WhatsappRegistrationState.Phase.PairingDone -> return@withTimeoutOrNull true
                    WhatsappRegistrationState.Phase.Failed -> return@withTimeoutOrNull false
                    else -> delay(500)
                }
            }
        }

        if (ok != true) {
            val msg = "Timeout ao digitar pairing code no WhatsApp"
            report(requestId, "submit_pairing_code", "failed", msg, evolutionInstance)
            WhatsappRegistrationState.reset()
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val msg = "Pairing code enviado no WhatsApp"
        report(requestId, "submit_pairing_code", "completed", msg, evolutionInstance)
        preferences.setLastStatus(msg)
        WhatsappRegistrationState.reset()
        Result.success(msg)
    }

    private fun openLinkedDevices() {
        val pkg = WhatsappSessionExporter.WHATSAPP_PACKAGE
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).apply {
                setClassName(pkg, "$pkg.com.whatsapp.settings.Settings")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        intents.filterNotNull().forEach { intent ->
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                /* tenta próximo */
            }
        }
    }

    private suspend fun report(
        requestId: String?,
        command: String,
        status: String,
        message: String,
        evolutionInstance: String?,
    ) {
        val config = preferences.getConfigSnapshot()
        preferences.setLastStatus(message)
        api.reportCommandResult(
            config = config,
            requestId = requestId,
            command = command,
            sessionLabel = evolutionInstance ?: "pairing",
            phoneE164 = null,
            status = status,
            message = message,
        ).onFailure { Log.w(TAG, "command-result: ${it.message}") }
    }

    private fun launchWhatsapp() {
        val intent = context.packageManager
            .getLaunchIntentForPackage(WhatsappSessionExporter.WHATSAPP_PACKAGE)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "WaMountCoordinator"
        private const val PAIRING_TIMEOUT_MS = 90_000L
    }
}
