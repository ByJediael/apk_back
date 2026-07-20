package com.folderbackup.agent.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.folderbackup.agent.service.BackupForegroundService
import com.folderbackup.agent.sync.SessionSwitchCoordinator
import com.folderbackup.agent.sync.WhatsappMacroCoordinator
import com.folderbackup.agent.sync.WhatsappMountCoordinator
import com.folderbackup.agent.sync.WhatsappRegistrationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DebugReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DEBUG) return
        val action = intent.getStringExtra("action") ?: return
        val requestId = intent.getStringExtra("request_id") ?: "debug-req"

        Log.i(TAG, "DebugReceiver received action: $action, request_id: $requestId")
        val appContext = context.applicationContext

        scope.launch {
            try {
                BackupForegroundService.start(appContext)
                when (action) {
                    "clear_session" -> {
                        SessionSwitchCoordinator(appContext).clear(requestId)
                    }
                    "uninstall_without_root" -> {
                        SessionSwitchCoordinator(appContext).uninstallWhatsappWithoutRoot(requestId)
                    }
                    "macro_install_whatsapp" -> {
                        WhatsappMacroCoordinator(appContext).installWhatsapp(requestId)
                    }
                    "macro_install_whatsapp_force" -> {
                        WhatsappMacroCoordinator(appContext).installWhatsapp(requestId, force = true)
                    }
                    "macro_home" -> {
                        WhatsappMacroCoordinator(appContext).goHome(requestId)
                    }
                    "macro_open_whatsapp" -> {
                        WhatsappMacroCoordinator(appContext).openWhatsapp(requestId)
                    }
                    "macro_navigate_link_phone" -> {
                        WhatsappMountCoordinator(appContext).navigateToLinkWithPhone(requestId)
                    }
                    "submit_pairing_code" -> {
                        val code = intent.getStringExtra("pairing_code").orEmpty()
                        val instance = intent.getStringExtra("evolution_instance")
                        WhatsappMountCoordinator(appContext).submitPairingCode(requestId, code, instance)
                    }
                    "register_whatsapp" -> {
                        val phone = intent.getStringExtra("phone_e164").orEmpty()
                        val label = intent.getStringExtra("session_label").orEmpty()
                        val displayName = intent.getStringExtra("display_name")
                        WhatsappRegistrationCoordinator(appContext).register(requestId, phone, label, displayName)
                    }
                    "submit_registration_code" -> {
                        val code = intent.getStringExtra("code").orEmpty()
                        WhatsappRegistrationCoordinator(appContext).submitCode(requestId, code)
                    }
                    else -> {
                        Log.w(TAG, "Ação desconhecida: $action")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar ação debug: ${e.message}", e)
            } finally {
                BackupForegroundService.stop(appContext)
            }
        }
    }

    companion object {
        private const val TAG = "BackupDebugReceiver"
        const val ACTION_DEBUG = "com.folderbackup.agent.ACTION_DEBUG_COMMAND"
    }
}
