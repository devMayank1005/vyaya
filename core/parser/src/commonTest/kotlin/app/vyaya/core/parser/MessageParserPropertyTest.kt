package app.vyaya.core.parser

import app.vyaya.core.fixtures.Channel
import app.vyaya.core.fixtures.Flow
import app.vyaya.core.fixtures.NoiseCategory
import app.vyaya.core.fixtures.Synthetic
import app.vyaya.core.model.CapturedAt
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Every synthetic template, with random amounts / payees / refs / masks, parses back to exactly its fields. */
class MessageParserPropertyTest {

    private val at = CapturedAt(Instant.parse("2026-10-26T10:00:00Z"), TimeZone.of("Asia/Kolkata"))
    private fun source(c: Channel) = if (c == Channel.SMS) Source.SMS else Source.NOTIFICATION

    @Test
    fun every_template_round_trips_its_fields() = runTest {
        for (template in Synthetic.transactions) {
            checkAll(200, Arb.long()) { seed ->
                val fields = Synthetic.fields(Random(seed))
                val text = template.text(fields)
                val expected = template.expected(fields)
                val result = MessageParser.parse(RawMessage(source(template.channel), template.sender, text, at))
                val txn = (result as? ParseResult.Transaction)?.txn
                val context = "template=${template.id}\n text=$text\n got=$result"
                assertTrue(txn != null, context)
                assertEquals(expected.amount, txn.amount, context)
                assertEquals(if (expected.flow == Flow.DEBIT) Direction.DEBIT else Direction.CREDIT, txn.direction, context)
                assertEquals(expected.counterparty, txn.counterparty, context)
                assertEquals(expected.upiRef, txn.upiRef, context)
                assertEquals(expected.accountMask, txn.accountMask, context)
            }
        }
    }

    @Test
    fun noise_that_the_parser_owns_never_becomes_a_transaction() = runTest {
        val parserOwned = setOf(NoiseCategory.OTP, NoiseCategory.PROMO, NoiseCategory.FAILED, NoiseCategory.REQUEST)
        for (template in Synthetic.noise.filter { it.category in parserOwned }) {
            checkAll(200, Arb.long()) { seed ->
                val text = template.text(Synthetic.fields(Random(seed)))
                val result = MessageParser.parse(RawMessage(source(template.channel), template.sender, text, at))
                assertTrue(result !is ParseResult.Transaction, "template=${template.id}\n text=$text\n got=$result")
            }
        }
    }
}
