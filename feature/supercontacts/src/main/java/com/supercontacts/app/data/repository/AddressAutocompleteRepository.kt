package com.supercontacts.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Public Luoghi contract mirrored in one place; API v7 keeps all older provider routes compatible. */
object LuoghiPlacesContract {
    const val CONTRACT_VERSION = 7
    const val AUTHORITY = "com.gernalix.personalhub.luoghi.places"
    const val READ_PERMISSION = "com.gernalix.personalhub.permission.READ_PLACES"
    const val PATH_NICKNAMES = "nicknames"
    val NICKNAMES_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_NICKNAMES")

    object Columns {
        const val STABLE_ID = "stable_id"
        const val NICKNAME = "nickname"
        const val ADDRESS = "address"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
        val REQUIRED = arrayOf(STABLE_ID, NICKNAME, ADDRESS, LATITUDE, LONGITUDE)
    }
}

data class AddressSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String,
    val source: AddressSuggestionSource = AddressSuggestionSource.Luoghi,
)

data class ResolvedAddress(
    val placeId: String,
    val nickname: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
)

enum class AddressSuggestionSource {
    Luoghi,
}

class LuoghiUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun interface LuoghiPlaceSource {
    fun queryAll(): List<ResolvedAddress>
}

class AddressAutocompleteRepository internal constructor(
    private val source: LuoghiPlaceSource,
) {
    constructor(context: Context) : this(ContentResolverLuoghiPlaceSource(context.applicationContext))

    suspend fun search(query: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        source.queryAll().filter { place ->
            normalizedQuery.isBlank() ||
                place.nickname.contains(normalizedQuery, ignoreCase = true) ||
                place.address.contains(normalizedQuery, ignoreCase = true)
        }.map { it.toSuggestion() }
    }

    suspend fun resolve(suggestion: AddressSuggestion): ResolvedAddress =
        resolveStableId(suggestion.placeId)
            ?: throw LuoghiUnavailableException("Il luogo selezionato non esiste più in Luoghi.")

    suspend fun resolveStableId(stableId: String): ResolvedAddress? = withContext(Dispatchers.IO) {
        source.queryAll().firstOrNull { it.placeId == stableId }
    }

    private fun ResolvedAddress.toSuggestion(): AddressSuggestion =
        AddressSuggestion(
            placeId = placeId,
            primaryText = nickname,
            secondaryText = address,
        )
}

private class ContentResolverLuoghiPlaceSource(context: Context) : LuoghiPlaceSource {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override fun queryAll(): List<ResolvedAddress> {
        if (appContext.packageManager.resolveContentProvider(LuoghiPlacesContract.AUTHORITY, 0) == null) {
            throw LuoghiUnavailableException("Il provider Luoghi interno non è disponibile.")
        }
        return try {
            resolver.query(
                LuoghiPlacesContract.NICKNAMES_URI,
                LuoghiPlacesContract.Columns.REQUIRED,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(LuoghiPlacesContract.Columns.STABLE_ID)
                val nicknameIndex = cursor.getColumnIndexOrThrow(LuoghiPlacesContract.Columns.NICKNAME)
                val addressIndex = cursor.getColumnIndexOrThrow(LuoghiPlacesContract.Columns.ADDRESS)
                val latitudeIndex = cursor.getColumnIndexOrThrow(LuoghiPlacesContract.Columns.LATITUDE)
                val longitudeIndex = cursor.getColumnIndexOrThrow(LuoghiPlacesContract.Columns.LONGITUDE)
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ResolvedAddress(
                                placeId = cursor.getString(idIndex),
                                nickname = cursor.getString(nicknameIndex),
                                address = cursor.getString(addressIndex).orEmpty(),
                                latitude = cursor.getNullableDouble(latitudeIndex),
                                longitude = cursor.getNullableDouble(longitudeIndex),
                            ),
                        )
                    }
                }
            } ?: throw LuoghiUnavailableException("La query a Luoghi non ha restituito dati.")
        } catch (error: SecurityException) {
            throw LuoghiUnavailableException(
                "Luoghi ha negato l'accesso: verifica che le app usino la stessa firma stabile.",
                error,
            )
        } catch (error: LuoghiUnavailableException) {
            throw error
        } catch (error: RuntimeException) {
            throw LuoghiUnavailableException("Impossibile leggere i luoghi disponibili.", error)
        }
    }
}

private fun android.database.Cursor.getNullableDouble(index: Int): Double? =
    if (isNull(index)) null else getDouble(index)
