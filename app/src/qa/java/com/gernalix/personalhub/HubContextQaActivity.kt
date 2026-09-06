package com.gernalix.personalhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.gernalix.personalhub.contracts.database.HubEntityRef
import com.gernalix.personalhub.core.hubcontext.HubContextLinks

/** Isolated QA-only host for exercising the production shared Composer on a real device. */
class HubContextQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ref = HubEntityRef(
            requireNotNull(intent.getStringExtra("module")),
            requireNotNull(intent.getStringExtra("kind")),
            requireNotNull(intent.getStringExtra("id")),
        )
        setContent { MaterialTheme { HubContextLinks(ref) } }
    }
}
