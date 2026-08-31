// v318
package com.example.multitimetracker.capsules.system

import com.example.multitimetracker.capsules.timeline.state.TimelineUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Timeline capsule access contract.
 *
 * Exposes only the data + operations that Timeline UI needs.
 */
interface TimelineCapsuleAccess {
    fun uiStateFlow(): StateFlow<TimelineUiState>

    fun addTag(name: String)
}
