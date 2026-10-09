package com.gernalix.personalhub.notifications.capsules.archive

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.notifications.R
import com.gernalix.personalhub.core.ui.HubTimeFormat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NotificationsActivity : ComponentActivity() {
    private var generation by mutableStateOf(0)
    private var exportResult by mutableStateOf<Int?>(null)
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
        if (uri != null) {
            // Caller chooses a local document provider; no network/sync transport exists here.
            lifecycleExport(uri)
        }
    }
    private fun lifecycleExport(uri: android.net.Uri) {
        lifecycleScope.launch { writeExport(uri) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme { Surface { ArchiveScreen(generation, exportResult,
            onExport={export.launch("notifications.sqlite")}, onPermission={startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}, onBack={finish()}) } } }
    }
    override fun onResume() { super.onResume(); generation++ }
    internal suspend fun writeExport(uri: android.net.Uri) {
        exportResult = withContext(Dispatchers.IO) {
            try { check(uri.authority in setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents")); contentResolver.openOutputStream(uri,"w")!!.use { ArchiveStore.get(this@NotificationsActivity).export(it) }; R.string.export_ok }
            catch (_: Exception) { R.string.archive_error }
        }
    }
}

@Composable
internal fun ArchiveScreen(generation: Int, exportResult: Int?, onExport: () -> Unit, onPermission: () -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { ArchiveStore.get(context) }
    val changed by ArchiveStore.changes.collectAsState()
    var search by remember { mutableStateOf("") }; var app by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }; var until by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(emptyList<ArchiveEvent>()) }; var apps by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf<ArchiveEvent?>(null) }
    var error by remember { mutableStateOf(false) }; var more by remember { mutableStateOf(0) }
    val granted = remember(generation) { context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context,CollectorService::class.java)) }
    val status = context.getSharedPreferences("notification_status",0)
    LaunchedEffect(search,app,from,until,changed,more) {
        try {
            val dates = archiveDateBounds(from,until)
            val result = withContext(Dispatchers.IO) { store.query(search,app,dates.first,dates.second,if(more==0) Long.MAX_VALUE else rows.lastOrNull()?.id ?: Long.MAX_VALUE) to store.apps() }
            rows = if(more==0) result.first else rows + result.first; apps=result.second; error=false
        } catch (_: Exception) { error=true }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row { TextButton(onClick=onBack){Text(stringResource(R.string.back))}; Text(stringResource(R.string.notifications_title),style=MaterialTheme.typography.titleLarge) }
        Text(stringResource(if(granted) R.string.permission_on else R.string.permission_off))
        if(granted && !status.getBoolean("connected",false)) Text(stringResource(R.string.listener_waiting))
        TextButton(onClick=onPermission){Text(stringResource(R.string.permission_settings))}
        Text(stringResource(R.string.archive_limits),style=MaterialTheme.typography.bodySmall)
        if(error || status.getBoolean("capture_error",false)) Text(stringResource(R.string.archive_error))
        exportResult?.let { Text(stringResource(it)) }
        TextButton(onClick=onExport){Text(stringResource(R.string.export_local))}
        OutlinedTextField(search,{search=it;more=0},label={Text(stringResource(R.string.search))},modifier=Modifier.fillMaxWidth())
        var menu by remember { mutableStateOf(false) }
        Box { TextButton(onClick={menu=true}){Text(if(app.isBlank()) stringResource(R.string.all_apps) else app)}
            DropdownMenu(menu,{menu=false}) {
                DropdownMenuItem(text={Text(stringResource(R.string.all_apps))},onClick={app="";more=0;menu=false})
                apps.forEach { value -> DropdownMenuItem(text={Text(value)},onClick={app=value;more=0;menu=false}) }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(from,{from=it;more=0},label={Text(stringResource(R.string.date_from))},modifier=Modifier.weight(1f),singleLine=true)
            OutlinedTextField(until,{until=it;more=0},label={Text(stringResource(R.string.date_until))},modifier=Modifier.weight(1f),singleLine=true)
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(rows,key={it.id}) { row -> Card(Modifier.fillMaxWidth().clickable { selected=row }) { Column(Modifier.padding(12.dp)) {
                Text(row.snapshot.optString("title",row.snapshot.optString("package")))
                Text(row.snapshot.optString("text"))
                Text("${row.snapshot.optString("package")} · ${eventTime(row)} · ${stringResource(kindLabel(row.kind))}",style=MaterialTheme.typography.bodySmall)
            } } }
            item { if(rows.isEmpty()) Text(stringResource(R.string.no_events)) else TextButton(onClick={more++}){Text(stringResource(R.string.more))} }
        }
    }
    selected?.let { row -> AlertDialog(onDismissRequest={selected=null},title={Text(stringResource(R.string.event_detail))},text={
        LazyColumn { item { Text("${stringResource(kindLabel(row.kind))}\n${eventTime(row)}") }; item { Text(row.snapshot.toString(2)) }; item { row.reason?.let { Text(stringResource(R.string.removal_reason,it)) } } }
    },confirmButton={TextButton(onClick={selected=null}){Text(stringResource(R.string.close))}}) }
}
internal fun archiveDateBounds(from: String, until: String, zone: ZoneId = ZoneId.systemDefault()): Pair<String,String> =
    (if(from.isBlank()) "" else LocalDate.parse(from).atStartOfDay(zone).toInstant().toString()) to
    (if(until.isBlank()) "" else LocalDate.parse(until).plusDays(1).atStartOfDay(zone).toInstant().toString())
internal fun eventTime(event: ArchiveEvent): String = HubTimeFormat.dateTime(Instant.parse(event.snapshot.getString("captured")).toEpochMilli())
internal fun kindLabel(kind: String): Int = when(kind) { "POSTED"->R.string.posted; "UPDATED"->R.string.updated; "REMOVED"->R.string.removed;else->R.string.observed }
