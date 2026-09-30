package com.musediagnostics.stemz

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { App() }

actual fun platformName(): String =
    UIDevice.currentDevice.systemName + " " + UIDevice.currentDevice.systemVersion
