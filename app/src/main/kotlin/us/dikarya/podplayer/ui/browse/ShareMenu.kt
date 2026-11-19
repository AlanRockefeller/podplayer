/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import us.dikarya.podplayer.R

/**
 * A share icon with Copy link and Share (owner, 2026-11-18). [link] null: the menu says the feed gives no web
 * link, so the owner isn't left wondering where the button went. [shareText] is what Share sends.
 */
@Composable
fun ShareMenu(link: ShareLink?, shareText: String?, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(TAG_SHARE)) {
            Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.share_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (link == null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.share_no_link)) }, onClick = { open = false }, enabled = false)
                return@DropdownMenu
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (link.isShowSite) R.string.share_copy_show_site else R.string.share_copy_link)) },
                onClick = {
                    open = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(resources.getString(R.string.share_clip_label), link.url))) }
                    // Android 13 and later confirm a copy themselves.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.testTag(TAG_SHARE_COPY),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_send)) },
                onClick = {
                    open = false
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText ?: link.url)
                    try {
                        context.startActivity(Intent.createChooser(send, resources.getString(R.string.share_send)))
                    } catch (_: ActivityNotFoundException) {
                        // Nothing on the phone takes text; Copy link still works.
                    }
                },
            )
        }
    }
}

const val TAG_SHARE = "share"
const val TAG_SHARE_COPY = "share-copy"
