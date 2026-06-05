package com.folderbackup.agent.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import com.folderbackup.agent.backup.WhatsappSessionExporter
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.registration.AccessibilityHelper
import com.folderbackup.agent.registration.RegistrationNotifier
import com.folderbackup.agent.registration.WhatsappMacroState
import com.folderbackup.agent.registration.WhatsappRegistrationAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WhatsappMacroCoordinator(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val api = BackupApiClient()

    suspend fun goHome(requestId: String?): Result<String> = withContext(Dispatchers.IO) {
        if (!AccessibilityHelper.isServiceEnabled(context)) {
            val msg = "Ative Acessibilidade → WhatsApp Backup"
            report(requestId, "macro_home", "failed", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        report(requestId, "macro_home", "running", "Indo para tela inicial…")
        WhatsappMacroState.beginHome()

        val pressed = WhatsappRegistrationAccessibilityService.pressHome()
        if (!pressed) {
            val msg = "Serviço de acessibilidade indisponível para HOME"
            WhatsappMacroState.markFailed(msg)
            report(requestId, "macro_home", "failed", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        delay(800)
        WhatsappMacroState.markDone()
        val msg = "Tela inicial (HOME)"
        report(requestId, "macro_home", "completed", msg)
        preferences.setLastStatus(msg)
        WhatsappMacroState.reset()
        Result.success(msg)
    }

    suspend fun openWhatsapp(requestId: String?): Result<String> = withContext(Dispatchers.IO) {
        if (!AccessibilityHelper.isServiceEnabled(context)) {
            val msg = "Ative Acessibilidade → WhatsApp Backup"
            report(requestId, "macro_open_whatsapp", "failed", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        report(requestId, "macro_open_whatsapp", "running", "Abrindo WhatsApp pelo launcher…")
        WhatsappRegistrationAccessibilityService.pressHome()
        delay(600)
        WhatsappMacroState.beginOpenWhatsapp()

        val ok = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            while (true) {
                when (WhatsappMacroState.phase) {
                    WhatsappMacroState.Phase.Done -> return@withTimeoutOrNull true
                    WhatsappMacroState.Phase.Failed -> return@withTimeoutOrNull false
                    else -> delay(400)
                }
            }
        }

        val usedFallback = ok != true
        if (usedFallback) {
            launchWhatsappFallback()
            delay(1500)
            if (WhatsappMacroState.phase == WhatsappMacroState.Phase.Failed) {
                val msg = WhatsappMacroState.error ?: "Timeout ao abrir WhatsApp no launcher"
                report(requestId, "macro_open_whatsapp", "failed", msg)
                WhatsappMacroState.reset()
                return@withContext Result.failure(IllegalStateException(msg))
            }
            WhatsappMacroState.markDone(fallback = true)
        }

        val status = if (usedFallback || WhatsappMacroState.usedFallback) {
            "completed_with_fallback"
        } else {
            "completed"
        }
        val msg = if (status == "completed_with_fallback") {
            "WhatsApp aberto (fallback intent)"
        } else {
            "WhatsApp aberto pelo ícone"
        }
        report(requestId, "macro_open_whatsapp", status, msg)
        preferences.setLastStatus(msg)
        RegistrationNotifier.show(context, "Macro", msg)
        WhatsappMacroState.reset()
        Result.success(msg)
    }

    private fun launchWhatsappFallback() {
        WhatsappMacroState.usedFallback = true
        val intent = context.packageManager
            .getLaunchIntentForPackage(WhatsappSessionExporter.WHATSAPP_PACKAGE)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private suspend fun report(
        requestId: String?,
        command: String,
        status: String,
        message: String,
    ) {
        val config = preferences.getConfigSnapshot()
        preferences.setLastStatus(message)
        api.reportCommandResult(
            config = config,
            requestId = requestId,
            command = command,
            sessionLabel = "macro",
            phoneE164 = null,
            status = status,
            message = message,
        ).onFailure { Log.w(TAG, "command-result: ${it.message}") }
    }

    companion object {
        private const val TAG = "WaMacroCoordinator"
        private const val OPEN_TIMEOUT_MS = 20_000L
    }
}
