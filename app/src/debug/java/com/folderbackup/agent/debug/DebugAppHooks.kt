package com.folderbackup.agent.debug

import android.app.Application
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.push.FcmTokenRegistrar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DebugAppHooks {
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        @JvmStatic
        fun onCreate(app: Application) {
            scope.launch(Dispatchers.IO) {
                val prefs = AppPreferences(app)
                val cfg = prefs.getConfigSnapshot()
                val deviceId = when {
                    cfg.deviceId.isBlank() -> "mi9-se"
                    cfg.deviceId.startsWith("device-") -> "mi9-se"
                    else -> cfg.deviceId
                }
                // USB + adb reverse tcp:8080 → backend no PC via 127.0.0.1
                val targetUrl = "http://127.0.0.1:8080"
                // Só preenche padrões se nunca configurou; não sobrescreve URL que você salvou (ex. 4G / IP público).
                val needsApi =
                    cfg.apiToken.isBlank() ||
                    cfg.apiBaseUrl.isBlank() ||
                    cfg.deviceId.isBlank()
                if (needsApi) {
                    val url = cfg.apiBaseUrl.ifBlank { targetUrl }
                    prefs.updateApi(url, cfg.apiToken.ifBlank { "12345678" }, deviceId)
                    prefs.setUseRootEnabled(true)
                    FcmTokenRegistrar.registerIfPossible(app)
                }
            }
        }
    }
}
