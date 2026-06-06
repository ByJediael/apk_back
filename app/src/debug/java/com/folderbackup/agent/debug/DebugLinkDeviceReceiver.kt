package com.folderbackup.agent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.folderbackup.agent.registration.WhatsappAutomationController
import com.folderbackup.agent.sync.WhatsappLinkDeviceCoordinator

/**
 * Debug only — testar navegação / pairing pelo USB:
 *
 * adb shell am broadcast -a com.folderbackup.agent.DEBUG_NAV_LINK
 * adb shell am broadcast -a com.folderbackup.agent.DEBUG_PAIR_CODE --es code ABCD1234
 * adb shell am broadcast -a com.folderbackup.agent.DEBUG_RELEASE_TOUCH
 */
class DebugLinkDeviceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            ACTION_NAV -> {
                Log.i(TAG, "DEBUG: navegar menu vincular")
                WhatsappAutomationController.runAutomation(app) {
                    WhatsappLinkDeviceCoordinator(app).navigateToLinkWithPhone("usb-debug-nav")
                }
            }
            ACTION_CODE -> {
                val code = intent.getStringExtra(EXTRA_CODE)?.trim().orEmpty()
                if (code.isEmpty()) {
                    Log.w(TAG, "DEBUG: --es code OBRIGATÓRIO")
                    return
                }
                Log.i(TAG, "DEBUG: digitar pairing code $code")
                WhatsappAutomationController.runAutomation(app) {
                    WhatsappLinkDeviceCoordinator(app).enterPairingCode(
                        requestId = "usb-debug-code",
                        pairingCode = code,
                        evolutionInstance = "wa-06",
                    )
                }
            }
            ACTION_RELEASE -> {
                Log.i(TAG, "DEBUG: liberar toque")
                WhatsappAutomationController.cancelAutomation(app)
            }
        }
    }

    companion object {
        private const val TAG = "DebugLinkDevice"
        const val ACTION_NAV = "com.folderbackup.agent.DEBUG_NAV_LINK"
        const val ACTION_CODE = "com.folderbackup.agent.DEBUG_PAIR_CODE"
        const val ACTION_RELEASE = "com.folderbackup.agent.DEBUG_RELEASE_TOUCH"
        const val EXTRA_CODE = "code"

        @Volatile
        private var registered: DebugLinkDeviceReceiver? = null

        fun register(app: Context) {
            if (registered != null) return
            val receiver = DebugLinkDeviceReceiver()
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_NAV)
                    addAction(ACTION_CODE)
                    addAction(ACTION_RELEASE)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            registered = receiver
        }
    }
}
