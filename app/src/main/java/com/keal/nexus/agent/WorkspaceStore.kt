package com.keal.nexus.agent

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

class WorkspaceStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("nexus_workspace", Context.MODE_PRIVATE)
    fun selectedTreeUri(): Uri? = preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun setWorkspace(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    private fun root(): DocumentFile {
        val uri = selectedTreeUri() ?: throw IllegalStateException("Choose a workspace folder first.")
        return DocumentFile.fromTreeUri(context, uri)
            ?.takeIf { it.exists() && it.isDirectory && it.canWrite() }
            ?: throw IllegalStateException("The selected workspace is unavailable or read-only.")
    }

    private fun safeName(name: String): String {
        require(name.isNotBlank() && name.length <= 120) { "Invalid file name." }
        require(name == name.trim()) { "Remove spaces at the beginning or end of the file name." }
        require(!name.contains('/') && !name.contains('\\')) { "Only files directly inside the selected workspace are supported." }
        require(name != "." && name != ".." && !name.contains("..")) { "Invalid file name." }
        return name
    }

    fun describeFilePath(name: String): String {
        val safe = safeName(name)
        val directory = root()
        val exists = directory.findFile(safe)?.isFile == true
        return buildString {
            append("Selected workspace: ").append(directory.name ?: "Selected folder").append("\\n")
            append("Relative file path: ").append(safe).append("\\n")
            append("File exists: ").append(if (exists) "yes" else "no")
            append(if (exists) "\\nThis file is directly inside the selected workspace folder."
                else "\\nThis file has not been found in the selected workspace folder.")
        }
    }

    fun listFiles(): List<String> = root().listFiles().filter { it.isFile }
        .mapNotNull { it.name }.sortedBy { it.lowercase() }

    fun fileExists(name: String): Boolean = root().findFile(safeName(name))?.isFile == true

    fun readFile(name: String): String {
        val file = root().findFile(safeName(name))?.takeIf { it.isFile }
            ?: throw FileNotFoundException("File not found: $name")
        require(file.length() <= MAX_TEXT_BYTES) { "File is larger than the 1 MB text-tool limit." }
        return context.contentResolver.openInputStream(file.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw FileNotFoundException("Could not open $name")
    }

    fun writeFile(name: String, content: String): String {
        val safe = safeName(name)
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "File content exceeds the 1 MB tool limit." }
        val directory = root()
        val existing = directory.findFile(safe)
        val target = if (existing != null) {
            require(existing.isFile) { "A folder with that name already exists." }
            existing
        } else {
            directory.createFile(mimeTypeFor(safe), safe)
                ?: throw IllegalStateException("Could not create file in the selected workspace.")
        }
        context.contentResolver.openOutputStream(target.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
            ?: throw IllegalStateException("Could not write to the selected file.")
        return safe
    }

    fun deleteFile(name: String): Boolean {
        val file = root().findFile(safeName(name))?.takeIf { it.isFile } ?: return false
        return file.delete()
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "json" -> "application/json"
        "md", "txt", "kt", "py" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private const val MAX_TEXT_BYTES = 1_048_576
    }
}
