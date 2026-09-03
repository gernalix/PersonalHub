package com.gernalix.personalhub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.ui.theme.PersonalHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PersonalHubTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PersonalHubApp()
                }
            }
        }
    }
}

@Composable
fun PersonalHubApp() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name) + " v" + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Button(onClick = { context.startActivity(Intent(context, DatabaseActivity::class.java)) }) {
            Text(stringResource(R.string.database_title))
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(HubModule.entries, key = { it.name }) { module ->
                ModuleTile(
                    module = module,
                    onClick = {
                        context.startActivity(
                            Intent().setClassName(context.packageName, module.activityClassName),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleTile(module: HubModule, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = module.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(module.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(module.subtitleRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

enum class HubModule(
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
    val activityClassName: String,
) {
    PEOPLE(
        titleRes = R.string.module_people,
        subtitleRes = R.string.module_people_subtitle,
        icon = Icons.Filled.Person,
        activityClassName = "com.supercontacts.app.MainActivity",
    ),
    TIMER(
        titleRes = R.string.module_timer,
        subtitleRes = R.string.module_timer_subtitle,
        icon = Icons.Filled.Timer,
        activityClassName = "com.example.multitimetracker.MainActivity",
    ),
    PLACES(
        titleRes = R.string.module_places,
        subtitleRes = R.string.module_places_subtitle,
        icon = Icons.Filled.Place,
        activityClassName = "com.gernalix.luoghi.MainActivity",
    ),
    SUBSTANCES(
        titleRes = R.string.module_substances,
        subtitleRes = R.string.module_substances_subtitle,
        icon = Icons.Filled.LocalPharmacy,
        activityClassName = "com.gernalix.sostanze.MainActivity",
    ),
    WORDPULSE(
        titleRes = R.string.module_wordpulse,
        subtitleRes = R.string.module_wordpulse_subtitle,
        icon = Icons.Filled.TextFields,
        activityClassName = "com.wordpulse.app.MainActivity",
    ),
}
