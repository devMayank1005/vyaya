# Findings: core/engine: 2026-11-03

**Scope:**
- `MonthSummary` (what's left), `MonthEnd` (Saved and overspent), `Recurring` (catch-up and the first due
  date of a new rule), `Balances` (lent and borrowed). Commits `3012d3e`, `d010a92`, `ec3f850`, `031125c`.
- The engine simulation: `EngineSimulation`.

**Method:**
- **Break phase:** temporary probes (not committed).
- **Simulation:** `./gradlew :core:simulation:jvmTest -Psim.engine.users=2000`. 2,000 users over 18 months,
  opening the app on random days. Personas: student, salaried, opens once a month, traveller. Chaos:
  - backdated entries and entries in another time zone;
  - generated entries deleted or moved to the next month;
  - rule days changed;
  - two-phone merges through a real backup file;
  - the phone's clock wrong (up to 3 months ahead or 40 days behind) for one open.

**Status:** reported only. **Nothing fixed.**

## Simulation summary

| Check | Result |
|---|---|
| App opens, entries | 274,138 opens, 389,432 entries |
| Chaos events | 27,038 backdated, 3,801 moved, 3,726 deleted, 2,303 day changes, 1,371 two-phone merges, 1,595 wrong clocks |
| Invariant 4: one entry per rule per month | **0 duplicates, 0 missing** |
| Invariants 2 and 3: left = income − spent; Saved − overspent = left, after backdated edits | **0 mismatches** |
| Invariant 6: balance = sum of unsettled entries | **0 mismatches** |
| The user can back up at the end | **60 of 2,000 refused** (all travellers whose clock was once ahead) |
| Entries generated before their month came due (clock ahead) | 245 |
| Catch-up time per open | p95 23 µs; at most 19 entries added in one open (opens once a month) |

The first run showed 12 "duplicates". They were the harness's fault: it appended a second version of an
entry instead of replacing it by id, as the database will. That was fixed in the harness before this run.

## Findings

1. **[SIM-001] [High] After the phone's clock was ahead once, the user can't back up**
   - Where: `Recurring.catchUp` stamps new entries with the phone's `now`. `BackupCodec.write` refuses
     records changed after the export time (SEC-005).
   - How to reproduce: the clock jumps ahead for one open, so catch-up creates entries "changed" in the
     future. Later the clock is right again, and a backup is refused: "changed after the backup was made".
     *(Simulation: 60 of 2,000 users, about 3 %, all travellers.)*
   - Why it matters: no backup is possible until real time passes the wrong clock. That can be months, and
     the user can't see why. Manual entries made while the clock was ahead do the same.
   - Direction (not a fix): when the app sees its clock go back (now is before the newest change time it
     stored), move those change times back to now, as Restore anyway does. Or have the writer move them
     when exporting. The first keeps the database honest.

2. **[SIM-002] [Medium] A clock ahead generates months that haven't come yet**
   - How to reproduce: one open with the clock 3 months ahead creates the next 3 months' rent, dated in
     the future. *(Simulation: 245 entries.)*
   - Why it matters: they count as paid in "what's left" when their month arrives, even if the rule changed
     or was deleted in between, and they appear in the list long before they're due.
   - Direction: when the clock goes back, remove generated entries that are for future months and were
     never edited (`createdAt == updatedAt`). Catch-up recreates them, with the same id, when they come due.

3. **[FLAW-003] [Medium] Resuming a paused rule fills in every paused month**
   - How to reproduce: pause a gym membership in February, resume it in July. Catch-up adds February to
     July, 6 entries. *(Probe confirmed.)*
   - Why it matters: pausing usually means "I'm not paying for these months". Nothing records when the
     pause started, so the engine can't tell.
   - Direction: a decision for you. Either (a) resume starts at the next due date, which needs a "resumed
     on" date on the rule, a backup format change before release; or (b) on resume, ask "Add the 5 missed
     months?"

4. **[DATA-004] [Low] Huge amounts crash the month summary**
   - How to reproduce: two expenses of half of `Long.MAX_VALUE` paise in one month → `ArithmeticException:
     Money overflow`. *(Probe confirmed.)*
   - Why it matters: only possible through a crafted backup, because amounts above ₹100 crore are still
     accepted (DATA-004 in the backup report, not fixed by your choice). A crash on the home screen is the
     worst place for it.
   - Direction: cap amounts in the model (for example ₹100 crore per entry, which keeps every sum far from
     overflow), or have the engine report "too large" instead of crashing.

5. **[FLAW-005] [Info] Is the SAVED entry type still needed?**
   - What happens: Saved is calculated now, but `EntryType.SAVED` still exists and is left out of every
     total.
   - Direction: keep it for "money set aside on purpose" (a manual savings transfer), or remove it before
     release. Your call.

6. **[Info] Assumptions made (please confirm)**
   - Saved is never negative; an overspent month shows the overspend instead.
   - Generated entries are dated at noon local time on the due day, with the rule's note, category and
     account.
   - Totals count an entry in the month of its local date in its own time zone. An entry made in Dubai at
     00:30 local, which is 02:00 in Kolkata, counts on the Dubai date.

7. **[Info] Things that held up**
   - All four invariants over 274,000 opens with chaos, and as property tests (200 random cases each).
   - A due day of 31 clamps to 28, 29 or 30. Moving a rule's day, paying late, deleting a generated entry, and
     re-creating a rule in the same month never double or lose a month.
   - Catch-up is cheap: 23 µs at p95, even after a month away.

## Decisions needed from you
- **#1, #2:** fix the clock-went-back case as suggested (move change times back; remove unedited future
  entries)?
- **#3:** paused rule on resume: (a) start at the next due date, or (b) ask about the missed months?
- **#4:** cap amounts in the model, or only stop the crash?
- **#5:** keep or remove the SAVED type?
