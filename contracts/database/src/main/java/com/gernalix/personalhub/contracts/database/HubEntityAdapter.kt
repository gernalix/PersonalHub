package com.gernalix.personalhub.contracts.database

data class HubEntityRef(val moduleId: String, val entityKind: String, val canonicalId: String)
data class HubEntitySummary(
    val ref: HubEntityRef,
    val label: String,
    val description: String? = null,
    val lifecycle: String = HubEntityLifecycle.ACTIVE,
    val attributes: Map<String, String> = emptyMap(),
)
data class HubOpenTarget(val uri: String, val activityClassName: String? = null)
data class HubCreateRequest(val suggestedLabel: String? = null, val extras: Map<String, String> = emptyMap())

/** Public module seam. Batched summaries avoid N+1 graph rendering. */
interface HubEntityAdapter {
    val moduleId: String
    val entityKind: String
    val capabilities: Set<String>
    suspend fun exists(canonicalId: String): Boolean
    suspend fun lifecycle(canonicalId: String): String
    suspend fun summaries(canonicalIds: Set<String>): Map<String, HubEntitySummary>
    suspend fun search(query: String, limit: Int): List<HubEntitySummary>
    suspend fun openTarget(canonicalId: String): HubOpenTarget?
    suspend fun sinceWhenSource(canonicalId: String): SinceWhenSourceDescriptor? = null
    suspend fun create(request: HubCreateRequest): HubEntitySummary? = null
}
