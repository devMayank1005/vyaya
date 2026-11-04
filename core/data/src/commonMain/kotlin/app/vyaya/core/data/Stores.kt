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

    private fun entryOf(row: EntryRow): Entry =
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

    suspend fun process(raw: RawMessage, now: Instant): List<Outcome> {
        val time = raw.receivedAt.instant
        val refs = MessageSplitter.split(raw).mapNotNull { (MessageParser.parse(it) as? ParseResult.Transaction)?.txn?.upiRef }
        val existing = entries.nearOrWithRef(time - window, time + window, refs)
        val outcomes = Capture.processAll(raw, existing, now)
        val parts = MessageSplitter.split(raw)
        outcomes.forEachIndexed { i, outcome -> inbox.record(parts.getOrElse(i) { raw }, outcome) }
        entries.save(outcomes.mapNotNull {
            when (it) {
                is Outcome.Added -> it.entry
                is Outcome.Transfer -> it.entry
                else -> null
            }
        })
        return outcomes
    }
}
