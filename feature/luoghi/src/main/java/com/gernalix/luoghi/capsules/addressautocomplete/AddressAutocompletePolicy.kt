package com.gernalix.luoghi.capsules.addressautocomplete

object AddressAutocompletePolicy {
    const val MIN_QUERY_CHARS = 3
    const val MAX_SUGGESTIONS = 6
    const val DEBOUNCE_MS = 300L
    const val SEARCH_TIMEOUT_MS = 2500L
    const val RESOLVE_TIMEOUT_MS = 4000L

    fun shouldSearch(query: String, configured: Boolean): Boolean =
        configured && query.trim().length >= MIN_QUERY_CHARS

    fun limitSuggestions(suggestions: List<AddressSuggestion>): List<AddressSuggestion> =
        suggestions.take(MAX_SUGGESTIONS)

    fun isDuplicateSearch(lastQuery: String?, nextValue: String): Boolean =
        lastQuery != null && lastQuery == nextValue.trim()
}

data class AddressAutocompleteRequest(
    val id: Long,
    val query: String,
)

class AddressAutocompleteLatestGate {
    private var latestId = 0L

    fun next(query: String): AddressAutocompleteRequest {
        latestId += 1
        return AddressAutocompleteRequest(latestId, query.trim())
    }

    fun invalidate() {
        latestId += 1
    }

    fun isLatest(request: AddressAutocompleteRequest, currentQuery: String): Boolean =
        request.id == latestId && request.query == currentQuery.trim()
}
