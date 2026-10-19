package com.gernalix.personalhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gernalix.personalhub.ui.theme.PersonalHubTheme

class SinceWhenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.gernalix.personalhub.core.database.DatabaseStartupGate.blockIfNotReady(this)) return
        enableEdgeToEdge()
        val counterId = intent?.data?.lastPathSegment?.let { id ->
            id.toLongOrNull() ?: com.gernalix.personalhub.core.database.capsules.identity.CanonicalIdentityCapsule(this).localKey("since_when/counter",id)?.toLongOrNull()
        }
        setContent {
            PersonalHubTheme {
                Surface(Modifier.fillMaxSize()) {
                    val mode = androidx.compose.runtime.produceState<String?>(null, counterId) {
                        value = counterId?.let { com.gernalix.personalhub.core.database.PersonalHubDatabase.get(this@SinceWhenActivity).sinceWhenCounterDao().get(it)?.mode }
                            ?: com.gernalix.personalhub.contracts.database.CounterModes.SINCE_WHEN
                    }.value
                    mode?.let {
                        com.gernalix.personalhub.core.ui.ModuleHistoryHost(com.gernalix.personalhub.contracts.database.CounterModes.module(it), counterId) {
                            SinceWhenScreen(onBack = ::finish, initialCounterId = counterId, mode = it)
                        }
                    }
                }
            }
        }
    }
}
