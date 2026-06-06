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

class WhatsappLinkDeviceCoordinator(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val api = BackupApiClient()

    /** Abre tela "Insira o código" (direto ou menu ⋮ como fallback). */
    suspend fun navigateToLinkWithPhone(requestId: String?): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureA11y(requestId)) {
            return@withContext Result.failure(IllegalStateException("Acessibilidade desativada"))
        }

        report(requestId, "macro_navigate_link_phone", "running", "Indo para vincular com número…", null)
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()

        if (!WhatsappRegistrationAccessibilityService.pressHome()) {
            fail(requestId, "Não foi possível ir para HOME")
            return@withContext Result.failure(IllegalStateException("HOME falhou"))
        }
        delay(800)

        val open = WhatsappOpenHelper.open(context)
        if (!open.ok) {
            fail(requestId, "Não foi possível abrir WhatsApp")
            return@withContext Result.failure(IllegalStateException("Não foi possível abrir WhatsApp"))
        }
        delay(1500)

        if (!runMenuNavigation()) {
            fail(requestId, "Timeout no menu Dispositivos conectados")
            return@withContext Result.failure(IllegalStateException("Timeout na navegação do menu"))
        }

        WhatsappLinkDeviceState.reset()

        val msg = "Tela pronta — Insira o código"
        report(requestId, "macro_navigate_link_phone", "completed", msg, null)
        preferences.setLastStatus(msg)
        RegistrationNotifier.show(context, "Vincular", msg)
        Result.success(msg)
    }

    suspend fun enterPairingCode(
        requestId: String?,
        pairingCode: String,
        evolutionInstance: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!ensureA11y(requestId)) {
            return@withContext Result.failure(IllegalStateException("Acessibilidade desativada"))
        }

        val code = pairingCode.replace(Regex("[^A-Za-z0-9]"), "").uppercase()
        if (code.length < 8) {
            return@withContext Result.failure(IllegalStateException("pairing_code inválido (precisa 8 caracteres)"))
        }

        report(requestId, "submit_pairing_code", "running", "Digitando código $code…", evolutionInstance)
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
        WhatsappRegistrationState.beginPairing(code)

        val alreadyOnScreen = WhatsappRegistrationAccessibilityService.isOnEnterCodeScreen()
        if (!alreadyOnScreen) {
            failPairing(
                requestId,
                "Tela Insira o código não aberta — rode macro_navigate_link_phone antes",
                evolutionInstance,
            )
            return@withContext Result.failure(IllegalStateException("Tela de código não aberta"))
        }

        val fieldsReady = withTimeoutOrNull(FIELDS_WAIT_MS) {
            while (WhatsappRegistrationAccessibilityService.countEnterCodeFields() < 4) {
                delay(400)
            }
            true
        } != true
        if (fieldsReady) {
            failPairing(requestId, "Campos do código não apareceram (acessibilidade)", evolutionInstance)
            return@withContext Result.failure(IllegalStateException("Campos do código não visíveis"))
        }
        delay(300)
        WhatsappLinkDeviceState.markReadyForCode()
        WhatsappLinkDeviceState.beginEnterCode()

        val ok = runEnterCodeLoop()

        if (!ok) {
            failPairing(requestId, "Timeout ao digitar pairing code", evolutionInstance)
            return@withContext Result.failure(IllegalStateException("Timeout ao digitar pairing code"))
        }

        val msg = "Pairing code $code enviado"
        report(requestId, "submit_pairing_code", "completed", msg, evolutionInstance)
        preferences.setLastStatus(msg)
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
        Result.success(msg)
    }

    /** Polling ativo — não depende só de eventos de acessibilidade. */
    private suspend fun runMenuNavigation(): Boolean {
        WhatsappLinkDeviceState.beginNavigation()
        return withTimeoutOrNull(NAV_TIMEOUT_MS) {
            while (true) {
                if (!AccessibilityHelper.isServiceEnabled(context)) {
                    WhatsappLinkDeviceState.markFailed("Acessibilidade desativada")
                    return@withTimeoutOrNull false
                }
                WhatsappRegistrationAccessibilityService.runLinkDeviceStep()
                when (WhatsappLinkDeviceState.step) {
                    WhatsappLinkDeviceState.Step.ReadyForCode,
                    WhatsappLinkDeviceState.Step.Done,
                    -> return@withTimeoutOrNull true
                    WhatsappLinkDeviceState.Step.Failed -> return@withTimeoutOrNull false
                    else -> delay(STEP_INTERVAL_MS)
                }
            }
        } == true
    }

    private suspend fun runEnterCodeLoop(): Boolean {
        return withTimeoutOrNull(CODE_TIMEOUT_MS) {
            while (true) {
                if (!AccessibilityHelper.isServiceEnabled(context)) {
                    WhatsappLinkDeviceState.markFailed("Acessibilidade desativada")
                    return@withTimeoutOrNull false
                }
                WhatsappRegistrationAccessibilityService.runLinkDeviceStep()
                when {
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.Done &&
                        WhatsappRegistrationState.phase == WhatsappRegistrationState.Phase.PairingDone ->
                        return@withTimeoutOrNull true
                    WhatsappLinkDeviceState.step == WhatsappLinkDeviceState.Step.Failed ||
                        WhatsappRegistrationState.phase == WhatsappRegistrationState.Phase.Failed ->
                        return@withTimeoutOrNull false
                    else -> delay(STEP_INTERVAL_MS)
                }
            }
        } == true
    }

    private suspend fun ensureA11y(requestId: String?): Boolean {
        if (AccessibilityHelper.isServiceEnabled(context)) return true
        val msg = "Ative Acessibilidade → WhatsApp Backup"
        report(requestId, "macro_navigate_link_phone", "failed", msg, null)
        return false
    }

    private suspend fun fail(requestId: String?, message: String) {
        WhatsappLinkDeviceState.markFailed(message)
        report(requestId, "macro_navigate_link_phone", "failed", message, null)
        preferences.setLastStatus(message)
        WhatsappLinkDeviceState.reset()
    }

    private suspend fun failPairing(requestId: String?, message: String, evolutionInstance: String?) {
        WhatsappRegistrationState.markFailed(message)
        WhatsappLinkDeviceState.markFailed(message)
        report(requestId, "submit_pairing_code", "failed", message, evolutionInstance)
        preferences.setLastStatus(message)
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
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
            sessionLabel = evolutionInstance ?: "link-device",
            phoneE164 = null,
            status = status,
            message = message,
        ).onFailure { Log.w(TAG, "command-result: ${it.message}") }
    }

    companion object {
        private const val TAG = "WaLinkDeviceCoord"
        private const val NAV_TIMEOUT_MS = 90_000L
        private const val CODE_TIMEOUT_MS = 60_000L
        private const val FIELDS_WAIT_MS = 12_000L
        private const val STEP_INTERVAL_MS = 1_200L
    }
}
