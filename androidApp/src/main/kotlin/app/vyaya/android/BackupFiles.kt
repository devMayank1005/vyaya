package app.vyaya.android

import app.vyaya.core.backup.BackupLimits
import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.EncodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.data.BackupStore
import app.vyaya.core.data.RestoreResult
import app.vyaya.core.data.VyayaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant

/**
 * Backups to and from the files the user picks.
 * - Export is written to a temporary file first and copied to the user's file only when it succeeded, so a refused
 *   export (data a restore would refuse) never leaves a broken backup behind.
 * - Restore copies the picked file into app storage first and restores from that copy (FLAW-005, core-data report):
 *   reading a slow cloud file can't hold the database locked, and a read error is a refusal, not a crash (FLAW-008).
 *   The copy stops at [maxCopyBytes]: no valid backup is larger (128M characters, at most 3 bytes each).
 */
class BackupFiles(
    private val db: VyayaDatabase,
    private val dir: File,
    private val appVersion: String,
    private val maxCopyBytes: Long = BackupLimits().maxTotalChars * 3,
) {
    suspend fun export(out: OutputStream, now: Instant): EncodeResult = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("export", ".tmp", dir)
        try {
            val result = temp.bufferedWriter().use { w ->
                BackupStore(db).export(ExportMeta(appVersion, "android", now)) { w.write(it); w.write("\n") }
            }
            if (result is EncodeResult.Success) temp.inputStream().use { it.copyTo(out) }
            result
        } catch (e: IOException) {
            EncodeResult.Failure("The backup couldn't be written: ${e.message ?: "storage error"}")
        } finally {
            temp.delete()
        }
    }

    suspend fun restore(input: InputStream, mode: RestoreMode, now: Instant, restoreAnyway: Boolean = false): RestoreResult =
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile("restore", ".tmp", dir)
            try {
                var copied = 0L
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > maxCopyBytes) return@withContext refused("This file is too large to be a Vyaya backup")
                        out.write(buffer, 0, n)
                    }
                }
                temp.bufferedReader().useLines { BackupStore(db).restore(it, mode, now, restoreAnyway) }
            } catch (e: IOException) {
                refused("The file couldn't be read to the end (${e.message ?: "read error"}). Nothing was changed.")
            } finally {
                temp.delete()
            }
        }

    private fun refused(problem: String) = RestoreResult.Refused(DecodeResult.Failure(listOf(problem)))
}
