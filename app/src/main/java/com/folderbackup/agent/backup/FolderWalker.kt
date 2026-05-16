package com.folderbackup.agent.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.security.MessageDigest

data class LocalFileEntry(
    val relativePath: String,
    val documentUri: Uri,
    val sizeBytes: Long,
    val lastModified: Long,
)

object FolderWalker {
    fun listFiles(context: Context, treeUri: Uri, prefix: String = ""): List<LocalFileEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<LocalFileEntry>()
        walk(context, root, prefix.trim('/'), result)
        return result
    }

    private fun walk(
        context: Context,
        dir: DocumentFile,
        relativePrefix: String,
        out: MutableList<LocalFileEntry>,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val rel = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
            if (child.isDirectory) {
                walk(context, child, rel, out)
            } else if (child.isFile) {
                out.add(
                    LocalFileEntry(
                        relativePath = rel,
                        documentUri = child.uri,
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                    ),
                )
            }
        }
    }

    fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
