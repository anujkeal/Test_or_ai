package com.keal.nexus

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.keal.nexus.agent.NexusToolCall
import com.keal.nexus.agent.NexusToolRuntime
import com.keal.nexus.data.ChatHistoryStore
import com.keal.nexus.data.SavedChat
import com.keal.nexus.data.SavedChatMessage
import com.keal.nexus.data.ModelCatalog
import com.keal.nexus.data.ModelManager
import com.keal.nexus.data.ModelSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

private data class PendingToolApproval(
    val call: NexusToolCall,
    val decision: CompletableDeferred<Boolean>
)

private data class ChatMessage(val user: Boolean, val text: String)

private val NexusDark = Color(0xFF0D1016)
private val NexusDarkSurface = Color(0xFF181D27)
private val NexusDarkBorder = Color(0xFF272E3B)
private val NexusLight = Color(0xFFF7F8FA)
private val NexusLightSurface = Color(0xFFFFFFFF)
private val NexusLightBorder = Color(0xFFE4E7EC)
private val NexusPurple = Color(0xFF6945F5)
private val NexusMint = Color(0xFF35CFA5)
private val NexusTextDark = Color(0xFFF1F3F8)
private val NexusMutedDark = Color(0xFF8992A4)
private val NexusTextLight = Color(0xFF171B24)
private val NexusMutedLight = Color(0xFF707887)

private enum class NexusThemeMode { SYSTEM, LIGHT, DARK }

class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexusChatApp(intent.getStringExtra(EXTRA_MODEL_PATH)) }
    }

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexusChatApp(initialPath: String?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nexus_preferences", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val toolRuntime = remember { NexusToolRuntime(context) }

    var themeMode by remember {
        mutableStateOf(runCatching {
            NexusThemeMode.valueOf(prefs.getString("theme", "SYSTEM") ?: "SYSTEM")
        }.getOrDefault(NexusThemeMode.SYSTEM))
    }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("wifi_only", false)) }
    var modelPath by remember { mutableStateOf(initialPath) }
    var engine by remember { mutableStateOf<InferenceEngine?>(null) }
    var ready by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loadStage by remember { mutableStateOf("Preparing model") }
    var input by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var showModels by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var chatHistory by remember { mutableStateOf(ChatHistoryStore.load(context)) }
    var currentChatId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var pendingRestoreChat by remember { mutableStateOf<SavedChat?>(null) }
    var restoringHistory by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var pendingToolApproval by remember { mutableStateOf<PendingToolApproval?>(null) }
    var downloadId by remember { mutableStateOf(prefs.getLong("active_download_id", -1L).takeIf { it >= 0L }) }
    var downloadModel by remember { mutableStateOf(ModelCatalog.models.firstOrNull { it.id == prefs.getString("active_download_model_id", null) }) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var importingModel by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importProgressKnown by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(ModelManager.installedModels(context)) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()

    val isDark = when (themeMode) {
        NexusThemeMode.DARK -> true
        NexusThemeMode.LIGHT -> false
        NexusThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val background = if (isDark) NexusDark else NexusLight
    val surface = if (isDark) NexusDarkSurface else NexusLightSurface
    val border = if (isDark) NexusDarkBorder else NexusLightBorder
    val primaryText = if (isDark) NexusTextDark else NexusTextLight
    val mutedText = if (isDark) NexusMutedDark else NexusMutedLight
    val userBubble = if (isDark) Color(0xFF242A36) else Color(0xFFECEFFD)
    val assistantBubble = if (isDark) Color(0xFF171C25) else Color(0xFFF0F2F5)

    SideEffect {
        (context as? ComponentActivity)?.window?.let { window ->
            window.statusBarColor = background.toArgbInt()
            window.navigationBarColor = background.toArgbInt()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (isDark) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importingModel) {
            importingModel = true
            importProgress = 0f
            importProgressKnown = false
            importError = null
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        ModelManager.importModel(context, uri) { copiedBytes, totalBytes ->
                            scope.launch {
                                importProgressKnown = totalBytes > 0L
                                importProgress = if (totalBytes > 0L) {
                                    (copiedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                            }
                        }
                    }
                    installed = ModelManager.installedModels(context)
                    modelPath = file.absolutePath
                    showModels = false
                    downloadStatus = "Imported ${file.name}"
                } catch (t: Throwable) {
                    importError = t.message ?: "Could not import model."
                    downloadStatus = "Import failed: ${importError}"
                } finally {
                    importingModel = false
                }
            }
        }
    }

    LaunchedEffect(modelPath, loadAttempt) {
        if (prefs.getBoolean("model_load_in_progress", false)) {
            prefs.edit().putBoolean("model_load_in_progress", false).commit()
            ready = false
            loading = false
            showModels = true
            loadError = "The previous model load was interrupted while native weights were loading. Android may have stopped NEXUS because of memory pressure or a native model-loading failure. The app has stopped automatic retries. Choose a smaller model, or retry after closing other apps."
            return@LaunchedEffect
        }
        prefs.edit().putString("last_model_path", modelPath).apply()
        val path = modelPath
        ready = false
        loadError = null
        engine = null
        if (path.isNullOrBlank()) {
            loading = false
            showModels = true
            return@LaunchedEffect
        }
        loading = true
        try {
            loadStage = "Initialising native runtime"
            val local = withContext(Dispatchers.Default) { AiChat.getInferenceEngine(context) }
            engine = local
            withContext(Dispatchers.IO) {
                loadStage = "Waiting for native runtime"
                // Wait for any previous load/generation transition before reusing the singleton.
                local.state.first {
                    it is InferenceEngine.State.Initialized ||
                    it is InferenceEngine.State.ModelReady ||
                    it is InferenceEngine.State.Error
                }
                val requestedFile = File(path)
                val requestedPath = requestedFile.absolutePath
                val modelAlreadyResident =
                    local.state.value is InferenceEngine.State.ModelReady &&
                    local.loadedModelPath == requestedPath

                if (!modelAlreadyResident) {
                    when (local.state.value) {
                        is InferenceEngine.State.ModelReady, is InferenceEngine.State.Error -> local.cleanUp()
                        else -> Unit
                    }
                    local.state.first { it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error }
                        .let { state -> if (state is InferenceEngine.State.Error) throw state.exception }
                    // Large GGUF models can be terminated by Android's low-memory killer
                    // during native loading. Check available system RAM before entering JNI.
                    val memoryInfo = ActivityManager.MemoryInfo()
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                        .getMemoryInfo(memoryInfo)
                    val modelBytes = requestedFile.length()
                    if (modelBytes <= 0L) {
                        throw IOException("The selected GGUF file is empty or unavailable. Re-download or re-import the model.")
                    }
                    // llama.cpp loads GGUF weights with mmap by default: the file is
                    // not duplicated as a second full in-memory model allocation.
                    // Reserve additional RAM for the KV cache, compute buffers and Android.
                    // The previous 2x-file-size + 1 GiB estimate rejected this 3B model
                    // even with 3.37 GiB available, despite the mapped-weight design.
                    val largeModelThreshold = 1_500L * 1024L * 1024L
                    val runtimeReserve = if (modelBytes >= largeModelThreshold) {
                        768L * 1024L * 1024L
                    } else {
                        512L * 1024L * 1024L
                    }
                    val requiredFreeBytes = modelBytes + runtimeReserve
                    if (memoryInfo.lowMemory || memoryInfo.availMem < requiredFreeBytes) {
                        val availableGb = memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0)
                        val requiredGb = requiredFreeBytes / (1024.0 * 1024.0 * 1024.0)
                        val modelGb = modelBytes / (1024.0 * 1024.0 * 1024.0)
                        val memoryMessage = String.format(
                            java.util.Locale.US,
                            "NEXUS stopped before loading to protect your phone from running out of memory. " +
                                "Model size: %.2f GB; free RAM: %.2f GB; estimated safe minimum: %.2f GB. " +
                                "Close other apps or choose Llama 3.2 1B / Qwen2.5 1.5B.",
                            modelGb,
                            availableGb,
                            requiredGb
                        )
                        throw IOException(memoryMessage)
                    }
                    loadStage = "Loading model weights"
                    // Persist before entering JNI: if Android kills the process in native
                    // code, the next launch must not blindly retry the same model forever.
                    prefs.edit().putBoolean("model_load_in_progress", true).commit()
                    local.loadModel(requestedPath)
                    loadStage = "Preparing chat"
                    local.setSystemPrompt(toolRuntime.systemPrompt())
                    prefs.edit().putBoolean("model_load_in_progress", false).commit()
                } else {
                    loadStage = "Reusing loaded model"
                }
            }
            ready = true
        } catch (t: Throwable) {
            prefs.edit().putBoolean("model_load_in_progress", false).commit()
            loadError = buildString {
                append(t.javaClass.simpleName)
                t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        } finally {
            loading = false
        }
    }

    fun resetChat() {
        messages.clear()
        input = ""
        loadError = null
        ready = false
        loading = true
        scope.launch {
            try {
                generationJob?.cancelAndJoin()
                generationJob = null
                generating = false
                engine?.resetConversation(toolRuntime.systemPrompt()) ?: error("Inference engine is unavailable.")
                ready = true
            } catch (t: Throwable) {
                ready = false
                loadError = buildString {
                    append(t.javaClass.simpleName)
                    t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                }
            } finally {
                loading = false
            }
        }
    }

    // Persist the active transcript locally whenever a message is added or a streamed reply changes.
    val messageSnapshot = messages.toList()
    LaunchedEffect(currentChatId, messageSnapshot) {
        if (messageSnapshot.isNotEmpty()) {
            ChatHistoryStore.save(
                context,
                currentChatId,
                messageSnapshot.map { SavedChatMessage(it.user, it.text) }
            )
            chatHistory = ChatHistoryStore.load(context)
        }
    }

    fun startNewChat() {
        if (messages.isNotEmpty()) {
            ChatHistoryStore.save(context, currentChatId, messages.map { SavedChatMessage(it.user, it.text) })
            chatHistory = ChatHistoryStore.load(context)
        }
        currentChatId = UUID.randomUUID().toString()
        if (ready || generating) resetChat()
        else { messages.clear(); input = "" }
    }

    fun openSavedChat(chat: SavedChat) {
        scope.launch {
            generationJob?.cancelAndJoin()
            generationJob = null
            generating = false
            messages.clear()
            messages.addAll(chat.messages.map { ChatMessage(it.user, it.text) })
            currentChatId = chat.id
            input = ""
            loadError = null
            showHistory = false
            pendingRestoreChat = chat
            if (modelPath.isNullOrBlank()) showModels = true
        }
    }

    // Restore saved turns only after the native model is fully ready. This also
    // handles a history item selected while the model is still loading.
    LaunchedEffect(pendingRestoreChat, engine, loadAttempt) {
        val chat = pendingRestoreChat ?: return@LaunchedEffect
        val activeEngine = engine ?: return@LaunchedEffect
        snapshotFlow { ready }.first { it }
        restoringHistory = true
        ready = false
        loading = true
        loadError = null
        try {
            activeEngine.resetConversation(toolRuntime.systemPrompt())
            chat.messages.filter { it.user }.takeLast(8).forEach { turn ->
                activeEngine.sendUserPrompt(turn.text, 512).collect { }
            }
            pendingRestoreChat = null
            ready = true
        } catch (t: Throwable) {
            loadError = t.message ?: "Could not restore this conversation. Select Try again to retry."
            ready = false
        } finally {
            restoringHistory = false
            loading = false
        }
    }

    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        while (true) {
            val snapshot = withContext(Dispatchers.IO) { ModelManager.queryDownload(context, id) }
            if (snapshot == null) {
                downloadStatus = "Download status unavailable"
                downloadId = null
                downloadModel = null
                prefs.edit().remove("active_download_id").remove("active_download_model_id").apply()
                break
            }
            downloadProgress = snapshot.progress.coerceIn(0f, 1f)
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val model = downloadModel
                    val file = model?.let { ModelManager.modelFile(context, it) }
                    val expectedSha = model?.sha256
                    val valid = if (file != null && model != null) withContext(Dispatchers.IO) {
                        ModelManager.isCompleteModelFile(file, model) &&
                            (expectedSha == null || ModelManager.verifySha256(file, expectedSha))
                    } else false
                    if (valid && file != null) {
                        installed = ModelManager.installedModels(context)
                        downloadStatus = "Ready to chat"
                        showModels = false
                        modelPath = file.absolutePath
                    } else {
                        file?.delete()
                        downloadStatus = "Model verification failed. Please download it again."
                    }
                    downloadId = null
                    downloadModel = null
                    prefs.edit().remove("active_download_id").remove("active_download_model_id").apply()
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    downloadStatus = "Download failed. Check your connection and storage."
                    downloadId = null
                    downloadModel = null
                    prefs.edit().remove("active_download_id").remove("active_download_model_id").apply()
                    break
                }
                else -> delay(700)
            }
        }
    }

    LaunchedEffect(downloadStatus) {
        if (downloadStatus != null) {
            delay(3200)
            downloadStatus = null
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
    }

    MaterialTheme(
        colorScheme = if (isDark) darkColorScheme(
            primary = NexusPurple, background = background, surface = surface,
            onSurface = primaryText, onBackground = primaryText
        ) else lightColorScheme(
            primary = NexusPurple, background = background, surface = surface,
            onSurface = primaryText, onBackground = primaryText
        )
    ) {
        Box(Modifier.fillMaxSize().background(background).imePadding()) {
            Column(Modifier.fillMaxSize()) {
                // Compact model selector and overflow menu.
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { showModels = true },
                        color = Color.Transparent,
                        shape = CircleShape
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(9.dp).background(if (ready) NexusMint else Color(0xFF8A91A1), CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                modelPath?.let { displayModelName(File(it).name) } ?: "Select model",
                                color = primaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("⌄", color = mutedText, fontSize = 21.sp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        Surface(
                            onClick = { showMenu = true },
                            color = if (showMenu) surface else Color.Transparent,
                            shape = CircleShape
                        ) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Text("⋮", color = primaryText, fontSize = 28.sp)
                            }
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = surface
                        ) {
                            MenuEntry("＋", "New chat") {
                                showMenu = false
                                startNewChat()
                            }
                            MenuEntry("▱", "Workspace folder") {
                                showMenu = false
                                context.startActivity(android.content.Intent(context, com.keal.nexus.agent.AgentActivity::class.java))
                            }
                            MenuEntry("◇", "Models") { showMenu = false; showModels = true }
                            MenuEntry("◷", "Chat history") { showMenu = false; showHistory = true }
                            MenuEntry("◉", "Voice animation") {
                                showMenu = false
                                context.startActivity(android.content.Intent(context, VoiceActivity::class.java).putExtra(VoiceActivity.EXTRA_MODEL_PATH, modelPath))
                            }
                            MenuEntry("☷", "Settings") { showMenu = false; showSettings = true }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().padding(horizontal = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.nexus_logo),
                                contentDescription = "NEXUS AI",
                                modifier = Modifier.size(120.dp)
                            )
                            Spacer(Modifier.height(28.dp))
                            Text("How can I help?", color = primaryText, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Private. Runs on your device.",
                                color = mutedText, fontSize = 17.sp
                            )
                            if (loading) {
                                Spacer(Modifier.height(22.dp))
                                CircularProgressIndicator(color = NexusPurple, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(if (restoringHistory) "Restoring conversation…" else "$loadStage…", color = mutedText, fontSize = 13.sp)
                            }
                            if (loadError != null) {
                                Spacer(Modifier.height(18.dp))
                                Surface(color = if (isDark) Color(0xFF3B2027) else Color(0xFFFFE9EA), shape = RoundedCornerShape(16.dp)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Couldn't load model", color = Color(0xFFE36B79), fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(6.dp))
                                        Text(loadError!!, color = primaryText, fontSize = 12.sp)
                                        Spacer(Modifier.height(8.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { loadAttempt++ }) { Text("Try again") }
                                            TextButton(onClick = { loadError = null; showModels = true }) { Text("Choose smaller model") }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            itemsIndexed(messages) { _, message ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                                    Surface(
                                        color = if (message.user) userBubble else assistantBubble,
                                        shape = RoundedCornerShape(22.dp),
                                        modifier = Modifier.widthIn(max = 340.dp)
                                    ) {
                                        Text(
                                            message.text.ifBlank { "…" },
                                            Modifier.padding(horizontal = 17.dp, vertical = 13.dp),
                                            color = primaryText, fontSize = 16.sp, lineHeight = 24.sp
                                        )
                                    }
                                }
                            }
                            if (generating) item { Text("NEXUS is thinking…", color = mutedText, fontSize = 13.sp) }
                        }
                    }
                }

                // Message composer.
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        enabled = ready && !generating && !restoringHistory,
                        placeholder = { Text(if (ready) "Message NEXUS" else "Choose a model to start", color = mutedText, fontSize = 16.sp) },
                        maxLines = 5,
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = primaryText, unfocusedTextColor = primaryText,
                            focusedBorderColor = border, unfocusedBorderColor = border,
                            cursorColor = NexusPurple, focusedContainerColor = surface,
                            unfocusedContainerColor = surface, disabledContainerColor = surface,
                            disabledTextColor = mutedText
                        )
                    )
                    Surface(
                        onClick = {
                            val prompt = input.trim()
                            if (prompt.isNotEmpty() && ready && !generating) {
                                input = ""
                                messages.add(ChatMessage(true, prompt))
                                messages.add(ChatMessage(false, ""))
                                generating = true
                                generationJob = scope.launch {
                                    try {
                                        val activeEngine = engine ?: error("The local inference engine is unavailable.")
                                        val workspaceIntent = toolRuntime.detectWorkspaceIntent(prompt)
                                        if (workspaceIntent != null) {
                                            val intentResult = when (workspaceIntent.action) {
                                                com.keal.nexus.agent.WorkspaceIntent.Action.PATH -> {
                                                    val listCall = toolRuntime.callFor(com.keal.nexus.agent.WorkspaceIntent.Action.LIST)
                                                        ?: error("Could not prepare the workspace path lookup.")
                                                    if (!toolRuntime.isAllowed(listCall)) {
                                                        "I cannot inspect workspace paths until a folder is selected and workspace.list_files is enabled."
                                                    } else withContext(Dispatchers.IO) {
                                                        toolRuntime.describePath(workspaceIntent.fileName ?: error("A file name is required."))
                                                    }
                                                }
                                                com.keal.nexus.agent.WorkspaceIntent.Action.LIST,
                                                com.keal.nexus.agent.WorkspaceIntent.Action.READ -> {
                                                    val call = toolRuntime.callFor(workspaceIntent.action, workspaceIntent.fileName)
                                                        ?: error("Could not prepare the workspace request.")
                                                    if (!toolRuntime.isAllowed(call)) {
                                                        "I did not access any files. Select a workspace folder and enable ${call.name} in Workspace permissions."
                                                    } else withContext(Dispatchers.IO) {
                                                        toolRuntime.execute(call)
                                                    }
                                                }
                                                com.keal.nexus.agent.WorkspaceIntent.Action.WRITE -> {
                                                    val fileName = workspaceIntent.fileName ?: error("A file name is required.")
                                                    val callWithoutContent = toolRuntime.callFor(workspaceIntent.action, fileName)
                                                        ?: error("Could not prepare the file write.")
                                                    if (!toolRuntime.isAllowed(callWithoutContent)) {
                                                        "I did not create the file. Select a workspace folder and enable workspace.write_file in Workspace permissions, then try again."
                                                    } else {
                                                        val rawContent = StringBuilder()
                                                        activeEngine.sendUserPrompt(
                                                            toolRuntime.contentGenerationPrompt(prompt, fileName),
                                                            768
                                                        ).collect { token -> rawContent.append(token) }
                                                        val generatedContent = toolRuntime.cleanGeneratedFileContent(rawContent.toString())
                                                        val looksLikeHtml = fileName.endsWith(".html", true) || fileName.endsWith(".htm", true)
                                                        if (generatedContent.isBlank() ||
                                                            toolRuntime.parseToolCall(generatedContent) != null ||
                                                            (looksLikeHtml && (!generatedContent.contains("<html", true) || !generatedContent.contains("</html>", true)))
                                                        ) {
                                                            "I did not create $fileName because the model did not produce valid file contents. Please try again with a more specific request."
                                                        } else {
                                                            val writeCall = toolRuntime.callFor(workspaceIntent.action, fileName, generatedContent)
                                                                ?: error("Could not prepare the file write.")
                                                            val decision = CompletableDeferred<Boolean>()
                                                            pendingToolApproval = PendingToolApproval(writeCall, decision)
                                                            val approved = decision.await()
                                                            pendingToolApproval = null
                                                            if (approved) withContext(Dispatchers.IO) {
                                                                toolRuntime.execute(writeCall, userApproved = true)
                                                            } else "File creation cancelled. No file was written."
                                                        }
                                                    }
                                                }
                                                com.keal.nexus.agent.WorkspaceIntent.Action.DELETE -> {
                                                    val call = toolRuntime.callFor(workspaceIntent.action, workspaceIntent.fileName)
                                                        ?: error("Could not prepare the delete request.")
                                                    if (!toolRuntime.isAllowed(call)) {
                                                        "I did not delete anything. Select a workspace folder and enable workspace.delete_file in Workspace permissions."
                                                    } else {
                                                        val decision = CompletableDeferred<Boolean>()
                                                        pendingToolApproval = PendingToolApproval(call, decision)
                                                        val approved = decision.await()
                                                        pendingToolApproval = null
                                                        if (approved) withContext(Dispatchers.IO) {
                                                            toolRuntime.execute(call, userApproved = true)
                                                        } else "File deletion cancelled. No file was deleted."
                                                    }
                                                }
                                            }
                                            if (messages.isNotEmpty()) messages[messages.lastIndex] = ChatMessage(false, intentResult)
                                        } else {
                                        var nextPrompt = prompt
                                        var toolCallCount = 0
                                        while (true) {
                                            val answer = StringBuilder()
                                            activeEngine.sendUserPrompt(nextPrompt, 512).collect { token ->
                                                answer.append(token)
                                            }
                                            val toolCall = toolRuntime.parseToolCall(answer.toString())
                                            if (toolCall == null) {
                                                if (messages.isNotEmpty()) {
                                                    messages[messages.lastIndex] = ChatMessage(false, answer.toString().ifBlank { "I couldn't produce a response. Please try again." })
                                                }
                                                break
                                            }
                                            if (toolCallCount >= 3) {
                                                if (messages.isNotEmpty()) messages[messages.lastIndex] =
                                                    ChatMessage(false, "I reached the workspace tool-call limit for this request. Please narrow the task and try again.")
                                                break
                                            }
                                            toolCallCount++
                                            val destructive = toolCall.name == "workspace.write_file" || toolCall.name == "workspace.delete_file"
                                            val toolResult = if (destructive && toolRuntime.isAllowed(toolCall)) {
                                                val decision = CompletableDeferred<Boolean>()
                                                pendingToolApproval = PendingToolApproval(toolCall, decision)
                                                if (messages.isNotEmpty()) messages[messages.lastIndex] =
                                                    ChatMessage(false, "Waiting for your approval to " + toolCall.name.substringAfter('.').replace('_', ' ') + "…")
                                                val approved = decision.await()
                                                pendingToolApproval = null
                                                if (approved) withContext(Dispatchers.IO) {
                                                    toolRuntime.execute(toolCall, userApproved = true)
                                                } else {
                                                    "Tool not executed: you declined the requested action."
                                                }
                                            } else {
                                                if (!destructive && messages.isNotEmpty()) messages[messages.lastIndex] =
                                                    ChatMessage(false, "Using " + toolCall.name + "…")
                                                withContext(Dispatchers.IO) { toolRuntime.execute(toolCall) }
                                            }
                                            nextPrompt = "Workspace tool execution has completed.\n" +
                                                toolResult +
                                                "\nNow answer the user's original request using this result. Be explicit if the tool failed or permission was denied. Do not output another tool request unless another permitted operation is essential."
                                        }
                                        }
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (t: Throwable) {
                                        if (messages.isNotEmpty() && messages.last().text.isBlank()) messages.removeAt(messages.lastIndex)
                                        messages.add(ChatMessage(false, "I couldn't generate a response: ${t.message ?: "unknown error"}"))
                                    } finally {
                                        pendingToolApproval?.decision?.complete(false)
                                        pendingToolApproval = null
                                        generating = false
                                        generationJob = null
                                    }
                                }
                            }
                        },
                        color = if (ready && input.isNotBlank() && !generating) NexusPurple else surface,
                        shape = CircleShape,
                        modifier = Modifier.size(58.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("↑", color = if (ready && input.isNotBlank() && !generating) Color.White else mutedText, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            if (downloadStatus != null) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
                    color = surface, shape = RoundedCornerShape(12.dp), shadowElevation = 5.dp
                ) {
                    Text(downloadStatus!!, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = primaryText, fontSize = 12.sp)
                }
            }
        }

        if (showModels) {
            ModalBottomSheet(
                onDismissRequest = { showModels = false },
                containerColor = surface,
                contentColor = primaryText,
                dragHandle = { BottomSheetHandle(border) }
            ) {
                Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
                    Text("Models", color = primaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("Tap an installed model to use it.", color = mutedText, fontSize = 14.sp)
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = border)
                    ModelCatalog.models.forEach { model ->
                        val file = installed.firstOrNull {
                            it.name == model.fileName && ModelManager.isCompleteModelFile(it, model)
                        }
                        // A missing file and a null modelPath must never look selected.
                        val selected = file != null && file.absolutePath == modelPath
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = file != null && !generating && !loading) {
                                file?.let { selectedFile ->
                                    if (selectedFile.absolutePath != modelPath) messages.clear()
                                    modelPath = selectedFile.absolutePath
                                    showModels = false
                                }
                            }.padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(model.name.replace(" Instruct", ""), color = primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(3.dp))
                                Text("${model.publisher.substringBefore(" ·")} · ${model.sizeLabel.removePrefix("~")}", color = mutedText, fontSize = 14.sp)
                                if (downloadModel?.id == model.id && downloadId != null) {
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth(), color = NexusMint)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            if (selected) {
                                Text("✓", color = NexusMint, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            } else if (file != null) {
                                Text("Use", color = NexusMint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            } else {
                                Button(
                                    onClick = {
                                        if (downloadId == null) {
                                            runCatching {
                                                ModelManager.enqueueDownload(context, model, wifiOnly)
                                            }.onSuccess { id ->
                                                downloadModel = model
                                                downloadId = id
                                                prefs.edit()
                                                    .putLong("active_download_id", id)
                                                    .putString("active_download_model_id", model.id)
                                                    .apply()
                                                downloadStatus = "Downloading ${model.name}"
                                            }.onFailure { downloadStatus = it.message ?: "Could not start download" }
                                        }
                                    },
                                    enabled = downloadId == null,
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NexusMint, contentColor = Color(0xFF07110E))
                                ) { Text(if (downloadModel?.id == model.id && downloadId != null) "Getting…" else "↓  Get", fontWeight = FontWeight.Bold) }
                            }
                        }
                        HorizontalDivider(color = border)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !importingModel) {
                            importLauncher.launch(arrayOf("*/*"))
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (importingModel) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(25.dp),
                                color = NexusMint,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("＋", color = NexusMint, fontSize = 28.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (importingModel) "Adding GGUF model…" else "Import a .gguf file",
                                color = primaryText,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (importingModel) {
                                Spacer(Modifier.height(8.dp))
                                if (importProgressKnown) {
                                    LinearProgressIndicator(
                                        progress = { importProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = NexusMint
                                    )
                                    Text(
                                        "${(importProgress * 100).toInt()}% copied",
                                        color = mutedText,
                                        fontSize = 12.sp
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = NexusMint
                                    )
                                    Text("Copying and validating file…", color = mutedText, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (importError != null) {
                        Text(
                            "Import failed: ${importError}",
                            color = Color(0xFFE36B79),
                            fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                        )
                    }
                    val imported = installed.filter { file -> ModelCatalog.models.none { it.fileName == file.name } }
                    imported.forEach { file ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (file.absolutePath != modelPath) messages.clear(); modelPath = file.absolutePath; showModels = false }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(displayModelName(file.name), color = primaryText, fontWeight = FontWeight.SemiBold)
                                Text("Imported · ${formatSize(file.length())}", color = mutedText, fontSize = 13.sp)
                            }
                            if (file.absolutePath == modelPath) Text("✓", color = NexusMint, fontSize = 24.sp)
                        }
                    }
                }
            }
        }

        pendingToolApproval?.let { pending ->
            val toolName = pending.call.name
            val fileName = pending.call.arguments.optString("name", "the selected file")
            val isDelete = toolName == "workspace.delete_file"
            val isWrite = toolName == "workspace.write_file"
            val preview = pending.call.arguments.optString("content").take(420)
            AlertDialog(
                onDismissRequest = { pending.decision.complete(false) },
                containerColor = surface,
                title = { Text(if (isDelete) "Confirm file deletion" else "Approve workspace write", color = primaryText) },
                text = {
                    Column {
                        Text(
                            if (isDelete) "NEXUS wants to delete \"${fileName}\" from your selected workspace."
                            else "NEXUS wants to create or replace \"${fileName}\" in your selected workspace.",
                            color = primaryText
                        )
                        if (isWrite && preview.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(preview, color = mutedText, fontSize = 12.sp, maxLines = 8)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("This action runs only after you approve it. Tool permissions must also be enabled in Workspace settings.", color = mutedText, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pending.decision.complete(true) }) {
                        Text(if (isDelete) "Delete file" else "Approve", color = if (isDelete) Color(0xFFE36B79) else NexusMint)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pending.decision.complete(false) }) {
                        Text("Cancel", color = mutedText)
                    }
                }
            )
        }

        if (showHistory) {
            ModalBottomSheet(
                onDismissRequest = { showHistory = false },
                containerColor = surface,
                contentColor = primaryText,
                dragHandle = { BottomSheetHandle(border) }
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 680.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp).padding(bottom = 28.dp)
                ) {
                    Text("Chat history", color = primaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("Your conversations are saved privately on this device.", color = mutedText, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = border)
                    if (chatHistory.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 38.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No saved conversations yet", color = primaryText, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("Your chats will appear here automatically.", color = mutedText, fontSize = 13.sp)
                        }
                    } else {
                        chatHistory.forEach { chat ->
                            Row(
                                Modifier.fillMaxWidth().clickable { openSavedChat(chat) }.padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(chat.title, color = primaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${chat.messages.size} messages · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(chat.updatedAt))}",
                                        color = mutedText, fontSize = 12.sp)
                                }
                                Text("›", color = mutedText, fontSize = 25.sp)
                            }
                            HorizontalDivider(color = border)
                        }
                    }
                }
            }
        }

        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                containerColor = surface,
                contentColor = primaryText,
                dragHandle = { BottomSheetHandle(border) }
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
                    Text("Settings", color = primaryText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = border)
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Wi-Fi only downloads", color = primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("Save mobile data on large models", color = mutedText, fontSize = 14.sp)
                        }
                        Switch(checked = wifiOnly, onCheckedChange = {
                            wifiOnly = it
                            prefs.edit().putBoolean("wifi_only", it).apply()
                        })
                    }
                    HorizontalDivider(color = border)
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Theme", color = primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.clip(CircleShape).background(if (isDark) Color(0xFF252B37) else Color(0xFFEEF0F4)).padding(4.dp)
                        ) {
                            listOf(NexusThemeMode.SYSTEM, NexusThemeMode.LIGHT, NexusThemeMode.DARK).forEach { mode ->
                                val selected = themeMode == mode
                                Surface(
                                    onClick = {
                                        themeMode = mode
                                        prefs.edit().putString("theme", mode.name).apply()
                                    },
                                    color = if (selected) (if (isDark) Color(0xFF151A23) else Color.White) else Color.Transparent,
                                    shape = CircleShape
                                ) {
                                    Text(
                                        when (mode) { NexusThemeMode.SYSTEM -> "System"; NexusThemeMode.LIGHT -> "Light"; NexusThemeMode.DARK -> "Dark" },
                                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        color = if (selected) primaryText else mutedText, fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = border)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            showSettings = false
                            if (ready || generating) resetChat()
                            else messages.clear()
                        }.padding(vertical = 22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("▤", color = mutedText, fontSize = 22.sp)
                        Spacer(Modifier.width(18.dp))
                        Text("Clear all chats", color = primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = border)
                    Spacer(Modifier.height(18.dp))
                    Text("NEXUS AI ${BuildConfig.VERSION_NAME} · Everything stays on your device.\nNo cloud, no tracking.", color = mutedText, fontSize = 14.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(symbol: String, label: String, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = 16.sp) },
        onClick = action,
        leadingIcon = { Text(symbol, fontSize = 21.sp, color = Color(0xFF8992A4)) },
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp)
    )
}

@Composable
private fun BottomSheetHandle(color: Color) {
    Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(width = 42.dp, height = 5.dp).clip(CircleShape).background(color))
}

private fun displayModelName(fileName: String): String = when {
    fileName.contains("Qwen2.5-1.5B", true) -> "Qwen2.5 1.5B"
    fileName.contains("Qwen2.5-3B", true) -> "Qwen2.5 3B"
    fileName.contains("Llama-3.2-1B", true) -> "Llama 3.2 1B"
    fileName.contains("Llama-3.2-3B", true) -> "Llama 3.2 3B"
    else -> fileName.removeSuffix(".gguf").replace("-Instruct", "").replace('-', ' ')
}

private fun formatSize(bytes: Long): String =
    if (bytes < 1_000_000_000L) "${bytes / 1_000_000L} MB" else "%.2f GB".format(bytes / 1_000_000_000.0)

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
