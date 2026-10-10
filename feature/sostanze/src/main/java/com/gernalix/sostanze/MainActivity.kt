package com.gernalix.sostanze

import android.content.Intent
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gernalix.sostanze.ui.SostanzeApp
import com.gernalix.sostanze.ui.theme.SostanzeTheme

class MainActivity : ComponentActivity() {
    private var hubSubstanceId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        hubSubstanceId = intent.hubSubstanceId(this)
        enableEdgeToEdge()
        setContent {
            SostanzeTheme {
                SostanzeApp(hubSubstanceId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hubSubstanceId = intent.hubSubstanceId(this)
    }
}

private fun Intent?.hubSubstanceId(context: android.content.Context): Long? = this?.data
    ?.takeIf { it.scheme == "personalhub" && it.host == "module" && it.path == "/substances" }
    ?.getQueryParameter("substanceId")?.let { id ->
        id.toLongOrNull() ?: com.gernalix.personalhub.core.database.capsules.identity.CanonicalIdentityCapsule(context).localKey("substances/substance", id)?.toLongOrNull()
    }
