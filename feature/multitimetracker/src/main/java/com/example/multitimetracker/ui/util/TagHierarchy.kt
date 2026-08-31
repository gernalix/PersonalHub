package com.example.multitimetracker.ui.util

/**
 * Utilities for "soft" tag hierarchies (child -> parent).
 *
 * - closure(selectedManual): returns selectedManual + all transitive parents.
 * - wouldCreateCycle(child, parent): checks if adding edge child->parent creates a cycle.
 */
object TagHierarchy {

    fun closure(
        selectedManual: Set<Long>,
        parentsByChild: Map<Long, Set<Long>>
    ): Set<Long> {
        if (selectedManual.isEmpty()) return emptySet()
        val out = LinkedHashSet<Long>(selectedManual.size * 2)
        val stack = ArrayDeque<Long>()
        selectedManual.forEach {
            out.add(it)
            stack.addLast(it)
        }

        var guard = 0
        while (stack.isNotEmpty()) {
            guard++
            if (guard > 50_000) break // safety for corrupted data
            val cur = stack.removeFirst()
            val parents = parentsByChild[cur] ?: emptySet()
            for (p in parents) {
                if (out.add(p)) stack.addLast(p)
            }
        }
        return out
    }

    /**
     * Returns true if adding edge [child] -> [parent] would create a cycle.
     * Equivalent: does [child] appear in the transitive parents of [parent] (or parent==child).
     */
    fun wouldCreateCycle(
        child: Long,
        parent: Long,
        parentsByChild: Map<Long, Set<Long>>
    ): Boolean {
        if (child == parent) return true
        val visited = HashSet<Long>()
        val stack = ArrayDeque<Long>()
        stack.addLast(parent)
        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            if (!visited.add(cur)) continue
            if (cur == child) return true
            parentsByChild[cur]?.forEach { stack.addLast(it) }
            if (visited.size > 50_000) break // safety
        }
        return false
    }
}
