# Findings: generation key and backup schema v3: 2026-11-02

**Scope:** `aadeeed` (`Entry.generatedBy`; ids derived from rule + due date) and `8a6b0f5` (backup schema
v3, migration from v1 and v2, simulation).

**Method:**
- **Break phase:** temporary probes (not committed) fed crafted v3 backups through `BackupCodec`, plus a
  design review of how the engine will use the key.
- **Simulation:** `./gradlew :core:simulation:jvmTest -Psim.backup.users=2000 -Psim.backup.big=true`.

**Status:** reported only. **Nothing fixed.**

## Simulation summary

| Check | Result |
|---|---|
| Round trips identical (2,000 users, now with generated entries) | 2,000 |
| Two-phone merges: phone B generated overlapping months on its own, then its backup was merged | 2,000 merges, **0 duplicated, 0 lost** |
| Corrupted files accepted with changed data (of 20,000) | **0** (114 accepted unchanged), 0 crashes |
| 200k entries | 96 MB file (92 MB in v2), write 2.5 s, read 4.2 s, fidelity ok |

## Findings

1. **[SEC-001] [Medium] A crafted backup can block future recurring entries for good**
   - Where: `Reader.entry`; `dueOn` is only range-checked (2000–2100)
   - How to reproduce: add a soft-deleted entry with key (rent, 2030-01-05) and its derived id. It's
     accepted. *(Probe confirmed.)*
   - What happens: once merged, catch-up will find the key for Jan 2030 already there (deleted), so it never
     creates January 2030's rent. Nothing tells the user.
   - Why it matters: a backup can't honestly contain a payment due after the backup was made, because
     catch-up only generates due dates up to today. It's the same idea as SEC-005, but for `dueOn`.
     Tampering is possible until encryption (accepted SEC-001 in the v2 report).
   - Direction (not a fix): refuse keys with `dueOn` after `exportedAt` (+1 day for time zones).

2. **[DATA-002] [Medium] Changing a rule's day gives a second entry in the same month**
   - Where: the key is rule + **due date** (CLAUDE.md rule 8.4), not rule + month.
   - How to reproduce (design): rent is due on the 5th, and October's is generated. On 7 Oct the user moves
     rent to the 10th. Catch-up sees no key (rent, 10 Oct) and creates a second October rent.
   - Why it matters: double counting, the thing the key exists to prevent. The engine isn't built, so this
     is the time to choose.
   - Direction: key on rule + budget month (or rule + period number), or have the engine treat a day change
     as "this month is done". This changes rule 8.4's wording, so it's your decision.

3. **[PEN-003] [Low] A manual entry can take the id a future generated entry will need**
   - How to reproduce: a crafted backup adds a manual entry (no key) whose id is the derived id for
     (rent, Nov 2026). It's accepted. *(Probe confirmed.)*
   - What happens: when catch-up generates November's rent, it gets the same id as the manual entry. A
     database upsert would overwrite one with the other, or skip it.
   - Direction: refuse version-8 ids on entries without a key. Ids the app creates itself are always
     version 4.

4. **[DATA-004] [Low] Keys that don't fit the rule's schedule are accepted**
   - How to reproduce: key (Spotify, 2025-12-20). The rule is due on the 5th and starts in January 2026.
     It's accepted. *(Probe confirmed.)*
   - Why it matters: the entry can't have come from catch-up. It's harmless until the engine trusts keys
     when it calculates what's due.
   - Direction: check the due date against the rule's day (clamped in short months) and `startsOn`. Day
     changes (#2) make this harder, so decide #2 first.

5. **[FLAW-005] [Low] Older entries marked "recurring" have no key**
   - What happens: v1 and v2 entries with source RECURRING migrate with no key. If catch-up later runs over
     those months, it adds a keyed copy next to each.
   - Why it matters: only files from before v3, and the app hasn't been released, so none exist.
   - Direction: accept as risk, or have catch-up skip months that already have a RECURRING entry for the
     same rule and amount.

6. **[FLAW-006] [Low] The generated entry's type and amount aren't tied to the rule**
   - What happens: a keyed entry may be a TRANSFER from an EXPENSE rule, or a different amount.
   - Why it matters: different amounts are intended (the user edits this month's bill). A different type
     is probably not.
   - Direction: decide whether editing a generated entry may change its type.

7. **[Info] `lastGeneratedOn` is now redundant**
   - It can be worked out from the keys. Keeping it stored breaks rule 8.5 and can drift from the entries.
     This is the still-open half of audit #8.

8. **[Info] Things that held up**
   - The two-phone merge invariant held for all 2,000 merges, through the full backup file path.
   - The derived id is identical to an independent Python computation, and a pinned test checks it on both
     platforms.
   - The id can't be forged by changing the due date: a mismatched id is refused.
   - A derived id (version 8) can never collide with a random id (version 4).
   - v2 stays frozen: a v2 line with `generatedBy` is refused as edited.
   - Unknown rules are refused.
   - Newer schemas (4+) give the clear "update the app" message.

## Decisions needed from you
- **#1, #3:** refuse future `dueOn` and version-8 ids without a key? Both are small.
- **#2:** key on rule + due date (as now) or rule + month? This changes rule 8.4.
- **#7:** remove `lastGeneratedOn` now, while v3 isn't released (fold it into v3 rather than a v4)?

## Resolution (2026-11-02)

Your decisions: fix #1 and #3, key by month (#2), remove `lastGeneratedOn` (#7). Every fix started with a
failing test. The follow-up break phase is in [`recurring-key-month-2026-11-02.md`](recurring-key-month-2026-11-02.md).

| # | Status | Fix |
|---|---|---|
| 1 SEC-001 | ✅ Fixed `12b5952` | A key for a month after the backup was made (in the backup's time zone) is refused. The Codex review on PR #1 raised the same finding |
| 2 DATA-002 | ✅ Fixed `12b5952` | The key is rule + **calendar month**; CLAUDE.md rule 8.4 updated |
| 3 PEN-003 | ✅ Fixed `12b5952` | Only an entry made by a rule may have a derived (version-8) id |
| 4 DATA-004 | ✅ Fixed `25b4211` (2026-11-03) | A key for a month before its rule's first month is refused |
| 5 FLAW-005 | Accepted risk (2026-11-03) | Only backups made before v3 have recurring entries with no key, and none exist: the app isn't released |
| 6 FLAW-006 | ✅ Fixed `25b4211` (2026-11-03) | A generated entry keeps its rule's type; the amount may differ |
| 7 `lastGeneratedOn` | ✅ Removed `12b5952` | Worked out from the keys; v1 and v2 values are dropped when read. v3 was changed in place (not released or merged) |
