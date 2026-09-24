package com.gernalix.personalhub.contracts.database

import android.net.Uri

/** Stable public permalink contract for canonical PersonalHub data and stable module launch URIs. */
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
    private const val MODULE = "module"
    private const val SINCE_WHEN = "since_when"
    private const val CREATE = "create"
    private const val PARAM_SOURCE = "source"
    private const val PARAM_ACTION = "action"
    private const val PARAM_FROM = "from"
    private const val PARAM_TO = "to"
    private const val PARAM_MODULE = "module"
    private const val PARAM_QUERY = "q"
    private const val PARAM_ENTITY_KIND = "entity_kind"
    private const val PARAM_ENTITY_ID = "entity_id"
    private const val PARAM_SCOPE_MODULE = "scope_module"

    sealed interface Target

    data class EntityTarget(
        val ref: HubEntityRef,
        val action: String = ACTION_VIEW,
    ) : Target

    data class ContextTarget(val contextId: String) : Target
    data class EventTarget(val eventId: String) : Target
    data class SinceWhenCreateTarget(val source: SinceWhenSourceDescriptor, val startEnabled: Boolean = false) : Target
    data class SearchTarget(
        val fromIso: String?,
        val toIso: String?,
        val modules: List<String>,
        val query: String? = null,
        val entityKind: String? = null,
        val entityId: String? = null,
        val scopeModuleId: String? = null,
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

    fun sinceWhenCreateUri(source: SinceWhenSourceDescriptor, startEnabled: Boolean = false): Uri {
        val timestamps = org.json.JSONArray().apply {
            source.timestampSources.forEach { timestamp ->
                put(org.json.JSONObject().put("id", timestamp.id).put("label", timestamp.label)
                    .put("timestamp", timestamp.timestamp).put("default", timestamp.isDefault))
            }
        }
        val payload = org.json.JSONObject()
            .put("type", source.entityType)
            .put("id", source.entityId)
            .put("title", source.defaultCounterTitle)
            .put("timestamps", timestamps)
            .toString()
        return Uri.Builder().scheme(SCHEME).authority(SINCE_WHEN).appendPath(VERSION).appendPath(CREATE)
            .appendQueryParameter(PARAM_SOURCE, payload)
            .apply { if (startEnabled) appendQueryParameter("enabled", "true") }
            .build()
    }

    /**
     * Stable compatibility URI for feature-module launchers/shortcuts/widgets.
     *
     * Feature code must use this builder instead of constructing personalhub://module/... manually,
     * so scheme/authority/path semantics stay centralized while existing public links remain valid.
     */
    fun moduleUri(
        moduleId: String,
        vararg queryParameters: Pair<String, String?>,
    ): Uri {
        val normalizedModuleId = moduleId.trim()
        require(normalizedModuleId.isNotEmpty())
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(MODULE)
            .appendPath(normalizedModuleId)
            .apply {
                queryParameters.forEach { (name, value) ->
                    val normalizedName = name.trim()
                    require(normalizedName.isNotEmpty())
                    value?.let { appendQueryParameter(normalizedName, it) }
                }
            }
            .build()
    }

    fun isModuleUri(uri: Uri?, moduleId: String): Boolean {
        val normalizedModuleId = moduleId.trim()
        if (uri == null || normalizedModuleId.isEmpty()) return false
        return uri.scheme.equals(SCHEME, ignoreCase = true) &&
            uri.host.equals(MODULE, ignoreCase = true) &&
            uri.pathSegments == listOf(normalizedModuleId)
    }

    /**
     * Opaque feature-scoped URI for stable PendingIntent/receiver identity.
     *
     * This is deliberately not parsed by [parse]; canonical user-facing permalinks use the
     * versioned entity/context/event/search endpoints above.
     */
    fun featureUri(featureId: String, vararg pathSegments: String): Uri {
        val normalizedFeatureId = featureId.trim()
        require(normalizedFeatureId.isNotEmpty())
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(normalizedFeatureId)
            .apply {
                pathSegments.forEach { segment ->
                    require(segment.isNotBlank())
                    appendPath(segment)
                }
            }
            .build()
    }

    fun searchUri(
        fromIso: String? = null,
        toIso: String? = null,
        modules: Collection<String> = emptyList(),
        query: String? = null,
        entityKind: String? = null,
        entityId: String? = null,
        scopeModuleId: String? = null,
    ): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(SEARCH)
        .appendPath(VERSION)
        .apply {
            fromIso?.trim()?.takeIf { it.isNotEmpty() }?.let { appendQueryParameter(PARAM_FROM, it) }
            toIso?.trim()?.takeIf { it.isNotEmpty() }?.let { appendQueryParameter(PARAM_TO, it) }
            modules.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
                .forEach { appendQueryParameter(PARAM_MODULE, it) }
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { appendQueryParameter(PARAM_QUERY, it) }
            entityKind?.trim()?.takeIf { it.isNotEmpty() }?.let { appendQueryParameter(PARAM_ENTITY_KIND, it) }
            entityId?.trim()?.takeIf { it.isNotEmpty() }?.let { appendQueryParameter(PARAM_ENTITY_ID, it) }
            scopeModuleId?.trim()?.takeIf { it.isNotEmpty() }?.let { appendQueryParameter(PARAM_SCOPE_MODULE, it) }
        }
        .build()

    fun moduleHistoryUri(moduleId: String): Uri {
        val normalized = moduleId.trim()
        require(normalized.isNotEmpty())
        return searchUri(modules = listOf(normalized), scopeModuleId = normalized)
    }

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
            SINCE_WHEN -> parseSinceWhenCreate(uri, segments)
            SEARCH -> parseSearch(uri, segments)
            else -> ParseResult(error = ParseError.UNKNOWN_ENDPOINT)
        }
    }

    private fun parseEntity(uri: Uri, segments: List<String>): ParseResult {
        if (segments.size != 4 || segments.drop(1).any { it.isBlank() }) return ParseResult(error = ParseError.MALFORMED)
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
        val fromValues = uri.getQueryParameters(PARAM_FROM)
        val toValues = uri.getQueryParameters(PARAM_TO)
        if (fromValues.size > 1 || toValues.size > 1) return ParseResult(error = ParseError.MALFORMED)
        val from = fromValues.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val to = toValues.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val modules = uri.getQueryParameters(PARAM_MODULE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        fun singleOptional(name: String): String? {
            val values = uri.getQueryParameters(name)
            require(values.size <= 1)
            return values.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
        return runCatching {
            val scopeModuleId = singleOptional(PARAM_SCOPE_MODULE)
            require(scopeModuleId == null || scopeModuleId in modules)
            SearchTarget(
                fromIso = from,
                toIso = to,
                modules = modules,
                query = singleOptional(PARAM_QUERY),
                entityKind = singleOptional(PARAM_ENTITY_KIND),
                entityId = singleOptional(PARAM_ENTITY_ID),
                scopeModuleId = scopeModuleId,
            )
        }.fold({ ParseResult(target = it) }, { ParseResult(error = ParseError.MALFORMED) })
    }

    private fun parseSinceWhenCreate(uri: Uri, segments: List<String>): ParseResult {
        if (segments != listOf(VERSION, CREATE)) return ParseResult(error = ParseError.MALFORMED)
        val values = uri.getQueryParameters(PARAM_SOURCE)
        if (values.size != 1) return ParseResult(error = ParseError.MALFORMED)
        val enabledValues = uri.getQueryParameters("enabled")
        if (enabledValues.size > 1 || enabledValues.any { it != "true" && it != "false" }) return ParseResult(error = ParseError.MALFORMED)
        return runCatching {
            val payload = org.json.JSONObject(values.single())
            val sources = payload.getJSONArray("timestamps")
            val timestamps = List(sources.length()) { index ->
                val item = sources.getJSONObject(index)
                SinceWhenTimestampSource(
                    id = item.getString("id"),
                    label = item.getString("label"),
                    timestamp = item.getLong("timestamp"),
                    isDefault = item.optBoolean("default"),
                )
            }
            SinceWhenCreateTarget(
                SinceWhenSourceDescriptor(
                    entityType = payload.getString("type"),
                    entityId = payload.getString("id"),
                    defaultCounterTitle = payload.getString("title"),
                    timestampSources = timestamps,
                ),
                startEnabled = enabledValues.singleOrNull() == "true",
            )
        }.fold({ ParseResult(target = it) }, { ParseResult(error = ParseError.MALFORMED) })
    }

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
