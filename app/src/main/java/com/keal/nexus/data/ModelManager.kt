package com.keal.nexus.data

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

data class DownloadSnapshot(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long
) {
    val progress: Float
        get() = if (totalBytes > 0L) downloadedBytes.toFloat() / totalBytes.toFloat() else 0f
}

object ModelManager {
    private fun internalDirectory(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    private fun downloadDirectory(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "models").apply { mkdirs() }

    fun modelFile(context: Context, model: ModelSpec): File =
        File(downloadDirectory(context), model.fileName)

    fun installedModels(context: Context): List<File> {
        val files = (internalDirectory(context).listFiles()?.toList() ?: emptyList()) +
            (downloadDirectory(context).listFiles()?.toList() ?: emptyList())
        return files.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
    }

    fun enqueueDownload(context: Context, model: ModelSpec): Long {
        val request = DownloadManager.Request(Uri.parse(model.downloadUrl))
            .setTitle(model.name)
            .setDescription("Downloading " + model.quantization + " GGUF model")
            .setMimeType("application/octet-stream")
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "models/" + model.fileName
            )
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    fun queryDownload(context: Context, downloadId: Long): DownloadSnapshot? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor: Cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return DownloadSnapshot(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloadedBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            )
        }
    }

    fun importModel(context: Context, uri: Uri): File {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        } ?: "imported-model.gguf"

        require(displayName.endsWith(".gguf", ignoreCase = true)) {
            "Please select a GGUF model file."
        }

        val safeName = displayName.substringAfterLast("/")
        val destination = File(internalDirectory(context), safeName)
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Android could not open the selected file.")
        input.use { source ->
            destination.outputStream().buffered().use { output -> source.copyTo(output) }
        }
        return destination
    }

    fun deleteModel(context: Context, fileName: String): Boolean {
        val internal = File(internalDirectory(context), fileName)
        val external = File(downloadDirectory(context), fileName)
        val internalDeleted = !internal.exists() || internal.delete()
        val externalDeleted = !external.exists() || external.delete()
        return internalDeleted && externalDeleted
    }

    fun verifySha256(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }
}
