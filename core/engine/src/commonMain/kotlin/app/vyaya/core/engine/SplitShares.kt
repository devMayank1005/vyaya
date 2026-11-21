package app.vyaya.core.engine

import app.vyaya.core.model.Money

/** A bill shared with friends: the user's own share and each friend's (split bills, Phase 3). */
data class SplitShares(val mine: Money, val friends: List<Money>) {
    companion object {
        /** Equal shares among the user and [friends] people, in paise; any odd paise stay with the user. */
        fun equal(total: Money, friends: Int): SplitShares {
            require(friends >= 1) { "at least one friend" }
            val each = total.paise / (friends + 1)
            return SplitShares(Money(total.paise - each * friends), List(friends) { Money(each) })
        }
    }
}
