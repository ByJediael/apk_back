package com.folderbackup.agent.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.folderbackup.agent.data.AppConfig
import com.folderbackup.agent.data.WatchedFolder
import com.folderbackup.agent.network.BackupApiClient
import com.folderbackup.agent.network.FileUploadRequest
import com.folderbackup.agent.network.JobProgressReport
import com.folderbackup.agent.network.JobType
import com.folderbackup.agent.network.RemoteJob
import java.io.File

class JobExecutor(
    private val context: Context,
    private val api: BackupApiClient = BackupApiClient(),
) {
    suspend fun execute(config: AppConfig, job: RemoteJob): JobProgressReport {
        return when (job.type) {
            JobType.BACKUP -> runBackup(config, job)
            JobType.RESTORE -> runRestore(config, job)
        }
    }

    private fun runBackup(config: AppConfig, job: RemoteJob): JobProgressReport {
        val folder = resolveFolder(config, job)
            ?: return failed(config, job.id, "Pasta não encontrada para o job")

        val treeUri = Uri.parse(folder.treeUri)
        val files = FolderWalker.listFiles(context, treeUri)
        var processed = 0
        var bytes = 0L

        report(config, job.id, "running", "Iniciando backup de ${files.size} arquivos", processed, bytes)

        for (entry in files) {
            val cacheFile = copyToCache(entry)
            val hash = FolderWalker.sha256(context, entry.documentUri)
            val upload = FileUploadRequest(
                jobId = job.id,
                folderId = folder.id,
                relativePath = entry.relativePath,
                sha256 = hash,
                sizeBytes = entry.sizeBytes,
                lastModified = entry.lastModified,
            )
            api.uploadFile(config, upload, cacheFile).getOrElse { err ->
                cacheFile.delete()
                return failed(config, job.id, "Falha no upload ${entry.relativePath}: ${err.message}")
            }
            cacheFile.delete()
            processed++
            bytes += entry.sizeBytes
            if (processed % 10 == 0) {
                report(config, job.id, "running", "Backup $processed/${files.size}", processed, bytes)
            }
        }

        val done = JobProgressReport(job.id, "completed", "Backup concluído", processed, bytes)
        api.reportJobProgress(config, done)
        return done
    }

    private fun runRestore(config: AppConfig, job: RemoteJob): JobProgressReport {
        val folder = resolveFolder(config, job)
            ?: return failed(config, job.id, "Pasta de destino não encontrada")

        val treeUri = Uri.parse(folder.treeUri)
        val manifest = api.fetchRestoreManifest(config, job).getOrElse { err ->
            return failed(config, job.id, "Manifest: ${err.message}")
        }

        var processed = 0
        var bytes = 0L
        report(config, job.id, "running", "Restaurando ${manifest.size} arquivos", processed, bytes)

        for (relativePath in manifest) {
            val temp = File(context.cacheDir, "restore-${System.nanoTime()}")
            api.downloadFile(config, job.id, relativePath, temp).getOrElse { err ->
                temp.delete()
                return failed(config, job.id, "Download $relativePath: ${err.message}")
            }
            writeToTree(treeUri, relativePath, temp)
            bytes += temp.length()
            temp.delete()
            processed++
        }

        val done = JobProgressReport(job.id, "completed", "Restore concluído", processed, bytes)
        api.reportJobProgress(config, done)
        return done
    }

    private fun resolveFolder(config: AppConfig, job: RemoteJob): WatchedFolder? {
        job.folderUri?.let { uri ->
            return config.watchedFolders.find { it.treeUri == uri }
        }
        job.folderId?.let { id ->
            return config.watchedFolders.find { it.id == id }
        }
        return config.watchedFolders.firstOrNull()
    }

    private fun copyToCache(entry: LocalFileEntry): File {
        val target = File(context.cacheDir, "upload-${System.nanoTime()}")
        context.contentResolver.openInputStream(entry.documentUri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun writeToTree(treeUri: Uri, relativePath: String, source: File) {
        val parts = relativePath.split("/")
        var current = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Árvore inválida")

        for (i in 0 until parts.size - 1) {
            val segment = parts[i]
            val next = current.findFile(segment)
                ?: current.createDirectory(segment)
                ?: error("Não foi possível criar pasta $segment")
            current = next
        }

        val fileName = parts.last()
        val existing = current.findFile(fileName)
        val target = existing ?: current.createFile("application/octet-stream", fileName)
            ?: error("Não foi possível criar arquivo $fileName")

        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        }
    }

    private fun report(
        config: AppConfig,
        jobId: String,
        status: String,
        message: String,
        files: Int,
        bytes: Long,
    ) {
        api.reportJobProgress(
            config,
            JobProgressReport(jobId, status, message, files, bytes),
        )
    }

    private fun failed(config: AppConfig, jobId: String, message: String): JobProgressReport {
        val report = JobProgressReport(jobId, "failed", message, 0, 0L)
        api.reportJobProgress(config, report)
        return report
    }
}
