package com.musediagnostics.stemz.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport

private fun userAgent(): String = js("navigator.userAgent")

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport("composeApp") { WebHello() }
}

/** Step W1: proves Kotlin/Wasm + Compose runs in iPhone Safari. Replaced by the real screen in W2. */
@Composable
private fun WebHello() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2ABFBF))) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            var taps by remember { mutableStateOf(0) }
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Stemz Web", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("Hello from Kotlin/Wasm + Compose")
                Text("Kotlin ${KotlinVersion.CURRENT}")
                Spacer(Modifier.height(12.dp))
                Text(userAgent(), fontSize = 11.sp, color = Color(0xFF999999), textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { taps++ }) { Text("Tapped $taps times") }
            }
        }
    }
}
