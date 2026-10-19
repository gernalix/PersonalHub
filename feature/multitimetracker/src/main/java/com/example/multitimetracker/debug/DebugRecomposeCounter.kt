// v323
package com.example.multitimetracker.debug

import androidx.compose.runtime.mutableIntStateOf
import com.example.multitimetracker.BuildConfig

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode

@Composable
fun DebugRecomposeCounter(tag: String) {
    if (!BuildConfig.DEBUG) return
    if (LocalInspectionMode.current) return

    val counter = remember { mutableIntStateOf(0) }
    SideEffect {
        counter.intValue++
        android.util.Log.d("RECOMPOSE", "$tag -> ${counter.intValue}")
    }
}
