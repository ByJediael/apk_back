package com.folderbackup.agent.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.data.WatchedFolder
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.worker.SyncWorkerScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val api = BackupApiClient()

    val config = preferences.configFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    var draftApiUrl: String = AppPreferences.DEFAULT_API_URL
    var draftToken: String = ""
    var draftDeviceId: String = ""

    fun syncDraftFromConfig() {
        config.value?.let { cfg ->
            draftApiUrl = cfg.apiBaseUrl
            draftToken = cfg.apiToken
            draftDeviceId = cfg.deviceId.ifBlank {
                "device-${UUID.randomUUID().toString().take(8)}"
            }
        }
    }

    fun saveApiSettings() {
        viewModelScope.launch {
            preferences.updateApi(draftApiUrl, draftToken, draftDeviceId)
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { preferences.setSyncOnlyOnWifi(enabled) }
    }

    fun testApiConnection() {
        viewModelScope.launch {
            val cfg = preferences.getConfigSnapshot().copy(
                apiBaseUrl = draftApiUrl.trimEnd('/'),
                apiToken = draftToken,
                deviceId = draftDeviceId,
            )
            val result = api.ping(cfg)
            preferences.setLastStatus(
                result.fold(
                    onSuccess = { body -> "API OK: $body" },
                    onFailure = { err -> "API erro: ${err.message}" },
                ),
            )
        }
    }

    fun syncNow() {
        SyncWorkerScheduler.runNow(getApplication())
        viewModelScope.launch {
            preferences.setLastStatus("Sincronização manual enfileirada")
        }
    }

    fun onFolderPicked(label: String, treeUri: Uri) {
        val context = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)

        viewModelScope.launch {
            preferences.addWatchedFolder(
                WatchedFolder(
                    id = UUID.randomUUID().toString(),
                    label = label.ifBlank { treeUri.lastPathSegment ?: "Pasta" },
                    treeUri = treeUri.toString(),
                ),
            )
        }
    }

    fun removeFolder(id: String) {
        viewModelScope.launch { preferences.removeWatchedFolder(id) }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(application) as T
                }
            }
    }
}
