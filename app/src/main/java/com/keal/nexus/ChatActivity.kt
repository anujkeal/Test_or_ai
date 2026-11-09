package com.keal.nexus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class ChatMessage(val user: Boolean, val text: String)

private val NexusBackground = Color(0xFF090D12)
private val NexusSurface = Color(0xFF151E29)
private val NexusSurfaceAlt = Color(0xFF1B2835)
private val NexusMint = Color(0xFF72E5BE)
private val NexusText = Color(0xFFF1F5F9)
private val NexusMuted = Color(0xFF9AA9B8)
private val NexusUserBubble = Color(0xFF17483F)
private val NexusAssistantBubble = Color(0xFF192330)

class ChatActivity : ComponentActivity() {
    private var engine: InferenceEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 13, 18)
        window.navigationBarColor = android.graphics.Color.rgb(9, 13, 18)
        window.decorView.systemUiVisibility = 0
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (modelPath.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = NexusMint, onPrimary = NexusBackground,
                background = NexusBackground, surface = NexusSurface,
                onSurface = NexusText, onBackground = NexusText
            )) {
                ChatScreen(modelPath, { engine = it }, { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
        var loadedModelPath: String? = null
    }
}

@Composable
private fun ChatScreen(modelPath: String, onEngine: (InferenceEngine) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var engine by remember { mutableStateOf<InferenceEngine?>(null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Loading model…") }
    var loadStage by remember { mutableStateOf("Preparing native engine") }
    var ready by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(modelPath) {
        try {
            loadStage = "Initialising native engine"
            val local = withContext(Dispatchers.Default) { AiChat.getInferenceEngine(context) }
            engine = local
            onEngine(local)
            withContext(Dispatchers.IO) {
                loadStage = "Waiting for native runtime"
                when (val initial = local.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }) {
                    is InferenceEngine.State.Error -> throw initial.exception
                    else -> Unit
                }
                loadStage = "Loading GGUF weights (${File(modelPath).length() / (1024 * 1024)} MB)"
                local.loadModel(File(modelPath).absolutePath)
                loadStage = "Preparing context"
                local.setSystemPrompt("You are a helpful, clear, and thoughtful AI assistant. Answer accurately.")
            }
            ready = true
            status = "Ready"
        } catch (t: Throwable) {
            val details = buildString {
                append(t.javaClass.simpleName)
                t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                t.cause?.let { cause ->
                    append("\nCaused by ").append(cause.javaClass.simpleName)
                    cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                }
            }
            error = "Failed during: $loadStage\n$details"
            status = "Could not load model"
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    Column(Modifier.fillMaxSize().background(NexusBackground).imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(NexusSurface).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("‹  Back", color = NexusMint, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("NEXUS AI", color = NexusText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(File(modelPath).name, color = NexusMuted, fontSize = 10.sp, maxLines = 1)
            }
            Surface(color = if (ready) Color(0xFF173B33) else Color(0xFF3A3020), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (ready) NexusMint else Color(0xFFF2C46D), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(status, color = if (ready) NexusMint else Color(0xFFF2C46D), fontSize = 11.sp)
                }
            }
        }
        HorizontalDivider(color = Color(0xFF263442), thickness = 1.dp)

        if (error != null) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3B2027)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Couldn't load model", color = Color(0xFFFFA3A8), fontWeight = FontWeight.SemiBold)
                    Text(error!!, color = NexusText, fontSize = 12.sp)
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (messages.isEmpty() && error == null) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 42.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(color = NexusSurfaceAlt, shape = CircleShape, modifier = Modifier.size(62.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("N", color = NexusMint, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Text(if (ready) "Your model is ready." else "Getting your model ready…", color = NexusText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (ready) "Ask a question to start chatting." else "This may take a moment on your device.", color = NexusMuted, fontSize = 13.sp)
                    }
                }
            }
            itemsIndexed(messages) { _, message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (message.user) NexusUserBubble else NexusAssistantBubble,
                        shape = RoundedCornerShape(
                            topStart = 18.dp, topEnd = 18.dp,
                            bottomEnd = if (message.user) 5.dp else 18.dp,
                            bottomStart = if (message.user) 18.dp else 5.dp
                        ),
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(message.text.ifBlank { "…" }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = NexusText, fontSize = 15.sp, lineHeight = 23.sp)
                    }
                }
            }
            if (generating) item { Text("NEXUS is thinking…", color = NexusMint, fontSize = 12.sp) }
        }

        Surface(color = NexusSurface, shadowElevation = 8.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), enabled = ready && !generating,
                    placeholder = { Text(if (ready) "Message NEXUS…" else "Waiting for model…", color = NexusMuted) },
                    maxLines = 5, shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NexusText, unfocusedTextColor = NexusText,
                        focusedBorderColor = NexusMint, unfocusedBorderColor = Color(0xFF3A4858),
                        cursorColor = NexusMint, focusedContainerColor = NexusBackground,
                        unfocusedContainerColor = NexusBackground, disabledContainerColor = NexusBackground,
                        disabledTextColor = NexusMuted
                    )
                )
                Button(
                    onClick = {
                        val prompt = input.trim()
                        if (prompt.isNotEmpty()) {
                            input = ""
                            messages.add(ChatMessage(true, prompt))
                            messages.add(ChatMessage(false, ""))
                            generating = true
                            scope.launch {
                                try {
                                    val answer = StringBuilder()
                                    engine?.sendUserPrompt(prompt, 384)?.collect { token ->
                                        answer.append(token)
                                        if (messages.isNotEmpty()) messages[messages.lastIndex] = ChatMessage(false, answer.toString())
                                    }
                                } catch (t: Throwable) {
                                    error = t.message ?: "Generation failed."
                                    if (messages.isNotEmpty() && messages.last().text.isBlank()) messages.removeAt(messages.lastIndex)
                                } finally {
                                    generating = false
                                }
                            }
                        }
                    },
                    enabled = ready && !generating && input.isNotBlank(),
                    modifier = Modifier.height(56.dp), shape = RoundedCornerShape(18.dp),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexusMint, contentColor = NexusBackground,
                        disabledContainerColor = Color(0xFF263442), disabledContentColor = NexusMuted
                    )
                ) { Text("Send", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
