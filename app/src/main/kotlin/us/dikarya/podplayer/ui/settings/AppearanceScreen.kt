/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.ui.theme.PodplayerTheme

@StringRes
private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.SYSTEM -> R.string.theme_system
}

/** Settings › Appearance, the first section of [SettingsScreen]. */
@Composable
fun AppearanceScreen(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_appearance),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.bodyMedium,
            color = PodplayerTheme.colors.textQuiet,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(modifier = Modifier.selectableGroup()) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(
                            selected = mode == themeMode,
                            onClick = { onThemeModeChange(mode) },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // The whole row is the control; the radio button only shows the state.
                    RadioButton(selected = mode == themeMode, onClick = null)
                    Text(text = stringResource(mode.label()), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
