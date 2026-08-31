package com.wordpulse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.wordpulse.app.data.SystemTimeProvider
import com.wordpulse.app.ui.WordPulseRoute
import com.wordpulse.app.ui.WordPulseTheme
import com.wordpulse.app.ui.WordPulseViewModel
import com.wordpulse.app.ui.WordPulseViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: WordPulseViewModel by viewModels {
        val app = application as WordPulseApplication
        WordPulseViewModelFactory(
            repository = app.repository,
            timeProvider = SystemTimeProvider,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WordPulseTheme {
                WordPulseRoute(viewModel = viewModel)
            }
        }
    }
}
