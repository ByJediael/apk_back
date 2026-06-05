package com.folderbackup.agent.backup

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootShell {
    private const val TAG = "RootShell"

    private val suCandidates = listOf(
        "su",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
    )

    /**
     * Magisk: [-mm] mount master — enxerga /data/data de outros apps.
     * Sem isso o app pode ter uid=0 mas "No such file" em /data/data/com.whatsapp.w4b.
     */
    private fun suArgVariants(suPath: String, command: String): List<Array<String>> = listOf(
        arrayOf(suPath, "-mm", "-c", command),
        arrayOf(suPath, "-mm", "0", "sh", "-c", command),
        arrayOf(suPath, "0", "sh", "-c", command),
        arrayOf(suPath, "-c", command),
        arrayOf(suPath, "0", "/system/bin/sh", "-c", command),
    )

    @Volatile
    private var cachedSuPath: String? = null

    @Volatile
    private var cachedVariantIndex: Int = 0

    fun clearSuCache() {
        cachedSuPath = null
        cachedVariantIndex = 0
    }

  /** @param refresh true só em "Testar root" — evita várias chamadas su (toast do Magisk). */
    fun isRootAvailable(refresh: Boolean = false): Boolean {
        if (refresh) clearSuCache()
        val idOk = runSu("id").getOrNull()?.contains("uid=0") == true
        if (!idOk) return false
        val canListData = runSu("ls /data/data 2>/dev/null | head -n 1")
            .getOrNull()
            ?.isNotBlank() == true
        if (!canListData) {
            Log.w(TAG, "uid=0 mas /data/data inacessível — use su -mm (Magisk mount master)")
        }
        return canListData
    }

    /** Uma única invocação su — ideal para troca rápida (menos toast do Magisk). */
    fun runSuScript(script: String): Result<String> = runSu(script)

    fun runSu(command: String): Result<String> {
        val cachedPath = cachedSuPath
        if (cachedPath != null) {
            val variants = suArgVariants(cachedPath, command)
            val idx = cachedVariantIndex.coerceIn(variants.indices)
            val fast = execSu(variants[idx])
            if (fast.isSuccess) return fast
            Log.w(TAG, "Cache su falhou, redescobrindo…")
            cachedSuPath = null
        }

        var lastError: Throwable? = null
        for (su in suCandidates) {
            val variants = suArgVariants(su, command)
            for ((index, args) in variants.withIndex()) {
                val result = execSu(args)
                if (result.isSuccess) {
                    cachedSuPath = su
                    cachedVariantIndex = index
                    return result
                }
                lastError = result.exceptionOrNull()
            }
        }
        return Result.failure(lastError ?: IllegalStateException("su não disponível"))
    }

    fun directoryExists(absolutePath: String): Boolean {
        val cmd = "[ -d ${shellQuote(absolutePath)} ] && echo __DIR_OK__"
        return runSu(cmd).getOrNull()?.contains("__DIR_OK__") == true
    }

    fun copyFromRoot(sourceAbsolutePath: String, destLocalFile: java.io.File): Result<Unit> {
        destLocalFile.parentFile?.mkdirs()
        val cmd = "cp ${shellQuote(sourceAbsolutePath)} ${shellQuote(destLocalFile.absolutePath)}"
        return runSu(cmd).map { }
    }

    fun copyToRoot(sourceLocalFile: java.io.File, destAbsolutePath: String): Result<Unit> {
        val parent = destAbsolutePath.substringBeforeLast('/', "")
        val mkdir = if (parent.isNotEmpty()) "mkdir -p ${shellQuote(parent)} && " else ""
        val cmd = "${mkdir}cp ${shellQuote(sourceLocalFile.absolutePath)} ${shellQuote(destAbsolutePath)}"
        return runSu(cmd).map { }
    }

    fun sha256File(absolutePath: String): Result<String> {
        val cmd =
            "sha256sum ${shellQuote(absolutePath)} 2>/dev/null || toybox sha256sum ${shellQuote(absolutePath)}"
        return runSu(cmd).map { output ->
            output.trim().substringBefore(' ').ifBlank { error("hash vazio") }
        }
    }

    fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun execSu(args: Array<String>): Result<String> {
        return try {
            val process = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
            }

            val finished = process.waitFor(300, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Result.failure(IllegalStateException("comando root expirou (timeout 5 min)"))
            }

            if (process.exitValue() != 0) {
                val msg = output.toString().trim()
                val short = if (msg.length > 500) msg.take(500) + "…" else msg
                Log.w(TAG, "su exit ${process.exitValue()}: $short")
                return Result.failure(
                    IllegalStateException("su exit ${process.exitValue()}: $short"),
                )
            }
            Result.success(output.toString().trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
