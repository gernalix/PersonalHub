// v323
package com.example.multitimetracker.debug

import com.example.multitimetracker.BuildConfig

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode

@Composable
fun DebugRecomposeCounter(tag: String) {
    if (!BuildConfig.DEBUG) return
    if (LocalInspectionMode.current) return

    val counter = remember { mutableStateOf(0) }
    SideEffect {
        counter.value++
        android.util.Log.d("RECOMPOSE", "$tag -> ${counter.value}")
    }
}
