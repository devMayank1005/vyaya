package app.vyaya.core.data

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
)

@Dao
interface EntryDao {
    @Upsert
    suspend fun upsert(rows: List<EntryRow>)

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun byId(id: String): EntryRow?

    @Query("SELECT * FROM entries")
    suspend fun all(): List<EntryRow>

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
    /** OPEN, then UNDONE, CONFIRMED or DISMISSED. */
    val status: String,
    val entryLine: String,
    val reason: String?,
    val possibleDuplicateOf: String?,
    val receivedAtMs: Long,
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

    @Query("UPDATE detected SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)
}

@Database(entities = [EntryRow::class, DetectedRow::class], version = 1)
@ConstructedBy(VyayaDatabaseConstructor::class)
abstract class VyayaDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun detected(): DetectedDao
}

// Room's compiler supplies the actual implementations for each platform.
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object VyayaDatabaseConstructor : RoomDatabaseConstructor<VyayaDatabase> {
    override fun initialize(): VyayaDatabase
}
