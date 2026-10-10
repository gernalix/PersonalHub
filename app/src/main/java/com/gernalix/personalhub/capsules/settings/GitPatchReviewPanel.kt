package com.gernalix.personalhub.capsules.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gernalix.personalhub.R
import com.gernalix.personalhub.core.ui.R as UiR
import com.gernalix.personalhub.core.database.DatabaseProfiles
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.gitdata.GitDataSync
import com.gernalix.personalhub.core.database.capsules.gitdata.GitPatchPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface GitPatchReviewActions {
    fun describe(context: android.content.Context,ref: String,id: String): GitDataSync.PatchReview
    fun preview(context: android.content.Context,ref: String,id: String): GitPatchPreview
    fun apply(context: android.content.Context,ref: String,id: String,hash: String)
}
internal object CanonicalGitPatchReviewActions: GitPatchReviewActions {
    override fun describe(context: android.content.Context,ref: String,id: String) = GitDataSync.describePatchFromRevision(context,ref,id)
    override fun preview(context: android.content.Context,ref: String,id: String) = GitDataSync.previewPatchFromRevision(context,ref,id)
    override fun apply(context: android.content.Context,ref: String,id: String,hash: String) { GitDataSync.applyPatchFromRevision(context,ref,id,hash) }
}

/** Same Git backend as Settings and History. A pinned revision survives a moving remote branch. */
@Composable
internal fun GitPatchReviewPanel(patchId: String, ref: String, onBack: () -> Unit, onApplied: () -> Unit, actions: GitPatchReviewActions = CanonicalGitPatchReviewActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember(patchId, ref) { DatabaseProfiles.activeProfileId(context) }
    var review by remember(patchId, ref) { mutableStateOf<GitDataSync.PatchReview?>(null) }
    var preview by remember(patchId, ref) { mutableStateOf<GitPatchPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(patchId, ref) {
        busy = true
        try {
            review = withContext(Dispatchers.IO) { actions.describe(context, ref, patchId) }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack, enabled = !busy) { Text(stringResource(R.string.activity_back)) }
        Text(stringResource(UiR.string.git_review_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(UiR.string.git_review_id, patchId))
        review?.let { item ->
            Text(stringResource(UiR.string.git_review_ref, item.revision))
            Text(stringResource(UiR.string.git_review_author, item.author))
            item.reason?.let { Text(it) }
            val compatible = item.schemaVersion == PersonalHubDatabase.SCHEMA_VERSION
            Text(stringResource(if (compatible) UiR.string.git_review_schema_pass else UiR.string.git_review_schema_fail, item.schemaVersion))
            Text(stringResource(UiR.string.git_review_operations, item.operations))
            Text(stringResource(UiR.string.git_review_tables, item.tables.joinToString(", ")))
            if (item.alreadyApplied) Text(stringResource(UiR.string.git_review_already_applied))
            Text(stringResource(UiR.string.git_review_isolated))
            OutlinedButton(enabled = !busy && compatible && !item.alreadyApplied, modifier = Modifier.testTag("git-patch-preview"), onClick = {
                preview = null
                failed = false
                busy = true
                scope.launch {
                    try {
                        preview = withContext(Dispatchers.IO) {
                            check(profile == DatabaseProfiles.activeProfileId(context))
                            actions.preview(context, item.revision, patchId).also { result ->
                                check(result.patchId == patchId && result.verifiedRevision == item.revision && result.sha256 == item.sha256)
                            }
                        }
                    } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }) { Text(stringResource(UiR.string.git_review_preview)) }
            preview?.let { result ->
                Text(stringResource(UiR.string.git_review_counts, result.inserts, result.updates, result.deletes), Modifier.testTag("git-patch-counts"))
                Text(stringResource(UiR.string.git_review_checks_pass), Modifier.testTag("git-patch-pass"))
                Button(enabled = !busy, modifier = Modifier.testTag("git-patch-apply"), onClick = { confirm = true }) {
                    Text(stringResource(UiR.string.git_review_apply))
                }
            }
        }
        if (failed) Text(stringResource(UiR.string.git_review_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("git-patch-failed"))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(UiR.string.git_review_apply)) },
        text = { Text(stringResource(UiR.string.git_review_confirm, patchId)) },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(UiR.string.git_review_cancel)) } },
        confirmButton = { TextButton(modifier = Modifier.testTag("git-patch-confirm"), onClick = {
            val result = requireNotNull(preview)
            val revision = requireNotNull(result.verifiedRevision)
            val hash = requireNotNull(result.sha256)
            confirm = false
            busy = true
            failed = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        check(profile == DatabaseProfiles.activeProfileId(context))
                        actions.apply(context, revision, patchId, hash)
                    }
                    onApplied()
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { preview = null; failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(UiR.string.git_review_apply)) } },
    )
}
