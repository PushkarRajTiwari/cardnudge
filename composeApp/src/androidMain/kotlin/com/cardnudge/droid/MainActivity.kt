package com.cardnudge.droid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.cardnudge.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Scoped to this Activity's composition, so it is released with the Activity.
            val platform = remember { AndroidPlatform(this) }
            App(platform)
        }
    }
}
