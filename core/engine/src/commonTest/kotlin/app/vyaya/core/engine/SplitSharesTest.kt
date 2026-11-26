package app.vyaya.core.engine

import app.vyaya.core.model.Money
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Split bills: equal shares in paise; any odd paise stay with you, so friends never owe more than their share. */
class SplitSharesTest {

    @Test
    fun equal_shares_among_you_and_your_friends() {
        assertEquals(SplitShares(mine = rupees(400), friends = listOf(rupees(400), rupees(400))), SplitShares.equal(rupees(1_200), friends = 2))
        assertEquals(SplitShares(Money(334), listOf(Money(333), Money(333))), SplitShares.equal(Money(1_000), friends = 2), "the odd paisa is yours")
        assertEquals(SplitShares(Money(1), listOf(Money(0))), SplitShares.equal(Money(1), friends = 1), "too small to share")
    }

    @Test
    fun the_shares_always_add_up_to_the_bill() = runTest {
        checkAll(Arb.long(1L..1_000_000_00L), Arb.int(1..20)) { total, friends ->
            val s = SplitShares.equal(Money(total), friends)
            check(s.mine.paise + s.friends.sumOf { it.paise } == total && s.friends.size == friends) { "$total $friends $s" }
            check(s.friends.all { it.paise <= s.mine.paise && s.mine.paise - it.paise < friends + 1 })
        }
    }
}
