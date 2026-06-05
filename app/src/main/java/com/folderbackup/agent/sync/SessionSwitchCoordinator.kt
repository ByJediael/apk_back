package com.folderbackup.agent.sync

import android.content.Context
import android.content.Intent
import com.folderbackup.agent.backup.WhatsappSessionExporter
import com.folderbackup.agent.data.AppPreferences
import com.folderbackup.agent.network.BackupApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SessionSwitchCoordinator(private val context: Context) {
    private val preferences = AppPreferences(context)
    private val api = BackupApiClient()

    suspend fun clear(requestId: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val config = preferences.getConfigSnapshot()
        report(config, requestId, "(limpar)", "running", "Limpando sessão do WhatsApp…")
        val result = WhatsappSessionExporter.clearWhatsappSession()
        result.fold(
            onSuccess = {
                val msg = "Sessão do WhatsApp limpa. Cadastre um número ou restaure um backup."
                report(config, requestId, "(limpar)", "completed", msg)
                preferences.setLastStatus(msg)
                Result.success(msg)
            },
            onFailure = { err ->
                val msg = err.message ?: "Falha ao limpar"
                report(config, requestId, "(limpar)", "failed", msg)
                preferences.setLastStatus("Limpar sessão falhou: $msg")
                Result.failure(err)
            },
        )
    }

    suspend fun export(sessionLabel: String, requestId: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val config = preferences.getConfigSnapshot()
            reportExport(config, requestId, sessionLabel, "running", "Exportando sessão…")
            val result = WhatsappSessionExporter.exportSession(sessionLabel)
            result.fold(
                onSuccess = { export ->
                    val deNote = if (export.hasUserDe) " + user_de" else ""
                    val msg = "Sessão exportada (${export.fileCount} arquivos$deNote)"
                    reportExport(config, requestId, sessionLabel, "completed", msg)
                    preferences.setLastStatus(msg)
                    preferences.setLastSessionExportAt(System.currentTimeMillis())
                    Result.success(msg)
                },
                onFailure = { err ->
                    val msg = err.message ?: "Falha ao exportar"
                    reportExport(config, requestId, sessionLabel, "failed", msg)
                    preferences.setLastStatus("Exportar sessão falhou: $msg")
                    Result.failure(err)
                },
            )
        }

    suspend fun run(
        sessionLabel: String,
        requestId: String?,
        openWhatsapp: Boolean,
        sessionFolder: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        val config = preferences.getConfigSnapshot()
        if (config.apiToken.isBlank() || config.deviceId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("API não configurada no app"))
        }

        report(config, requestId, sessionLabel, "running", "Trocando para $sessionLabel…")

        val folderName = sessionFolder?.trim()?.takeIf { it.isNotBlank() }
            ?: WhatsappSessionExporter.findLatestSessionFolder(sessionLabel)
            ?: run {
                val msg = "Backup \"$sessionLabel\" não encontrado no celular"
                report(config, requestId, sessionLabel, "failed", msg)
                preferences.setLastStatus(msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

        val started = System.currentTimeMillis()
        val result = WhatsappSessionExporter.quickRestoreSession(folderName)
        result.fold(
            onSuccess = {
                if (openWhatsapp) {
                    launchWhatsapp()
                }
                val secs = (System.currentTimeMillis() - started) / 1000.0
                val msg = "${sessionLabel} ativo em ${"%.1f".format(secs)}s"
                report(config, requestId, sessionLabel, "completed", msg)
                preferences.setLastStatus(msg)
                Result.success(msg)
            },
            onFailure = { err ->
                val msg = err.message ?: "Falha na troca"
                report(config, requestId, sessionLabel, "failed", msg)
                preferences.setLastStatus("Troca remota falhou: $msg")
                Result.failure(err)
            },
        )
    }

    private suspend fun report(
        config: com.folderbackup.agent.data.AppConfig,
        requestId: String?,
        sessionLabel: String,
        status: String,
        message: String,
    ) {
        api.reportWhatsappSwitchStatus(
            config = config,
            requestId = requestId,
            sessionLabel = sessionLabel,
            status = status,
            message = message,
        ).onFailure { /* status remoto opcional */ }
    }

    private suspend fun reportExport(
        config: com.folderbackup.agent.data.AppConfig,
        requestId: String?,
        sessionLabel: String,
        status: String,
        message: String,
    ) {
        api.reportCommandResult(
            config = config,
            requestId = requestId,
            command = "export_session",
            sessionLabel = sessionLabel,
            phoneE164 = null,
            status = status,
            message = message,
        ).onFailure { /* status remoto opcional */ }
    }

    private fun launchWhatsapp() {
        val intent = context.packageManager
            .getLaunchIntentForPackage(WhatsappSessionExporter.WHATSAPP_PACKAGE)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
