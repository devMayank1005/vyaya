package app.vyaya.core.data

import kotlinx.coroutines.flow.Flow

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert

/**
 * One entry. [line] is the record exactly as a backup v3 line, so export and restore stream rows as they are; the
 * other columns are copies of fields that queries need, kept in step with [line] by the repository.
 */
@Entity(
    tableName = "entries",
    indices = [Index("occurredAtMs"), Index("upiRef"), Index("ruleId", "month"), Index("updatedAtMs")],
)
data class EntryRow(
    @PrimaryKey val id: String,
    val line: String,
    val occurredAtMs: Long,
    val amountPaise: Long,
    val type: String,
    val upiRef: String?,
    /** The loose payee key capture compares (letters and digits, part before `@`). */
    val payeeKey: String?,
    /** The generation key of an entry a recurring rule made (CLAUDE.md rule 8.4). */
    val ruleId: String?,
    val month: String?,
    val deleted: Boolean,
    val updatedAtMs: Long,
    /** For clock repair on open: entries made while the clock was ahead (FLAW-004, core-data report). */
    val createdAtMs: Long,
)

@Dao
interface EntryDao {
    @Upsert
    suspend fun upsert(rows: List<EntryRow>)

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: String): EntryRow?

    @Query("SELECT * FROM entries")
    suspend fun all(): List<EntryRow>

    @Query("SELECT * FROM entries WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<EntryRow>

    /** Entries dated in [fromMs, toMs): a month's, with the month function deciding by each entry's zone. */
    @Query("SELECT * FROM entries WHERE occurredAtMs >= :fromMs AND occurredAtMs < :toMs")
    suspend fun inRange(fromMs: Long, toMs: Long): List<EntryRow>

    @Query("SELECT * FROM entries WHERE occurredAtMs >= :fromMs AND occurredAtMs < :toMs")
    fun watchRange(fromMs: Long, toMs: Long): Flow<List<EntryRow>>

    /** What changes when any entry in [fromMs, toMs) is added, edited, deleted or removed (MVP2 FLAW-001). */
    @Query("SELECT COUNT(*) AS n, COALESCE(SUM(updatedAtMs), 0) AS changed, COALESCE(SUM(createdAtMs), 0) AS made FROM entries WHERE occurredAtMs >= :fromMs AND occurredAtMs < :toMs")
    suspend fun fingerprint(fromMs: Long, toMs: Long): RangeFingerprint

    /** When the earliest entry not deleted happened; null when there is none. */
    @Query("SELECT MIN(occurredAtMs) FROM entries WHERE deleted = 0")
    suspend fun earliestMs(): Long?

    /** Entries a recurring rule made (catch-up and clock repair look at these only). */
    @Query("SELECT * FROM entries WHERE ruleId IS NOT NULL")
    suspend fun generated(): List<EntryRow>

    /** Entries made or changed after [ms] (the clock was ahead). */
    @Query("SELECT * FROM entries WHERE updatedAtMs > :ms OR createdAtMs > :ms")
    suspend fun changedAfter(ms: Long): List<EntryRow>

    @Query("DELETE FROM entries WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    /** Review 2026-11-06 #9: pages go on from the last id (an index seek), not by OFFSET (which re-walks every row). */
    @Query("SELECT id, line FROM entries WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageAfter(afterId: String, limit: Int): List<IdLine>

    @Query("SELECT COUNT(*) FROM entries")
    suspend fun count(): Int

    @Query("DELETE FROM entries")
    suspend fun clear()

    /** Rows dated in [fromMs, toMs], or carrying one of [refs]: what capture needs to recognise a payment again. */
    @Query("SELECT * FROM entries WHERE (occurredAtMs BETWEEN :fromMs AND :toMs) OR upiRef IN (:refs)")
    suspend fun nearOrWithRef(fromMs: Long, toMs: Long, refs: List<String>): List<EntryRow>
}

/**
 * One item of the Detected inbox. [id] comes from the message itself (sender, text, time), so the same message read
 * again (live, then the import) is never a second item. [entryLine] is the entry as its backup line; no SMS text is kept.
 */
@Entity(tableName = "detected", indices = [Index("status")])
data class DetectedRow(
    @PrimaryKey val id: String,
    /** ADDED (saved by itself: edit or undo) or REVIEW (saved only when confirmed). */
    val kind: String,
    /** OPEN, then UNDONE, CONFIRMED, DUPLICATE (captured meanwhile) or DISMISSED; CLOSED when a Replace restore took away the data it was about (DATA-003). */
    val status: String,
    val entryLine: String,
    val reason: String?,
    val possibleDuplicateOf: String?,
    val receivedAtMs: Long,
    /** The entry this item is about. */
    val entryId: String? = null,
)

@Dao
interface DetectedDao {
    /** A message already in the inbox (in any status) is left as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(row: DetectedRow)

    @Query("SELECT * FROM detected WHERE id = :id")
    suspend fun byId(id: String): DetectedRow?

    @Query("SELECT * FROM detected WHERE status = 'OPEN' ORDER BY receivedAtMs DESC")
    suspend fun open(): List<DetectedRow>

    @Query("SELECT * FROM detected WHERE status = 'OPEN' ORDER BY receivedAtMs DESC")
    fun watchOpen(): Flow<List<DetectedRow>>

    @Query("UPDATE detected SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    /** Kept, not deleted, so re-reading the same message doesn't bring the item back. */
    @Query("UPDATE detected SET status = 'CLOSED' WHERE status = 'OPEN'")
    suspend fun closeOpen()

    /** The item about [entryId] now shows [line] as [kind] (an added payment that became a transfer). */
    @Query("UPDATE detected SET kind = :kind, entryLine = :line WHERE entryId = :entryId")
    suspend fun updateEntry(entryId: String, kind: String, line: String)
}

/**
 * Every record that isn't an entry (accounts, categories, recurring rules, people, ledger, budgets, merchant rules), and
 * the settings (kind "settings"), each as its backup line. Few and small, so one table.
 */
@Entity(tableName = "records", primaryKeys = ["kind", "id"])
data class RecordRow(val kind: String, val id: String, val line: String, val deleted: Boolean, val updatedAtMs: Long)

@Dao
interface RecordDao {
    @Upsert
    suspend fun upsert(rows: List<RecordRow>)

    @Query("SELECT * FROM records ORDER BY kind, id")
    suspend fun all(): List<RecordRow>

    @Query("SELECT id, line FROM records WHERE kind = :kind AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageAfter(kind: String, afterId: String, limit: Int): List<IdLine>

    @Query("SELECT COUNT(*) FROM records WHERE kind = :kind")
    suspend fun count(kind: String): Int

    @Query("DELETE FROM records")
    suspend fun clear()
}

@Database(entities = [EntryRow::class, DetectedRow::class, RecordRow::class], version = 1)
@ConstructedBy(VyayaDatabaseConstructor::class)
abstract class VyayaDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun detected(): DetectedDao
    abstract fun records(): RecordDao
}

// Room's compiler supplies the actual implementations for each platform.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object VyayaDatabaseConstructor : RoomDatabaseConstructor<VyayaDatabase> {
    override fun initialize(): VyayaDatabase
}

/** A row's id and backup line, for paging in id order. */
data class IdLine(val id: String, val line: String)

/** Count and change times of a range of entries: equal fingerprints mean nothing in the range changed. */
data class RangeFingerprint(val n: Long, val changed: Long, val made: Long)
