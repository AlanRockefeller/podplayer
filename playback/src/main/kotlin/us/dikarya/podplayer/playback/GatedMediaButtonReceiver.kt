/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:OptIn(UnstableApi::class)

package us.dikarya.podplayer.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver

/**
 * Playback resumption (SPEC §10): a Play media button reaches us after the service or the process was stopped.
 * Media3's receiver starts the service in the foreground for it, and Android then requires the service to show
 * its notification within seconds. If the Bluetooth gate ignored that Play once the service was up (a ticked car's
 * automatic Play, CLAUDE.md), nothing would play, the service would never go to the foreground, and Android would
 * stop the app. So the gate decides here first: an ignored Play never starts the service; any other starts it as
 * before, and the session resumes the saved episode.
 */
class GatedMediaButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val deps = (context.applicationContext as? PlaybackHost)?.playbackDependencies() ?: return true
        val reason = deps.playGate.ignoreMediaButtonReason() ?: return true
        deps.store.log("Ignored Play from a media button while stopped: $reason")
        return false
    }
}
