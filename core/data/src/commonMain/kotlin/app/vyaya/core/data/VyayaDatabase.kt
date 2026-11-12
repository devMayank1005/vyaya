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
    indices = [Index("occurredAtMs", "id"), Index("upiRef"), Index("ruleId", "month"), Index("updatedAtMs")],
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
    /** To count what uses a category or an account before it's deleted (catalog report #5). */
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
)

/**
 * Entries not deleted, by time then id, after the last one read: keyset pages for the spreadsheet export. The row-value
 * condition reads straight from the (occurredAtMs, id) index; an OR of the two columns sorted the rest of the table for
 * every page, so the export took time growing with the square of the entries (csv report #1).
 */
internal const val PAGE_BY_TIME =
    "SELECT * FROM entries WHERE (occurredAtMs, id) > (:afterMs, :afterId) AND deleted = 0 ORDER BY occurredAtMs, id LIMIT :limit"

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

    /** Entries not deleted, by time then id, after the last one read (keyset pages for the spreadsheet export). */
    @Query(PAGE_BY_TIME)
    suspend fun pageByTime(afterMs: Long, afterId: String, limit: Int): List<EntryRow>

    /** Entries (not deleted) of [type] to one payee (by its key): how many, and how many have no category. */
    @Query(
        "SELECT COUNT(*) AS total, COALESCE(SUM(categoryId IS NULL), 0) AS uncategorised FROM entries " +
            "WHERE payeeKey = :key AND type = :type AND deleted = 0",
    )
    suspend fun payeeCounts(key: String, type: String): PayeeCounts

    @Query("SELECT * FROM entries WHERE payeeKey = :key AND type = :type AND categoryId IS NULL AND deleted = 0")
    suspend fun uncategorisedFor(key: String, type: String): List<EntryRow>

    @Query("SELECT COUNT(*) FROM entries WHERE deleted = 0 AND categoryId = :id")
    suspend fun countWithCategory(id: String): Int

    @Query("SELECT COUNT(*) FROM entries WHERE deleted = 0 AND (accountId = :id OR toAccountId = :id)")
    suspend fun countWithAccount(id: String): Int

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

    /** The item's entry as it is now, its kind unchanged (a later message filled in the payee or account). */
    @Query("UPDATE detected SET entryLine = :line WHERE entryId = :entryId")
    suspend fun updateLine(entryId: String, line: String)
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

    @Query("SELECT * FROM records WHERE kind = :kind")
    suspend fun ofKind(kind: String): List<RecordRow>

    /**
     * A cheap check of what capture reads (accounts, categories, merchant rules): how many, the sum of their change
     * times, and the sum of their lengths. Any add, edit, delete or restore changes it (merchant memory report #5).
     */
    @Query(
        "SELECT COUNT(*) AS n, COALESCE(SUM(updatedAtMs), 0) AS changed, COALESCE(SUM(LENGTH(line)), 0) AS made " +
            "FROM records WHERE kind IN ('account', 'category', 'merchantRule')",
    )
    suspend fun captureFingerprint(): RangeFingerprint

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
data class PayeeCounts(val total: Int, val uncategorised: Int)

data class RangeFingerprint(val n: Long, val changed: Long, val made: Long)
