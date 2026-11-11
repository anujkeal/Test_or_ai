package com.keal.nexus.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CodeRunResult(
    val language: String,
    val version: String,
    val stdout: String,
    val stderr: String,
    val compileOutput: String,
    val exitCode: Int?,
    val signal: String?
) {
    fun display(fileName: String): String = buildString {
        append("Run result · ").append(fileName).append("\n")
        append("Language: ").append(language).append(" ").append(version).append("\n")
        append("Exit code: ").append(exitCode?.toString() ?: signal ?: "unknown").append("\n\n")
        append("OUTPUT\n").append(stdout.ifBlank { "(no standard output)" }).append("\n")
        if (compileOutput.isNotBlank()) append("\nCOMPILE\n").append(compileOutput).append("\n")
        if (stderr.isNotBlank()) append("\nERRORS\n").append(stderr).append("\n")
    }.take(7000)
}

/**
 * Piston-compatible remote runner. Use a server controlled by the user.
 * Source code is transmitted to that server; never describe this as on-device execution.
 */
class NexusCodeRunner(context: Context) {
    private val prefs = context.getSharedPreferences("nexus_code_runner", Context.MODE_PRIVATE)
    var endpoint: String
        get() = prefs.getString("endpoint", "") ?: ""
        set(value) { prefs.edit().putString("endpoint", value.trim().trimEnd('/')).apply() }
    private fun encryptionKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey("nexus_code_runner_token", null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("nexus_code_runner_token",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    var token: String
        get() = runCatching {
            val data = Base64.decode(prefs.getString("token_cipher", "") ?: "", Base64.NO_WRAP)
            val iv = Base64.decode(prefs.getString("token_iv", "") ?: "", Base64.NO_WRAP)
            if (data.isEmpty() || iv.isEmpty()) "" else Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
                String(doFinal(data), Charsets.UTF_8)
            }
        }.getOrDefault("")
        set(value) {
            val clean = value.trim()
            if (clean.isBlank()) {
                prefs.edit().remove("token_cipher").remove("token_iv").apply()
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
                prefs.edit()
                    .putString("token_cipher", Base64.encodeToString(cipher.doFinal(clean.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                    .putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                    .apply()
            }
        }
    var configured: Boolean get() = endpoint.isNotBlank()

    suspend fun run(fileName: String, source: String, stdin: String = ""): CodeRunResult = withContext(Dispatchers.IO) {
        require(source.isNotBlank()) { "The selected source file is empty." }
        require(source.toByteArray(Charsets.UTF_8).size <= 100_000) { "Code is larger than the 100 KB execution limit." }
        val base = URL(endpoint)
        require(base.protocol == "https" && base.userInfo == null && base.port in listOf(-1, 443)) {
            "Configure an HTTPS Piston-compatible execution server in Settings."
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val language = when (extension) {
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "java" -> "java"
            "c" -> "c"
            "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "c++"
            "cs" -> "csharp"
            "go" -> "go"
            "rs" -> "rust"
            "kt", "kts" -> "kotlin"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "dart" -> "dart"
            "lua" -> "lua"
            "pl" -> "perl"
            "sh", "bash" -> "bash"
            "r" -> "rscript"
            "sql" -> "sqlite3"
            else -> error("No language mapping for .$extension. Supported file types include py, js, ts, java, c, cpp, cs, go, rs, kt, rb, php, swift, dart, lua, pl, sh, r and sql.")
        }
        fun request(path: String, body: JSONObject? = null): JSONObject {
            val url = URL(base.toString().trimEnd('/') + path)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = 12_000
                readTimeout = 25_000
                instanceFollowRedirects = false
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText().take(100_000) }.orEmpty()
                if (status !in 200..299) error("Execution server returned HTTP $status: $text")
                return if (text.isBlank()) JSONObject() else if (text.trimStart().startsWith("[")) JSONObject().put("items", JSONArray(text)) else JSONObject(text)
            } finally { conn.disconnect() }
        }
        val runtimes = request("/api/v2/runtimes").optJSONArray("items") ?: JSONArray()
        val runtime = (0 until runtimes.length()).map { runtimes.optJSONObject(it) ?: JSONObject() }
            .firstOrNull { it.optString("language") == language }
            ?: error("The configured runner does not have a $language runtime installed.")
        val version = runtime.optString("version").ifBlank { "*" }
        val payload = JSONObject()
            .put("language", language)
            .put("version", version)
            .put("files", JSONArray().put(JSONObject().put("name", fileName).put("content", source)))
            .put("stdin", stdin.take(10_000))
            .put("run_timeout", 3000)
            .put("compile_timeout", 10000)
        val result = request("/api/v2/execute", payload)
        val run = result.optJSONObject("run") ?: JSONObject()
        val compile = result.optJSONObject("compile") ?: JSONObject()
        CodeRunResult(
            language = language,
            version = version,
            stdout = run.optString("stdout").take(4000),
            stderr = run.optString("stderr").take(2000),
            compileOutput = compile.optString("output").take(2000),
            exitCode = if (run.has("code") && !run.isNull("code")) run.optInt("code") else null,
            signal = run.optString("signal").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
