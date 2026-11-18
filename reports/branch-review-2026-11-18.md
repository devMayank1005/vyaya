# Findings: whole-branch review of `android-mvp-2`: 2026-11-18

**Scope:** `android-mvp-2`: 148 commits on top of PR #5 (`ios-app`, open since 7 Nov, CI green there). It covers 7–18 Nov:
- categories and accounts;
- CSV export;
- merchant memory;
- budgets;
- safe to spend;
- the Ignored tab;
- split bills;
- every fix round on those.

**Method:**
- Read-only checks: CI history, the Room schema history, migrations, coverage configuration, every report's open rows,
  and the code behind the rows.
- A second reviewer pass.

**Status:** reported only. **Nothing fixed.**

**Verdict: not done yet.** The Android and JVM work is solid:
- every feature was test-first;
- every feature has a break-phase report and a simulation;
- the full local gate is green;
- commits are authored as `mayank` with no Claude trailer.

But #1–#3 block calling any of it done.

## Blocking

1. **[SEC-001] [High] CI and iOS have never run on these 148 commits**
   - Where: `.github/workflows/ci.yml` triggers only on pushes to `main` and on pull requests; `android-mvp-2` has
     neither. `gh run list --branch android-mvp-2` is empty.
   - Why it matters:
     - CLAUDE.md rule 2 says to run Android and iOS before calling anything done.
     - Rule 8.6 says shared core must compile for iOS.
     - The local `compileCommonMainKotlinMetadata` checks neither: local Xcode is broken, and iOS last ran on PR #5's
       CI (7 Nov).
   - Direction: compile the iOS targets locally where the toolchain allows. Open a draft PR (base `ios-app`) so CI runs
     the JVM, iOS and Android jobs without merging anything.

2. **[DATA-002] [High] Updating the installed app crashes it, and losing it loses the data**
   - Where: the Room schema changed in 5 commits since 7 Nov (`04232b6`, `5432b72`, `d90fb83`, `e618e71`, `6c632c6`),
     all at `version = 1` with no migrations:
     - new entry columns;
     - the composite index;
     - the `prefs` and `ignored` tables;
     - `ignored.credit`.
   - What happens: a phone with the 7-Nov build can't open its database after an update (schema mismatch at the same
     version). `allowBackup=false`, right for privacy, means uninstalling deletes the data.
   - Why it matters: every "install it on your phone" since then would have hit this.
   - Direction: before installing a new build, export a backup in the current app, then uninstall, install and restore.
     From the first beta, every schema change needs a version bump, a migration and a migration test.

3. **[FLAW-003] [High] An approved decision shipped differently, and wasn't flagged**
   - Where: safe-to-spend #1 was approved as "link when the amount is equal *and the payee matches*; otherwise ask in
     the Detected inbox".
   - What happens: rules have no payee field. `Recurring.linkPaid` / `paymentFor` link on type, amount and ±5 days
     only, and never ask.
   - Why it matters: round amounts get swallowed. A ₹60,000 credit near salary day becomes "Salary"; a ₹5,000 purchase
     near a SIP date takes the SIP's category and note. It should have come back for a decision when the rule couldn't
     be built as approved.
   - Direction (options):
     - link only when the payment has no category or already has the rule's;
     - otherwise ask in the Detected inbox "Is this your Rent?";
     - or add a payee to rules.

## Not blocking, but real

4. **[FLAW-004] [Medium] The coverage gate measures the core only**
   - Where: Kover in `build.gradle.kts` lists the 7 `core/*` modules. `:shared` (the UI and its logic) and
     `androidApp` aren't measured.
   - Why it matters: CLAUDE.md requires ≥ 70 % for platform adapters.
   - Direction: Kover on `:shared` and `androidApp` with that rule.

5. **[FLAW-005] [Medium] The reports have drifted from the code**
   - What: 29 reports, about 21 rows still marked ⏳.
   - Done in code but still marked open (core-data report):
     - SEC-007: Auto Backup off, `ManifestTest.sec_007_*`;
     - FLAW-004: the app opens by queries, `AppOpen.kt`;
     - FLAW-005: restore from a local copy, `BackupFiles.kt`;
     - FLAW-008: read errors are refusals, `BackupFiles.kt`.
   - Genuinely waiting on you:
     - split-bills #1–#5 (two High);
     - wallet cashback (DATA-006);
     - SIM-006 (same-amount payments minutes apart merge);
     - PEN-009;
     - which settings a merge keeps;
     - encryption at rest (SEC-008);
     - the real-sample items (the trusted-bank list, DATA-002 / FLAW-003 watch, SIM-020).

6. **[SIM-006] [Medium] Some "0 differ" simulations check the plumbing, not the rule**
   - Where:
     - the database-open simulation runs `Recurring.linkPaid` on both sides;
     - the safe-to-spend reference reuses `Recurring.paymentFor`;
     - the budget reference reuses `Budgets.suggest`.
   - Why it matters: those runs prove the database path matches the engine. They don't prove the engine's rule is
     right; the unit and property tests carry that.
   - Also: the explanation for 130 of 1,314 early payments not linked ("rules the simulation moved or paused") was
     asserted, not checked.

7. **[PEN-007] [Medium] A few screen actions can crash the app**
   - Where: `VyayaRoot`'s `scope.launch` blocks have no error handling.
   - What happens: `MonthSchedule.withStartDay`'s `require` (a phone clock set back past a scheduled change), or a
     damaged row, throws inside them and crashes the app.
   - Direction: catch and show a message.

8. **[DATA-008] [Low] "Already logged" ignores money in or out**
   - Where: `IgnoredStore.alreadyLogged` matches on amount and day only.
   - What happens: an income of the same amount counts as "already logged" for a skipped payment out.

9. **[FLAW-009] [Medium] Product risks**
   - Parse accuracy on real messages is still unmeasured; the Phase 2 exit gate (≥ 95 %) isn't met. Everything rests on
     a synthetic corpus.
   - The UI has had only Robolectric tests on a small screen since 7 Nov. There, the Home summary filled the view:
     safe to spend, budgets and the salary hint push the entries below the fold.
   - Interaction bugs are the pattern this month: early payment × recurring (safe-to-spend DATA-001), repayment ×
     capture (split-bills DATA-002). A cross-feature money-correctness simulation (capture, recurring, splits, ledger
     and budgets together) would catch them earlier.

10. **[Info] Housekeeping**
    - PR #5 has been open 11 days, with this branch stacked on it.
    - The stray-edit stash `stash@{0}`.
    - The GitGuardian incidents.
    - Local Xcode.
    - `VyayaRoot.kt` (493 long lines holding all screen state) is a refactor candidate, not urgent.

## Decisions needed from you
- **#1:** open a draft PR (base `ios-app`) so CI runs on this branch?
- **#2:** from the first beta, version bumps and migrations for every schema change (recommended); until then, backup
  → uninstall → install → restore?
- **#3:** the early-payment link: no category or the rule's own category only, otherwise ask in the inbox
  (recommended), or add a payee to rules?
- **#4, #7, #8:** Kover on `:shared` and `androidApp`, error handling on screen actions, and money in/out in "already
  logged" (recommended)?
