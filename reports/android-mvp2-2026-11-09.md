# Findings: UI/UX pass, Saved and the month end, People (lent and borrowed): 2026-11-09

**Scope:**
- `ios-app` `d396229`, `ca44d07`: the theme, the summary card, entries by day, icons;
- `android-mvp-2` `0c165b3`: `SavingsStore` and Saved on Home;
- `482a23e`, `76be846`: `LedgerStore` and the People tab.

**Method:**
- Code analysis, plus the tests: shared logic on Android and iOS, Compose UI over a real database.
- `SavingsSimulationRunTest`: 300 users with entries in five zones, deleted ones, transfers and month starts 1–28.
  Saved so far from the database was compared with the engine's `MonthEnd` over every entry, and a 10-year user timed.

**Status:** reported only. **Nothing fixed.**

## Simulation summary

| Check | Result |
|---|---|
| Saved / Overspent so far: database = engine (300 users) | **300 / 300** |
| 10 years, 20,000 entries | 120 months summed in **187 ms** (this Mac) |

## Findings

1. **[FLAW-001] [Medium] Saved so far is recounted from every month each time Home's month changes**
   - What: one query per finished month (120 for 10 years), on each change to the shown month's entries: 187 ms on
     this Mac, likely 1–2 s on a low-end phone. A wrong old date (year 2000) means 26 years of queries.
   - Direction: keep each finished month's result in memory until an entry in that month changes (the database
     knows which months changed), or recount only when the app opens.

2. **[FLAW-002] [Medium] "Saved so far" leaves out the overspent months**
   - What: a user who saved ₹6,000 in September and overspent ₹2,000 in October sees "Saved so far ₹6,000". The
     net is ₹4,000.
   - Decision for you: show the net ("₹4,000 over 2 months"), or both ("Saved ₹6,000, overspent ₹2,000")? I
     recommend both. Hiding the overspent month makes the number flattering, not true.

3. **[DATA-003] [Medium] Paying back more than is owed silently turns the debt around**
   - How: Rahul owes ₹200; "Paid back ₹300" makes it "You owe Rahul ₹100".
   - Why it matters: a slip of the finger (an extra zero) changes who owes whom without saying so.
   - Direction: cap "Paid back" at the balance, saying so ("Rahul owes ₹200; settle all?"), or ask before turning it
     around.

4. **[FLAW-004] [Low] A lent or borrowed entry can't be edited or deleted in the app**
   - The store can delete one, but the screen has no way to. A typo can only be undone with an entry the other way.
   - Direction: tap an entry to edit or delete it (soft delete).

5. **[FLAW-005] [Low] A person can't be renamed or removed in the app**
   - The store supports both; the screen doesn't. Removing someone with an open balance should first ask to settle.

6. **[Info] Not yet seen on a phone:** dark mode, small screens and large font sizes of the new design. Worth a look
   on your phone.

7. **[Info] Things that held up**
   - Saved is calculated, never stored (rule 8.5), and matches the engine for every simulated user, including
     custom month starts and entries recorded in other time zones.
   - A finished month says what it saved or overspent; the current month doesn't.
   - Balances come from the engine each time (invariant 6). Settle all keeps the history, marked settled. Lent and
     borrowed never count as spending.
   - CI's common-code compile caught a JVM-only call (`toSortedMap`), fixed. It is now part of the local checks.

## Decisions needed from you
- **#1:** keep finished months' results until they change (recommended)?
- **#2:** show both saved and overspent (recommended), or the net?
- **#3:** cap "Paid back" at the balance (recommended)?
- **#4, #5:** edit and delete for ledger entries and people (recommended)?

## Resolution (2026-11-09)

Your decision: go with the recommendations.

| # | Status | Fix |
|---|---|---|
| 1 FLAW-001 | ✅ Fixed | `SavingsStore` keeps each finished month's result with a fingerprint of its rows (count and change times, one SQL aggregate); a month is read again only when something in it changed. One store per screen |
| 2 FLAW-002 | ✅ Fixed | "Saved so far ₹6,000 over 2 months, overspent ₹2,000" |
| 3 DATA-003 | ✅ Fixed | "Paid back" can't exceed the balance; the form says "Rahul owes you ₹200; use Settle all to clear it", and the store refuses it too |
| 4 FLAW-004 | ✅ Fixed | Tap an entry to correct its amount or note, or delete it |
| 5 FLAW-005 | ✅ Fixed | Rename a person; remove once settled ("Settle all first to remove …") |
