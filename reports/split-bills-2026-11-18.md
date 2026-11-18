# Findings: split bills (across categories and with friends): 2026-11-18

**Scope:** `4dfeb0f`, which covers:
- `Entry.ownShare` and `MonthSummary` (only the user's share is spending);
- `SplitShares.equal`;
- `SplitStore` (the entry plus friends' LENT ledger entries, in one transaction);
- the entry form's "Split across categories" and "Split with friends";
- the CSV `signed_amount`.

**Decision applied:** option A. The user's own share is spending; friends' shares are owed back (People), not
spending, and a repayment isn't income.

**Method:**
- Code analysis.
- Split-bill simulation `-Psim.engine.users=2000`.
- The full local gate is green.

**Status:** all five fixed on 2026-11-18. See the Resolution at the end.

## Simulation

| Check | Result |
|---|---|
| Splits tried (2,000 users, with chaos) | 44,274; refused 6,345, **0 changed anything while refused** |
| An entry's parts add up to its amount | 0 off |
| The month's spending = the users' own shares | 0 off |
| Spending by category = the parts | 0 off |
| What friends owe = their shares | 0 off |

## Findings

1. **[DATA-001] [High] Deleting or changing a split payment leaves what friends "owe" behind**
   - Where: the friends' LENT ledger entries aren't linked to the entry they came from. The backup format has no field
     for it: `LedgerEntry` has no entry id.
   - How to reproduce (by analysis): split a ₹1,200 dinner with Rahul and Priya, then delete it (a mistake, or the
     payment was refunded). People still says each owes ₹400. Adding the dinner again puts ₹800 more on top.
   - Why it matters: People becomes wrong in a way the user can't see, and they may ask friends for money they don't
     owe.
   - Direction (not a fix): link each ledger entry to its payment. This is a backup format change (`schemaVersion`
     bump, migration, round-trip test; rule 8.7, cheap before the first release). Deleting the payment then asks
     "Also take back what Rahul and Priya owe (₹800)?".

2. **[DATA-002] [High] A friend paying back by UPI is counted as income**
   - Where: option A says a repayment isn't income. But Rahul's UPI "₹400 received from Rahul" is captured as an
     ordinary income entry, and settling him in People is separate and manual.
   - What happens: "Left" goes up by ₹400 that was never the user's spending money, and the People balance stays
     until the user remembers to settle it.
   - Direction: when a credit's payee matches a person with an open balance (and the amount is at most what they owe),
     ask in the Detected inbox "Rahul paid you back ₹400?". Yes makes it a pay-back in People and no income
     (plan B2 "auto-settle on incoming credit").

3. **[FLAW-003] [Medium] A split payment can't be edited**
   - Where: the form shows a split entry's parts read-only; the amount and parts can't change. The only way is to
     delete it and add it again, and with #1 that doubles what friends owe.
   - Direction: once #1 links them, allow editing the parts and shares, with the ledger entries updated in the same
     transaction.

4. **[FLAW-004] [Medium] Safe to spend treats money lent as yours to spend**
   - Where: Left (and so safe to spend) counts only the user's share. The ₹800 paid for friends left the bank account
     but isn't subtracted until they pay back.
   - What happens: right after a big shared bill, the daily amount goes up rather than down.
   - Direction: safe to spend subtracts this month's money lent and not yet repaid ("after ₹800 your friends owe
     you").

5. **[FLAW-005] [Low] Adding a friend after editing a share leaves the new share empty**
   - Where: equal shares are filled in only until the user edits one. A friend added after that gets an empty field,
     and Save says "Enter each friend's share".
   - Direction: fill the new friend's field with an equal share of what's left; keep the edited ones.

6. **[Info] Things that held up**
   - The entry keeps the payment's full amount, so de-dup and the bank's later message match it as before.
   - The friends' part is a split in the system "Lent & Borrowed" category: already valid in backups, and no format
     change.
   - The entry and the ledger entries are one transaction: a refused or failed split writes nothing (0 in the
     simulation).
   - Budgets count each part in its own category; friends' shares are in no budget.
   - The CSV's `signed_amount` is the user's share (SUM = what's left); `amount` stays the payment.
   - Equal shares are in paise, with any odd paise yours. Friends never owe more than an equal share (property test).

## Decisions needed from you
- **#1:** link friends' shares to the payment now, as a backup format change before the first release
  (recommended)?
- **#2:** ask "Rahul paid you back ₹400?" when a matching credit arrives (recommended)?
- **#3:** edit a split payment's parts and shares, after #1 (recommended)?
- **#4:** safe to spend subtracts money lent this month and not yet repaid (recommended)?
- **#5:** fill a new friend's share from what's left (recommended)?

## Resolution (2026-11-18)

Your decision: go with the recommendations (#1–#5). Each fix began with a failing test.

| # | Status | Fix |
|---|---|---|
| 1 DATA-001 | ✅ Fixed | **Backup v4.** A ledger entry has an optional `paymentId`. Split shares are linked to their payment. v2 and v3 files restore with no link, and a v3 file carrying the field is refused as edited. A link to an unknown payment is refused. Golden v4 file, round-trip property test, v3 → v4 migration test (rule 8.7). Deleting a split payment asks "Also take back what Priya and Rahul owe (₹800)?" (names in alphabetical order; Take it back / Keep in People). Delete reads the shares itself, so a tap before the form has loaded them still asks. Both are one transaction (`SplitStore.delete`) |
| 2 DATA-002 | ✅ Fixed | Capture asks in Detected "Rahul paid you back ₹400?" when money comes in from a person who owes at least that. The name must agree word for word as far as the shorter goes ("Rahul" and "Rahul Sharma"); two equally good matches mean nobody is asked. Yes adds a pay-back in People and removes the income (soft delete, so an SMS import won't bring it back). No keeps it as income. A question about someone who no longer owes that much just closes |
| 3 FLAW-003 | ✅ Fixed | A split payment opens with its parts and friends' shares filled in. Save replaces the shares (the old ones are taken back), never adding to them. Removing every part and friend makes it a plain payment again. Save waits until the shares are read |
| 4 FLAW-004 | ✅ Fixed | Safe to spend subtracts `openLent`: for each friend, this month's lending, at most what they owe now. Home says "after ₹800 your friends owe you". A friend's share of a bill paid today counts in today's amount like your own share, so the day's amount doesn't jump |
| 5 FLAW-005 | ✅ Fixed | A friend added after a share was typed gets an equal part of what's left (shared with you). Typed shares stay |

Simulation after the fixes (`-Psim.engine.users=2000`; reference: an independent model of what each friend owes):

| Check | Result |
|---|---|
| Settled in People 4,817; edits 8,233; deletes 4,069 (shares taken back 2,034); money-in messages 9,569 (asked 5,781: yes 4,344, no 1,437) | — |
| Asked when it shouldn't be / not asked when it should | 0 / 0 |
| What each person owes, against the model | 0 off |
| Income, against the model (pay-backs out, "no" kept) | 0 off |
| Links lost in a backup round trip; backups refused | 0; 0 |
| Open lending this month > what's owed | 0 |
| First simulation again (44,274 splits) | all 0. A payment saved with no parts and no friends is now a plain entry, so the parts check skips it |

The simulation's model also settles people in People. Run against the first version of #3 (every edit replaced every
share), it reported 51 people off; with the fix below, 0.

Found while fixing (also fixed): safe to spend added back today's spending at the full payment amount rather than your
share. The split store refused to un-split a payment corrected to income.

Found in review after the fixes (fixed test-first; each test was red before):
- An edit made a settled share owed again. Saving a split re-created every share as not settled, and every edit of a
  split payment (even its note) goes through the split store. Now a share with the same person and amount stays as it
  is; only changed ones are replaced (`split_flaw_003_an_edit_never_reopens_a_share_already_settled`).
- Home said "Bills still due (₹0) are ₹300 more than what's left" when money lent was what took it below zero. It now
  says "Nothing safe to spend until your friends pay you back: they owe you ₹800, ₹200 more than what's left", or
  names bills and lending together.
- "Yes, Rahul paid me back" on money since made a transfer deleted the transfer. It now only takes out income.
- Delete asks only about shares not yet settled. Save waits while the shares are being read (screen test with the
  shares still loading).

Not covered by a test: Delete reading the shares itself rather than the form's copy. The race needs a tap before a
database read finishes, which a Robolectric test can't time reliably.

## Assumptions made without asking (say if you want any changed)
1. Matching a pay-back to a person: the names agree word for word as far as the shorter goes ("Rahul" = "Rahul
   Sharma"). Two equally good matches mean no question. The amount must be at most what they owe.
2. "Take it back", or removing a friend from a split, after they already paid back part of it leaves the pay-back in
   People. The balance can then say you owe them, which is true: they paid for a share that's gone.
3. Saving a split payment with every part and friend removed makes it a plain payment (it used to make a one-part
   split of the whole amount). The first simulation's parts check was changed to match.
4. Open lending counts only this month's lending. Older lending came out of an earlier month's money.
5. Pay-back questions appear in Detected, which iOS hides for now (the iOS-step item in the branch review).

