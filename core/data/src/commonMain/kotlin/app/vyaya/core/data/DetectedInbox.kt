package app.vyaya.core.data

import app.vyaya.core.backup.RecordLines
import app.vyaya.core.dedup.Outcome
import app.vyaya.core.model.Entry
import app.vyaya.core.model.Sha256
import app.vyaya.core.parser.RawMessage
import kotlin.time.Instant

enum class DetectedKind { ADDED, REVIEW }

/** An open inbox item: [entry] was added by itself ([DetectedKind.ADDED]) or waits for the user ([DetectedKind.REVIEW]). */
data class DetectedItem(val id: String, val kind: DetectedKind, val entry: Entry, val reason: String?, val possibleDuplicateOf: String?)

/**
 * The Detected inbox (plan step 3; the user's choice: detected payments are added automatically, then listed here to edit
 * or undo). Review items become entries only when confirmed.
 */
class DetectedInbox(private val db: VyayaDatabase) {

    private val entries = EntryStore(db)

    /** Notes what capture did with [message]: added and review outcomes become inbox items, once per message. */
    suspend fun record(message: RawMessage, outcome: Outcome) {
        val (kind, entry, reason, duplicate) = when (outcome) {
            is Outcome.Added -> Quad(DetectedKind.ADDED, outcome.entry, null, null)
            is Outcome.Review -> Quad(DetectedKind.REVIEW, outcome.draft, outcome.reason?.name, outcome.possibleDuplicateOf?.value)
            else -> return
        }
        db.detected().add(DetectedRow(idOf(message), kind.name, "OPEN", RecordLines.entry(entry), reason, duplicate,
            message.receivedAt.instant.toEpochMilliseconds()))
    }

    suspend fun items(): List<DetectedItem> = db.detected().open().map { row ->
        DetectedItem(row.id, DetectedKind.valueOf(row.kind), RecordLines.entryOf(row.entryLine) ?: error("detected ${row.id}: bad entry line"),
            row.reason, row.possibleDuplicateOf)
    }

    /** An added payment the user didn't make: soft-deleted, so the SMS import won't bring it back. */
    suspend fun undo(id: String, now: Instant) {
        val item = open(id, DetectedKind.ADDED) ?: return
        entries.byId(item.entry.id)?.let { entries.save(listOf(it.markDeleted(now))) }
        db.detected().setStatus(id, "UNDONE")
    }

    /** A review item the user confirms: it becomes an entry. */
    suspend fun confirm(id: String, now: Instant) {
        val item = open(id, DetectedKind.REVIEW) ?: return
        entries.save(listOf(item.entry.copy(updatedAt = now)))
        db.detected().setStatus(id, "CONFIRMED")
    }

    /** A review item the user says isn't a payment of theirs; it stays dismissed when the message is read again. */
    suspend fun dismiss(id: String) {
        open(id, DetectedKind.REVIEW) ?: return
        db.detected().setStatus(id, "DISMISSED")
    }

    private suspend fun open(id: String, kind: DetectedKind): DetectedItem? = items().firstOrNull { it.id == id && it.kind == kind }

    private data class Quad(val kind: DetectedKind, val entry: Entry, val reason: String?, val duplicate: String?)

    companion object {
        /** The same message (sender, text, time) always gives the same id. */
        fun idOf(m: RawMessage): String =
            Sha256().apply { update("${m.source}|${m.sender}|${m.receivedAt.instant.toEpochMilliseconds()}|${m.text}") }.hexDigest().take(32)
    }
}
