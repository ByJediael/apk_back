package com.folderbackup.agent.push

import android.content.Context
import android.util.Log
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object FcmTokenRegistrar {
    private const val TAG = "FcmTokenRegistrar"

    suspend fun registerIfPossible(context: Context) {
        try {
            val token = fetchToken()
            if (com.folderbackup.agent.BuildConfig.DEBUG) {
                Log.i(TAG, "FCM token (registrar no servidor): $token")
            }
            registerWithToken(context, token)
        } catch (err: Exception) {
            Log.w(TAG, "FCM indisponível: ${err.message}")
        }
    }

    suspend fun registerWithToken(context: Context, token: String) {
        withContext(Dispatchers.IO) {
            val prefs = AppPreferences(context)
            val config = prefs.getConfigSnapshot()
            if (config.apiBaseUrl.isBlank() || config.apiToken.isBlank() || config.deviceId.isBlank()) {
                return@withContext
            }
            val result = BackupApiClient().registerFcmToken(config, token)
            result.fold(
                onSuccess = {
                    prefs.setLastStatus("Push FCM registrado no servidor")
                },
                onFailure = { err ->
                    Log.w(TAG, "Falha ao registrar FCM: ${err.message}")
                    prefs.setLastStatus("Push: falha ao registrar — ${err.message}")
                },
            )
        }
    }

    private suspend fun fetchToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}
