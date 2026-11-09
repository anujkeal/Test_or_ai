package com.keal.nexus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ChatMessage(val user: Boolean, val text: String)

class ChatActivity : ComponentActivity() {
    private var engine: InferenceEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (modelPath.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF6EE7B7),
                background = Color(0xFF090D12),
                surface = Color(0xFF161F2B),
                onSurface = Color(0xFFE2E8F0)
            )) {
                ChatScreen(
                    modelPath = modelPath,
                    onEngine = { engine = it },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        engine?.destroy()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
    }
}

@Composable
private fun ChatScreen(
    modelPath: String,
    onEngine: (InferenceEngine) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var engine by remember { mutableStateOf<InferenceEngine?>(null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Loading model…") }
    var ready by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(modelPath) {
        try {
            val local = withContext(Dispatchers.Default) {
                AiChat.getInferenceEngine(context)
            }
            engine = local
            onEngine(local)
            withContext(Dispatchers.IO) {
                when (val initial = local.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }) {
                    is InferenceEngine.State.Error -> throw initial.exception
                    else -> Unit
                }
                local.loadModel(File(modelPath).absolutePath)
                local.setSystemPrompt("You are a helpful, clear, and thoughtful AI assistant. Answer accurately.")
            }
            ready = true
            status = "Ready"
        } catch (t: Throwable) {
            error = t.message ?: "Could not load this model."
            status = "Could not load model"
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("NEXUS AI", style = MaterialTheme.typography.titleLarge)
                Text(status, color = Color(0xFF6EE7B7), fontSize = 12.sp)
            }
        }
        HorizontalDivider(color = Color(0xFF263445))
        if (error != null) {
            Text(error!!, color = Color(0xFFF87171), modifier = Modifier.padding(12.dp))
            Text("Check that the GGUF file is valid and that your phone has enough free memory.", color = Color.LightGray)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty() && error == null) {
                item {
                    Text(
                        if (ready) "Model loaded. Start a conversation." else "Loading your model into memory. This can take a little while.",
                        color = Color.LightGray,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(messages) { message ->
                Surface(
                    color = if (message.user) Color(0xFF203B38) else Color(0xFF161F2B),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(message.text, modifier = Modifier.padding(12.dp), color = Color(0xFFE2E8F0))
                }
            }
            if (generating) item { Text("Thinking…", color = Color(0xFF6EE7B7)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = ready && !generating,
                placeholder = { Text("Message your AI…") },
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = ready && !generating && input.isNotBlank(),
                onClick = {
                    val prompt = input.trim()
                    input = ""
                    messages.add(ChatMessage(true, prompt))
                    messages.add(ChatMessage(false, ""))
                    generating = true
                    scope.launch {
                        try {
                            val answer = StringBuilder()
                            engine?.sendUserPrompt(prompt, 384)?.collect { token ->
                                answer.append(token)
                                messages[messages.lastIndex] = ChatMessage(false, answer.toString())
                            }
                        } catch (t: Throwable) {
                            error = t.message ?: "Generation failed."
                            if (messages.isNotEmpty() && messages.last().text.isBlank()) messages.removeAt(messages.lastIndex)
                        } finally {
                            generating = false
                        }
                    }
                }
            ) { Text("Send") }
        }
    }
}
