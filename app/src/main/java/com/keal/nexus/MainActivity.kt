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
        val intent = Intent(this, ChatActivity::class.java)
        installed.firstOrNull()?.let {
            intent.putExtra(ChatActivity.EXTRA_MODEL_PATH, it.absolutePath)
        }
        startActivity(intent)
        finish()
    }
}
