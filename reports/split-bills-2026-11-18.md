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

**Status:** reported only. **Nothing fixed.**

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
