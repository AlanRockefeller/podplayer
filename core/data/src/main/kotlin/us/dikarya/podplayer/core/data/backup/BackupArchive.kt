/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import us.dikarya.podplayer.core.data.db.PodDatabase

@Serializable
data class BackupManifest(
    val format: Int,
    val app: String = "Podplayer",
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: Long,
)

/** How much a backup may unpack to. A backup is any file the owner picks, so these hold for every read. */
data class BackupLimits(
    val maxDatabaseBytes: Long = 1L shl 30,
    /** Manifest, settings, and anything unknown (skipped, but still read). */
    val maxOtherBytes: Int = 256 * 1024,
    val maxEntries: Int = 16,
)

/** Why a backup can't be restored. */
enum class RestoreProblem {
    /** Not a zip, or missing a part, or a part that doesn't parse. */
    NotABackup,

    /** A part unpacks to more than the limits allow. */
    TooLarge,

    /** Made by a newer version of the app. */
    NewerThanApp,

    /** The database inside fails SQLite's integrity check. */
    Damaged,

    /** The database is intact but isn't shaped like ours, or holds values this version can't read. */
    UnexpectedContent,

    /** The file couldn't be read (permission lost, storage gone). */
    Unreadable,

    /** The library couldn't be backed up first, so it wasn't replaced. */
    SafetyBackupFailed,
}

/**
 * The backup file: a zip with `manifest.json` (first), `database.sqlite` (a consistent snapshot) and
 * `settings.json` (every setting from AppSettings). Entry names are only ever compared against these three;
 * nothing is unpacked under a name taken from the file.
 */
internal object BackupArchive {
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val DATABASE = "database.sqlite"
    const val SETTINGS = "settings.json"

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface ReadResult {
        class Ok(val manifest: BackupManifest, val settingsJson: String) : ReadResult

        data class Refused(val problem: RestoreProblem) : ReadResult
    }

    /** Writes the archive to [out] without closing it. */
    fun write(out: OutputStream, manifest: BackupManifest, database: File, settingsJson: String) {
        val zip = ZipOutputStream(out.buffered())
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(DATABASE))
        database.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(SETTINGS))
        zip.write(settingsJson.toByteArray())
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    /** Reads an archive, unpacking its database to [databaseOut]. Never throws for bad input. */
    fun read(input: InputStream, databaseOut: File, limits: BackupLimits): ReadResult {
        val refused = { problem: RestoreProblem -> ReadResult.Refused(problem) }
        var manifestText: String? = null
        var settingsText: String? = null
        var haveDatabase = false
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > limits.maxEntries) return refused(RestoreProblem.NotABackup)
                    when (entry.name) {
                        MANIFEST -> {
                            if (manifestText != null) return refused(RestoreProblem.NotABackup)
                            manifestText = readLimited(zip, limits.maxOtherBytes) ?: return refused(RestoreProblem.TooLarge)
                        }
                        SETTINGS -> {
                            if (settingsText != null) return refused(RestoreProblem.NotABackup)
                            settingsText = readLimited(zip, limits.maxOtherBytes) ?: return refused(RestoreProblem.TooLarge)
                        }
                        DATABASE -> {
                            if (haveDatabase) return refused(RestoreProblem.NotABackup)
                            if (!copyLimited(zip, databaseOut, limits.maxDatabaseBytes)) return refused(RestoreProblem.TooLarge)
                            haveDatabase = true
                        }
                        // Unknown entries (a newer app's extras, or a hostile name) are read past, never stored.
                        else -> if (readLimited(zip, limits.maxOtherBytes) == null) return refused(RestoreProblem.TooLarge)
                    }
                }
            }
        } catch (_: ZipException) {
            return refused(RestoreProblem.NotABackup)
        } catch (_: EOFException) {
            return refused(RestoreProblem.NotABackup)
        } catch (_: IOException) {
            return refused(RestoreProblem.Unreadable)
        } catch (_: IllegalArgumentException) {
            // Malformed entry names.
            return refused(RestoreProblem.NotABackup)
        }
        val manifest = manifestText?.let { runCatching { json.decodeFromString(BackupManifest.serializer(), it) }.getOrNull() }
        val settings = settingsText
        if (manifest == null || settings == null || !haveDatabase) return refused(RestoreProblem.NotABackup)
        return when {
            manifest.format > FORMAT || manifest.schemaVersion > PodDatabase.VERSION -> refused(RestoreProblem.NewerThanApp)
            manifest.format < 1 || manifest.schemaVersion < 1 -> refused(RestoreProblem.NotABackup)
            else -> ReadResult.Ok(manifest, settings)
        }
    }

    /** The entry as text, or null if it is longer than [max] bytes. */
    private fun readLimited(input: InputStream, max: Int): String? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (buffer.size() + n > max) return null
            buffer.write(chunk, 0, n)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun copyLimited(input: InputStream, target: File, max: Long): Boolean {
        target.parentFile?.mkdirs()
        var total = 0L
        target.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > max) return false
                out.write(chunk, 0, n)
            }
        }
        return true
    }
}
