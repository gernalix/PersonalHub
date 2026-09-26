package com.gernalix.personalhub

import androidx.compose.runtime.Composable

/** Home route for the single History/Search surface. */
@Composable
fun HubAuditLogScreen(onBack: () -> Unit) {
    HubHistorySearchScreen(onBack = onBack)
}
