package com.folderbackup.agent.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
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

        val open = WhatsappOpenHelper.open(context)
        if (!open.ok) {
            val msg = "Não foi possível abrir WhatsApp Business"
            report(requestId, "macro_open_whatsapp", "failed", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val status = if (open.method == "intent") "completed_with_fallback" else "completed"
        val msg = when (open.method) {
            "intent" -> "WhatsApp aberto (intent)"
            else -> "WhatsApp aberto pelo ícone"
        }
        report(requestId, "macro_open_whatsapp", status, msg)
        preferences.setLastStatus(msg)
        RegistrationNotifier.show(context, "Macro", msg)
        Result.success(msg)
    }

    suspend fun installWhatsapp(requestId: String?, force: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        if (!AccessibilityHelper.isServiceEnabled(context)) {
            val msg = "Acessibilidade desativada. Ative Acessibilidade -> Folder Backup Agent para instalar automaticamente."
            report(requestId, "macro_install_whatsapp", "failed", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        if (!force && isAppInstalled(WhatsappSessionExporter.WHATSAPP_PACKAGE)) {
            val msg = "WhatsApp Business já está instalado."
            report(requestId, "macro_install_whatsapp", "completed", msg)
            preferences.setLastStatus(msg)
            RegistrationNotifier.show(context, "Macro", msg)
            WhatsappMacroState.reset()
            return@withContext Result.success(msg)
        }

        report(requestId, "macro_install_whatsapp", "running", "Abrindo Play Store na página do WhatsApp Business…")
        
        WhatsappMacroState.beginHome()
        WhatsappMacroState.phase = WhatsappMacroState.Phase.InstallWhatsAppFromPlayStore

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${WhatsappSessionExporter.WHATSAPP_PACKAGE}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${WhatsappSessionExporter.WHATSAPP_PACKAGE}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (webEx: Exception) {
                val msg = "Falha ao abrir Play Store: ${e.message}"
                WhatsappMacroState.markFailed(msg)
                report(requestId, "macro_install_whatsapp", "failed", msg)
                return@withContext Result.failure(e)
            }
        }

        // Aguarda a instalação por até 120s (tempo de download)
        var elapsed = 0
        while (elapsed < 120) {
            if (isAppInstalled(WhatsappSessionExporter.WHATSAPP_PACKAGE)) {
                WhatsappMacroState.markDone()
                break
            }
            delay(1000)
            elapsed++
        }

        if (WhatsappMacroState.phase == WhatsappMacroState.Phase.Done) {
            val msg = "WhatsApp Business instalado com sucesso."
            report(requestId, "macro_install_whatsapp", "completed", msg)
            preferences.setLastStatus(msg)
            RegistrationNotifier.show(context, "Macro", msg)
            WhatsappMacroState.reset()
            Result.success(msg)
        } else {
            val err = WhatsappMacroState.error ?: "Tempo limite esgotado para instalação automática"
            report(requestId, "macro_install_whatsapp", "failed", err)
            preferences.setLastStatus("Instalação falhou: $err")
            WhatsappMacroState.reset()
            Result.failure(IllegalStateException(err))
        }
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

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "WaMacroCoordinator"
    }
}
