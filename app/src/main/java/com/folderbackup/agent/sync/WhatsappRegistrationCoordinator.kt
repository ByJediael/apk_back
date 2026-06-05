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

class WhatsappRegistrationCoordinator(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val api = BackupApiClient()

    suspend fun register(
        requestId: String?,
        phoneE164: String,
        sessionLabel: String,
        displayName: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!AccessibilityHelper.isServiceEnabled(context)) {
            val msg = "Ative Acessibilidade → WhatsApp Backup em Configurações"
            report(requestId, "register_whatsapp", sessionLabel, phoneE164, "failed", msg)
            RegistrationNotifier.show(context, "Cadastro automático", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        report(requestId, "register_whatsapp", sessionLabel, phoneE164, "running", "Iniciando cadastro…")
        WhatsappRegistrationState.begin(phoneE164, sessionLabel, displayName)
        launchWhatsapp()

        val phoneOk = waitForPhase(
            target = WhatsappRegistrationState.Phase.WaitCode,
            timeoutMs = PHONE_TIMEOUT_MS,
            alsoAccept = setOf(WhatsappRegistrationState.Phase.EnterCode),
        )
        if (!phoneOk) {
            val msg = WhatsappRegistrationState.error ?: "Timeout ao digitar número no WhatsApp"
            WhatsappRegistrationState.markFailed(msg)
            report(requestId, "register_whatsapp", sessionLabel, phoneE164, "failed", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        report(
            requestId,
            "register_whatsapp",
            sessionLabel,
            phoneE164,
            "running",
            "Número enviado — aguardando código SMS…",
        )
        Result.success("Aguardando código SMS")
    }

    suspend fun submitCode(requestId: String?, code: String): Result<String> = withContext(Dispatchers.IO) {
        val label = WhatsappRegistrationState.sessionLabel ?: "numero-novo"
        val phone = WhatsappRegistrationState.phoneE164

        report(requestId, "submit_registration_code", label, phone, "running", "Aplicando código…")
        WhatsappRegistrationState.submitCode(code)
        launchWhatsapp()

        val profileOk = waitForPhase(
            target = WhatsappRegistrationState.Phase.ProfileSetup,
            timeoutMs = CODE_TIMEOUT_MS,
            alsoAccept = setOf(WhatsappRegistrationState.Phase.Done),
        )
        if (!profileOk) {
            val msg = "Timeout ao preencher código no WhatsApp"
            report(requestId, "submit_registration_code", label, phone, "failed", msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        if (WhatsappRegistrationState.phase != WhatsappRegistrationState.Phase.Done) {
            val doneOk = waitForPhase(
                target = WhatsappRegistrationState.Phase.Done,
                timeoutMs = PROFILE_TIMEOUT_MS,
            )
            if (!doneOk) {
                val msg = "Timeout na configuração do perfil"
                report(requestId, "submit_registration_code", label, phone, "failed", msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }
        }

        exportAfterRegister(requestId, label, phone)
    }

    private suspend fun exportAfterRegister(
        requestId: String?,
        sessionLabel: String,
        phone: String?,
    ): Result<String> {
        report(requestId, "export_session", sessionLabel, phone, "running", "Exportando sessão…")
        val result = WhatsappSessionExporter.exportSession(sessionLabel)
        return result.fold(
            onSuccess = { export ->
                val deNote = if (export.hasUserDe) " + user_de" else ""
                val msg = "Cadastro e export OK (${export.fileCount} arquivos$deNote)"
                report(requestId, "register_whatsapp", sessionLabel, phone, "completed", msg)
                preferences.setLastStatus(msg)
                WhatsappRegistrationState.reset()
                RegistrationNotifier.show(context, "Cadastro concluído", msg)
                Result.success(msg)
            },
            onFailure = { err ->
                val msg = err.message ?: "Falha ao exportar"
                report(requestId, "export_session", sessionLabel, phone, "failed", msg)
                preferences.setLastStatus(msg)
                Result.failure(err)
            },
        )
    }

    private suspend fun waitForPhase(
        target: WhatsappRegistrationState.Phase,
        timeoutMs: Long,
        alsoAccept: Set<WhatsappRegistrationState.Phase> = emptySet(),
    ): Boolean {
        val accepted = alsoAccept + target + WhatsappRegistrationState.Phase.Done + WhatsappRegistrationState.Phase.PairingDone
        val ok = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val phase = WhatsappRegistrationState.phase
                if (phase == WhatsappRegistrationState.Phase.Failed) return@withTimeoutOrNull false
                if (phase in accepted) return@withTimeoutOrNull true
                delay(500)
            }
        }
        return ok == true
    }

    private suspend fun report(
        requestId: String?,
        command: String,
        sessionLabel: String,
        phoneE164: String?,
        status: String,
        message: String,
    ) {
        val config = preferences.getConfigSnapshot()
        preferences.setLastStatus(message)
        api.reportCommandResult(
            config = config,
            requestId = requestId,
            command = command,
            sessionLabel = sessionLabel,
            phoneE164 = phoneE164,
            status = status,
            message = message,
        ).onFailure { Log.w(TAG, "command-result falhou: ${it.message}") }
    }

    private fun launchWhatsapp() {
        val intent = context.packageManager
            .getLaunchIntentForPackage(WhatsappSessionExporter.WHATSAPP_PACKAGE)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "WaRegCoordinator"
        private const val PHONE_TIMEOUT_MS = 120_000L
        private const val CODE_TIMEOUT_MS = 180_000L
        private const val PROFILE_TIMEOUT_MS = 120_000L
    }
}
