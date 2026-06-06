package com.folderbackup.agent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.folderbackup.agent.backup.RootShell
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.push.FcmTokenRegistrar
import com.folderbackup.agent.sync.SessionInventoryReporter
import com.folderbackup.agent.registration.AccessibilityHelper
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)

    val config = preferences.configFlow.stateIn(
        viewModelScope,
        // Eagerly: não zera config ao sair do app (WhileSubscribed(5s) deixava campos vazios na UI).
        SharingStarted.Eagerly,
        null,
    )

    var draftApiUrl: String = AppPreferences.DEFAULT_API_URL
    var draftToken: String = ""
    var draftDeviceId: String = ""

    init {
        viewModelScope.launch {
            syncDraftFromConfig(preferences.getConfigSnapshot())
        }
    }

    fun syncDraftFromConfig(cfg: com.folderbackup.agent.data.AppConfig? = config.value) {
        cfg ?: return
        draftApiUrl = cfg.apiBaseUrl
        draftToken = cfg.apiToken
        draftDeviceId = cfg.deviceId.ifBlank { "mi9-se" }
    }

    fun saveApiSettings() {
        viewModelScope.launch {
            val baseUrl = draftApiUrl.trim().trimEnd('/')
            val token = draftToken.trim()
            val deviceId = draftDeviceId.trim().ifBlank { "mi9-se" }
            draftApiUrl = baseUrl
            draftToken = token
            draftDeviceId = deviceId
            preferences.updateApi(baseUrl, token, deviceId)
            val status = withContext(Dispatchers.IO) {
                val config = preferences.getConfigSnapshot()
                val api = BackupApiClient()
                val ping = api.ping(config)
                ping.fold(
                    onSuccess = {
                        FcmTokenRegistrar.registerIfPossible(getApplication())
                        SessionInventoryReporter.syncIfConfigured(getApplication())
                        "Servidor OK — device $deviceId (veja slot na central)"
                    },
                    onFailure = { err ->
                        "Falha ao conectar: ${err.message ?: err}"
                    },
                )
            }
            preferences.setLastStatus(status)
        }
    }

    fun setUseRootEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setUseRootEnabled(enabled) }
    }

    fun isAccessibilityEnabled(): Boolean =
        AccessibilityHelper.isServiceEnabled(getApplication())

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(intent)
    }

    fun testRootAccess() {
        viewModelScope.launch {
            preferences.setLastStatus("Testando root…")
            val message = withContext(Dispatchers.IO) {
                if (RootShell.isRootAvailable(refresh = true)) {
                    "Root OK — Magisk concedeu superusuário"
                } else {
                    "Root indisponível — abra Magisk e permita Folder Backup Agent"
                }
            }
            preferences.setLastStatus(message)
        }
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
