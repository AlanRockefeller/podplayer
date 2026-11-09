/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.imagesandbox.ipc;

import android.os.SharedMemory;

/**
 * The only interface between the app and the isolated image process (SPEC §6).
 *
 * The app creates both shared-memory regions. `input` holds the fetched, untrusted image bytes and is
 * read-only. The sandbox writes the re-encoded PNG or JPEG at the start of `output`, whose fixed size caps
 * what it can send back, and returns the number of bytes written. A negative result is a refusal code
 * (`Refusal` in ImageFormat.kt). The app treats the output as untrusted too (OutputCheck).
 */
interface IImageSandbox {
    int decode(in SharedMemory input, in SharedMemory output, int maxSidePx);
}
