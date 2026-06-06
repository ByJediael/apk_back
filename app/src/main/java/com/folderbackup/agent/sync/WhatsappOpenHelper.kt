package com.folderbackup.agent.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import com.folderbackup.agent.backup.RootShell
import com.folderbackup.agent.backup.WhatsappSessionExporter
import com.folderbackup.agent.data.AppPreferences
import kotlinx.coroutines.delay

/** Abre WhatsApp Business (root no MIUI) e tela "Insira o código". */
object WhatsappOpenHelper {
    private const val WA_HOME = "${WhatsappSessionExporter.WHATSAPP_PACKAGE}/com.whatsapp.home.ui.HomeActivity"
    private const val WA_ENTER_CODE =
        "${WhatsappSessionExporter.WHATSAPP_PACKAGE}/com.whatsapp.companiondevice.LinkedDevicesEnterCodeActivity"

    suspend fun open(context: Context): OpenResult {
        if (launchComponent(context, WA_HOME) || launchViaIntent(context)) {
            Log.i(TAG, "WhatsApp Home aberto")
            delay(1500)
            return OpenResult(method = "home")
        }
        Log.e(TAG, "Falha ao abrir WhatsApp — ative Root no app / Magisk")
        return OpenResult(method = "failed", ok = false)
    }

    /** Abre direto a tela "Insira o código" (LinkedDevicesEnterCodeActivity). */
    suspend fun openLinkedDevicesEnterCode(context: Context): Boolean {
        if (!launchComponent(context, WA_ENTER_CODE)) {
            open(context)
            delay(800)
            if (!launchComponent(context, WA_ENTER_CODE)) return false
        }
        Log.i(TAG, "Tela de código aberta")
        delay(2000)
        return true
    }

    private suspend fun launchComponent(context: Context, component: String): Boolean {
        val prefs = AppPreferences(context).getConfigSnapshot()
        if (prefs.useRootEnabled || RootShell.isRootAvailable()) {
            val cmd = "am start -W -n $component"
            RootShell.runSu(cmd)
                .onSuccess { Log.i(TAG, "root ok: $component") }
                .onFailure { Log.w(TAG, "root falhou: ${it.message}") }
                .isSuccess
                .let { if (it) return true }
        }
        return false
    }

    fun launchViaIntent(context: Context): Boolean {
        val intent = context.packageManager
            .getLaunchIntentForPackage(WhatsappSessionExporter.WHATSAPP_PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startActivity WA: ${e.message}")
            false
        }
    }

    data class OpenResult(val method: String, val ok: Boolean = true)

    private const val TAG = "WhatsappOpenHelper"
}
