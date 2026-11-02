# Findings: month key, future-key check, derived-id check: 2026-11-02

**Scope:** `12b5952`:
- generated entries are keyed by rule + calendar month;
- keys after the backup's month are refused;
- only generated entries may have derived ids;
- `lastGeneratedOn` is removed.

**Method:**
- **Break phase:** temporary probes (not committed) on crafted v3 backups and odd month values.
- **Simulation:** `./gradlew :core:simulation:jvmTest -Psim.backup.users=2000 -Psim.backup.big=true`.
  Phone B now moves a third of its rules to another day before running catch-up on its own. Duplicates are
  counted by rule and the calendar month of the payment, so they don't depend on the key design.

**Status:** reported only. **Nothing fixed.**

## Simulation summary

| Check | Result |
|---|---|
| Round trips identical (2,000 users) | 2,000 |
| Two-phone merges, with days moved on phone B | 2,000 merges, **0 duplicated, 0 lost** |
| Corrupted files accepted with changed data (of 20,000) | **0** (121 accepted unchanged), 0 crashes |
| 200k entries | 96 MB file, write 2.8 s, read 4.6 s, fidelity ok |

## Findings

1. **[DATA-001] [Medium] A key's month and the payment's date can disagree**
   - Where: `Reader.entry`; `Entry` doesn't tie `generatedBy.month` to `occurredAt`
   - How to reproduce: a generated entry with key October 2026 and `occurredAt` 5 December. It's accepted.
     *(Probe confirmed.)*
   - What happens: October counts as done for catch-up, while the payment shows in December. When catch-up
     creates December's entry, December shows two payments and October none. The same happens without
     tampering if the app lets the user move a generated entry's date into another month (rent paid late).
   - Why it matters: monthly totals and "what's left" are off by one payment in two months.
   - Direction (not a fix): decide whether a generated entry may move out of its month. If not, check it in
     the model and the backup. If it may, the engine has to use the key month, not the date, for "done".

2. **[FLAW-002] [Medium] Deleting a rule and re-creating it in the same month gives two entries**
   - How to reproduce: delete Spotify (its October entry stays), add Spotify again in October. Catch-up
     makes October's entry for the new rule. *(Probe confirmed: two October Spotify entries.)*
   - Why it matters: the key is per rule, and the new rule has a new id. Users do this to "fix" a rule.
   - Direction: in the engine, a new rule's first month is the month after creation if the due day has
     passed, or "edit" is offered instead of delete-and-add. Decide when the engine is built.

3. **[PEN-003] [Low] A backup's own time zone can buy it one extra month**
   - How to reproduce: set the backup's time zone to `Pacific/Kiritimati` (UTC+14). Exported 31 Oct 10:30
     UTC, it is already 1 November there, so a November key is accepted. The same file with Kolkata time is
     refused. *(Probe confirmed.)*
   - Why it matters: it's limited to a few hours near a month's end and one month ahead, and needs a
     tampered file (integrity, not authenticity, until encryption).
   - Direction: accept as risk, or use the restoring phone's time zone for the check.

4. **[FLAW-004] [Low] The engine needs a fast "last generated month" now that it isn't stored**
   - What happens: working it out means finding the latest key per rule among all entries.
   - Direction: when `core/data` is built, index entries by (`generatedBy.ruleId`, `generatedBy.month`).

5. **[Info] Things that held up**
   - Months must be written exactly as `2026-10`: `+2026-10`, `2026-1`, `02026-10`, trailing spaces and
     full-width digits are all refused.
   - Month keys survive a moved day in all 2,000 merges.
   - Keys after the export month are refused, with the backup's time zone respected (a November key from
     a backup made 31 Oct 20:00 UTC in Kolkata is accepted).
   - A manual entry can't take a derived id.
   - A v3 recurring line that still has `lastGeneratedOn` is refused as edited.

## Decisions needed from you
- **#1:** may a generated entry be moved to another month?
- **#2:** how should re-creating a rule in the same month behave?
- **#3:** accept as risk?
