package com.gernalix.luoghi.provider

import android.net.Uri
import android.provider.BaseColumns

object PlacesContract {
    const val API_VERSION = 7
    const val AUTHORITY = "com.gernalix.personalhub.luoghi.places"
    const val READ_PERMISSION = "com.gernalix.personalhub.permission.READ_PLACES"
    const val WRITE_PERMISSION = "com.gernalix.personalhub.permission.WRITE_PLACES"
    val BASE_URI: Uri = Uri.parse("content://$AUTHORITY")

    object Places : BaseColumns {
        val CONTENT_URI: Uri = BASE_URI.buildUpon().appendPath("places").build()
        const val PATH = "places"
        const val PLACE_ID = "place_id"
        const val DISPLAY_NAME = "display_name"
        const val UUID = "uuid"
        const val NICKNAME = "nickname"
        const val ADDRESS = "address"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
        const val LAT = "lat"
        const val LON = "lon"
        const val RADIUS_M = "radius_m"
        const val NOTES = "notes"
        const val SOURCE_APP = "source_app"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
        const val ARCHIVED = "archived"
    }

    object Nicknames {
        val CONTENT_URI: Uri = BASE_URI.buildUpon().appendPath("nicknames").build()
        const val PATH = "nicknames"
        const val STABLE_ID = "stable_id"
        const val NICKNAME = "nickname"
        const val ADDRESS = "address"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
    }

    object Events : BaseColumns {
        val CONTENT_URI: Uri = BASE_URI.buildUpon().appendPath("events").build()
        const val PATH = "events"
        const val ID = "id"
        const val EVENT_UUID = "event_uuid"
        const val SESSION_UUID = "session_uuid"
        const val PLACE_ID = "place_id"
        const val EVENT_TYPE = "event_type"
        const val TIMESTAMP = "timestamp"
        const val LAT = "lat"
        const val LON = "lon"
        const val ACCURACY_M = "accuracy_m"
        const val SOURCE = "source"
        const val NOTES = "notes"
    }

    object Nearby {
        val CONTENT_URI: Uri = BASE_URI.buildUpon().appendPath("nearby").build()
        const val PATH = "nearby"
        const val LAT = "lat"
        const val LON = "lon"
        const val RADIUS_M = "radius_m"
        const val LIMIT = "limit"
    }

    object Nearest {
        val CONTENT_URI: Uri = BASE_URI.buildUpon().appendPath("nearest").build()
        const val PATH = "nearest"
        const val LAT = "lat"
        const val LON = "lon"
        const val LIMIT = "limit"
    }

    object Links : BaseColumns {
        val CONTENT_URI: Uri = BASE_URI.buildUpon().appendPath("links").build()
        const val PATH = "links"
        const val ID = "id"
        const val PLACE_UUID = "place_uuid"
        const val OWNER_APP = "owner_app"
        const val OWNER_TYPE = "owner_type"
        const val OWNER_ID = "owner_id"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }

    object Aliases : BaseColumns {
        val CONTENT_URI: Uri = BASE_URI.buildUpon().appendPath("aliases").build()
        const val PATH = "aliases"
        const val ID = "id"
        const val PLACE_UUID = "place_uuid"
        const val ALIAS = "alias"
        const val APP_SCOPE = "app_scope"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }
}
