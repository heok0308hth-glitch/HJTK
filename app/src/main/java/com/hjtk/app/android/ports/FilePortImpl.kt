package com.hjtk.app.android.ports

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.hjtk.app.core.ExportFile
import com.hjtk.app.core.FilePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exports live in the app's own external files dir, so no storage permission is
 * needed on any API level, and the files survive app restarts. Sharing goes
 * through a FileProvider declared in the manifest.
 */
class FilePortImpl(private val context: Context) : FilePort {

    private fun dir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val d = File(base, "exports")
        if (!d.exists()) d.mkdirs()
        return d
    }

    override suspend fun saveExport(name: String, content: String, mime: String): String = withContext(Dispatchers.IO) {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "export.txt" }
        val file = File(dir(), safe)
        file.writeText(content, Charsets.UTF_8)
        safe
    }

    override suspend fun listExports(): List<ExportFile> = withContext(Dispatchers.IO) {
        dir().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.map { ExportFile(it.name, it.length(), it.lastModified()) } ?: emptyList()
    }

    override suspend fun openExportsFolder() {
        withContext(Dispatchers.Main) {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", dir())
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                // No folder viewer: fall back to the system picker at the same place.
                val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(fallback) }
            }
        }
    }

    override suspend fun shareExport(name: String) {
        withContext(Dispatchers.Main) {
            val file = File(dir(), name)
            require(file.exists()) { "파일을 찾을 수 없습니다: $name" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (name.endsWith(".md")) "text/markdown" else "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "파일 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
