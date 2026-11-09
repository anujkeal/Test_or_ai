package com.keal.nexus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.keal.nexus.data.ModelManager

/**
 * The launcher is intentionally only a router. The new chat-first experience
 * lives in ChatActivity; the former home dashboard and bottom navigation are removed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installed = ModelManager.installedModels(this)
        val preferences = getSharedPreferences("nexus_preferences", MODE_PRIVATE)
        val lastPath = preferences.getString("last_model_path", null)
        val selected = installed.firstOrNull { file ->
            file.absolutePath == lastPath &&
                (com.keal.nexus.data.ModelCatalog.models.firstOrNull { it.fileName == file.name }
                    ?.let { model -> ModelManager.isCompleteModelFile(file, model) }
                    ?: (file.length() >= 64L * 1024L * 1024L && ModelManager.hasGgufMagic(file)))
        }
        val intent = Intent(this, ChatActivity::class.java)
        selected?.let { intent.putExtra(ChatActivity.EXTRA_MODEL_PATH, it.absolutePath) }
        startActivity(intent)
        finish()
    }
}
