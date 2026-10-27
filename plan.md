# Vyaya: Project Plan

An expense tracker where everything stays on your phone. Log money in, log money out, and the app tells you
what's left. Payments are detected automatically wherever the platform allows it.

Targets: **Android first, iOS next, from one shared codebase.**

---

## Part A: How we work (default for this project)

These rules apply to **every feature, every time**. `CLAUDE.md` holds the same rules so the assistant
follows them automatically.

### A1. Test-Driven Development (TDD)
1. **Red:** write a failing test that describes the behaviour before any production code exists.
2. **Green:** write the minimum code that makes it pass.
3. **Refactor:** clean up with all tests green.
4. No production code is merged without the test that demanded it.
5. Every bug gets a failing test **first** that reproduces it, then the fix.

### A2. Unit testing
- Covers every pure function in shared core: parser, filters, de-dup, recurring, Saved, budgets, balances,
  backup serialisation, money maths, date and month logic.
- Fast (whole suite < 30 s), no I/O, no device.
- Parser uses **golden-file tests**: a corpus of real, anonymised SMS and notification samples, each with its
  expected parsed result.
- **Property-based tests** (Kotest) for invariants. Examples: "sum of splits = total", "running recurring
  catch-up twice = once", "export → import → export is identical".
- Coverage gate: **shared core ≥ 90 % lines and branches**; platform adapters ≥ 70 %.

### A3. Integration testing
- **Database integration:** repositories against an in-memory Room DB (Android and iOS targets).
- **Pipeline integration:** RawMessage → parser → filters → de-dup → inbox → DB, end to end.
- **Engine integration:** recurring + Saved + backdated edits + restore working together over simulated months.
- **Backup round-trip:** Android-made backup restored on the iOS target and vice versa, merge and replace.
- **Platform adapters:**
  - Android: SMS receiver, notification listener, share intent (Robolectric + instrumented tests).
  - iOS: Share Extension, App Intents (XCTest).
- **UI flows:** Compose UI tests for the critical journeys (add, confirm inbox, restore, settle-up).

### A4. Break phase (after a feature is complete)
Once a feature is green, the assistant switches role to **security agent, penetration tester and chaos
tester**, and tries to break it.

- **It does not fix anything** in this phase.
- It reports findings as **numbered points** (format below) so we can discuss them one by one.
- Fixes start only after we've discussed and agreed. Each agreed fix starts with a failing test (TDD).

### A5. Scale simulation (thousands of users)
Vyaya has no server, so "thousands of users" means **thousands of different phones, people and data sets**.
A simulation harness in shared Kotlin generates synthetic users and replays their lives through the real
pipeline and engine (see Part E). The report lists **what broke, and what is flawed even when it didn't
crash**, as numbered points.

### A6. Report format (Break and Simulation phases)
```
## Findings — <feature> — <date>

1. [SEC-001] [High] Fake bank SMS can create an income entry
   - Where: core-parser / sender check
   - How to reproduce: send "Rs 25,000 credited to A/c XX1234" from header "AD-HDFCBK"
   - What happens: entry is logged as income, "left" goes up by ₹25,000
   - Why it matters: user overspends trusting a fake balance
   - Direction (not a fix): ...
2. [SIM-002] [Medium] ...
```
- ID prefixes: `SEC` security, `PEN` penetration/abuse, `SIM` simulation/scale, `FLAW` design or UX flaw
  that isn't a bug, `DATA` data correctness.
- Severity: **Critical / High / Medium / Low / Info.**
- Reports are saved in `reports/<feature>-<date>.md`.

### A7. Definition of Done (per feature)
- [ ] Tests written first; unit and integration tests green on **Android and iOS** targets
- [ ] Coverage gates met
- [ ] Break phase report produced
- [ ] Scale simulation report produced (for features touching data, parsing, engine or backup)
- [ ] Findings discussed; agreed fixes done test-first; others recorded as accepted risk
- [ ] Backup schema unchanged, or bumped with a migration and a test

---

## Part B: Product

### B1. Core (your original scope)
- Money in / money out / what's left.
- Auto-detect payments from:
  - UPI app notifications: PhonePe, GPay, Paytm, BHIM, CRED, Amazon Pay, Navi, MobiKwik and Freecharge.
  - Bank SMS, with a 30-day import.
- De-duplication by UPI ref, time and payee, also against manual and recurring entries.
- Ignores noise (OTP, promos, failed or declined, autopay reminders, money requests) and scams (fake
  credits, personal numbers, lottery, KYC).
- Works while the app is closed: messages are queued, with a "New payment detected" notification.
- Screenshot sharing (PhonePe first, then all apps).
- Recurring entries with catch-up; month-end Saved that self-adjusts; overspend and category-limit alerts.
- Lent and Borrowed, with per-person balances and settle-up.
- JSON backup and restore, CSV export, and an automatic OS safety copy.
- Privacy: no account, no server, no sync.

### B2. Additions agreed in brainstorm
- **Capture:**
  - Review inbox (swipe to confirm), merchant memory, quick-add (widget, QS tile, shortcuts).
  - Amount-first keypad with a calculator, split across categories, undo, receipt photo, voice add.
- **Correctness:**
  - Self-transfers.
  - Credit-card swipe vs bill payment.
  - Cash wallet (ATM).
  - Refunds linked to their originals.
  - Salary detection.
  - Auto-created accounts.
  - Custom month start.
  - Overspent months.
  - Saved as its own type, excluded from spending.
- **Understand:**
  - "Left" plus safe-to-spend per day, and upcoming bills.
  - Subscription finder.
  - Budgets with suggestions.
  - Tags and events, search, savings goals, recaps.
- **Friends:**
  - Split bill.
  - WhatsApp reminder with a UPI link.
  - Auto-settle on incoming credit, partial settlements.
- **Trust:**
  - "Why was this logged?", Ignored tab, teach-the-parser.
  - Optional permissions, app lock, privacy mode.
  - Encrypted scheduled backup, wipe all.
  - Lakh formatting and regional languages.

---

## Part C: Architecture (Android + iOS)

| Layer | Choice |
|---|---|
| Language | Kotlin Multiplatform |
| UI | Compose Multiplatform (shared), Swift only where Apple requires it (widgets, Share Extension, App Intents) |
| DB | Room (KMP) |
| JSON | kotlinx.serialization |
| Dates | kotlinx-datetime |
| DI | Koin |
| Tests | kotlin.test, Kotest (property), Turbine, Robolectric, Compose UI test, XCTest |

```
SHARED: Parser → Filters → De-dup → Review inbox → DB
        Engine (recurring, Saved, budgets, balances, lent/borrowed)
        Backup/CSV · Settings · Compose UI
            ▲ RawMessage(text, sender, time, source)
ANDROID: SMS receiver · Notification listener · Share intent · ML Kit OCR
iOS:     Shortcuts action · Share Extension · App Intents (Siri) · Vision OCR
```

### Design rules
1. Money is stored as `Long` paise.
2. IDs are UUIDs.
3. Every record has `updatedAt` and a soft-delete flag.
4. Times are stored in UTC plus the user's timezone; one shared month function, with a custom month start.
5. System categories have fixed IDs (`sys.saved`, `sys.transfer`, `sys.lent`).
6. Generated entries are idempotent (`ruleId+date`, `sys.saved+month`).
7. Derived numbers are calculated, never stored.
8. Capability flags drive the UI per platform.
9. No platform code in the shared core; CI compiles the shared core for iOS from day 1.
10. Parser rules are versioned data files.

### Platform differences
| Feature | Android | iOS |
|---|---|---|
| Notifications from UPI apps | ✅ | ❌ |
| Live bank SMS | ✅ (Play policy permitting) | 🟡 Shortcuts automation |
| 30-day import | ✅ | ❌ |
| Screenshot + OCR | ✅ | ✅ |
| Background jobs | WorkManager | Catch-up on open (primary) |
| Widget / quick add | Glance, QS tile | WidgetKit, Control Center, Siri |

---

## Part D: Backup format (identical on both platforms)

**Schema v2 (current).** One compact JSON object per line:
```
{"t":"header","format":"vyaya-backup","schemaVersion":2,"appVersion":"1.0.0","createdOn":"android","exportedAt":"…","currency":"INR","settings":{…},"counts":{…}}
{"t":"account",…}  {"t":"category",…}  {"t":"entry",…}  {"t":"recurring",…}  {"t":"person",…}  {"t":"ledger",…}  {"t":"budget",…}  {"t":"merchantRule",…}
{"t":"end","records":N,"sha256":"<SHA-256 of every line above, each followed by \n>"}
```
- Written and read line by line, so there's no huge string in memory. Records are ordered by type, then by
  id, so the same data always gives the same bytes.
- **Shared limits:** the writer refuses anything the reader would refuse (64 KB per line, 2 million
  records), so the app can always restore its own backups.
- **Integrity:**
  - The checksum detects damage, edits and cut-off files.
  - Duplicate keys are refused.
  - Records newer than the backup, or a backup from the future, are refused.
  - A leading BOM and Windows line endings are accepted.
- Restore offers Merge (by UUID, newer `updatedAt` wins) or Replace.
- Every problem is reported. Restore never crashes, and nothing is restored from a bad file.
- **v1** files (one pretty-printed JSON document) still restore. The app only writes v2.
- **Planned before release:**
  - optional password encryption (`.vyaya`, AES-GCM, platform crypto), which adds authenticity;
  - a `.zip` variant with receipts.
- CSV columns are the same on both platforms (export only).

## Part E: Scale simulation harness

**Personas** (mixed into thousands of synthetic users):

| Persona | What it stresses |
|---|---|
| Student with pocket money, few transactions | Baseline correctness |
| Salaried, 2 banks + 1 credit card | Transfers, card bill double counting, salary detection |
| Shopkeeper or freelancer, 100–300 transactions/day | Volume, de-dup under load, inbox usability |
| Heavy UPI user: PhonePe **and** GPay **and** bank SMS for the same payment | Triple de-dup |
| Dual SIM, Hindi or regional-language SMS | Parser coverage |
| Traveller / NRI: timezone changes, phone clock wrong | Month boundaries, Saved, recurring |
| "Opens app once a month" | Catch-up, queue size, Saved recomputation |
| 10 years of history, 200k entries | DB performance, backup size, restore time |
| Low-end phone (2 GB RAM, slow storage) | Startup time, OCR memory, jank |
| Scam target: 30 % of messages are fake or promo | Filter precision and recall |
| Switcher: Android → iPhone → Android | Backup round-trip fidelity |

**Event chaos injected:**
- Duplicate, out-of-order and delayed messages.
- App killed mid-write; low storage; reboot.
- Bank format changes; backdated edits.
- Restore in the middle of a month; same-second payments to the same payee.
- Refund before the original arrives.

**Invariants checked for every simulated user:**
1. No payment logged twice; no real payment missed (vs. ground truth).
2. "What's left" = income − expenses (excluding transfers, Saved, lent/borrowed) for every month.
3. Saved for every past month equals that month's leftover after any edit.
4. Recurring catch-up produces exactly one entry per due date.
5. Export → import → export is identical; Android ↔ iOS identical.
6. Per-person lent/borrowed balances equal the sum of unsettled entries.
7. No scam message ever becomes an entry; false-positive rate on real messages is under the target.

**Metrics reported:**
- Parse accuracy per bank or app.
- De-dup precision and recall.
- Filter false positives and negatives.
- p50/p95 time per message.
- Cold start time; DB size; backup and restore time at 10k, 100k and 200k entries.
- Memory peak during OCR.

---

## Part F: Roadmap

| Phase | Rough time | Scope | Exit gate |
|---|---|---|---|
| 0. Foundation | 2–3 wks | KMP project, shared modules, test tooling + CI (Android + iOS targets), backup schema v1 spec + tests, parser corpus (200+ samples), simulation harness skeleton, Play SMS policy check | CI green on both targets |
| 1. Android MVP | 4–6 wks | Manual add, accounts, categories, home "left", recurring, Saved, lent/borrowed, backup/restore, CSV, app lock | DoD per feature (incl. Break reports) |
| 2. Android auto-detect | 4–6 wks | Notification listener, SMS + import, screenshot OCR, inbox, de-dup, filters, merchant memory, transfers and cards | Simulation: ≥ 95 % parse accuracy, 0 double logs, 0 scam entries |
| 3. Android comfort + launch | 3–4 wks | Widget, QS tile, budgets, safe-to-spend, split bills, Ignored tab, teach-the-parser | Beta, then Play Store |
| 4. iOS | 4–6 wks | iOS adapters (Share Extension, Vision, App Intents + Shortcuts, Face ID, WidgetKit), cross-platform backup tests | TestFlight, then App Store |
| 5. Both | ongoing | Subscriptions, goals, tags, recaps, encrypted scheduled backups, languages | Ship both together |

## Part G: Known risks to verify early
1. Google Play SMS permission policy may not allow live SMS reading.
2. iOS cannot read notifications or SMS directly; it relies on a Shortcuts setup by the user.
3. iOS background execution is not guaranteed; everything must catch up safely.
4. Bank SMS formats change without notice; the parser must be data-driven and quickly updatable.
5. Building for iOS needs a Mac and an Apple Developer account ($99/yr).
