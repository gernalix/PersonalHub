package com.gernalix.personalhub.contracts.database

data class SinceWhenTimestampSource(
    val id: String,
    val label: String,
    val timestamp: Long,
    val isDefault: Boolean = false,
)

data class SinceWhenSourceDescriptor(
    val entityType: String,
    val entityId: String,
    val defaultCounterTitle: String,
    val timestampSources: List<SinceWhenTimestampSource>,
) {
    init {
        require(entityType.isNotBlank() && entityId.isNotBlank())
        require(timestampSources.map { it.id }.distinct().size == timestampSources.size)
        require(timestampSources.count { it.isDefault } <= 1)
    }
}
