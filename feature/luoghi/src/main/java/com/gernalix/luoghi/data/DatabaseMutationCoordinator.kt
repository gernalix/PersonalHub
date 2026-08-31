package com.gernalix.luoghi.data

import kotlinx.coroutines.sync.Mutex

object DatabaseMutationCoordinator {
    val mutex = Mutex()
}
