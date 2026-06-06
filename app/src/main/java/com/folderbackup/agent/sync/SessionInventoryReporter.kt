package com.folderbackup.agent.sync

import android.content.Context
import android.util.Log
import com.folderbackup.agent.backup.WhatsappSessionExporter
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object SessionInventoryReporter {
    private const val TAG = "SessionInventory"

    suspend fun syncIfConfigured(context: Context) {
        withContext(Dispatchers.IO) {
            val config = AppPreferences(context).getConfigSnapshot()
            if (config.apiBaseUrl.isBlank() || config.apiToken.isBlank() || config.deviceId.isBlank()) {
                return@withContext
            }
            val sessions = WhatsappSessionExporter.listExportedSessions()
            val array = JSONArray()
            for (s in sessions) {
                array.put(
                    JSONObject()
                        .put("folder_name", s.folderName)
                        .put("label", s.label ?: JSONObject.NULL)
                        .put("exported_at", s.exportedAt ?: JSONObject.NULL)
                        .put("file_count", s.fileCount ?: JSONObject.NULL)
                        .put("has_user_de", s.hasUserDe)
                        .put("manifest_version", s.manifestVersion),
                )
            }
            BackupApiClient().reportSessionInventory(config, array).fold(
                onSuccess = {
                    Log.i(TAG, "Inventário enviado: ${sessions.size} sessão(ões)")
                },
                onFailure = { err ->
                    Log.w(TAG, "Falha ao enviar inventário: ${err.message}")
                },
            )
        }
    }
}
