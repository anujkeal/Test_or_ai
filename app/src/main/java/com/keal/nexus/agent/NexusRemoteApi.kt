package com.keal.nexus.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * User-provided OpenAI-compatible chat-completions endpoint.
 * Credentials stay encrypted in Android Keystore-backed app storage.
 * Never commit API keys or send them to arbitrary redirect hosts.
 */
class NexusRemoteApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("nexus_remote_api", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var endpoint: String
        get() = prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions") ?: ""
        set(value) { prefs.edit().putString("endpoint", value.trim()).apply() }
    var model: String
        get() = prefs.getString("model", "gpt-4.1-mini") ?: ""
        set(value) { prefs.edit().putString("model", value.trim()).apply() }
    fun hasKey() = prefs.contains("key_cipher")
    fun clearKey() { prefs.edit().remove("key_cipher").remove("key_iv").apply() }
    private fun keystoreKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "nexus_remote_api_aes"
        store.getKey(alias, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    fun saveKey(raw: String) {
        val value = raw.trim()
        require(value.isNotBlank()) { "API key cannot be empty" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        prefs.edit()
            .putString("key_cipher", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString("key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }
    private fun readKey(): String {
        val encrypted = prefs.getString("key_cipher", null) ?: error("Add an API key in NEXUS Settings.")
        val iv = prefs.getString("key_iv", null) ?: error("Missing API key IV.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    suspend fun reply(prompt: String, memory: String): String = withContext(Dispatchers.IO) {
        val url = URL(endpoint)
        require(url.protocol == "https" && !url.host.isNullOrBlank() && url.userInfo == null && url.port in listOf(-1, 443)) {
            "API endpoint must be an HTTPS URL on port 443."
        }
        require(model.isNotBlank()) { "Enter an API model ID." }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${readKey()}")
        }
        try {
            val body = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system")
                        .put("content", "You are NEXUS, a helpful Android AI assistant. Answer accurately. Never claim to have used phone tools. " + memory))
                    .put(JSONObject().put("role", "user").put("content", prompt)))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(400) }.orEmpty()
                error("API returned HTTP $status: $detail")
            }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")?.takeIf { it.isNotBlank() }
                ?: error("API returned no assistant message.")
        } finally {
            connection.disconnect()
        }
    }
}
