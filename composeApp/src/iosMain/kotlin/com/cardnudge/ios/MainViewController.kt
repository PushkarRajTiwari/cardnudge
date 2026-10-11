package com.cardnudge.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.cardnudge.ui.App
import platform.UIKit.UIViewController

/** Called from Swift: the whole shared Compose UI as a UIViewController. */
fun MainViewController(): UIViewController = ComposeUIViewController { App(IosPlatform.shared) }

/** Called from the Swift app delegate at launch so region events are handled in the background too. */
fun startBackgroundServices() {
    IosPlatform.shared
}
