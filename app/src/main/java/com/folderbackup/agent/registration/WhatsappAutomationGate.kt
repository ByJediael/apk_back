package com.folderbackup.agent.registration

import android.util.Log
import com.folderbackup.agent.service.BackupForegroundService

/**
 * Só automatiza WhatsApp enquanto [BackupForegroundService] está ativo (comando FCM/debug).
 * Se o serviço parou e ainda há estado pendente, reseta tudo para não travar o toque do usuário.
 */
object WhatsappAutomationGate {
    private const val TAG = "WaAutomationGate"

    /** @return true se pode executar cliques/setText no WhatsApp agora */
    fun allowAutomation(): Boolean = BackupForegroundService.isRunning

    fun releaseOrphanedAutomation() {
        if (!hasPendingAutomation()) return
        Log.w(TAG, "Liberando automação órfã — serviço parado (toque manual no WA)")
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
        WhatsappMacroState.reset()
    }

    fun hasPendingAutomation(): Boolean =
        WhatsappLinkDeviceState.isActive() ||
            !WhatsappRegistrationState.isInactive() ||
            WhatsappMacroState.isActive()

    fun releaseAll() {
        WhatsappRegistrationState.reset()
        WhatsappLinkDeviceState.reset()
        WhatsappMacroState.reset()
    }
}
