package app.vyaya.android

import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.data.RestoreResult
import java.io.InputStream

/**
 * The steps of a restore: choose Merge or Replace, pick the file, restore; and "Restore anyway" on the same file.
 * MVP FLAW-006: when that file is no longer at hand (the app was closed, or the picker's access to it ended), the user
 * is asked to pick it again and the restore then goes ahead anyway, as asked.
 */
class RestoreFlow<K>(
    private val open: (K) -> InputStream?,
    private val pick: () -> Unit,
    private val restore: suspend (InputStream, RestoreMode, Boolean) -> RestoreResult,
) {
    private var mode = RestoreMode.MERGE
    private var file: K? = null
    private var anywayNext = false

    fun start(mode: RestoreMode) {
        this.mode = mode
        anywayNext = false
        pick()
    }

    suspend fun picked(k: K): RestoreResult {
        file = k
        val anyway = anywayNext
        anywayNext = false
        return open(k)?.use { restore(it, mode, anyway) } ?: RestoreResult.Refused(DecodeResult.Failure(listOf("The file couldn't be opened")))
    }

    /** Null when the file had to be asked for again (the result then comes from [picked]). */
    suspend fun restoreAnyway(): RestoreResult? {
        val stream = file?.let(open)
        if (stream == null) {
            anywayNext = true
            pick()
            return null
        }
        return stream.use { restore(it, mode, true) }
    }
}
