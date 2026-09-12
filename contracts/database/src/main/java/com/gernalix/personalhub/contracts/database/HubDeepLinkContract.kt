package com.gernalix.personalhub.contracts.database

import android.net.Uri

/** Stable public permalink contract for canonical PersonalHub data. */
object HubDeepLinkContract {
    const val SCHEME = "personalhub"
    const val VERSION = "v1"

    const val ACTION_VIEW = "view"
    const val ACTION_EDIT = "edit"
    const val EXTRA_ACTION = "com.gernalix.personalhub.extra.DEEP_LINK_ACTION"

    private const val ENTITY = "entity"
    private const val CONTEXT = "context"
    private const val EVENT = "event"
    private const val SEARCH = "search"
    private const val PARAM_ACTION = "action"
    private const val PARAM_FROM = "from"
    private const val PARAM_TO = "to"
    private const val PARAM_MODULE = "module"

    sealed interface Target

    data class EntityTarget(
        val ref: HubEntityRef,
        val action: String = ACTION_VIEW,
    ) : Target

    data class ContextTarget(val contextId: String) : Target
    data class EventTarget(val eventId: String) : Target
    data class SearchTarget(
        val fromIso: String?,
        val toIso: String?,
        val modules: List<String>,
    ) : Target

    enum class ParseError {
        MISSING_URI,
        INVALID_SCHEME,
        UNKNOWN_ENDPOINT,
        UNSUPPORTED_VERSION,
        MALFORMED,
        UNSUPPORTED_ACTION,
    }

    data class ParseResult(
        val target: Target? = null,
        val error: ParseError? = null,
    ) {
        val isSuccess: Boolean get() = target != null && error == null
    }

    fun entityUri(ref: HubEntityRef, action: String = ACTION_VIEW): Uri {
        require(ref.moduleId.isNotBlank() && ref.entityKind.isNotBlank() && ref.canonicalId.isNotBlank())
        require(action == ACTION_VIEW || action == ACTION_EDIT)
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(ENTITY)
            .appendPath(VERSION)
            .appendPath(ref.moduleId)
            .appendPath(ref.entityKind)
            .appendPath(ref.canonicalId)
            .apply { if (action != ACTION_VIEW) appendQueryParameter(PARAM_ACTION, action) }
            .build()
    }

    fun contextUri(contextId: String): Uri = simpleUri(CONTEXT, contextId)
    fun eventUri(eventId: String): Uri = simpleUri(EVENT, eventId)

    fun searchUri(
        fromIso: String? = null,
        toIso: String? = null,
        modules: Collection<String> = emptyList(),
    ): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(SEARCH)
        .appendPath(VERSION)
        .apply {
            fromIso?.trim()?.takeIf(String::isNotEmpty)?.let { appendQueryParameter(PARAM_FROM, it) }
            toIso?.trim()?.takeIf(String::isNotEmpty)?.let { appendQueryParameter(PARAM_TO, it) }
            modules.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
                .forEach { appendQueryParameter(PARAM_MODULE, it) }
        }
        .build()

    fun parse(uri: Uri?): ParseResult {
        if (uri == null) return ParseResult(error = ParseError.MISSING_URI)
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return ParseResult(error = ParseError.INVALID_SCHEME)
        val endpoint = uri.host?.lowercase() ?: return ParseResult(error = ParseError.UNKNOWN_ENDPOINT)
        val segments = uri.pathSegments
        if (segments.firstOrNull() != VERSION) return ParseResult(error = ParseError.UNSUPPORTED_VERSION)

        return when (endpoint) {
            ENTITY -> parseEntity(uri, segments)
            CONTEXT -> parseSimple(segments, ParseError.MALFORMED) { ContextTarget(it) }
            EVENT -> parseSimple(segments, ParseError.MALFORMED) { EventTarget(it) }
            SEARCH -> parseSearch(uri, segments)
            else -> ParseResult(error = ParseError.UNKNOWN_ENDPOINT)
        }
    }

    private fun parseEntity(uri: Uri, segments: List<String>): ParseResult {
        if (segments.size != 4 || segments.drop(1).any(String::isBlank)) return ParseResult(error = ParseError.MALFORMED)
        val actions = uri.getQueryParameters(PARAM_ACTION)
        if (actions.size > 1) return ParseResult(error = ParseError.MALFORMED)
        val action = actions.singleOrNull()?.ifBlank { ACTION_VIEW } ?: ACTION_VIEW
        if (action != ACTION_VIEW && action != ACTION_EDIT) return ParseResult(error = ParseError.UNSUPPORTED_ACTION)
        return ParseResult(
            target = EntityTarget(
                HubEntityRef(segments[1], segments[2], segments[3]),
                action,
            ),
        )
    }

    private fun parseSearch(uri: Uri, segments: List<String>): ParseResult {
        if (segments.size != 1) return ParseResult(error = ParseError.MALFORMED)
        val from = singleQueryValue(uri, PARAM_FROM) ?: if (uri.getQueryParameters(PARAM_FROM).size > 1) return ParseResult(error = ParseError.MALFORMED) else null
        val to = singleQueryValue(uri, PARAM_TO) ?: if (uri.getQueryParameters(PARAM_TO).size > 1) return ParseResult(error = ParseError.MALFORMED) else null
        val modules = uri.getQueryParameters(PARAM_MODULE).map(String::trim).filter(String::isNotEmpty).distinct()
        return ParseResult(target = SearchTarget(from, to, modules))
    }

    private fun singleQueryValue(uri: Uri, key: String): String? =
        uri.getQueryParameters(key).singleOrNull()?.trim()?.takeIf(String::isNotEmpty)

    private inline fun <T : Target> parseSimple(
        segments: List<String>,
        error: ParseError,
        factory: (String) -> T,
    ): ParseResult {
        if (segments.size != 2 || segments[1].isBlank()) return ParseResult(error = error)
        return ParseResult(target = factory(segments[1]))
    }

    private fun simpleUri(endpoint: String, id: String): Uri {
        require(id.isNotBlank())
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(endpoint)
            .appendPath(VERSION)
            .appendPath(id)
            .build()
    }
}
