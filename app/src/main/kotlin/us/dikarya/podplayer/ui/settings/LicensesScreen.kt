/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import us.dikarya.podplayer.R
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/** A library that ships in the app, with its license (docs/THIRD_PARTY.md has the full table). */
data class OpenSourceLibrary(val name: String, val authors: String, val license: String)

/** What the release app contains. Keep in step with docs/THIRD_PARTY.md when a library is added. */
val OPEN_SOURCE_LIBRARIES = listOf(
    OpenSourceLibrary("Kotlin standard library, kotlinx-coroutines, kotlinx-serialization", "JetBrains", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX (core, activity, lifecycle, navigation, DataStore, Room, SQLite, WorkManager, media)", "The Android Open Source Project", "Apache License 2.0"),
    OpenSourceLibrary("Jetpack Compose and Material 3", "The Android Open Source Project", "Apache License 2.0"),
    OpenSourceLibrary("Media3 (ExoPlayer, session, OkHttp data source)", "The Android Open Source Project", "Apache License 2.0"),
    OpenSourceLibrary("Guava (Android)", "Google", "Apache License 2.0"),
    OpenSourceLibrary("OkHttp and Okio", "Square", "Apache License 2.0"),
    OpenSourceLibrary("Coil", "Coil Contributors", "Apache License 2.0"),
    OpenSourceLibrary("Accompanist Drawable Painter", "Google", "Apache License 2.0"),
    OpenSourceLibrary("jsoup", "Jonathan Hedley", "MIT License"),
    OpenSourceLibrary("Material Symbols icons", "Google", "Apache License 2.0"),
)

@Composable
fun LicensesScreen() {
    val colors = PodplayerTheme.colors
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.licenses_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(OPEN_SOURCE_LIBRARIES) { library ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(library.name, style = MaterialTheme.typography.bodyLarge)
                Text(library.authors, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Text(library.license, style = MaterialTheme.typography.bodyMedium, color = colors.accentSecondary)
            }
            HorizontalDivider(color = colors.divider)
        }
    }
}
