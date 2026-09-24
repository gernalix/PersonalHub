package com.gernalix.personalhub.soldi

import android.content.Intent
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule

class SoldiActivity : ComponentActivity() {
    private var hubTransactionUuid by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        hubTransactionUuid = intent.hubTransactionUuid()
        enableEdgeToEdge()
        val capsule = FinanceCapsule(applicationContext)
        setContent {
            SoldiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SoldiV2Screen(capsule, ::finish, hubTransactionUuid)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hubTransactionUuid = intent.hubTransactionUuid()
    }
}

private fun Intent?.hubTransactionUuid(): String? = this?.data
    ?.takeIf { it.scheme == "personalhub" && it.host == "module" && it.path == "/soldi" }
    ?.getQueryParameter("transactionUuid")
    ?.takeIf(String::isNotBlank)
