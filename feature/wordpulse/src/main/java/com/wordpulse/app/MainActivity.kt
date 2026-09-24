package com.wordpulse.app

import android.content.Intent
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wordpulse.app.data.SystemTimeProvider
import com.wordpulse.app.data.HealthConnectSleepSource
import com.wordpulse.app.ui.WordPulseRoute
import com.wordpulse.app.ui.WordPulseTheme
import com.wordpulse.app.ui.WordPulseViewModel
import com.wordpulse.app.ui.WordPulseViewModelFactory

class MainActivity : ComponentActivity() {
    private var hubSessionId by mutableStateOf<String?>(null)
    private val viewModel: WordPulseViewModel by viewModels {
        WordPulseViewModelFactory(
            repository = WordPulseRuntime.repository(applicationContext),
            timeProvider = SystemTimeProvider,
            sleepContextSource = HealthConnectSleepSource(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        hubSessionId = intent.hubSessionId()
        enableEdgeToEdge()
        setContent {
            WordPulseTheme {
                WordPulseRoute(viewModel = viewModel, initialSessionId = hubSessionId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hubSessionId = intent.hubSessionId()
    }
}

private fun Intent?.hubSessionId(): String? = this?.data
    ?.takeIf { it.scheme == "personalhub" && it.host == "module" && it.path == "/wordpulse" }
    ?.getQueryParameter("sessionId")?.takeIf(String::isNotBlank)
