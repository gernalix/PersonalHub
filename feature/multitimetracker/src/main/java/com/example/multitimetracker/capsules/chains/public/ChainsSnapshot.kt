package com.example.multitimetracker.capsules.chains.public

import com.example.multitimetracker.model.ActiveChainRun
import com.example.multitimetracker.model.TaskChain

data class ChainsSnapshot(
    val chains: List<TaskChain>,
    val activeChainRun: ActiveChainRun?,
) {
    companion object {
        val EMPTY = ChainsSnapshot(chains = emptyList(), activeChainRun = null)
    }
}
