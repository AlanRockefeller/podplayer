/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.artwork

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.imagesandbox.ArtworkResult
import us.dikarya.podplayer.core.imagesandbox.ImageSandbox

/**
 * The image sandbox screens load artwork through (`AppGraph.images`, provided by MainActivity). Null in UI tests:
 * then every image is the plain placeholder and nothing is fetched.
 */
val LocalImageSandbox = staticCompositionLocalOf<ImageSandbox?> { null }

/**
 * The artwork for [imageUrl] (a feed or episode image), or null while it loads. [approvedHost] is the feed's
 * owner-approved host (SPEC §3). The image is fetched by the gate and decoded only in the sandbox process.
 */
@Composable
fun rememberArtwork(imageUrl: String?, approvedHost: String?): ArtworkResult? {
    val images = LocalImageSandbox.current
    val result by produceState<ArtworkResult?>(null, images, imageUrl, approvedHost) {
        value = images?.artwork?.artwork(imageUrl, approvedHost)
    }
    return result
}

/**
 * Shows [artwork] from its sandbox-produced cache file, or a plain placeholder: while loading, and for every
 * image that was refused, failed or never existed.
 */
@Composable
fun ArtworkImage(
    artwork: ArtworkResult?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = stringResource(R.string.artwork),
) {
    val images = LocalImageSandbox.current
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (artwork is ArtworkResult.Ready && images != null) {
            AsyncImage(
                model = artwork.file,
                contentDescription = contentDescription,
                imageLoader = images.imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** [imageUrl]'s artwork, loaded and shown in one step. Decorative unless [contentDescription] is given. */
@Composable
fun Artwork(imageUrl: String?, approvedHost: String?, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    ArtworkImage(rememberArtwork(imageUrl, approvedHost), size, modifier, contentDescription)
}
