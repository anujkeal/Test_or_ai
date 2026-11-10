package com.keal.nexus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.provider.Settings
import android.provider.ContactsContract
import android.net.Uri
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.keal.nexus.device.NexusDeviceActions
import com.keal.nexus.device.NexusAccessibilityService
import com.keal.nexus.agent.PhoneControlAgent
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

class VoiceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        setContent { VoiceAssistantScreen(modelPath = modelPath, onClose = { finish() }) }
    }

    companion object {
        const val EXTRA_MODEL_PATH = "voice_model_path"
    }
}

private enum class VoiceVisualState { IDLE, LISTENING, THINKING, SPEAKING }
private data class SphereDot(val x: Float, val y: Float, val z: Float, val phase: Float, val seed: Float)

@Composable
private fun VoiceAssistantScreen(modelPath: String?, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { runCatching { AiChat.getInferenceEngine(context.applicationContext) }.getOrNull() }
    val bg = Color(0xFF05070B)
    val ink = Color(0xFFF2F5FA)
    val muted = Color(0xFF8A94A6)
    val accent = Color(0xFF5EE0B0)

    var visualState by remember { mutableStateOf(VoiceVisualState.IDLE) }
    var status by remember { mutableStateOf("Tap the microphone to talk") }
    var caption by remember { mutableStateOf("Speech recognition runs on-device when supported") }
    var transcript by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var onRecognized by remember { mutableStateOf<(String) -> Unit>({}) }
    var startListeningRequested by remember { mutableStateOf(false) }
    var pendingTorchCommand by remember { mutableStateOf<String?>(null) }
    var frame by remember { mutableLongStateOf(0L) }
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    var selectedCallName by remember { mutableStateOf<String?>(null) }
    val voiceContactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val requestedName = selectedCallName ?: "contact"
        val contactUri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && contactUri != null) {
            val contact = runCatching {
                context.contentResolver.query(contactUri,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0).orEmpty() to cursor.getString(1).orEmpty() else null
                }
            }.getOrNull()
            val number = contact?.second
            if (!number.isNullOrBlank()) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
                    response = "Opened the dialer for ${contact.first.ifBlank { requestedName }}. Review the number and tap Call to confirm."
                    caption = response
                    status = "Ready for your confirmation"
                    if (ttsReady) tts?.speak(response, TextToSpeech.QUEUE_FLUSH, null, "nexus-call")
                }.onFailure {
                    response = "I found the contact but could not open the dialer: ${it.message ?: "no dialer available"}"
                    caption = response
                    status = "Action failed"
                }
            } else {
                response = "I could not read a phone number for that contact. Choose a contact with a saved number."
                caption = response
                status = "Contact number unavailable"
            }
        } else {
            response = "Contact selection cancelled. No call was placed."
            caption = response
            status = "Cancelled"
        }
        selectedCallName = null
        visualState = VoiceVisualState.IDLE
    }


    val dots = remember {
        val random = Random(23)
        List(1800) { index ->
            val y = 1f - 2f * (index + .5f) / 1800f
            val radius = sqrt(max(0f, 1f - y * y))
            val theta = index * 2.399963f
            SphereDot(cos(theta) * radius, y, sin(theta) * radius, random.nextFloat() * 6.28318f, random.nextFloat())
        }
    }

    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val command = pendingTorchCommand
        pendingTorchCommand = null
        if (granted && command != null) {
            val result = NexusDeviceActions(context.applicationContext).execute(command)
                ?: "I couldn't complete that device action."
            response = result
            caption = result
            status = "NEXUS action"
            visualState = if (ttsReady) VoiceVisualState.SPEAKING else VoiceVisualState.IDLE
            if (ttsReady) tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "nexus-device-action")
        } else if (!granted) {
            error = "Camera permission is required to control the flashlight. You can allow it in Android app settings."
            caption = error.orEmpty()
            status = "Permission needed"
        }
    }

    fun submitRecognizedSpeech(spokenText: String) {
        val cleanText = spokenText.trim()
        if (cleanText.isEmpty()) {
            visualState = VoiceVisualState.IDLE
            status = "I didn't catch that"
            return
        }
        transcript = cleanText
        response = ""
        error = null
        visualState = VoiceVisualState.THINKING
        status = "Thinking…"
        caption = "Sending your words to NEXUS"
        scope.launch {
            try {
                val callMatch = Regex("""^(?:please\s+)?(?:call|dial|phone)\s+(?:my\s+)?(.+)$""", RegexOption.IGNORE_CASE).matchEntire(cleanText)
                if (callMatch != null) {
                    val name = callMatch.groupValues[1].trim()
                    selectedCallName = name
                    response = "Choose $name from your contacts. I will open the dialer; you confirm the call there."
                    caption = response
                    status = "Choose a contact"
                    voiceContactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    return@launch
                }

                if (PhoneControlAgent.isMultiStepGoal(cleanText)) {
                    val controlEnabled = context.getSharedPreferences("nexus_preferences", android.content.Context.MODE_PRIVATE)
                        .getBoolean("android_control_enabled", false)
                    if (!controlEnabled) {
                        response = "Phone control is disabled. Enable Android command control in NEXUS Settings first."
                    } else if (!NexusAccessibilityService.isConnected()) {
                        response = "Enable NEXUS app interaction in Android Settings → Accessibility to let the voice assistant read and control visible app screens."
                    } else {
                        val actionHistory = mutableListOf<String>()
                        var screen = NexusAccessibilityService.screenSnapshot()
                        var result: String? = null
                        var step = 0
                        while (step < 8 && result == null) {
                            if (screen.isNullOrBlank()) {
                                result = "I cannot read the current screen. Check NEXUS Accessibility permission."
                                break
                            }
                            val rawAction = StringBuilder()
                            kotlinx.coroutines.withTimeout(30_000L) {
                                activeEngine.sendUserPrompt(
                                    PhoneControlAgent.stepPrompt(cleanText, actionHistory, screen),
                                    160
                                ).collect { token -> rawAction.append(token) }
                            }
                            val action = PhoneControlAgent.parse(rawAction.toString())
                            if (action == null) {
                                result = "I couldn't get a valid one-action instruction from the model. Please try again."
                                break
                            }
                            val summary = when (action.action) {
                                "open_app" -> "open_app ${action.name}"
                                "click" -> "click ${action.id}"
                                "type" -> "type ${action.id}"
                                "scroll" -> "scroll ${action.direction}"
                                else -> action.action
                            }
                            if (actionHistory.lastOrNull()?.substringBefore(" -> ") == summary) {
                                result = "I stopped because the model repeated the previous action."
                                break
                            }
                            when (action.action) {
                                "done" -> result = "Completed: $cleanText"
                                "fail" -> result = action.reason ?: "The phone agent could not continue."
                                "open_app" -> {
                                    val opened = NexusDeviceActions(context.applicationContext).execute("open ${action.name}")
                                    if (opened == null || opened.startsWith("I couldn't", true) || opened.startsWith("I could not", true)) {
                                        result = opened ?: "I couldn't open that app."
                                    } else actionHistory += "$summary -> $opened"
                                }
                                "click" -> {
                                    val id = action.id!!
                                    val line = screen.lineSequence().firstOrNull { it.substringBefore(":").trim().toIntOrNull() == id }
                                    val label = line?.substringAfter(":")?.substringBefore(" [")?.trim().orEmpty()
                                    if (line == null) result = "That screen id is no longer available."
                                    else if (PhoneControlAgent.isSensitiveTarget(label)) result = "That control needs your confirmation. Please tap it yourself."
                                    else if (!NexusAccessibilityService.clickScreenElement(id)) result = "I couldn't tap that screen item."
                                    else actionHistory += "$summary -> tapped $label"
                                }
                                "type" -> {
                                    val id = action.id!!
                                    val line = screen.lineSequence().firstOrNull { it.substringBefore(":").trim().toIntOrNull() == id }
                                    if (line == null || "[text field]" !in line) result = "That id is not an editable text field."
                                    else if (!NexusAccessibilityService.typeScreenElement(id, action.text.orEmpty())) result = "I couldn't type into that field."
                                    else actionHistory += "$summary -> typed text"
                                }
                                "scroll" -> {
                                    if (!NexusAccessibilityService.scroll(action.direction.orEmpty())) result = "I couldn't find a scrollable area."
                                    else actionHistory += "$summary -> completed"
                                }
                                "back" -> {
                                    if (!NexusAccessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) result = "Android Back is unavailable."
                                    else actionHistory += "$summary -> completed"
                                }
                            }
                            if (result == null) {
                                step++
                                status = "Phone control · step $step/8"
                                delay(900)
                                screen = NexusAccessibilityService.screenSnapshot()
                            }
                        }
                        response = result ?: "I stopped after 8 actions to keep phone control bounded."
                    }
                    caption = response
                    status = "NEXUS phone control"
                    visualState = if (ttsReady) VoiceVisualState.SPEAKING else VoiceVisualState.IDLE
                    if (ttsReady) tts?.speak(response, TextToSpeech.QUEUE_FLUSH, null, "nexus-phone-agent")
                    return@launch
                }

                // Flashlight control requires Android's runtime CAMERA permission.
                val torchCommand = cleanText.lowercase() in listOf(
                    "turn on flashlight", "turn on the flashlight", "switch on flashlight", "switch on the flashlight",
                    "enable flashlight", "enable the flashlight", "turn off flashlight", "turn off the flashlight",
                    "switch off flashlight", "switch off the flashlight", "disable flashlight", "disable the flashlight"
                )
                if (torchCommand && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    pendingTorchCommand = cleanText
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    return@launch
                }
                // Deterministic, allow-listed device actions run before asking the local model.
                val deviceResult = NexusDeviceActions(context.applicationContext).execute(cleanText)
                if (deviceResult != null) {
                    response = deviceResult
                    caption = deviceResult
                    status = "NEXUS action"
                    visualState = VoiceVisualState.SPEAKING
                    if (ttsReady) {
                        tts?.speak(deviceResult, TextToSpeech.QUEUE_FLUSH, null, "nexus-device-action")
                    } else {
                        visualState = VoiceVisualState.IDLE
                    }
                    return@launch
                }
                val activeEngine = engine ?: kotlin.error("The inference engine is unavailable.")
                if (activeEngine.state.value !is InferenceEngine.State.ModelReady) {
                    kotlin.error("The model is not ready. Return to chat and load a model first.")
                }
                if (modelPath.isNullOrBlank() || activeEngine.loadedModelPath != modelPath) {
                    kotlin.error("The selected model is not loaded. Return to chat and select it again.")
                }
                activeEngine.sendUserPrompt(cleanText, 512).collect { token ->
                    response += token
                }
                if (response.isBlank()) response = "I couldn't generate a response. Please try again."
                val leakedToolPayload = Regex("""(?s)^\s*\{.*("tool"|"tool_call"|"tool_calls").*\}\s*$""").matches(response.trim())
                if (leakedToolPayload) response = "I recognised a possible phone action, but the model returned an internal command instead of a safe action. Try a direct command such as: open Google Maps or open YouTube."
                visualState = VoiceVisualState.SPEAKING
                status = "NEXUS"
                caption = response
                if (ttsReady) {
                    tts?.speak(response, TextToSpeech.QUEUE_FLUSH, null, "nexus-response")
                } else {
                    visualState = VoiceVisualState.IDLE
                    status = "Response ready"
                }
            } catch (t: Throwable) {
                visualState = VoiceVisualState.IDLE
                status = "Something went wrong"
                error = t.message ?: "NEXUS could not process your speech."
                caption = error.orEmpty()
            }
        }
    }

    onRecognized = { spokenText -> submitRecognizedSpeech(spokenText) }

    DisposableEffect(context) {
        var recognizer: SpeechRecognizer? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    visualState = VoiceVisualState.LISTENING
                    status = "Listening…"
                    caption = "Speak naturally. NEXUS is listening."
                    error = null
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    visualState = VoiceVisualState.THINKING
                    status = "Recognising speech…"
                    caption = "Converting your speech to text"
                }
                override fun onError(errorCode: Int) {
                    visualState = VoiceVisualState.IDLE
                    status = "Speech recognition stopped"
                    error = when (errorCode) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that. Please try again."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't hear anything. Tap the microphone and try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                        else -> "Speech recognition error ($errorCode). Please try again."
                    }
                    caption = error.orEmpty()
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (spoken.isNullOrBlank()) {
                        visualState = VoiceVisualState.IDLE
                        status = "I didn't catch that"
                    } else onRecognized(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) transcript = partial
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        speechRecognizer = recognizer
        onDispose {
            recognizer?.destroy()
            speechRecognizer = null
        }
    }

    DisposableEffect(context) {
        val speech = TextToSpeech(context) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (result == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
        tts = speech
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                visualState = VoiceVisualState.SPEAKING
            }
            override fun onDone(utteranceId: String?) {
                visualState = VoiceVisualState.IDLE
                status = "Tap the microphone to talk"
                caption = "You can continue the conversation."
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                visualState = VoiceVisualState.IDLE
                status = "Response ready"
            }
        })
        onDispose {
            speech.stop()
            speech.shutdown()
            tts = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListeningRequested = true
        else {
            error = "Microphone permission was denied. Allow it in Android app settings to use voice."
            status = "Microphone permission needed"
            caption = error.orEmpty()
        }
    }

    LaunchedEffect(startListeningRequested) {
        if (startListeningRequested) {
            startListeningRequested = false
            val recognizer = speechRecognizer
            if (recognizer == null) {
                error = "On-device speech recognition is not available on this phone. Install or enable an on-device speech recognition service to use private voice input."
                status = "On-device recognition unavailable"
                caption = error.orEmpty()
            } else {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                try {
                    transcript = ""
                    response = ""
                    error = null
                    recognizer.startListening(intent)
                } catch (t: Throwable) {
                    status = "Couldn't start listening"
                    error = t.message ?: "Speech recognition could not start."
                    caption = error.orEmpty()
                }
            }
        }
    }

    val targetSpeed = when (visualState) {
        VoiceVisualState.IDLE -> .004f
        VoiceVisualState.LISTENING -> .006f
        VoiceVisualState.THINKING -> .028f
        VoiceVisualState.SPEAKING -> .008f
    }
    val targetRadius = when (visualState) {
        VoiceVisualState.IDLE -> .62f
        VoiceVisualState.LISTENING -> .66f
        VoiceVisualState.THINKING -> .50f
        VoiceVisualState.SPEAKING -> .64f
    }
    val speed = animateFloatAsState(targetSpeed, tween(300), label = "sphereSpeed").value
    val radius = animateFloatAsState(targetRadius, tween(300), label = "sphereRadius").value
    val twist = animateFloatAsState(if (visualState == VoiceVisualState.THINKING) 2.4f else 0f, tween(300), label = "sphereTwist").value
    val ripple = animateFloatAsState(if (visualState == VoiceVisualState.SPEAKING) .3f else 0f, tween(300), label = "sphereRipple").value
    val jitter = animateFloatAsState(if (visualState == VoiceVisualState.LISTENING) .55f else 0f, tween(300), label = "sphereJitter").value
    val time = (frame / 1_000_000_000.0).toFloat()
    val angle = time * speed * 60f
    val level = max(0f, .5f + .5f * sin(time * 7.3f) * sin(time * 2.9f + 1f))
    val offsetX = remember { FloatArray(1800) }
    val offsetY = remember { FloatArray(1800) }

    Surface(color = bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("×", color = ink, fontSize = 30.sp, fontWeight = FontWeight.Light) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Voice", color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(accent, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("NEXUS · on device", color = muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.width(48.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1f)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { pointer = Offset.Unspecified },
                                onDragCancel = { pointer = Offset.Unspecified }
                            ) { change, _ -> pointer = change.position }
                        }
                        .pointerInput(Unit) { detectTapGestures { pointer = it } }
                ) {
                    val size = min(this.size.width, this.size.height)
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val baseRadius = size * radius * .5f
                    val tiltCos = cos(.35f)
                    val tiltSin = sin(.35f)
                    dots.forEachIndexed { index, dot ->
                        val a = angle + twist * dot.y * sin(time * 1.3f)
                        val ca = cos(a)
                        val sa = sin(a)
                        val x1 = dot.x * ca + dot.z * sa
                        val z1 = -dot.x * sa + dot.z * ca
                        val y2 = dot.y * tiltCos - z1 * tiltSin
                        val z2 = dot.y * tiltSin + z1 * tiltCos
                        val pulse = if (visualState == VoiceVisualState.IDLE || visualState == VoiceVisualState.THINKING) .03f * sin(time * 2f) else 0f
                        val motion = 1f + pulse +
                            ripple * level * sin(dot.y * 7f - time * 5f) +
                            jitter * level * dot.seed * .4f * sin(time * 8f + dot.phase)
                        val perspective = 1f + z2 * .22f
                        var x = cx + x1 * motion * baseRadius * perspective + offsetX[index]
                        var y = cy + y2 * motion * baseRadius * perspective + offsetY[index]
                        if (pointer != Offset.Unspecified) {
                            val dx = x - pointer.x
                            val dy = y - pointer.y
                            val distanceSquared = dx * dx + dy * dy
                            if (distanceSquared < 7000f) {
                                val distance = sqrt(distanceSquared) + .01f
                                val force = (84f - distance) / 84f
                                offsetX[index] += dx / distance * force * 7f
                                offsetY[index] += dy / distance * force * 7f
                            }
                        }
                        offsetX[index] *= .9f
                        offsetY[index] *= .9f
                        x = cx + x1 * motion * baseRadius * perspective + offsetX[index]
                        y = cy + y2 * motion * baseRadius * perspective + offsetY[index]
                        val alpha = (.18f + .82f * (z2 * .5f + .5f)).coerceIn(.12f, 1f)
                        val dotSize = (.7f + dot.seed * .9f) * perspective
                        drawCircle(ink.copy(alpha = alpha), radius = dotSize.coerceAtLeast(.35f), center = Offset(x, y))
                    }
                }
            }
            Text(status, color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(caption, color = muted, fontSize = 13.sp, maxLines = 2)
            if (transcript.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("YOU", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(transcript, color = ink, fontSize = 14.sp, maxLines = 3)
            }
            if (response.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("NEXUS", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(response, color = ink, fontSize = 14.sp, maxLines = 4)
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error.orEmpty(), color = Color(0xFFE88991), fontSize = 12.sp, maxLines = 3)
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                onClick = {
                    if (visualState == VoiceVisualState.LISTENING) {
                        speechRecognizer?.cancel()
                        visualState = VoiceVisualState.IDLE
                        status = "Listening cancelled"
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startListeningRequested = true
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                color = if (visualState == VoiceVisualState.LISTENING) accent else ink,
                contentColor = bg,
                shape = CircleShape,
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (visualState == VoiceVisualState.LISTENING) "■" else "●", fontSize = 24.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Tap to speak · on-device recognition", color = muted, fontSize = 11.sp)
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                Text("Set up app control", color = accent, fontSize = 12.sp)
            }
        }
    }
}
