/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.notes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import us.dikarya.podplayer.R
import us.dikarya.podplayer.core.feed.notes.RichStyle
import us.dikarya.podplayer.core.net.UrlPolicy

/**
 * The intent that opens [url] outside the app, or null when it isn't an http, https or mailto link
 * (CLAUDE.md). Checked again here, right before opening, whatever the sanitizer decided earlier.
 */
fun linkIntent(url: String): Intent? {
    if (!UrlPolicy.isAllowedLink(url)) return null
    // Intent filters match schemes case-sensitively, so `MAILTO:` would find no mail app (CodeRabbit review, PR #1).
    val uri = url.trim().toUri().normalizeScheme()
    return when (uri.scheme) {
        "http", "https" -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        "mailto" -> Intent(Intent.ACTION_SENDTO, uri)
        else -> null
    }
}

/** Opens [url] in another app; a plain message when nothing can. */
fun Context.openLink(url: String) {
    val intent = linkIntent(url) ?: return
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.link_no_app, Toast.LENGTH_SHORT).show()
    }
}

/** SPEC §5: tapping a link shows where it goes before the browser opens. */
@Composable
fun LinkConfirmDialog(link: RichStyle.Link, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_open_title)) },
        text = { Text(stringResource(R.string.link_open_body, link.destination)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                context.openLink(link.url)
            }) { Text(stringResource(R.string.link_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
