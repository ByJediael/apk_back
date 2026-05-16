package com.folderbackup.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "folder_backup_prefs")

data class WatchedFolder(
    val id: String,
    val label: String,
    val treeUri: String,
)

data class AppConfig(
    val apiBaseUrl: String,
    val apiToken: String,
    val deviceId: String,
    val pollIntervalMinutes: Int,
    val syncOnlyOnWifi: Boolean,
    val watchedFolders: List<WatchedFolder>,
    val lastStatusMessage: String,
    val lastSyncAtMillis: Long,
)

class AppPreferences(private val context: Context) {
    val configFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        AppConfig(
            apiBaseUrl = prefs[Keys.API_BASE_URL] ?: DEFAULT_API_URL,
            apiToken = prefs[Keys.API_TOKEN] ?: "",
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            pollIntervalMinutes = prefs[Keys.POLL_INTERVAL] ?: 15,
            syncOnlyOnWifi = prefs[Keys.SYNC_WIFI_ONLY] ?: true,
            watchedFolders = decodeFolders(prefs[Keys.WATCHED_FOLDERS] ?: "[]"),
            lastStatusMessage = prefs[Keys.LAST_STATUS] ?: "Aguardando configuração",
            lastSyncAtMillis = prefs[Keys.LAST_SYNC_AT]?.toLongOrNull() ?: 0L,
        )
    }

    suspend fun updateApi(baseUrl: String, token: String, deviceId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.API_BASE_URL] = baseUrl.trimEnd('/')
            prefs[Keys.API_TOKEN] = token.trim()
            prefs[Keys.DEVICE_ID] = deviceId.trim()
        }
    }

    suspend fun updatePollInterval(minutes: Int) {
        context.dataStore.edit { it[Keys.POLL_INTERVAL] = minutes.coerceIn(5, 360) }
    }

    suspend fun setSyncOnlyOnWifi(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SYNC_WIFI_ONLY] = enabled }
    }

    suspend fun addWatchedFolder(folder: WatchedFolder) {
        context.dataStore.edit { prefs ->
            val current = decodeFolders(prefs[Keys.WATCHED_FOLDERS] ?: "[]").toMutableList()
            if (current.none { it.treeUri == folder.treeUri }) {
                current.add(folder)
            }
            prefs[Keys.WATCHED_FOLDERS] = encodeFolders(current)
        }
    }

    suspend fun removeWatchedFolder(id: String) {
        context.dataStore.edit { prefs ->
            val current = decodeFolders(prefs[Keys.WATCHED_FOLDERS] ?: "[]")
                .filterNot { it.id == id }
            prefs[Keys.WATCHED_FOLDERS] = encodeFolders(current)
        }
    }

    suspend fun setLastStatus(message: String) {
        context.dataStore.edit {
            it[Keys.LAST_STATUS] = message
            it[Keys.LAST_SYNC_AT] = System.currentTimeMillis().toString()
        }
    }

    suspend fun getConfigSnapshot(): AppConfig = configFlow.first()

    private object Keys {
        val API_BASE_URL = stringPreferencesKey("api_base_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val POLL_INTERVAL = intPreferencesKey("poll_interval_minutes")
        val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val WATCHED_FOLDERS = stringPreferencesKey("watched_folders_json")
        val LAST_STATUS = stringPreferencesKey("last_status")
        val LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
    }

    companion object {
        const val DEFAULT_API_URL = "http://192.168.0.10:8080"

        private fun encodeFolders(folders: List<WatchedFolder>): String {
            val array = JSONArray()
            folders.forEach { folder ->
                array.put(
                    JSONObject()
                        .put("id", folder.id)
                        .put("label", folder.label)
                        .put("treeUri", folder.treeUri),
                )
            }
            return array.toString()
        }

        private fun decodeFolders(json: String): List<WatchedFolder> {
            val array = JSONArray(json)
            return buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        WatchedFolder(
                            id = obj.getString("id"),
                            label = obj.getString("label"),
                            treeUri = obj.getString("treeUri"),
                        ),
                    )
                }
            }
        }
    }
}
