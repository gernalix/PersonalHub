package com.example.multitimetracker.capsules.chains.state

import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TaskChain

data class ChainsUiState(
    val tags: List<Tag>,
    val chains: List<TaskChain>,
    val activeChainRun: ActiveChainRun?,
    val isReadOnly: Boolean,
)

data class ChainsHostState(
    val tags: List<Tag>,
    val isReadOnly: Boolean,
)
