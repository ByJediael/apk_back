package com.folderbackup.agent.push

import android.util.Log
import com.folderbackup.agent.service.BackupForegroundService
import com.folderbackup.agent.sync.SessionSwitchCoordinator
import com.folderbackup.agent.sync.WhatsappMacroCoordinator
import com.folderbackup.agent.sync.WhatsappMountCoordinator
import com.folderbackup.agent.sync.WhatsappRegistrationCoordinator
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BackupFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["action"]) {
            "clear_session" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                Log.i(TAG, "FCM clear_session")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        SessionSwitchCoordinator(applicationContext).clear(requestId)
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "switch_session" -> {
                val label = message.data["session_label"]?.trim().orEmpty()
                if (label.isEmpty()) {
                    Log.w(TAG, "FCM switch_session sem session_label")
                    return
                }
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                val sessionFolder = message.data["session_folder"]?.takeIf { it.isNotBlank() }
                val openWa = message.data["open_whatsapp"] != "0"
                Log.i(TAG, "FCM switch_session — $label")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        SessionSwitchCoordinator(applicationContext).run(
                            sessionLabel = label,
                            requestId = requestId,
                            openWhatsapp = openWa,
                            sessionFolder = sessionFolder,
                        )
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "export_session" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                val label = message.data["session_label"]?.trim().orEmpty()
                if (label.isEmpty()) {
                    Log.w(TAG, "FCM export_session sem session_label")
                    return
                }
                Log.i(TAG, "FCM export_session — $label")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        SessionSwitchCoordinator(applicationContext).export(label, requestId)
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "register_whatsapp" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                val phone = message.data["phone_e164"]?.trim().orEmpty()
                val label = message.data["session_label"]?.trim().orEmpty()
                val displayName = message.data["display_name"]?.trim()?.takeIf { it.isNotBlank() }
                if (phone.isEmpty() || label.isEmpty()) {
                    Log.w(TAG, "FCM register_whatsapp incompleto")
                    return
                }
                Log.i(TAG, "FCM register_whatsapp — $phone")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        WhatsappRegistrationCoordinator(applicationContext).register(
                            requestId = requestId,
                            phoneE164 = phone,
                            sessionLabel = label,
                            displayName = displayName,
                        )
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "submit_registration_code" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                val code = message.data["code"]?.trim().orEmpty()
                if (code.isEmpty()) {
                    Log.w(TAG, "FCM submit_registration_code sem code")
                    return
                }
                Log.i(TAG, "FCM submit_registration_code")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        WhatsappRegistrationCoordinator(applicationContext).submitCode(requestId, code)
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "macro_home" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                Log.i(TAG, "FCM macro_home")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        WhatsappMacroCoordinator(applicationContext).goHome(requestId)
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "macro_open_whatsapp" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                Log.i(TAG, "FCM macro_open_whatsapp")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        WhatsappMacroCoordinator(applicationContext).openWhatsapp(requestId)
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "macro_navigate_link_phone" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                Log.i(TAG, "FCM macro_navigate_link_phone")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        WhatsappMountCoordinator(applicationContext).navigateToLinkWithPhone(requestId)
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "submit_pairing_code" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                val pairingCode = message.data["pairing_code"]?.trim().orEmpty()
                val evoInstance = message.data["evolution_instance"]?.trim()?.takeIf { it.isNotBlank() }
                if (pairingCode.isEmpty()) {
                    Log.w(TAG, "FCM submit_pairing_code sem pairing_code")
                    return
                }
                Log.i(TAG, "FCM submit_pairing_code")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        WhatsappMountCoordinator(applicationContext).submitPairingCode(
                            requestId = requestId,
                            pairingCode = pairingCode,
                            evolutionInstance = evoInstance,
                        )
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            "macro_install_whatsapp" -> {
                val requestId = message.data["request_id"]?.takeIf { it.isNotBlank() }
                Log.i(TAG, "FCM macro_install_whatsapp")
                scope.launch {
                    try {
                        BackupForegroundService.start(applicationContext)
                        WhatsappMacroCoordinator(applicationContext).installWhatsapp(requestId)
                    } finally {
                        BackupForegroundService.stop(applicationContext)
                    }
                }
            }
            else -> {
                if (message.data["action"] != null) {
                    Log.w(TAG, "FCM ação ignorada: ${message.data["action"]}")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "Novo token FCM")
        scope.launch {
            FcmTokenRegistrar.registerWithToken(applicationContext, token)
        }
    }

    companion object {
        private const val TAG = "BackupFCM"
    }
}
