package com.musediagnostics.stemz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musediagnostics.stemz.audio.AudioInputInfo
import com.musediagnostics.stemz.audio.AudioProbe
import com.musediagnostics.stemz.audio.LevelUpdate
import kotlin.math.roundToInt

private val TealPrimary = Color(0xFF2ABFBF)
private val WaveBlue = Color(0xFF2D7DD2)
private val OkGreen = Color(0xFF4CAF50)
private val WarnRed = Color(0xFFE85555)

@Composable
fun App(probe: AudioProbe) {
    MaterialTheme(colorScheme = lightColorScheme(primary = TealPrimary)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F5F5)) {
            AudioDiagnosticsScreen(probe)
        }
    }
}

@Composable
private fun AudioDiagnosticsScreen(probe: AudioProbe) {
    var permission by remember { mutableStateOf<Boolean?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<AudioInputInfo?>(null) }
    var running by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf<LevelUpdate?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        sessionError = probe.prepareSession()
        info = probe.inputInfo()
    }

    LaunchedEffect(Unit) {
        probe.requestPermission { granted ->
            permission = granted
            if (granted) refresh()
        }
    }
    DisposableEffect(Unit) { onDispose { probe.stop() } }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Stemz iOS", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Step 3: TAAL audio input diagnostics", color = Color(0xFF666666))

        // Connection status
        val usb = info?.usbConnected == true
        StatusPill(
            text = when {
                permission == false -> "Microphone permission denied"
                info == null -> "Checking audio input..."
                usb -> "TAAL / USB audio connected"
                else -> "No USB audio device. Using built-in mic"
            },
            color = when {
                permission == false -> WarnRed
                usb -> OkGreen
                else -> Color(0xFFFF9800)
            },
        )

        InfoCard("Input route") {
            val i = info
            Row2("Device", i?.deviceName ?: "-")
            Row2("Port type", i?.portType ?: "-")
            Row2("Session sample rate", i?.let { "${it.sessionSampleRate.roundToInt()} Hz" } ?: "-")
            Row2("Input channels", i?.inputChannels?.toString() ?: "-")
            Row2("Capture sample rate", level?.let { "${it.captureSampleRate.roundToInt()} Hz" } ?: "(start capture)")
            Row2("Frames / buffer", level?.framesPerBuffer?.toString() ?: "-")
            if (!i?.availableInputs.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Available inputs:", fontSize = 12.sp, color = Color(0xFF666666))
                i!!.availableInputs.forEach { Text("• $it", fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
            sessionError?.let { Text("Session: $it", color = WarnRed, fontSize = 12.sp) }
        }

        InfoCard("Live level") {
            val rms = level?.rmsDbfs ?: -90f
            val peak = level?.peakDbfs ?: -90f
            LinearProgressIndicator(
                progress = { ((rms + 60f) / 60f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = WaveBlue,
                trackColor = Color(0xFFE0E0E0),
            )
            Spacer(Modifier.height(6.dp))
            Row2("RMS", if (running) "${rms.format1()} dBFS" else "-")
            Row2("Peak", if (running) "${peak.format1()} dBFS" else "-")
            Spacer(Modifier.height(8.dp))
            Envelope(level?.envelope)
            Text("Last ~3 s envelope. Tap the diaphragm: you should see spikes.", fontSize = 11.sp, color = Color(0xFF999999))
            captureError?.let { Text("Capture: $it", color = WarnRed, fontSize = 12.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (running) {
                        probe.stop(); running = false
                    } else {
                        captureError = null
                        refresh()
                        probe.start(
                            onUpdate = { level = it },
                            onError = { captureError = it; running = false },
                        )
                        running = true
                        info = probe.inputInfo()
                    }
                },
                enabled = permission == true,
                colors = ButtonDefaults.buttonColors(containerColor = if (running) WarnRed else TealPrimary),
                modifier = Modifier.weight(1f),
            ) { Text(if (running) "Stop capture" else "Start capture") }
            OutlinedButton(onClick = { refresh() }, enabled = !running, modifier = Modifier.weight(1f)) {
                Text("Refresh route")
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = Color(0xFF666666))
        Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Envelope(env: FloatArray?) {
    Canvas(Modifier.fillMaxWidth().height(90.dp).background(Color(0xFFFAFAFA))) {
        val mid = size.height / 2
        drawLine(Color(0xFFE0E0E0), Offset(0f, mid), Offset(size.width, mid))
        if (env == null || env.isEmpty()) return@Canvas
        val step = size.width / env.size
        for (i in env.indices) {
            val h = (env[i].coerceIn(0f, 1f)) * mid
            val x = i * step
            drawLine(WaveBlue, Offset(x, mid - h), Offset(x, mid + h), strokeWidth = step.coerceAtLeast(1f))
        }
    }
}

private fun Float.format1(): String {
    val r = (this * 10).roundToInt() / 10.0
    return r.toString()
}
