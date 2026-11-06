package app.vyaya.core.data

import app.vyaya.core.backup.RecordLines
import app.vyaya.core.dedup.Capture
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.EntityId
import app.vyaya.core.model.Entry
import app.vyaya.core.parser.MessageParser
import app.vyaya.core.parser.MessageSplitter
import app.vyaya.core.parser.ParseResult
import app.vyaya.core.parser.RawMessage
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Entries on the database: each row is the entry's backup line plus the columns queries use. */
class EntryStore(private val db: VyayaDatabase) {

    suspend fun save(entries: List<Entry>) = db.entries().upsert(entries.map(::row))

    suspend fun byId(id: EntityId): Entry? = db.entries().byId(id.value)?.let(::entryOf)

    suspend fun all(): List<Entry> = db.entries().all().map(::entryOf)

    /** Entries dated between [from] and [to], or carrying one of [refs]. */
    suspend fun nearOrWithRef(from: Instant, to: Instant, refs: List<String>): List<Entry> =
        db.entries().nearOrWithRef(from.toEpochMilliseconds(), to.toEpochMilliseconds(), refs).map(::entryOf)

    /** Entries dated from [from] (inclusive) to [to] (exclusive). */
    suspend fun inRange(from: Instant, to: Instant): List<Entry> =
        db.entries().inRange(from.toEpochMilliseconds(), to.toEpochMilliseconds()).map(::entryOf)

    /** [inRange], again each time the entries change. */
    fun watchRange(from: Instant, to: Instant): Flow<List<Entry>> =
        db.entries().watchRange(from.toEpochMilliseconds(), to.toEpochMilliseconds()).map { rows -> rows.map(::entryOf) }

    internal suspend fun generated(): List<Entry> = db.entries().generated().map(::entryOf)

    internal suspend fun changedAfter(t: Instant): List<Entry> = db.entries().changedAfter(t.toEpochMilliseconds()).map(::entryOf)

    internal suspend fun delete(ids: List<EntityId>) = BackupStore.lookupChunks(ids.map { it.value }).forEach { db.entries().delete(it) }

    internal fun entryOf(row: EntryRow): Entry =
        RecordLines.entryOf(row.line) ?: error("entry ${row.id}: its stored line is not a valid entry") // written only by save()

    companion object {
        fun row(e: Entry) = EntryRow(
            id = e.id.value,
            line = RecordLines.entry(e),
            occurredAtMs = e.occurredAt.instant.toEpochMilliseconds(),
            amountPaise = e.amount.paise,
            type = e.type.name,
            upiRef = e.upiRef,
            payeeKey = e.payee?.let(Capture::payeeKey),
            ruleId = e.generatedBy?.ruleId?.value,
            month = e.generatedBy?.month?.toString(),
            deleted = e.deleted,
            updatedAtMs = e.updatedAt.toEpochMilliseconds(),
            createdAtMs = e.createdAt.toEpochMilliseconds(),
        )
    }
}

/**
 * Capture on the database: a message from the live SMS receiver, the SMS import or a UPI app's notification becomes an
 * entry, a transfer, a duplicate (nothing saved) or a review item. Detected payments are added automatically (the
 * user's choice); the app's Detected inbox lists them for edit or undo.
 */
class CaptureStore(private val db: VyayaDatabase, private val inbox: DetectedInbox = DetectedInbox(db)) {

    private val entries = EntryStore(db)

    /** Every capture rule looks at most this far from a message's time (recurring entries: 5 days); references, any time. */
    private val window = 6.days

    /**
     * DATA-004 (branch review): one message at a time (the SMS receiver and the notification listener can fire together),
     * and each message in one database transaction, so a crash leaves nothing half-saved. Each part of a message is
     * looked up near its own date (DATA-002), and split and parsed once (FLAW-009).
     */
    suspend fun process(raw: RawMessage, now: Instant): List<Outcome> = lock.withLock {
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                MessageSplitter.split(raw).map { part ->
                    val parsed = MessageParser.parse(part)
                    val refs = listOfNotNull((parsed as? ParseResult.Transaction)?.txn?.upiRef)
                    val time = part.receivedAt.instant
                    val outcome = Capture.process(part, parsed, entries.nearOrWithRef(time - window, time + window, refs), now)
                    when (outcome) {
                        is Outcome.Added -> entries.save(listOf(outcome.entry))
                        is Outcome.Transfer -> entries.save(listOf(outcome.entry))
                        else -> {}
                    }
                    inbox.record(part, outcome)
                    outcome
                }
            }
        }
    }

    private companion object {
        /** One capture at a time for the app's one database. */
        val lock = Mutex()
    }
}
