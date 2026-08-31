package com.gernalix.luoghi.capsules.providerapi

import android.net.Uri
import com.gernalix.luoghi.provider.PlacesContract

object ProviderApiCapsule {
    val placesUri: Uri = PlacesContract.Places.CONTENT_URI
    val aliasesUri: Uri = PlacesContract.Aliases.CONTENT_URI
    val linksUri: Uri = PlacesContract.Links.CONTENT_URI

    fun placeUri(uuid: String): Uri =
        PlacesContract.Places.CONTENT_URI.buildUpon().appendPath(uuid).build()
}
