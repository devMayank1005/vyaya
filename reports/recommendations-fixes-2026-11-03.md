# Findings: fixes from the agreed recommendations: 2026-11-03

**Scope:** `a81a2ed` (Restore anyway), `fee7bb5` (generated entry dated in its month ±1), `d8a563f` (128 MB
cap), `25b4211` (key before rule start, rule type), `b8aebb5` (messages), `ba1a718` (repeatable simulation).

**Method:**
- **Break phase:** temporary probes (not committed): Restore anyway together with Merge, and snapshots the
  app's own model allows but the new checks refuse.
- **Simulation:** `./gradlew :core:simulation:jvmTest -Psim.backup.users=2000 -Psim.backup.big=true`, run
  twice.

**Status:** reported only. **Nothing fixed.**

## Simulation summary

| Check | Result |
|---|---|
| Round trips identical (2,000 users) | 2,000 |
| Two-phone merges, with days moved on phone B | 2,000 merges, **0 duplicated, 0 lost** |
| Corrupted files accepted with changed data (of 20,000) | **0** (129 accepted unchanged, the same on both runs), 0 crashes |
| 200k entries | 96 MB file (under the 128 MB cap), write 2.9 s, read 4.5 s, fidelity ok |

## Findings

1. **[DATA-001] [High] The app can write a backup it then refuses to restore**
   - Where: `BackupLines.write` checks line length, record count and total size only. The new cross-record
     checks (a generated entry's type, its month against the rule's start) and the older ones (dangling
     references, duplicate ids) run only when reading.
   - How to reproduce: change a generated entry's type to INCOME, or move its rule's start after the
     entry's month. Write: `Success`. Read: refused. *(Probe confirmed both.)*
   - Why it matters: the user's only backup could be unrestorable. The writer was meant to refuse anything
     the reader refuses (DATA-001 in the v2 report). Nothing in the app makes these edits yet, but the model
     allows them.
   - Direction (not a fix): run the reader's cross-record checks before writing, and refuse with the same
     message. Also make the model and engine forbid the edits: a generated entry's type can't change, and a
     rule's start can't move past months it already generated.

2. **[DATA-002] [Medium] "Restore anyway" with Merge can overwrite edits made after the real export**
   - Where: `Reader.finish`, which resets future change times to *now*.
   - How to reproduce: phone A's clock is 3 days ahead when it exports (real time 24 Oct 12:00). Phone B
     edits Rahul at 13:00, then restores anyway with Merge at 14:00. The backup's Rahul now looks changed at
     14:00, so it wins, and B's newer edit is lost. *(Probe confirmed.)*
   - Why it matters: resetting to *now* makes every restored record the newest one.
   - Direction: move all the backup's change times back by the clock difference (export time minus now),
     which keeps their real order against local edits. Or allow only Replace when restoring anyway.

3. **[FLAW-003] [Low] Heavy note-takers reach the 128 MB cap at about 119,000 entries**
   - How to reproduce: an entry line with a 500-character note is 1,129 characters, so about 118,900 fit.
     *(Measured.)*
   - What happens: past that, export fails entirely, with no partial or alternative backup.
   - Why it matters: rare (most entries have short notes or none), but whoever hits it can't back up at all.
   - Direction: warn in the app at about 80 % of the cap. Batching in `core/data` is the real fix, and then
     the cap can be raised.

4. **[Info] Things that held up**
   - Restore anyway is offered only when the clock is the only problem. Damage, and records newer than the
     backup itself (SEC-005), are still refused. It works for v1, v2 and v3 files.
   - Generated entries dated in the adjacent month restore, including the Kolkata midnight boundary; two
     months away is refused.
   - The 2,000-user simulation repeats exactly (129 accepted unchanged on both runs).
   - The early-stop message leads with "damaged or edited", and the settings message reads correctly.

## Decisions needed from you
- **#1:** check before writing, plus model and engine rules? Recommended; it's small for the writer.
- **#2:** shift change times back by the clock difference (recommended), or allow only Replace?
- **#3:** add the 80 % warning when the app's backup screen is built?

## Resolution (2026-11-03)

Your decision: go with the plan.

| # | Status | Fix |
|---|---|---|
| 1 DATA-001 | ✅ Fixed `9149c90` | `Consistency` holds the checks across records and against the export time; reader and writer share it, so the writer refuses, in the reader's words, whatever a restore would refuse. A property test over 200 random snapshots checks: written exactly when restorable. The engine forbids the two edits (plan.md) |
| 2 DATA-002 | ✅ Fixed `347e225` | Restore anyway moves change times back by the clock difference (never before 2000) instead of resetting them to now |
| 3 FLAW-003 | ➡ Backup screen (plan.md) | Warn at about 80 % of the 128 MB cap |

## Follow-up break phase (2026-11-03): items 1 and 2

1. **[DATA-004] [Low] Restore anyway keeps the order only approximately**
   - Where: `Reader.finish`; the shift assumes the backup was made at the moment of restore.
   - How to reproduce (by analysis): the shifted times are later than the real ones by the gap between the
     real export and the restore. An edit on this phone made in that gap, but before the backup's own change,
     still loses a merge.
   - Why it matters: rare. It needs a wrong clock, a restore long after the export, and edits to the same
     record in between.
   - Direction: accept as risk. Replace mode avoids it entirely.

2. **[FLAW-005] [Low] The writer's refusal is a long technical message**
   - What happens: up to 20 problems joined with ";", with record ids.
   - Direction: the backup screen shows a summary ("Some records can't be backed up") with details on request.

3. **[Info] Things that held up**
   - Writer and reader agree for 200 random snapshots and all 10 hand-made refused cases.
   - The writer's checks cost nothing measurable: 200k entries write in 2.6 s, as before.
   - A backup restored anyway from a clock decades ahead can be backed up again.
   - 206 JVM tests and the coverage gate pass, and the iOS target compiles. The 2,000-user simulation gave
     the same counts as the previous run, with 0 changed and 0 crashes.
