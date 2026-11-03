# Findings: core/backup v2 fixes (PEN-002, PEN-003, FLAW-004): 2026-11-02

**Scope:** `0f72212` (at most 20 splits per entry) and `1724378` (lines checked as they're read, 16 KB line
limit, 256 MB total size cap, stop reading once the problem list is full, clock message).

**Method:**
- **Break phase:** a temporary probe (not committed) measured peak and retained heap under a 512 MB heap,
  on the old code (`a83a3c9`) and the new code:
  - crafted files with correct checksums;
  - a valid file at the size cap;
  - real 100k and 200k entry backups.
- **Simulation:** `./gradlew :core:simulation:jvmTest -Psim.backup.users=2000 -Psim.backup.big=true`.

**Status:** reported only. **Nothing fixed.**

## Measurements (old → new, same probe, 512 MB heap)

| Case | File | Old: peak / held after | New: peak / held after |
|---|---|---|---|
| Real backup, 100k entries | 46 MB | 201 / 65 MB, 2.3 s | 181 / 65 MB, 2.0 s |
| Real backup, 200k entries | 92 MB | 312 / 129 MB, 4.0 s | 251 / 129 MB, 3.9 s |
| Crafted: 3,000 lines with 60 KB notes | 172 MB | 238 MB, 2.6 s, refused | ~0 MB, 2 ms: "Line 2 is too long" |
| Crafted: 12,000 lines with 15 KB notes | 176 MB | 240 MB, 3.0 s, refused | ~2 MB, 5 ms: stops after 22 lines |
| Valid: 250,000 entries with 500-character notes | 212 MB | 446 / 228 MB, accepted | 371 / 228 MB, accepted |

"Held after" is what stays in memory once the restored data is built, so it is the data itself.

**Simulation:**
- 2,000 users: all round trips identical.
- 20,000 corrupted files: **0** accepted with changed data, **0** crashes; 108 to 134 accepted with the data
  unchanged (it varies between runs, see SIM-007).
- Large backups streamed through a file:
  - 200k entries: 92 MB file, write 2.2 s, read 4.4 s, fidelity ok.

## Findings

1. **[FLAW-001] [Medium] If the exporting phone's clock was ahead, the user has no way out**
   - Where: `Reader.header`, the "made in the future" check (SEC-005), with option (a)
   - How to reproduce: export on a phone whose clock is 3 days ahead, then restore on a phone with the right
     time.
   - What happens: the message asks the user to check *this* phone's date and time. It's correct, so they
     can't do anything. Restore stays blocked. The old message at least said the exporting phone's clock may
     have been wrong.
   - Why it matters: the backup is valid and the user can't restore it, for example after losing the
     old phone.
   - Direction (not a fix): option (b), "Restore anyway" after a confirmation, or a second message for this
     case. This is the open (a)/(b) question.

2. **[PEN-002] [Medium] A valid file at the 256 MB cap still needs about 371 MB to read**
   - How to reproduce: 250,000 valid entries with 500-character notes (212 MB) → accepted, peak about 371 MB,
     228 MB held.
   - Why it matters: every record passes validation, so nothing stops it early. Low-end phones often give
     an app 192 to 256 MB, so a file like this (crafted, or 10 years of long notes) runs out of memory there.
     The size cap is not tied to the phone's memory.
   - Direction: restore into the database in batches (`core/data`), so memory doesn't grow with file size.
     Or a lower cap (128 MB is about 280,000 real entries). Measure on a real low-end phone.

3. **[PEN-003] [Medium] The restored data itself is the memory floor**
   - What happens: 200k real entries peak at 251 MB and hold 129 MB. All of that is the `Snapshot` that
     restore returns.
   - Why it matters: the reader can't get much smaller while restore returns everything in memory.
   - Direction: as #2, stream into the database in batches, and merge by id inside the database.

4. **[FLAW-004] [Low] A file with many bad records no longer says "edited"**
   - How to reproduce: edit 25 entries so each breaks a rule, then recompute nothing.
   - What happens: reading stops at the 21st problem and shows 20 technical lines with ids, plus "stopped
     reading at line N". Before, the checksum check ran first and said "damaged or edited".
   - Why it matters: the user sees a wall of ids, not "this file was changed".
   - Direction: when reading stops early, lead with "This file looks damaged or edited". The UI shows a
     summary first and the details on request.

5. **[FLAW-005] [Low] About 210 month-start changes make the whole backup fail**
   - Where: the header line holds every month-start change (about 75 characters each) and now has the 16 KB
     limit.
   - What happens: past about 210 changes (one a month for about 17 years), export fails with "The settings
     is too long to back up". The wording is also ungrammatical.
   - Direction: cap the stored changes in `MonthSchedule` or merge old ones; fix the wording.

6. **[FLAW-006] [Low] 20 splits per entry is a new product limit**
   - What happens: an entry with more than 20 splits is now invalid everywhere, not only in backups.
   - Why it matters: this is my assumption; you didn't decide it. The split screen will have to show the
     limit.
   - Direction: confirm 20, or pick another number. The line limit then follows from it.

7. **[SIM-007] [Info] Simulation results aren't repeatable**
   - What happens: with the same seed, "accepted with unchanged data" was 108, 130 and 134 in three runs,
     because synthetic records use `EntityId.random()`.
   - Why it matters: you can't compare reports exactly. The v2 report's 115 is within this range, so it's
     not a regression.
   - Direction: generate synthetic ids from the simulation's seeded `Random`.

8. **[Info] The size count is one character off when reading from a file**
   - What happens: a file read line by line has no final empty line, so the reader allows one character more
     than the writer counts. Harmless.

9. **[Info] Things that held up**
   - The largest valid snapshot (every field at its limit, every character escaped) writes and reads back
     within the 16 KB line limit.
   - Records in any order still restore, because references are checked after reading.
   - BOM, CRLF line endings, duplicate keys, future records and cut-off files behave as before (all existing
     tests pass).
   - The shared core compiles for the iOS simulator target (main and tests). iOS tests run only in CI on
     macOS.

## Decisions needed from you
- **#1:** keep option (a), or add "Restore anyway" after a confirmation (b)?
- **#2, #3:** stream restore into the database in batches when `core/data` is built (planned next anyway),
  or lower the size cap now?
- **#6:** is 20 splits per entry right?

## Resolution (2026-11-03)

Your decision: go with the recommendations. Every fix started with a failing test. The follow-up break phase is in
[`recommendations-fixes-2026-11-03.md`](recommendations-fixes-2026-11-03.md).

| # | Status | Fix or reason |
|---|---|---|
| 1 FLAW-001 | ✅ Fixed `a81a2ed` (option b) | `Failure.clockBehind` lets the app offer "Restore anyway"; then every change time after this phone's time is reset to it. See the new DATA-002 |
| 2 PEN-002 | ✅ Fixed `d8a563f` | Size cap 256 MB → 128 MB (about 280,000 real entries). Restoring in batches comes with `core/data` |
| 3 PEN-003 | ➡ Carried to `core/data` | Restore into the database in batches; measure on a real low-end phone |
| 4 FLAW-004 | ✅ Fixed `b8aebb5` | When reading stops early, the first line says "This file looks damaged or edited" |
| 5 FLAW-005 | ✅ Wording fixed `b8aebb5`; limit is accepted risk | Reaching the limit takes about 210 month-start changes (one a month for about 17 years) |
| 6 FLAW-006 | Kept as is | 20 splits per entry: real bills split 3–5 ways, and a higher cap needs the line-limit sums redone |
| 7 SIM-007 | ✅ Fixed `ba1a718` | Simulation ids come from the seeded random; the 2,000-user report now repeats exactly |
