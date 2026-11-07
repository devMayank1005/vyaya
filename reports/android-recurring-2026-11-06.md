# Findings: recurring rules (RulesStore and the Recurring tab): 2026-11-06

**Scope:** `ff53735` (`RulesStore`, `SnapshotStore.saveRecords`) and `c7e17e4` (the Recurring tab).

**Method:**
- Code analysis, and the tests: `RulesStoreTest` on JVM and iOS, and the Compose tests on the real database.
- No new simulation: catch-up and clock repair on the database were simulated for `AppOpen` (38,508 opens: 0
  duplicates, 0 missing, 0 refused). This change adds how rules are made and paused, not how entries are made.

**Status:** reported only. **Nothing fixed.**

## Findings

1. **[FLAW-001] [Low] Changing a rule's amount doesn't change this month's entry**
   - What: catch-up already made this month's entry with the old amount. The new amount applies from the next month.
     Someone whose rent went up today sees the old amount on Home.
   - Direction: when the amount changes, offer "Also change this month's (₹15,000 → ₹16,000)?". Or leave it, and the
     user edits that entry.

2. **[FLAW-002] [Low] A rule paused for years lists every missed month in the question**
   - What: the dialog joins all the months ("2024-01, 2024-02, …"). Long, but correct.
   - Direction: "14 months, from Jan 2024 to Feb 2025".

3. **[Info] Things that held up**
   - An existing rule's type can't change (its entries keep matching it, so backups aren't refused).
   - Resume asks about the missed months; "Skip them" marks them done (catch-up leaves them empty), in one write
     transaction with the resume.
   - Saving a rule doesn't touch the settings: found while building it, `SnapshotStore.save` with a one-rule snapshot
     would have reset the settings to the defaults. Rules use `saveRecords`.
   - A deleted rule makes no more entries; its past entries stay.
   - A rule added mid-month after its day starts next month (`Recurring.startsOnFor`).

## Decisions needed from you
- **#1:** offer to change this month's entry too (recommended)?
- **#2:** a shorter month range in the question (recommended)?
