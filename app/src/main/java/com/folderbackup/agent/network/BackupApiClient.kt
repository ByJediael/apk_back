package com.folderbackup.agent.network

import com.folderbackup.agent.data.AppConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class BackupApiClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

    fun fetchPendingJobs(config: AppConfig): Result<List<RemoteJob>> = runCatching {
        val url = "${config.apiBaseUrl}/api/v1/devices/${config.deviceId}/commands"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", bearer(config.apiToken))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${response.message}")
            }
            parseJobsResponse(response.body?.string().orEmpty())
        }
    }

    fun reportJobProgress(config: AppConfig, report: JobProgressReport): Result<Unit> = runCatching {
        val payload = JSONObject()
            .put("job_id", report.jobId)
            .put("status", report.status)
            .put("message", report.message)
            .put("files_processed", report.filesProcessed)
            .put("bytes_processed", report.bytesProcessed)
        postJson(config, "/api/v1/jobs/${report.jobId}/progress", payload)
    }

    fun uploadFile(
        config: AppConfig,
        meta: FileUploadRequest,
        file: File,
    ): Result<Unit> = runCatching {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("job_id", meta.jobId)
            .addFormDataPart("folder_id", meta.folderId)
            .addFormDataPart("relative_path", meta.relativePath)
            .addFormDataPart("sha256", meta.sha256)
            .addFormDataPart("size_bytes", meta.sizeBytes.toString())
            .addFormDataPart("last_modified", meta.lastModified.toString())
            .addFormDataPart(
                "file",
                file.name,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url("${config.apiBaseUrl}/api/v1/upload")
            .post(body)
            .header("Authorization", bearer(config.apiToken))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Upload falhou HTTP ${response.code}")
            }
        }
    }

    fun downloadFile(config: AppConfig, jobId: String, relativePath: String, target: File): Result<Unit> =
        runCatching {
            val encodedPath = relativePath.split("/").joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
            }
            val url =
                "${config.apiBaseUrl}/api/v1/jobs/$jobId/files?path=$encodedPath"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", bearer(config.apiToken))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download falhou HTTP ${response.code}")
                }
                val body = response.body ?: error("Corpo vazio")
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
        }

    fun fetchRestoreManifest(config: AppConfig, job: RemoteJob): Result<List<String>> = runCatching {
        val backupId = job.backupId ?: error("backup_id obrigatório")
        val url = "${config.apiBaseUrl}/api/v1/backups/$backupId/manifest"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", bearer(config.apiToken))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val files = root.optJSONArray("files") ?: JSONArray()
            buildList {
                for (i in 0 until files.length()) {
                    val path = files.optString(i)
                    if (path.isNotBlank()) add(path)
                }
            }
        }
    }

    fun reportWhatsappSwitchStatus(
        config: AppConfig,
        requestId: String?,
        sessionLabel: String,
        status: String,
        message: String,
    ): Result<Unit> = runCatching {
        val payload = JSONObject()
            .put("request_id", requestId ?: JSONObject.NULL)
            .put("session_label", sessionLabel)
            .put("status", status)
            .put("message", message)
        postJson(
            config,
            "/api/v1/devices/${config.deviceId}/whatsapp/switch-status",
            payload,
        )
    }

    fun reportCommandResult(
        config: AppConfig,
        requestId: String?,
        command: String,
        sessionLabel: String,
        phoneE164: String?,
        status: String,
        message: String,
    ): Result<Unit> = runCatching {
        val payload = JSONObject()
            .put("request_id", requestId ?: JSONObject.NULL)
            .put("command", command)
            .put("session_label", sessionLabel)
            .put("status", status)
            .put("message", message)
        if (!phoneE164.isNullOrBlank()) {
            payload.put("phone_e164", phoneE164)
        }
        postJson(
            config,
            "/api/v1/devices/${config.deviceId}/command-result",
            payload,
        )
    }

    fun registerFcmToken(config: AppConfig, fcmToken: String): Result<Unit> = runCatching {
        val payload = JSONObject().put("fcm_token", fcmToken)
        val request = Request.Builder()
            .url("${config.apiBaseUrl}/api/v1/devices/${config.deviceId}/fcm-token")
            .put(payload.toString().toRequestBody(jsonMediaType))
            .header("Authorization", bearer(config.apiToken))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${response.message}")
            }
        }
    }

    fun reportSessionInventory(config: AppConfig, sessions: JSONArray): Result<Unit> = runCatching {
        val payload = JSONObject().put("sessions", sessions)
        val request = Request.Builder()
            .url("${config.apiBaseUrl}/api/v1/devices/${config.deviceId}/sessions")
            .put(payload.toString().toRequestBody(jsonMediaType))
            .header("Authorization", bearer(config.apiToken))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${response.message}")
            }
        }
    }

    fun ping(config: AppConfig): Result<String> = runCatching {
        val url = "${config.apiBaseUrl}/api/v1/health"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", bearer(config.apiToken))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string() ?: "ok"
        }
    }

    fun testN8nWebhook(config: AppConfig): Result<N8nTestResult> = runCatching {
        val request = Request.Builder()
            .url("${config.apiBaseUrl}/api/v1/admin/n8n/test")
            .post("{}".toRequestBody(jsonMediaType))
            .header("Authorization", bearer(config.apiToken))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
            val result = N8nTestResult(
                ok = json.optBoolean("ok", false),
                n8nConfigured = json.optBoolean("n8n_configured", false),
                n8nOk = json.optBoolean("n8n_ok", false),
                n8nStatus = json.opt("n8n_status")?.let { v ->
                    if (v is Number) v.toInt() else null
                },
                error = json.optString("error").ifBlank { null },
            )
            if (!response.isSuccessful && body.isBlank()) {
                error("HTTP ${response.code}")
            }
            result
        }
    }

    private fun postJson(config: AppConfig, path: String, json: JSONObject) {
        val request = Request.Builder()
            .url("${config.apiBaseUrl}$path")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("Authorization", bearer(config.apiToken))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
        }
    }

    private fun bearer(token: String): String =
        if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
}
