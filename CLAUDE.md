# CLAUDE.md: Vyaya working rules

Vyaya is an on-device expense tracker (Kotlin Multiplatform + Compose Multiplatform; Android first, iOS
next). The full plan is in `plan.md`. These rules are the default for all work in this repository.

## 1. Test-Driven Development is mandatory
- Write the failing test first, then the minimum code to pass, then refactor. Never write production code
  without a test that requires it.
- For a bug, first add a failing test that reproduces it, then fix.
- Show the red → green steps in your summary: which tests were added, and that they failed before and pass after.

## 2. Every feature has unit AND integration tests
- **Unit tests:** pure shared-core logic (parser, filters, de-dup, engine, backup, money and date maths).
  Use golden-file tests for the parser corpus and Kotest property tests for invariants.
- **Integration tests:**
  - Repositories on an in-memory Room DB.
  - The full pipeline (RawMessage → DB).
  - Engine over simulated months.
  - Backup round-trip across Android and iOS targets.
  - Platform adapters (Robolectric/instrumented on Android, XCTest on iOS).
  - Compose UI tests for critical flows.
- Coverage gate: shared core ≥ 90 %, platform adapters ≥ 70 %.
- Run the tests for **both** Android and iOS targets before calling anything done. Report actual results;
  never claim tests pass without running them.

## 3. After a feature is complete: Break phase (do NOT fix)
When a feature's tests are green, switch role to **security agent + penetration tester + chaos tester**
and actively try to break the feature.
- Attack surface to probe every time (as relevant):
  - Spoofed or crafted SMS and notifications (wrong sender, other apps posing as PhonePe, lookalike
    headers).
  - Parser abuse: huge or unicode or RTL text, ReDoS-prone regexes, amounts like `1e9`, negative, `₹0`.
  - Malicious backup files: oversized, deeply nested, wrong types, duplicate IDs, zip-slip in receipt zips,
    future `schemaVersion`.
  - CSV/formula injection in exported fields (`=HYPERLINK(...)`).
  - Exported Android components, intents and deep links; iOS App Intents and Shortcuts invoked by other
    shortcuts.
  - Data at rest, Auto Backup and iCloud backup contents, logs leaking SMS text.
  - App-lock bypass via widgets, notifications, app switcher, back stack.
  - Clock and timezone tampering, process death mid-write, low storage.
  - Dependency vulnerabilities and debuggable or unobfuscated builds.
- **Do not change any code in this phase.** Only report.
- Report as **numbered points** using the format below, then stop and wait for discussion.

## 4. Scale simulation (thousands of users)
For features that touch parsing, de-dup, the engine, the DB or backup, run the simulation harness (see
`plan.md` Part E):
- Thousands of synthetic users across the personas, with chaos events injected.
- Check every invariant; report metrics.

List **what broke** and also **what is flawed even when it didn't break**, such as UX friction at volume,
slow paths, or confusing results. Numbered points only. Do not fix.

## 5. Report format
```
## Findings — <feature> — <date>
1. [SEC-001] [High] <one-line title>
   - Where:
   - How to reproduce:
   - What happens:
   - Why it matters:
   - Direction (not a fix):
```
- Prefixes: `SEC` security, `PEN` penetration/abuse, `SIM` simulation/scale, `DATA` data correctness,
  `FLAW` design or UX flaw.
- Severity: Critical, High, Medium, Low or Info. Sort by severity.
- Save the report to `reports/<feature>-<YYYY-MM-DD>.md` and summarise it in chat as the same numbered points.

## 6. Fixing findings
- Only fix findings the user has agreed to, by number.
- Each fix starts with a failing test reproducing the finding (TDD), and references the finding ID in the
  commit message.
- Findings the user declines are recorded in the report as "Accepted risk" with the reason.

## 7. Communication
- Answer in short numbered points so each one can be discussed individually.
- Ask before making product decisions; state assumptions explicitly.

## 8. Architecture rules (never break)
1. Money is `Long` paise; never Float or Double.
2. UUID IDs, `updatedAt`, soft delete.
3. Times are UTC plus the user's timezone; use the single shared month function.
4. Generated entries are idempotent (`ruleId+date`, `sys.saved+month`).
5. Derived values are computed, never stored.
6. No Android or iOS APIs in shared core; it must compile for the iOS target.
7. Backup format changes require a `schemaVersion` bump, a migration and a round-trip test.
8. Nothing leaves the device: no network calls, analytics or crash reporters without explicit approval.
