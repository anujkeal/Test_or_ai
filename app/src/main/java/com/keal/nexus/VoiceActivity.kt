package com.keal.nexus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*
import kotlin.random.Random

class VoiceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoiceVisualizerScreen(onClose = { finish() }) }
    }
}

private enum class VoiceVisualState { IDLE, LISTENING, THINKING, SPEAKING }
private data class SphereDot(val x: Float, val y: Float, val z: Float, val phase: Float, val seed: Float)

@Composable
private fun VoiceVisualizerScreen(onClose: () -> Unit) {
    val bg = Color(0xFF05070B)
    val ink = Color(0xFFF2F5FA)
    val muted = Color(0xFF8A94A6)
    val accent = Color(0xFF5EE0B0)
    val scope = rememberCoroutineScope()
    var visualState by remember { mutableStateOf(VoiceVisualState.IDLE) }
    var status by remember { mutableStateOf("Tap to talk") }
    var caption by remember { mutableStateOf("Touch the dots, or tap the mic") }
    var sequence by remember { mutableStateOf<Job?>(null) }
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    var frame by remember { mutableLongStateOf(0L) }
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

    fun startAnimation() {
        sequence?.cancel()
        if (visualState != VoiceVisualState.IDLE) {
            visualState = VoiceVisualState.IDLE
            status = "Tap to talk"
            caption = "Touch the dots, or tap the mic"
            return
        }
        visualState = VoiceVisualState.LISTENING
        status = "Listening…"
        caption = "Listening for your voice"
        sequence = scope.launch {
            delay(2800)
            visualState = VoiceVisualState.THINKING
            status = "Thinking…"
            caption = "NEXUS is processing"
            delay(1700)
            visualState = VoiceVisualState.SPEAKING
            status = "Speaking…"
            caption = "Here is your response."
            delay(3000)
            visualState = VoiceVisualState.IDLE
            status = "Tap to talk"
            caption = "Touch the dots, or tap the mic"
        }
    }

    val seconds = frame / 1_000_000_000.0
    val time = seconds.toFloat()
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
    val targetTwist = if (visualState == VoiceVisualState.THINKING) 2.4f else 0f
    val targetRipple = if (visualState == VoiceVisualState.SPEAKING) .3f else 0f
    val targetJitter = if (visualState == VoiceVisualState.LISTENING) .55f else 0f
    val pulse = if (visualState == VoiceVisualState.IDLE || visualState == VoiceVisualState.THINKING) .03f else 0f
    val level = max(0f, .5f + .5f * sin(time * 7.3f) * sin(time * 2.9f + 1f))
    val speed = animateFloatAsState(targetSpeed, tween(300), label = "sphereSpeed").value
    val radius = animateFloatAsState(targetRadius, tween(300), label = "sphereRadius").value
    val twist = animateFloatAsState(targetTwist, tween(300), label = "sphereTwist").value
    val ripple = animateFloatAsState(targetRipple, tween(300), label = "sphereRipple").value
    val jitter = animateFloatAsState(targetJitter, tween(300), label = "sphereJitter").value
    // The source HTML advances rotation once per animation frame (~60 fps).
    val angle = time * speed * 60f
    val offsetX = remember { FloatArray(1800) }
    val offsetY = remember { FloatArray(1800) }

    Surface(color = bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = onClose) {
                    Text("×", color = ink, fontSize = 30.sp, fontWeight = FontWeight.Light)
                }
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
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1f)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { pointer = Offset.Unspecified },
                                onDragCancel = { pointer = Offset.Unspecified }
                            ) { change, _ -> pointer = change.position }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { pointer = it }
                        }
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
                        val motion = 1f + pulse * sin(time * 2f) +
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
            Spacer(Modifier.height(8.dp))
            Text(caption, color = muted, fontSize = 14.sp)
            Spacer(Modifier.height(22.dp))
            Surface(
                onClick = { startAnimation() },
                color = if (visualState == VoiceVisualState.IDLE) ink else accent,
                contentColor = bg,
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (visualState == VoiceVisualState.IDLE) "●" else "■", fontSize = 24.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Voice animation preview", color = muted, fontSize = 11.sp)
        }
    }
}
