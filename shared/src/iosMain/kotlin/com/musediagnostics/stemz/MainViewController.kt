package com.musediagnostics.stemz

import androidx.compose.ui.window.ComposeUIViewController
import com.musediagnostics.stemz.audio.IosAudioProbe
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val probe = IosAudioProbe()
    return ComposeUIViewController { App(probe) }
}
