package app.vyaya.core.backup

import app.vyaya.core.model.Entry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

/**
 * One record as its exact backup v3 line, and back. The database (core/data) stores each record this way, so backup
 * export and restore stream rows as they are, with no second format to keep in step.
 *
 * Reading checks what a restore checks for a single record (shape, types, ranges, the model's rules). What needs the
 * other records (references, duplicates, the export time) is checked by a restore as a whole.
 */
object RecordLines {

    fun entry(e: Entry): String = BackupLines.line("entry", BackupLines.compact.encodeToJsonElement(EntryV3.serializer(), BackupCodec.entryV3(e)))

    /** The entry in [line]; null when it isn't a valid entry line. */
    fun entryOf(line: String): Entry? {
        val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        if ((obj["t"] as? JsonPrimitive)?.content != "entry" || JsonKeys.firstDuplicate(line) != null) return null
        val record = BackupLines.decode(EntryV3.serializer(), obj) ?: return null
        return Reader(Instant.fromEpochSeconds(0)).run { entry(record); entriesRead.singleOrNull() }
    }
}
