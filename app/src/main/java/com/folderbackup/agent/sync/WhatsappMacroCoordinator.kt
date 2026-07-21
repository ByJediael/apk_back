package com.folderbackup.agent.sync

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.folderbackup.agent.backup.RootShell
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

    suspend fun forceStopWhatsapp(requestId: String?): Result<String> = withContext(Dispatchers.IO) {
        report(requestId, "macro_force_stop_whatsapp", "running", "Fechando WhatsApp (apps recentes + force-stop)…")

        val pkg = WhatsappSessionExporter.WHATSAPP_PACKAGE
        WhatsappRegistrationAccessibilityService.pressHome()
        delay(500)

        if (AccessibilityHelper.isServiceEnabled(context)) {
            report(requestId, "macro_force_stop_whatsapp", "running", "Lista de apps → fechar WhatsApp (X ou arrastar)…")
            repeat(RECENTS_DISMISS_ATTEMPTS) {
                WhatsappRegistrationAccessibilityService.dismissWhatsappFromRecents()
                delay(800)
            }
        }

        val killScript = """
            am force-stop $pkg
            killall $pkg 2>/dev/null || true
            am force-stop $pkg
        """.trimIndent()

        if (RootShell.isRootAvailable()) {
            RootShell.runSu(killScript)
        }
        runShellForceStop(pkg)

        var verified = false
        repeat(FORCE_STOP_ATTEMPTS) { attempt ->
            if (!isWhatsappProcessRunning(pkg)) {
                verified = true
                return@repeat
            }
            Log.w(TAG, "WA ainda rodando — tentativa ${attempt + 1}/$FORCE_STOP_ATTEMPTS")
            if (RootShell.isRootAvailable()) {
                RootShell.runSu(killScript)
            }
            runShellForceStop(pkg)
            delay(FORCE_STOP_RETRY_MS)
        }

        delay(800)
        val stillRunning = isWhatsappProcessRunning(pkg)
        if (stillRunning) {
            val msg = "WhatsApp ainda em segundo plano após force-stop"
            report(requestId, "macro_force_stop_whatsapp", "failed", msg)
            preferences.setLastStatus(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        WhatsappRegistrationAccessibilityService.pressHome()
        val msg = if (verified) {
            "WhatsApp fechado por completo (recentes + force-stop)"
        } else {
            "WhatsApp fechado (recentes + force-stop)"
        }
        report(requestId, "macro_force_stop_whatsapp", "completed", msg)
        preferences.setLastStatus(msg)
        RegistrationNotifier.show(context, "Macro", msg)
        Result.success(msg)
    }

    private fun runShellForceStop(pkg: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "am force-stop $pkg")).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "am force-stop shell: ${e.message}")
        }
    }

    private fun isWhatsappProcessRunning(pkg: String): Boolean {
        if (RootShell.isRootAvailable()) {
            val pid = RootShell.runSu("pidof $pkg 2>/dev/null").getOrNull().orEmpty().trim()
            if (pid.isNotBlank()) return true
            return false
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.runningAppProcesses?.any { proc ->
                proc.processName == pkg || proc.pkgList?.contains(pkg) == true
            } == true
        } catch (e: Exception) {
            Log.w(TAG, "isWhatsappProcessRunning: ${e.message}")
            false
        }
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

        delay(2000)
        report(requestId, "macro_open_whatsapp", "running", "Aguardando tela inicial (conversas)…")
        var onHome = false
        repeat(HOME_WAIT_ATTEMPTS) {
            WhatsappRegistrationAccessibilityService.ensureWhatsappChatHome()
            if (WhatsappRegistrationAccessibilityService.isSafeForPairingCodeFetch()) {
                onHome = true
                return@repeat
            }
            delay(HOME_WAIT_MS)
        }

        if (!onHome && !WhatsappRegistrationAccessibilityService.isSafeForPairingCodeFetch()) {
            val msg = "WhatsApp aberto mas ainda em tela de pairing/menu"
            report(requestId, "macro_open_whatsapp", "failed", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val status = if (open.method == "intent") "completed_with_fallback" else "completed"
        val msg = if (WhatsappRegistrationAccessibilityService.isOnWhatsappChatHome()) {
            "WhatsApp na tela inicial (conversas)"
        } else {
            "WhatsApp aberto — fora do menu de dispositivos"
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
        private const val FORCE_STOP_ATTEMPTS = 5
        private const val FORCE_STOP_RETRY_MS = 900L
        private const val RECENTS_DISMISS_ATTEMPTS = 4
        private const val HOME_WAIT_ATTEMPTS = 10
        private const val HOME_WAIT_MS = 800L
    }
}
