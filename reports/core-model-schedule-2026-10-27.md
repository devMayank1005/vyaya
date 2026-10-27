# Findings: core/model MonthSchedule and CapturedAt: 2026-10-27

**Scope:** `MonthSchedule` (`aec9d43`) and `CapturedAt` (`7da18e1`). Both were built as fixes for
FLAW-003 and FLAW-004 and had not had their own break phase until now, so this closes that protocol gap.

**Method:**
- Crafted inputs.
- An exhaustive sweep of every old/new start day across every month of 2026.
- A timing run over 2,000 changes.
- Probes were temporary and are not committed.
- A persona simulation does not apply here: no user data flows through these types yet. The existing
  property test already covers random change sequences.

**Status:** reported only. **Nothing fixed.**

## Findings

1. **[FLAW-001] [High] A backdated change silently rewrites months that have already happened**
   - Where: `MonthSchedule.withStartDay`
   - How to reproduce:
     - Change 1 → 25 on 10 Mar 2026; it takes effect on 1 Apr.
     - Later, with the phone's clock wrongly set to 5 Feb, change to 10.
     - The April change is **deleted**, and May's month moves from 25 Apr–25 May to 10 May–10 Jun.
   - Why it matters: this breaks the FLAW-003 promise that past months never move. It's triggered by a
     wrong phone clock, a restore, or any caller passing an older date.
   - Direction (not a fix): reject or clamp a `changedOn` earlier than the latest change already in
     effect.

2. **[FLAW-002] [Medium] `copy()` gets around the private constructor**
   - How to reproduce: `MonthSchedule.of(1).copy(initialStartDay = 99)` compiles. Kotlin warns, and it
     becomes an error only in language version 2.5.
   - Why it matters: any caller can build a schedule that breaks its own rules.
   - Direction: turn on `@ConsistentCopyVisibility`, or make it a regular class.

3. **[PEN-003] [Low] Month lookup slows down as changes pile up**
   - How to reproduce: with 1,999 changes, 100,000 lookups take 2.37 s (about 24 µs each).
   - Why it matters: normal use has a handful of changes, but a tampered backup with 100k changes would
     slow every month calculation.
   - Direction: cap the number of changes when restoring a backup, or use binary search.
     **→ carry to core/backup**

4. **[DATA-004] [Low] Equivalent zone names don't compare as equal**
   - How to reproduce: `CapturedAt(i, Asia/Kolkata) != CapturedAt(i, Asia/Calcutta)`, and
     `UTC != Etc/UTC`. Both pairs are the same instant and give the same local date.
   - Why it matters: backup merge or de-duplication that compares `CapturedAt` would treat equal moments
     as different.
   - Direction: compare by instant plus local date, or canonicalise zone names.
     **→ carry to core/backup and core/dedup**

5. **[PEN-005] [Low] A far-future change date throws**
   - How to reproduce: `withStartDay(15, LocalDate(999_999_999, 12, 20))` →
     `DateTimeArithmeticException`. This is the same family as PEN-009.
   - Direction: limit dates to a valid range at the data boundary. **→ carry to core/backup**

6. **[FLAW-006] [Info] Transition months can be 37 days long**
   - What happens: across every old/new start-day pair the longest is 37 days (changing 1 → 7 in Feb
     gives 1 Mar–7 Apr). With `MIN_TRANSITION_DAYS = 7`, the range is 7–37 days.
   - Why it matters: safe-to-spend per day and budget comparisons must handle months of 28–37 days.

7. **[FLAW-007] [Info] "From next month" can mean tomorrow**
   - What happens: a change made on 31 Mar takes effect on 1 Apr.
   - Why it matters: this is correct, but the UI should tell the user the exact date a change applies.

8. **[Info] DST zones are fine**
   - An instant during New York's fall-back hour gets the right local date.

## Decisions needed from you
- **#1:** should a change dated before the latest change already in effect be rejected, or applied
  from the next upcoming month?
- **#2:** fix now? It's a small change.
