package com.gernalix.personalhub

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.gernalix.personalhub.core.database.DatabaseStartupGate
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.gernalix.personalhub.contracts.database.DataExplorerContract
import com.gernalix.personalhub.core.database.DataExplorerSnapshot
import com.gernalix.personalhub.core.database.DataExplorerSnapshots
import com.gernalix.personalhub.core.database.capsules.sync.DatasetteSettings
import com.gernalix.personalhub.ui.theme.PersonalHubTheme
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataExplorerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseStartupGate.blockIfNotReady(this)) return
        setContent {
            PersonalHubTheme {
                Surface(Modifier.fillMaxSize()) {
                    DataExplorerScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        DataExplorerSnapshots.clear(this)
        super.onDestroy()
    }

    private fun hasEmbeddedLite(): Boolean = runCatching {
        assets.open("datasette-lite/index.html").use { Unit }
        true
    }.getOrDefault(false)

    @Composable
    private fun DataExplorerScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val requestedTable = remember { DataExplorerContract.table(intent) }
        var page by rememberSaveable { mutableStateOf("home") }
        var snapshot by remember { mutableStateOf<DataExplorerSnapshot?>(null) }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var remoteDatabase by rememberSaveable {
            mutableStateOf(
                runCatching { DatasetteSettings.explorerDatabase(context) }
                    .getOrDefault(DatasetteSettings.DEFAULT_EXPLORER_DATABASE),
            )
        }
        var remoteUrl by remember {
            mutableStateOf(runCatching { DatasetteSettings.explorerUrl(context, requestedTable) }.getOrNull())
        }
        val liteAvailable = remember { hasEmbeddedLite() }

        BackHandler {
            if (page == "home") finish() else page = "home"
        }

        when (page) {
            "local" -> snapshot?.let {
                ExplorerWebView(
                    url = localLiteUrl(it.file, requestedTable),
                    snapshotDirectory = it.file.parentFile,
                    localOnly = true,
                    onBack = { page = "home" },
                )
            }
            "remote" -> remoteUrl?.let {
                ExplorerWebView(
                    url = it,
                    snapshotDirectory = null,
                    localOnly = false,
                    onBack = { page = "home" },
                )
            }
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.data_explorer_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.data_explorer_description))
                Text(stringResource(R.string.data_explorer_read_only))

                Button(
                    enabled = liteAvailable && !busy,
                    onClick = {
                        busy = true
                        failed = false
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { DataExplorerSnapshots.create(context) }
                            }.onSuccess {
                                snapshot = it
                                page = "local"
                            }.onFailure {
                                failed = true
                            }
                            busy = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.data_explorer_local))
                }
                if (!liteAvailable) {
                    Text(stringResource(R.string.data_explorer_local_unavailable))
                }
                snapshot?.let {
                    Text(stringResource(R.string.data_explorer_snapshot_generation, it.generation))
                }

                Text(stringResource(R.string.data_explorer_remote_section), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = remoteDatabase,
                    onValueChange = { remoteDatabase = it },
                    label = { Text(stringResource(R.string.data_explorer_remote_database)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            failed = false
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        DatasetteSettings.saveExplorerDatabase(context, remoteDatabase)
                                        DatasetteSettings.explorerUrl(context, requestedTable)
                                    }
                                }.onSuccess { url ->
                                    remoteDatabase = DatasetteSettings.explorerDatabase(context)
                                    remoteUrl = url
                                }.onFailure {
                                    failed = true
                                }
                                busy = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.data_explorer_remote_save))
                    }
                    Button(
                        enabled = !busy && remoteUrl != null,
                        onClick = { page = "remote" },
                    ) {
                        Text(stringResource(R.string.data_explorer_remote))
                    }
                }
                if (remoteUrl == null) {
                    Text(stringResource(R.string.data_explorer_remote_unconfigured))
                } else {
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(remoteUrl)))
                        },
                    ) {
                        Text(stringResource(R.string.data_explorer_remote_browser))
                    }
                }

                if (busy) CircularProgressIndicator()
                if (failed) Text(stringResource(R.string.data_explorer_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { finish() }, enabled = !busy) {
                    Text(stringResource(R.string.data_explorer_back))
                }
            }
        }
    }

    private fun localLiteUrl(snapshot: File, table: String?): String {
        val databaseUrl =
            "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/snapshot/${Uri.encode(snapshot.name)}"
        val fragment = table?.let { "#/personalhub_read/${Uri.encode(it)}" }.orEmpty()
        return "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/datasette-lite/index.html" +
            "?snapshot=${Uri.encode(databaseUrl)}$fragment"
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun ExplorerWebView(
        url: String,
        snapshotDirectory: File?,
        localOnly: Boolean,
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        var webView by remember { mutableStateOf<WebView?>(null) }
        val assetLoader = remember(snapshotDirectory) {
            snapshotDirectory?.let {
                WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .addPathHandler("/snapshot/", WebViewAssetLoader.InternalStoragePathHandler(context, it))
                    .build()
            }
        }

        BackHandler {
            val view = webView
            if (view?.canGoBack() == true) view.goBack() else onBack()
        }
        DisposableEffect(Unit) {
            onDispose {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    webView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.setGeolocationEnabled(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            if (!localOnly) return null
                            val uri = request.url
                            if (
                                uri.scheme == "https" &&
                                uri.host == WebViewAssetLoader.DEFAULT_DOMAIN
                            ) {
                                return assetLoader?.shouldInterceptRequest(uri)
                            }
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                403,
                                "Blocked",
                                mapOf("Cache-Control" to "no-store"),
                                ByteArrayInputStream(ByteArray(0)),
                            )
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest,
                        ): Boolean {
                            val uri = request.url
                            return if (localOnly) {
                                uri.scheme != "https" || uri.host != WebViewAssetLoader.DEFAULT_DOMAIN
                            } else {
                                uri.scheme != "https"
                            }
                        }
                    }
                    loadUrl(url)
                }
            },
        )
    }
}
