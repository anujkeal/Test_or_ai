package com.keal.nexus

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
import com.keal.nexus.data.ModelCatalog
import com.keal.nexus.data.ModelManager
import com.keal.nexus.data.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    var showModels by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var downloadModel by remember { mutableStateOf<ModelSpec?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
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
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ModelManager.importModel(context, uri) } }
                .onSuccess { file ->
                    installed = ModelManager.installedModels(context)
                    showModels = false
                    modelPath = file.absolutePath
                    downloadStatus = "Imported ${file.name}"
                }
                .onFailure { downloadStatus = it.message ?: "Could not import model." }
        }
    }

    LaunchedEffect(modelPath, loadAttempt) {
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
                when (val initial = local.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }) {
                    is InferenceEngine.State.Error -> throw initial.exception
                    else -> Unit
                }
                loadStage = "Loading model weights"
                local.loadModel(File(path).absolutePath)
                loadStage = "Preparing chat"
                local.setSystemPrompt("You are NEXUS, a helpful, clear and thoughtful on-device AI assistant. Answer accurately and naturally.")
            }
            ready = true
        } catch (t: Throwable) {
            loadError = buildString {
                append(t.javaClass.simpleName)
                t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        } finally {
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
                break
            }
            downloadProgress = snapshot.progress.coerceIn(0f, 1f)
            when (snapshot.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val model = downloadModel
                    val file = model?.let { ModelManager.modelFile(context, it) }
                    val expectedSha = model?.sha256
                    val valid = file?.let { candidate -> expectedSha == null || withContext(Dispatchers.IO) { ModelManager.verifySha256(candidate, expectedSha) } } ?: false
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
                    break
                }
                DownloadManager.STATUS_FAILED -> {
                    downloadStatus = "Download failed. Check your connection and storage."
                    downloadId = null
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
                                messages.clear(); input = ""; loadError = null; showMenu = false
                            }
                            MenuEntry("▱", "Workspace folder") {
                                showMenu = false
                                context.startActivity(android.content.Intent(context, com.keal.nexus.agent.AgentActivity::class.java))
                            }
                            MenuEntry("◇", "Models") { showMenu = false; showModels = true }
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
                                Text("Loading model…", color = mutedText, fontSize = 13.sp)
                            }
                            if (loadError != null) {
                                Spacer(Modifier.height(18.dp))
                                Surface(color = if (isDark) Color(0xFF3B2027) else Color(0xFFFFE9EA), shape = RoundedCornerShape(16.dp)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Couldn't load model", color = Color(0xFFE36B79), fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(6.dp))
                                        Text(loadError!!, color = primaryText, fontSize = 12.sp)
                                        Spacer(Modifier.height(8.dp))
                                        TextButton(onClick = { loadAttempt++ }) { Text("Try again") }
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
                        enabled = ready && !generating,
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
                                scope.launch {
                                    try {
                                        val answer = StringBuilder()
                                        engine?.sendUserPrompt(prompt, 512)?.collect { token ->
                                            answer.append(token)
                                            if (messages.isNotEmpty()) messages[messages.lastIndex] = ChatMessage(false, answer.toString())
                                        }
                                    } catch (t: Throwable) {
                                        if (messages.isNotEmpty() && messages.last().text.isBlank()) messages.removeAt(messages.lastIndex)
                                        messages.add(ChatMessage(false, "I couldn't generate a response: ${t.message ?: "unknown error"}"))
                                    } finally { generating = false }
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
                        val file = installed.firstOrNull { it.name == model.fileName }
                        val selected = file?.absolutePath == modelPath
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = file != null) {
                                if (file!!.absolutePath != modelPath) messages.clear()
                                modelPath = file.absolutePath
                                showModels = false
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
                                Text("✓", color = NexusMint, fontSize = 25.sp)
                            } else {
                                Button(
                                    onClick = {
                                        if (downloadId == null) {
                                            runCatching {
                                                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                                val request = DownloadManager.Request(android.net.Uri.parse(model.downloadUrl))
                                                    .setTitle(model.name)
                                                    .setDescription("Downloading GGUF model")
                                                    .setMimeType("application/octet-stream")
                                                    .setAllowedOverMetered(!wifiOnly)
                                                    .setAllowedOverRoaming(false)
                                                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                    .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, "models/${model.fileName}")
                                                manager.enqueue(request)
                                            }.onSuccess { id ->
                                                downloadModel = model
                                                downloadId = it
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
                        Modifier.fillMaxWidth().clickable { importLauncher.launch(arrayOf("*/*")) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("＋", color = NexusMint, fontSize = 28.sp)
                        Spacer(Modifier.width(14.dp))
                        Text("Import a .gguf file", color = primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
                        Modifier.fillMaxWidth().clickable { messages.clear(); showSettings = false }.padding(vertical = 22.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("▤", color = mutedText, fontSize = 22.sp)
                        Spacer(Modifier.width(18.dp))
                        Text("Clear all chats", color = primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = border)
                    Spacer(Modifier.height(18.dp))
                    Text("NEXUS AI 0.6.0 · Everything stays on your device.\nNo cloud, no tracking.", color = mutedText, fontSize = 14.sp, lineHeight = 21.sp)
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
