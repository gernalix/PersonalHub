// v342
package com.example.multitimetracker.capsules.chains.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.multitimetracker.capsules.chains.ui.ChainsCapsuleUi
import com.example.multitimetracker.capsules.chains.controller.ChainsCapsuleViewModel

/**
 * Screen wrapper.
 *
 * Systemic Feature Capsule: Chains
 * - UI implementation lives in [ChainsCapsuleUi]
 * - Mutations go through [ChainsCapsuleViewModel] boundary contract
 */
@Composable
fun ChainsScreen(
    modifier: Modifier = Modifier,
    capsule: ChainsCapsuleViewModel
) {
    ChainsCapsuleUi(
        vm = capsule,
        modifier = modifier
    )
}
