package com.gernalix.personalhub.capsules.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gernalix.personalhub.R
import com.gernalix.personalhub.core.ui.R as UiR
import com.gernalix.personalhub.core.database.capsules.gitdata.GitHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GitHistoryTools(busy: Boolean, runOperation: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf("") }
    var after by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var proposal by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var comparing by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(UiR.string.git_review_tools)) }
    if (!expanded) return
    OutlinedButton(enabled = !busy, onClick = { runOperation { GitHistory.rebuildIndex(context) } }) {
        Text(stringResource(UiR.string.git_review_rebuild))
    }
    OutlinedTextField(before, { before = it }, label = { Text(stringResource(UiR.string.git_review_before)) })
    OutlinedTextField(after, { after = it }, label = { Text(stringResource(UiR.string.git_review_after)) })
    OutlinedButton(enabled = !busy && !comparing && before.isNotBlank() && after.isNotBlank(), onClick = {
        comparing = true
        scope.launch {
            try {
                val diff = withContext(Dispatchers.IO) { GitHistory.semanticCompare(context,before,after) }
                output = diff.changes.groupBy { it.table }.entries.joinToString("\n") { (table, rows) ->
                    resources.getString(UiR.string.git_review_diff_table,table,
                        rows.count { it.operation.equals("insert",true) },rows.count { it.operation.equals("update",true) },rows.count { it.operation.equals("delete",true) })
                }.ifBlank { resources.getString(UiR.string.git_review_no_changes) }
                if (diff.truncated) output += "\n" + resources.getString(UiR.string.git_review_truncated)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { output = resources.getString(UiR.string.git_review_failed) }
            finally { comparing = false }
        }
    }) { Text(stringResource(UiR.string.git_review_compare)) }
    if (output.isNotBlank()) Text(output)
    OutlinedTextField(name, { name = it }, label = { Text(stringResource(UiR.string.git_review_milestone_name)) })
    OutlinedButton(enabled = !busy && name.isNotBlank(), onClick = { runOperation { GitHistory.createMilestone(context,name) } }) {
        Text(stringResource(UiR.string.git_review_milestone))
    }
    OutlinedTextField(proposal, { proposal = it }, label = { Text(stringResource(UiR.string.git_review_proposal_name)) })
    OutlinedButton(enabled = !busy && proposal.isNotBlank(), onClick = { runOperation { GitHistory.createProposalBranch(context,proposal) } }) {
        Text(stringResource(UiR.string.git_review_proposal))
    }
    OutlinedButton(enabled = !busy && proposal.isNotBlank(), onClick = { runOperation {
        val url = GitHistory.createProposalPullRequest(context,proposal,"PersonalHub data proposal", "Explicit data proposal for review; patches require sandbox preview and confirmation in PersonalHub.")
        scope.launch { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
    } }) { Text(stringResource(UiR.string.git_review_pr)) }
    OutlinedButton(enabled = !busy && proposal.isNotBlank(), onClick = { discard = true }) { Text(stringResource(UiR.string.git_review_discard)) }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text(stringResource(UiR.string.git_review_discard)) },
        text = { Text(proposal) },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(UiR.string.git_review_cancel)) } },
        confirmButton = { TextButton(onClick = { discard = false; runOperation { GitHistory.discardProposalBranch(context,proposal) } }) { Text(stringResource(UiR.string.git_review_discard)) } },
    )
}
