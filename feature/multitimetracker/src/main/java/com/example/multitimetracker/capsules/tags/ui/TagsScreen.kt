// v312
package com.example.multitimetracker.capsules.tags.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.multitimetracker.capsules.tags.ui.TagsCapsuleUi
import com.example.multitimetracker.capsules.tags.controller.TagsCapsuleViewModel
import com.example.multitimetracker.capsules.tags.state.TagsUiState

/**
 * Screen wrapper.
 *
 * Systemic Feature Capsule: Tags
 * - UI implementation lives in [TagsCapsuleUi]
 * - Mutations go through [TagsCapsuleViewModel] boundary contract
 */
@Composable
fun TagsScreen(
    modifier: Modifier = Modifier,
    state: TagsUiState,
    capsule: TagsCapsuleViewModel,
    initialOpenedTagId: Long? = null,
    onConsumedInitialOpenedTagId: () -> Unit = {},
    showSeconds: Boolean,
    hideHoursIfZero: Boolean
) {
    TagsCapsuleUi(
        modifier = modifier,
        state = state,
        capsule = capsule,
        initialOpenedTagId = initialOpenedTagId,
        onConsumedInitialOpenedTagId = onConsumedInitialOpenedTagId,
        showSeconds = showSeconds,
        hideHoursIfZero = hideHoursIfZero
    )
}
