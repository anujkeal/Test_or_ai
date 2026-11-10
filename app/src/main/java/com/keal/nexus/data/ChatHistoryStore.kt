package com.keal.nexus.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedChatMessage(val user: Boolean, val text: String)

data class SavedChat(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<SavedChatMessage>
)

object ChatHistoryStore {
    private const val PREFS = "nexus_chat_history"
    private const val KEY = "conversations"
    private const val MAX_CHATS = 100

    fun load(context: Context): List<SavedChat> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val messagesJson = item.optJSONArray("messages") ?: JSONArray()
                    val messages = buildList {
                        for (j in 0 until messagesJson.length()) {
                            val message = messagesJson.getJSONObject(j)
                            add(SavedChatMessage(message.optBoolean("user"), message.optString("text")))
                        }
                    }
                    if (messages.isNotEmpty()) {
                        add(SavedChat(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            title = item.optString("title", "New chat"),
                            updatedAt = item.optLong("updatedAt"),
                            messages = messages
                        ))
                    }
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, id: String, messages: List<SavedChatMessage>) {
        if (messages.isEmpty()) return
        val existing = load(context).filterNot { it.id == id }
        val title = messages.firstOrNull { it.user }?.text
            ?.replace(Regex("\\s+"), " ")?.take(52)?.ifBlank { "New chat" } ?: "New chat"
        val entry = SavedChat(id, title, System.currentTimeMillis(), messages)
        val all = (listOf(entry) + existing).take(MAX_CHATS)
        val array = JSONArray()
        all.forEach { chat ->
            val messageArray = JSONArray()
            chat.messages.forEach { message ->
                messageArray.put(JSONObject().put("user", message.user).put("text", message.text))
            }
            array.put(JSONObject()
                .put("id", chat.id)
                .put("title", chat.title)
                .put("updatedAt", chat.updatedAt)
                .put("messages", messageArray))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun delete(context: Context, id: String) {
        val remaining = load(context).filterNot { it.id == id }
        val array = JSONArray()
        remaining.forEach { chat ->
            val messageArray = JSONArray()
            chat.messages.forEach { message ->
                messageArray.put(JSONObject().put("user", message.user).put("text", message.text))
            }
            array.put(JSONObject().put("id", chat.id).put("title", chat.title)
                .put("updatedAt", chat.updatedAt).put("messages", messageArray))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
