package com.musediagnostics.stemz

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val TealPrimary = Color(0xFF2ABFBF)

@Composable
fun App() {
    MaterialTheme(colorScheme = lightColorScheme(primary = TealPrimary)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            var taps by remember { mutableStateOf(0) }
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Stemz iOS", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text("Hello from Kotlin Multiplatform")
                Text("Kotlin ${KotlinVersion.CURRENT} on ${platformName()}")
                Spacer(Modifier.height(24.dp))
                Button(onClick = { taps++ }) { Text("Tapped $taps times") }
            }
        }
    }
}

expect fun platformName(): String
