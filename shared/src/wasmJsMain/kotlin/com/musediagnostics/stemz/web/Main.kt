package com.musediagnostics.stemz.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.musediagnostics.stemz.App

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val probe = WebAudioProbe()
    // Same commonMain screen as the iOS app (AudioFilterEngine, heart filter, pre-amp, envelope).
    ComposeViewport("composeApp") { App(probe) }
}
