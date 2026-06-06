package com.folderbackup.agent.backup

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WhatsappSessionExporter {
    const val WHATSAPP_PACKAGE = "com.whatsapp.w4b"
    const val SESSIONS_BASE_PATH = "/sdcard/Download/FolderBackupAgent/sessions"
    private const val MANIFEST_VERSION = 2

    private val sourcePathCandidates = listOf(
        "/data/user/0/$WHATSAPP_PACKAGE",
        "/data/data/$WHATSAPP_PACKAGE",
    )

    private val sourcePathDeCandidates = listOf(
        "/data/user_de/0/$WHATSAPP_PACKAGE",
    )

    data class ExportResult(
        val folderName: String,
        val absolutePath: String,
        val fileCount: Int,
        val hasUserDe: Boolean,
    )

    data class SessionInfo(
        val folderName: String,
        val absolutePath: String,
        val label: String?,
        val exportedAt: String?,
        val fileCount: Int?,
        val hasUserDe: Boolean = false,
        val manifestVersion: Int = 1,
    )

    fun exportSession(label: String): Result<ExportResult> = runCatching {
        if (!RootShell.isRootAvailable()) {
            error(
                "Root indisponível — abra Magisk, permita Folder Backup Agent e toque Testar root",
            )
        }

        val sourcePath = resolvePath(sourcePathCandidates, WHATSAPP_PACKAGE).getOrElse { err ->
            error(err.message ?: "Dados do WhatsApp Business inacessíveis")
        }
        val sourcePathDe = resolvePath(sourcePathDeCandidates, WHATSAPP_PACKAGE).getOrNull()
        val packageUid = resolvePackageUid(sourcePath)
            ?: error("Não foi possível obter UID do $WHATSAPP_PACKAGE")

        RootShell.runSu("mkdir -p ${RootShell.shellQuote(SESSIONS_BASE_PATH)}").getOrThrow()

        val folderName = buildFolderName(label)
        val destRoot = "$SESSIONS_BASE_PATH/$folderName"
        val destData = "$destRoot/data"
        val destDataDe = "$destRoot/data_de"

        stopWhatsApp()

        RootShell.runSu("mkdir -p ${RootShell.shellQuote(destData)}").getOrThrow()
        copyTree("$sourcePath/.", destData)

        var hasUserDe = false
        if (sourcePathDe != null && RootShell.directoryExists(sourcePathDe)) {
            RootShell.runSu("mkdir -p ${RootShell.shellQuote(destDataDe)}").getOrThrow()
            copyTree("$sourcePathDe/.", destDataDe)
            hasUserDe = RootShell.directoryExists(destDataDe) && countFilesUnder(destDataDe) > 0
        }

        RootShell.runSu("chmod -R a+rX ${RootShell.shellQuote(destRoot)}").getOrElse { }

        if (!RootShell.directoryExists(destData)) {
            error("Exportação não criou $destData — verifique espaço em disco")
        }

        val fileCount = countFilesUnder(destData) + if (hasUserDe) countFilesUnder(destDataDe) else 0
        if (fileCount == 0) {
            error("Cópia vazia — root pode não ter acesso aos dados do WhatsApp")
        }

        writeManifest(
            destRoot = destRoot,
            folderName = folderName,
            label = label,
            sourcePath = sourcePath,
            sourcePathDe = sourcePathDe,
            hasUserDe = hasUserDe,
            packageUid = packageUid,
            fileCount = fileCount,
        )

        ExportResult(
            folderName = folderName,
            absolutePath = destRoot,
            fileCount = fileCount,
            hasUserDe = hasUserDe,
        )
    }

    fun listExportedSessions(): List<SessionInfo> {
        val listCmd = RootShell.runSu("ls -1 ${RootShell.shellQuote(SESSIONS_BASE_PATH)} 2>/dev/null")
            .getOrNull() ?: return emptyList()

        return listCmd.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.startsWith("session_") }
            .mapNotNull { folderName ->
                val path = "$SESSIONS_BASE_PATH/$folderName"
                val dataDir = "$path/data"
                if (!RootShell.directoryExists(dataDir)) return@mapNotNull null

                val manifest = readManifest(path)
                SessionInfo(
                    folderName = folderName,
                    absolutePath = path,
                    label = manifest?.optString("label")?.takeIf { it.isNotBlank() },
                    exportedAt = manifest?.optString("exportedAt")?.takeIf { it.isNotBlank() },
                    fileCount = manifest?.optInt("fileCount")?.takeIf { it >= 0 },
                    hasUserDe = manifest?.optBoolean("hasUserDe") == true ||
                        RootShell.directoryExists("$path/data_de"),
                    manifestVersion = manifest?.optInt("manifestVersion") ?: 1,
                )
            }
            .sortedByDescending { it.exportedAt ?: it.folderName }
            .toList()
    }

    /**
     * Equivale a Configurações → Apps → WhatsApp Business → Limpar dados,
     * sem desinstalar. Os backups em Download/FolderBackupAgent/sessions/ permanecem.
     */
    fun clearWhatsappSession(): Result<Unit> = runCatching {
        if (!RootShell.isRootAvailable()) {
            error("Root indisponível — permita no Magisk")
        }

        // Só pm clear — o script antigo (rm + mkdir + chown manual) deixava o WA
        // num estado que trava o toque na tela de boas-vindas em alguns MIUI.
        val script = """
            set -e
            am force-stop $WHATSAPP_PACKAGE
            killall $WHATSAPP_PACKAGE 2>/dev/null || true
            sleep 0.5
            pm clear $WHATSAPP_PACKAGE
            am force-stop $WHATSAPP_PACKAGE
            echo OK
        """.trimIndent()

        val output = RootShell.runSuScript(script).getOrElse { err ->
            error("Limpar sessão falhou: ${err.message}")
        }
        if (!output.contains("OK")) {
            error("Limpar sessão incompleto: $output")
        }
        Thread.sleep(800)
    }

    fun restoreSession(folderName: String): Result<Unit> = runCatching {
        if (!RootShell.isRootAvailable()) {
            error("Root indisponível — permita no Magisk")
        }

        val sessionPath = "$SESSIONS_BASE_PATH/$folderName"
        val backupData = "$sessionPath/data"
        if (!RootShell.directoryExists(backupData)) {
            error("Backup não encontrado: $backupData")
        }

        val manifest = readManifest(sessionPath)
        val targetPath = manifest?.optString("sourcePath")?.takeIf { it.isNotBlank() }
            ?: resolvePath(sourcePathCandidates, WHATSAPP_PACKAGE).getOrThrow()
        val targetPathDe = manifest?.optString("sourcePathDe")?.takeIf { it.isNotBlank() }
            ?: resolvePath(sourcePathDeCandidates, WHATSAPP_PACKAGE).getOrNull()
        val packageUid = manifest?.optInt("packageUid")?.takeIf { it > 0 }
            ?: resolvePackageUid(targetPath)
            ?: error("Não foi possível obter UID do pacote para chown")
        val backupDataDe = "$sessionPath/data_de"
        val hasDataDeBackup = RootShell.directoryExists(backupDataDe) &&
            countFilesUnder(backupDataDe) > 0

        val liveUid = resolvePackageUid(targetPath)
            ?: error("Não foi possível obter UID atual do $WHATSAPP_PACKAGE")
        if (packageUid != liveUid) {
            android.util.Log.w(
                "WhatsappSessionExporter",
                "manifest packageUid=$packageUid mas UID atual=$liveUid — usando UID atual no restore",
            )
        }

        stopWhatsApp()

        restoreTree(backupData, targetPath, liveUid)

        if (hasDataDeBackup && targetPathDe != null) {
            restoreTree(backupDataDe, targetPathDe, liveUid)
        } else if (targetPathDe != null && RootShell.directoryExists(targetPathDe)) {
            wipeDirectoryContents(targetPathDe)
            applyOwnershipAndSelinux(targetPathDe, liveUid)
        }

        finalizeAfterRestore(targetPath)
        Unit
    }

    const val QUICK_SWITCH_TIM_LABEL = "numero-timv2"
    const val QUICK_SWITCH_CLARO_LABEL = "numero-clarov2"

    fun findLatestSessionFolder(label: String): String? {
        val safeLabel = label.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]"), "-")
            .trim('-')
        if (safeLabel.isEmpty()) return null

        val listCmd = RootShell.runSu(
            "ls -1 ${RootShell.shellQuote(SESSIONS_BASE_PATH)} 2>/dev/null | " +
                "grep '_$safeLabel' | sort | tail -n 1",
        ).getOrNull()?.trim()
        if (!listCmd.isNullOrBlank() && listCmd.startsWith("session_")) {
            val dataDir = "$SESSIONS_BASE_PATH/$listCmd/data"
            if (RootShell.directoryExists(dataDir)) return listCmd
        }

        return listExportedSessions()
            .filter { it.label.equals(label.trim(), ignoreCase = true) }
            .maxWithOrNull(compareBy({ it.exportedAt ?: "" }, { it.folderName }))
            ?.folderName
    }

    /**
     * Troca rápida para demo: um único script root (sem pm clear, ~3–5 s).
     */
    fun quickRestoreSession(folderName: String): Result<Unit> = runCatching {
        val sessionPath = "$SESSIONS_BASE_PATH/$folderName"
        val backupData = "$sessionPath/data"
        if (!RootShell.directoryExists(backupData)) {
            error("Backup não encontrado: $backupData")
        }
        val backupDataDe = "$sessionPath/data_de"
        val hasDataDe = RootShell.directoryExists(backupDataDe) &&
            countFilesUnder(backupDataDe) > 0

        val dataPath = "/data/user/0/$WHATSAPP_PACKAGE"
        val dataDePath = "/data/user_de/0/$WHATSAPP_PACKAGE"
        val qBackup = RootShell.shellQuote(sessionPath)
        val qData = RootShell.shellQuote(dataPath)
        val qDataDe = RootShell.shellQuote(dataDePath)

        val copyDe = if (hasDataDe) {
            "cp -a ${RootShell.shellQuote("$backupDataDe/.")} $qDataDe/"
        } else {
            "find $qDataDe -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true"
        }

        val script = """
            set -e
            am force-stop $WHATSAPP_PACKAGE
            killall $WHATSAPP_PACKAGE 2>/dev/null || true
            sleep 0.2
            UID=${'$'}(stat -c %u $qData)
            find $qData -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
            find $qDataDe -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
            mkdir -p $qData $qDataDe
            cp -a ${RootShell.shellQuote("$backupData/.")} $qData/
            $copyDe
            chown -R ${'$'}UID:${'$'}UID $qData $qDataDe
            chmod -R u+rwX,go-rwx $qData $qDataDe
            restorecon -RF $qData $qDataDe 2>/dev/null || true
            find $qData/databases -maxdepth 1 \( -name '*.db-wal' -o -name '*.db-shm' -o -name '*-journal' \) -delete 2>/dev/null || true
            rm -rf $qData/cache/* $qData/code_cache/* 2>/dev/null || true
            echo OK
        """.trimIndent()

        RootShell.runSuScript(script).getOrThrow()
    }

    private fun stopWhatsApp() {
        repeat(3) {
            RootShell.runSu("am force-stop $WHATSAPP_PACKAGE").getOrElse { }
            RootShell.runSu("killall $WHATSAPP_PACKAGE 2>/dev/null || true").getOrElse { }
        }
        Thread.sleep(1_500)
    }

    private fun restoreTree(backupDir: String, targetPath: String, packageUid: Int) {
        wipeDirectoryContents(targetPath)
        RootShell.runSu("mkdir -p ${RootShell.shellQuote(targetPath)}").getOrThrow()
        copyTree("$backupDir/.", targetPath)
        applyOwnershipAndSelinux(targetPath, packageUid)
    }

    /** WAL/SHM de export com app aberto podem travar o WA no logo após restore. */
    private fun finalizeAfterRestore(dataPath: String) {
        val databases = "$dataPath/databases"
        RootShell.runSu(
            "find ${RootShell.shellQuote(databases)} -maxdepth 1 " +
                "\\( -name '*.db-wal' -o -name '*.db-shm' -o -name '*-journal' \\) " +
                "-delete 2>/dev/null || true",
        ).getOrElse { }
        RootShell.runSu(
            "rm -rf ${RootShell.shellQuote("$dataPath/cache")}/* " +
                "${RootShell.shellQuote("$dataPath/code_cache")}/* 2>/dev/null || true",
        ).getOrElse { }
    }

    private fun wipeDirectoryContents(targetPath: String) {
        RootShell.runSu(
            "find ${RootShell.shellQuote(targetPath)} -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || " +
                "rm -rf ${RootShell.shellQuote("$targetPath/*")} ${RootShell.shellQuote("$targetPath/.[!.]*")} " +
                "${RootShell.shellQuote("$targetPath/..?*")} 2>/dev/null || true",
        ).getOrThrow()
    }

    private fun applyOwnershipAndSelinux(path: String, packageUid: Int) {
        val owner = "$packageUid:$packageUid"
        RootShell.runSu("chown -R $owner ${RootShell.shellQuote(path)}").getOrThrow()
        RootShell.runSu(
            "chmod -R u+rwX,go-rwx ${RootShell.shellQuote(path)}",
        ).getOrElse { }
        RootShell.runSu(
            "restorecon -RF ${RootShell.shellQuote(path)} 2>/dev/null || true",
        ).getOrElse { }
    }

    private fun copyTree(from: String, toDir: String) {
        RootShell.runSu(
            "cp -a ${RootShell.shellQuote(from)} ${RootShell.shellQuote(toDir)}/",
        ).getOrElse { err ->
            error("Cópia falhou ($from → $toDir): ${err.message}")
        }
    }

    private fun resolvePath(candidates: List<String>, packageName: String): Result<String> {
        for (path in candidates) {
            if (RootShell.directoryExists(path)) {
                return Result.success(path)
            }
        }
        val discovered = RootShell.runSu(
            "find /data -maxdepth 5 -type d -name '$packageName' 2>/dev/null | head -n 1",
        ).getOrNull()?.lineSequence()?.firstOrNull { it.contains(packageName) }?.trim()

        if (!discovered.isNullOrBlank() && RootShell.directoryExists(discovered)) {
            return Result.success(discovered)
        }

        return Result.failure(
            IllegalStateException(
                "WhatsApp Business inacessível. Magisk: permita Folder Backup Agent e " +
                    "ative mount master (su -mm).",
            ),
        )
    }

    private fun resolvePackageUid(dataPath: String): Int? {
        val fromStat = RootShell.runSu(
            "stat -c '%u' ${RootShell.shellQuote(dataPath)} 2>/dev/null",
        ).getOrNull()?.trim()?.toIntOrNull()
        if (fromStat != null && fromStat > 0) return fromStat

        val dumpsys = RootShell.runSu(
            "dumpsys package $WHATSAPP_PACKAGE 2>/dev/null",
        ).getOrNull() ?: return null

        val match = Regex("""(?:userId|appId)=(\d+)""").find(dumpsys)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun buildFolderName(label: String): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safeLabel = label.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]"), "-")
            .trim('-')
            .take(32)
        return if (safeLabel.isEmpty()) {
            "session_$timestamp"
        } else {
            "session_${timestamp}_$safeLabel"
        }
    }

    private fun countFilesUnder(path: String): Int {
        val output = RootShell.runSu(
            "find ${RootShell.shellQuote(path)} -type f 2>/dev/null | wc -l",
        ).getOrNull() ?: return 0
        return output.trim().toIntOrNull() ?: 0
    }

    private fun writeManifest(
        destRoot: String,
        folderName: String,
        label: String,
        sourcePath: String,
        sourcePathDe: String?,
        hasUserDe: Boolean,
        packageUid: Int,
        fileCount: Int,
    ) {
        val json = JSONObject()
            .put("manifestVersion", MANIFEST_VERSION)
            .put("folderName", folderName)
            .put("label", label.trim())
            .put("package", WHATSAPP_PACKAGE)
            .put("sourcePath", sourcePath)
            .put("sourcePathDe", sourcePathDe ?: JSONObject.NULL)
            .put("hasUserDe", hasUserDe)
            .put("packageUid", packageUid)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            .put("fileCount", fileCount)
            .toString()

        val manifestPath = "$destRoot/manifest.json"
        val escaped = json.replace("'", "'\\''")
        RootShell.runSu(
            "printf '%s' '$escaped' > ${RootShell.shellQuote(manifestPath)}",
        ).getOrThrow()
    }

    private fun readManifest(sessionPath: String): JSONObject? {
        val manifestPath = "$sessionPath/manifest.json"
        val content = RootShell.runSu("cat ${RootShell.shellQuote(manifestPath)} 2>/dev/null")
            .getOrNull() ?: return null
        return try {
            JSONObject(content)
        } catch (_: Exception) {
            null
        }
    }
}
