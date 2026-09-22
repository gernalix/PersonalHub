package com.gernalix.personalhub.core.ui.photo

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

data class HubPhoto(
    val id: String,
    val ownerId: String,
    val reference: String,
    val contentDescription: String,
    val loaderData: Any? = reference,
)

data class HubPhotoSelection(
    val reference: String,
    val mimeType: String?,
    val displayName: String,
)

data class HubPhotoGridItem<T>(val photo: HubPhoto, val owner: T)

@Composable
fun rememberHubPhotoPicker(
    mimeTypes: Array<String> = arrayOf("image/*"),
    onSelected: (HubPhotoSelection) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onSelected(
                HubPhotoSelection(
                    reference = uri.toString(),
                    mimeType = context.contentResolver.getType(uri),
                    displayName = uri.lastPathSegment.orEmpty(),
                ),
            )
        }
    }
    return { launcher.launch(mimeTypes) }
}

@Composable
fun HubSquarePhotoThumbnail(
    photo: HubPhoto,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    onClick: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val request = ImageRequest.Builder(LocalContext.current)
        .data(photo.loaderData)
        .size(with(density) { (size ?: 160.dp).roundToPx() })
        .build()
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .clickable(
                role = Role.Button,
                onClickLabel = photo.contentDescription,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = photo.contentDescription
            }
    }
    Box(
        modifier = modifier
            .then(if (size == null) Modifier else Modifier.size(size))
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(clickable),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = photo.contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { Text("…") },
            error = { Text("×") },
        )
    }
}

@Composable
fun <T> HubPhotoGrid(
    photos: List<HubPhotoGridItem<T>>,
    onOpenOwner: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 96.dp), modifier = modifier) {
        items(photos, key = { it.photo.id }) { item ->
            HubSquarePhotoThumbnail(
                photo = item.photo,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenOwner(item.owner) },
            )
        }
    }
}
