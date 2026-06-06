package com.folderbackup.agent.sync

import android.content.Context
import android.util.Log
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.registration.AccessibilityHelper
import com.folderbackup.agent.registration.RegistrationNotifier
import com.folderbackup.agent.registration.WhatsappLinkDeviceState
import com.folderbackup.agent.registration.WhatsappRegistrationAccessibilityService
import com.folderbackup.agent.registration.WhatsappRegistrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WhatsappMountCoordinator(private val context: Context) {
    private val linkCoordinator = WhatsappLinkDeviceCoordinator(context)
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
        if (code.length < 8) {
            return@withContext Result.failure(IllegalStateException("pairing_code inválido (precisa 8 caracteres)"))
        }

        report(requestId, "submit_pairing_code", "running", "Digitando código $code…", evolutionInstance)
        RegistrationNotifier.show(context, "Evolution", "Código: $code")

        if (!WhatsappRegistrationAccessibilityService.isOnEnterCodeScreen() &&
            WhatsappLinkDeviceState.step != WhatsappLinkDeviceState.Step.ReadyForCode
        ) {
            val nav = navigateMenuOnly(requestId)
            if (nav.isFailure) return@withContext nav
        }

        linkCoordinator.enterPairingCode(requestId, code, evolutionInstance)
    }

    suspend fun navigateToLinkWithPhone(requestId: String?): Result<String> =
        linkCoordinator.navigateToLinkWithPhone(requestId)

    private suspend fun navigateMenuOnly(requestId: String?): Result<String> {
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()

        if (!WhatsappRegistrationAccessibilityService.pressHome()) {
            return Result.failure(IllegalStateException("HOME falhou"))
        }
        delay(800)

        val open = WhatsappOpenHelper.open(context)
        if (!open.ok) {
            return Result.failure(IllegalStateException("Não foi possível abrir WhatsApp"))
        }
        delay(1500)

        WhatsappLinkDeviceState.beginNavigation()
        val navigated = withTimeoutOrNull(NAV_TIMEOUT_MS) {
            while (true) {
                WhatsappRegistrationAccessibilityService.runLinkDeviceStep()
                when (WhatsappLinkDeviceState.step) {
                    WhatsappLinkDeviceState.Step.ReadyForCode,
                    WhatsappLinkDeviceState.Step.Done,
                    -> return@withTimeoutOrNull true
                    WhatsappLinkDeviceState.Step.Failed -> return@withTimeoutOrNull false
                    else -> delay(1200)
                }
            }
        }
        if (navigated != true) {
            WhatsappLinkDeviceState.reset()
            return Result.failure(IllegalStateException("Timeout na navegação do menu"))
        }
        return Result.success("Menu pronto")
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

    companion object {
        private const val TAG = "WaMountCoordinator"
        private const val NAV_TIMEOUT_MS = 90_000L
    }
}
