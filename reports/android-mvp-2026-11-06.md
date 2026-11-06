# Findings: Android MVP screens (home, add/edit, backup) and the open routine: 2026-11-06

**Scope:** branch `android-mvp`, `fe9b516`..`df8f66c`:
- `AppOpen` (default categories, clock repair, catch-up on the database);
- `MonthStore`;
- `DefaultCategories`;
- the home screen, the add/edit form, the backup screen (`BackupFiles`);
- `VyayaRoot`.

**Method:**
- **Break phase:** code analysis, plus temporary Robolectric probes (not committed): editing a recurring entry's type,
  and moving a recurring entry two months away.
- **Simulation:** `DatabaseOpenSimulationRunTest`, 1,000 users over 18 months with clock jumps, edits while ahead,
  rules moved, paused and resumed, and generated entries edited and deleted.

**Status:** reported only. **Nothing fixed.**

## Simulation summary

| Check | Result |
|---|---|
| Opens (1,000 users) | 38,508, with 3,240 clock jumps |
| The database after each open = the in-memory engine | **38,508 / 38,508** |
| Recurring entries doubled / months missing (invariant 4) | **0 / 0** |
| Backups refused once the clock was right | **0** |

## Findings

1. **[DATA-001] [High] Changing a recurring entry to Income (or back) blocks every backup**
   - Where: the edit form (`AddEntryScreen` / `EntryInput.toEntry`) lets a generated entry change type.
   - How to reproduce (probe): rent (an expense rule) makes November's entry; edit it, tap Income, Save. Every export
     after that fails with "type INCOME differs from its rule's (EXPENSE)" (FLAW-006 of the engine report: a restore
     refuses it). The user can't back up and has no way to tell which entry is the cause.
   - Direction: for an entry a rule made, keep the type fixed (show "From rule: Rent"); amount, day within the month,
     category and note stay editable. Or have the save refuse what a backup would refuse, with that message.

2. **[SEC-002] [Medium] A backup is a plain file with the whole financial history**
   - What: the export writes readable JSON wherever the user picks (Drive, WhatsApp, Downloads). Anyone who gets the
     file can read every payee, amount, note and UPI reference.
   - Direction: the plan's password-encrypted `.vyaya` (AES-GCM) before release. Until then, say on the screen that the
     file isn't encrypted.

3. **[SEC-003] [Medium] No app lock: anyone with the unlocked phone sees everything and can export it**
   - What: home, entries and Export open without a check. A backup can be made and shared in seconds.
   - Direction: the plan's app lock (biometric or device credential), at least before Export and Restore.

4. **[FLAW-004] [Low] Moving a recurring entry to another month is refused with a vague message**
   - Probe: picking a day two months away doesn't save. The form only says "Check the amount and the fields".
   - Direction: limit the date picker for such entries, or say why.

5. **[FLAW-005] [Low] Payee or note too long only shows the generic error**
   - What: an entry allows 100 characters of payee and 500 of note. Over that, the form refuses without saying which field.
   - Direction: limit the fields' length as typed.

6. **[FLAW-006] [Low] "Restore anyway" does nothing after the app was closed**
   - What: the picked file is remembered only while the app runs. If the phone closes the app between the refusal and
     "Restore anyway", the button silently does nothing.
   - Direction: ask for the file again when it is no longer at hand.

7. **[Info] The open routine runs each time the app comes to the front** (also back from the file picker or
   settings). It reads only records, generated entries and entries changed after now, so it's cheap at today's sizes
   (38,508 opens in the simulation). It grows with rules × months, which is bounded.

8. **[Info] Things that held up**
   - Clock repair and catch-up on the database give the engine's result exactly.
   - Default categories have the same ids on every phone: merging two new phones can't double them, and deleting one
     doesn't bring it back.
   - A refused export writes nothing to the user's file. The restore copy is always removed.
   - Damaged and oversized files change nothing.
   - The home month follows each entry's own date and zone.

## Decisions needed from you
- **#1:** keep a recurring entry's type fixed (recommended)?
- **#2 and #3:** encrypted backups and the app lock next (recommended; both are plan items before release)?
- **#4, #5, #6:** fix with the form and backup screen polish (recommended)?
