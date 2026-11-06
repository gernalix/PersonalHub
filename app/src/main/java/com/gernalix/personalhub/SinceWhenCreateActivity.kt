package com.gernalix.personalhub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.gernalix.personalhub.contracts.database.HubDeepLinkContract
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import com.gernalix.personalhub.ui.theme.PersonalHubTheme

/** Host-owned private route for an opt-in already confirmed inside PersonalHub. */
class SinceWhenCreateActivity : ComponentActivity() {
    private var currentUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        currentUri = intent?.data
        enableEdgeToEdge()
        setContent {
            PersonalHubTheme {
                Surface {
                    val target = remember(currentUri) {
                        HubDeepLinkContract.parse(currentUri).target as? HubDeepLinkContract.SinceWhenCreateTarget
                    }
                    if (target != null) {
                        SinceWhenCreateScreen(target.source, ::finish, target.startEnabled, autoCreate = true)
                    } else {
                        com.gernalix.personalhub.core.ui.HubMessagePane(
                            message = stringResource(R.string.deep_link_invalid),
                            actionLabel = stringResource(R.string.deep_link_back), onAction = ::finish,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentUri = intent.data
    }
}
