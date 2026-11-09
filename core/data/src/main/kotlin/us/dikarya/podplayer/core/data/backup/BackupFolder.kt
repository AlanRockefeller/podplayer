/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A file in a backup folder. [id] is whatever the folder needs to find it again. */
data class BackupEntry(val name: String, val id: String)

/** Somewhere backups are kept: the app's own folder, or the folder the owner picked. Failures are IOExceptions. */
interface BackupFolder {
    fun list(): List<BackupEntry>

    /** Writes a new file. A failed write leaves nothing behind that looks like a finished backup. */
    fun write(name: String, writer: (OutputStream) -> Unit)

    fun <T> read(entry: BackupEntry, reader: (InputStream) -> T): T

    fun delete(entry: BackupEntry)
}

/** Backup file names: `podplayer-backup-YYYYMMDD-HHMMSS[-tag].zip`, in UTC. Only these are ever rotated. */
object BackupNames {
    private val pattern = Regex("^podplayer-backup-(\\d{8}-\\d{6})(-[a-z0-9-]{1,40})?\\.zip$")
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun create(at: Long, tag: String? = null): String {
        val suffix = tag?.lowercase()?.filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }?.take(40)?.trim('-')
        return "podplayer-backup-${stamp.format(Instant.ofEpochMilli(at))}${if (suffix.isNullOrEmpty()) "" else "-$suffix"}.zip"
    }

    /** When a backup with this name was made (to the second), or null if the name isn't one of ours. */
    fun parse(name: String): Long? {
        val match = pattern.matchEntire(name) ?: return null
        return runCatching {
            LocalDateTime.parse(match.groupValues[1], DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }
}

/** A folder on the file system: the app-private copies, and tests. Writes go to a hidden file, then rename. */
class FileBackupFolder(private val dir: File) : BackupFolder {
    override fun list(): List<BackupEntry> {
        if (dir.exists() && !dir.isDirectory) throw IOException("not a folder")
        return dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.map { BackupEntry(it.name, it.name) }.orEmpty()
    }

    override fun write(name: String, writer: (OutputStream) -> Unit) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create the folder")
        val partial = File(dir, ".$name.partial")
        try {
            partial.outputStream().use { out ->
                writer(out)
                out.flush()
                out.fd.sync()
            }
            if (!partial.renameTo(File(dir, name))) throw IOException("can't rename the backup")
        } finally {
            partial.delete()
        }
    }

    override fun <T> read(entry: BackupEntry, reader: (InputStream) -> T): T = file(entry).inputStream().use(reader)

    override fun delete(entry: BackupEntry) {
        file(entry).delete()
    }

    private fun file(entry: BackupEntry): File {
        if ('/' in entry.id || '\\' in entry.id || entry.id.startsWith(".")) throw IOException("not a backup in this folder")
        return File(dir, entry.id)
    }
}

/**
 * The folder the owner picked with the Storage Access Framework (SPEC §7). The app holds a persisted
 * permission for the tree; if that is lost (folder deleted, permission revoked), every call fails with an
 * IOException and Settings asks the owner to choose the folder again.
 */
class SafBackupFolder(private val resolver: ContentResolver, private val tree: Uri) : BackupFolder {
    private fun treeId(): String = guarded { DocumentsContract.getTreeDocumentId(tree) }

    override fun list(): List<BackupEntry> = guarded {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId())
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(children, columns, null, null, null) ?: throw IOException("the backup folder can't be listed")
        cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) continue
                    add(BackupEntry(name, id))
                }
            }
        }
    }

    override fun write(name: String, writer: (OutputStream) -> Unit) = guarded {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId())
        val document = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
            ?: throw IOException("can't create a file in the backup folder")
        try {
            val out = resolver.openOutputStream(document, "w") ?: throw IOException("can't write to the backup folder")
            out.use(writer)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw e
        }
    }

    override fun <T> read(entry: BackupEntry, reader: (InputStream) -> T): T = guarded {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
        (resolver.openInputStream(uri) ?: throw IOException("can't read the backup")).use(reader)
    }

    override fun delete(entry: BackupEntry) = guarded {
        DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, entry.id))
        Unit
    }

    /** Lost permissions and vanished providers arrive as SecurityException or IllegalArgumentException. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw IOException("no access to the backup folder", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("the backup folder is gone", e)
    }

    companion object {
        /** The folder's name as the provider shows it, for Settings. */
        fun displayName(resolver: ContentResolver, tree: Uri): String? = runCatching {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}
