package com.wordpulse.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wordpulse.app.ui.WordPulseTheme

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WordPulseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "Sleep data in WordPulse",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = "WordPulse can read sleep sessions from Health Connect to estimate time since waking " +
                                "and recent sleep duration. This optional context is used only on-device to improve the " +
                                "personal alertness estimate. WordPulse does not upload or share this data.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "You can deny or revoke access at any time. The typing-based score continues to work " +
                                "without Health Connect.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = ::finish) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}
