package com.folderbackup.agent.network

import org.json.JSONArray
import org.json.JSONObject

enum class JobType {
    BACKUP,
    RESTORE,
    ;

    companion object {
        fun from(raw: String): JobType? = entries.find { it.name.equals(raw, ignoreCase = true) }
    }
}

data class RemoteJob(
    val id: String,
    val type: JobType,
    val folderId: String?,
    val folderUri: String?,
    val backupId: String?,
    val incremental: Boolean,
) {
    companion object {
        fun fromJson(obj: JSONObject): RemoteJob? {
            val id = obj.optString("id").ifBlank { return null }
            val type = JobType.from(obj.optString("type")) ?: return null
            return RemoteJob(
                id = id,
                type = type,
                folderId = obj.optString("folder_id").ifBlank { null },
                folderUri = obj.optString("folder_uri").ifBlank { null },
                backupId = obj.optString("backup_id").ifBlank { null },
                incremental = obj.optBoolean("incremental", true),
            )
        }
    }
}

data class FileUploadRequest(
    val jobId: String,
    val folderId: String,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

data class JobProgressReport(
    val jobId: String,
    val status: String,
    val message: String,
    val filesProcessed: Int,
    val bytesProcessed: Long,
)

fun List<RemoteJob>.toDebugString(): String = joinToString { "${it.type}:${it.id}" }

fun parseJobsResponse(body: String): List<RemoteJob> {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return emptyList()
    return when {
        trimmed.startsWith("[") -> {
            val array = JSONArray(trimmed)
            buildList {
                for (i in 0 until array.length()) {
                    val job = RemoteJob.fromJson(array.getJSONObject(i)) ?: continue
                    add(job)
                }
            }
        }
        trimmed.startsWith("{") -> {
            val root = JSONObject(trimmed)
            val array = root.optJSONArray("commands")
                ?: root.optJSONArray("jobs")
                ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val job = RemoteJob.fromJson(array.getJSONObject(i)) ?: continue
                    add(job)
                }
            }
        }
        else -> emptyList()
    }
}
