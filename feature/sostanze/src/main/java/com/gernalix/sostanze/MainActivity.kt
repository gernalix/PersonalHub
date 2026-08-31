package com.gernalix.sostanze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gernalix.sostanze.ui.SostanzeApp
import com.gernalix.sostanze.ui.theme.SostanzeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SostanzeTheme {
                SostanzeApp()
            }
        }
    }
}
