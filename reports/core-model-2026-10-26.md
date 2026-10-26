# Findings: core/model (Money, BudgetMonth): 2026-10-26

**Scope:** `Money` (parse, format, arithmetic) and `BudgetMonth` (custom start day, time zone), at commit `f1aaa18`.

**Method:**
- Break phase: crafted, unicode, oversized and edge-value inputs, run on the JVM.
- Mini scale simulation: 10,000 synthetic users × 30 amounts each = **300,000 amount strings** in 10
  formats used by Indian banks and UPI apps.
- Probe code was temporary and is not committed.

**Not covered:**
- The iOS target. No Mac is available in the cloud session, so CI's macOS job will be the first iOS run.
- Integration tests. This feature has no I/O; they start with `core/data` and the pipeline.

**Status:** reported on 2026-10-26, then discussed. The agreed fixes are listed under *Resolution* at the end.

## Simulation result (Money.parse)

| Format | Samples | Lost |
|---|---|---|
| `Rs.120` | 30,183 | 0 % |
| `Rs 120.00` | 30,169 | 0 % |
| `INR 120` | 29,938 | 0 % |
| `₹1,20,000` | 29,867 | 0 % |
| `₹ 120` | 29,856 | 0 % |
| `Rs.120/-` | 30,140 | **100 %** |
| `₹`+NBSP+`120` | 29,984 | **100 %** |
| `INR. 120` | 29,739 | **100 %** |
| `Rs.120.` (end of sentence) | 30,108 | **100 %** |
| `Rs:120` | 30,016 | **100 %** |

The overall figure is 50 % lost, but it is an artefact of weighting every format equally. The per-format rows are what
matter. A real rate needs the anonymised SMS corpus planned for Phase 0.

## Findings

1. **[DATA-001] [High] `Rs.500/-` amounts are rejected**
   - Where: `Money.parse`, `AMOUNT` regex
   - How to reproduce: `Money.parse("Rs.500/-")` → `null` (30,140 of 30,140 simulated samples lost)
   - What happens: the amount is not recognised, so the payment is never logged
   - Why it matters: the `/-` suffix is very common in Indian bank SMS. Auto-detect would silently miss these payments.
   - Direction (not a fix): decide whether `/-` is stripped by the future SMS extractor or accepted by `Money.parse`

2. **[DATA-002] [High] Non-breaking spaces after `₹` are rejected**
   - Where: `Money.parse`, `PREFIX` uses `\s`, which is ASCII-only on the JVM
   - How to reproduce: `Money.parse("₹ 120")` and `Money.parse("₹ 120")` → `null`
   - What happens: the amount is lost
   - Why it matters: Android's own number formatting and many apps put U+00A0 or U+202F between the symbol
     and the digits, so notifications often contain exactly this
   - Direction: define a normalisation step (unicode spaces to a plain space) and decide where it lives

3. **[FLAW-003] [High] Changing the month start day silently rewrites history**
   - Where: `BudgetMonth.containing(date, startDay)` takes the *current* setting
   - How to reproduce: 27 Mar 2026 belongs to the month starting 1 Mar with `startDay=1`, but to the month
     starting 25 Mar with `startDay=25`
   - What happens: every past month's boundaries move as soon as the setting changes
   - Why it matters: month-end Saved is keyed per month (`sys.saved+month`). Old Saved entries become
     orphaned or duplicated, and past totals change without the user doing anything.
   - Direction: decide product behaviour. Should the setting be effective-dated, apply only from now on, or
     recompute all history with a warning?

4. **[FLAW-004] [Medium] Changing time zone moves entries between months**
   - Where: `BudgetMonth.containing(instant, timeZone, startDay)`
   - How to reproduce: `2026-03-31T20:00:00Z` is April in `Asia/Kolkata` but March in `America/New_York`
   - What happens: a traveller's or NRI's late-night payments jump months when the phone's zone changes,
     which also changes Saved
   - Why it matters: past months change without any edit
   - Direction: decide whether each entry keeps the zone it was captured in, or everything uses one home zone

5. **[DATA-005] [Medium] Other common prefixes are rejected**
   - How to reproduce: `"INR. 120"`, `"Rs:120"`, `"₨120"` (U+20A8, the old rupee sign) → `null`
   - Why it matters: all of these appear in real alerts, so those payments would be missed
   - Direction: collect real samples per bank before widening the grammar

6. **[DATA-006] [Medium] An amount at the end of a sentence is rejected**
   - How to reproduce: `Money.parse("Rs.120.")` → `null`
   - Why it matters: the contract between the future SMS extractor and `Money.parse` is undefined, so it is
     unclear who strips trailing punctuation. Text like "debited by Rs.120." would be missed.
   - Direction: write that contract down before building `core/parser`

7. **[DATA-007] [Medium] (plausible, not verified) Parsing may differ on iOS**
   - Where: regex `\d` and `\s` plus `String.toLong()`
   - What happens: on the JVM `\d` and `\s` are ASCII-only, while Kotlin/Native's regex semantics on iOS may
     differ. The same SMS text could then parse differently on Android and iPhone.
   - Why it matters: this breaks the "identical on both platforms" rule
   - How to verify: run the same tests with unicode inputs in the macOS CI job

8. **[SEC-008] [Medium] An unknown time-zone ID crashes**
   - How to reproduce: `TimeZone.of("Asia/Bombay!")` → `IllegalTimeZoneException`
   - What happens: a tampered backup, or a zone ID that one platform's database doesn't know, can crash the
     restore or every app launch if the zone is read at start-up
   - Why it matters: this is a cheap crash-loop for anyone who can hand the user a backup file
   - Direction: carry into the `core/backup` threat model

9. **[PEN-009] [Low] An extreme date crashes the month calculation**
   - How to reproduce: `BudgetMonth.containing(LocalDate(999_999_999, 12, 15), 1)` →
     `DateTimeArithmeticException`. The far-past equivalent does not throw, so the behaviour is asymmetric.
   - Why it matters: one crafted entry in a backup could crash every screen that computes months
   - Direction: decide what date range is valid for an entry

10. **[PEN-010] [Low] Input length is unbounded**
    - What happens: an 8 MB string takes about 286 ms per parse on a desktop JVM, likely several times
      longer on a low-end phone. No catastrophic regex backtracking was seen; the cost is linear.
    - Why it matters: a flood of crafted notifications or SMS with huge bodies could stall the ingestion queue
    - Direction: decide on maximum input sizes at the ingestion boundary

11. **[DATA-011] [Low] Non-ASCII digits are rejected**
    - How to reproduce: `"₹१२०"` (Devanagari), `"１２０"` (fullwidth), `"٤٥"` → `null`
    - Why it matters: Hindi-language bank SMS and OCR output may contain these digits
    - Direction: decide whether to normalise digits to ASCII before parsing

12. **[FLAW-012] [Low] Zero amounts are accepted**
    - How to reproduce: `"₹0"` and `"0.00"` parse as ₹0
    - Why it matters: penny-drop checks, ₹0 mandates and test transactions could create empty entries
    - Direction: decide whether the parser layer drops ₹0

13. **[FLAW-013] [Low] Negative formatted amounts can't be parsed back**
    - How to reproduce: `Money.parse(Money(-50_000).format())` → `null`. The round-trip property test only
      covers amounts of ₹0 and above.
    - Why it matters: any feature that reads displayed text back (paste, OCR of a Vyaya screenshot) breaks
      on negative amounts

14. **[FLAW-014] [Low] `Money.ofRupees` sign handling is confusing**
    - What happens: there is no way to express −₹0.50, and a negative `rupees` with a positive `paise`
      quietly becomes more negative
    - Why it matters: easy to misuse in future code

15. **[FLAW-015] [Low] `BudgetMonth` can be built in an invalid state**
    - How to reproduce: the public constructor accepts `end < start` and `startDay = 99`; `contains` then
      silently returns false
    - Why it matters: the invariants are only enforced by `containing(...)`

16. **[SEC-016] [Low] `Money.toString()` prints the formatted amount**
    - What happens: any log line or crash report that prints an entry object will contain real amounts
    - Why it matters: conflicts with "logs must not leak financial data"
    - Direction: decide on a logging policy and redaction

17. **[SEC-017] [Low] Supply chain is not pinned**
    - What happens:
      - The Gradle wrapper has no `distributionSha256Sum`, because the official checksum host was
        unreachable from the cloud session.
      - There is no Gradle dependency-verification metadata.
      - CI actions are pinned to tags, not commit SHAs.
    - Why it matters: this is a public repo, and a tampered build tool or dependency could ship in the app
    - Direction: pin all three before the first release build

18. **[FLAW-018] [Info] The ₹100 crore limit is not a scam defence**
    - What happens: a fake "₹25,000 credited" message passes it. The code comment ("parse error or a scam")
      overstates what the limit does.
    - Why it matters: scam filtering must happen in `core/filters`

19. **[FLAW-019] [Info] Start day 31 produces uneven periods**
    - What happens: period lengths in 2026 are 28, 31, 30, 31 … days. This is correct by design, but
      "safe to spend per day" and month-to-month comparisons need to account for it.

20. **[SIM-020] [Info] The simulation used synthetic formats, not the real distribution**
    - What happens: the 50 % total weights every format equally
    - Why it matters: it isn't a real-world loss rate yet
    - Direction: build the anonymised real-SMS corpus (Phase 0) and rerun

## Decisions needed from you
- **#1, #2, #5, #6, #11:** does normalisation happen in `Money.parse` or in the SMS extractor?
- **#3, #4:** product behaviour when the month start day or the time zone changes.
- **#12:** should ₹0 messages be ignored?

## Resolution (2026-10-26)

Decisions by the user:
- Messy text is cleaned by a separate normaliser, and `Money.parse` stays strict.
- A start-day change applies from the next month.
- Each entry keeps the zone it was logged in.
- ₹0 is ignored.

Every fix started with a failing test.

| # | Status | Fix |
|---|---|---|
| 1 DATA-001 | ✅ Fixed `9f11650` | `AmountReader` accepts `/-` |
| 2 DATA-002 | ✅ Fixed `9f11650` | `TextNormalizer` turns Unicode spaces into plain spaces |
| 3 FLAW-003 | ✅ Fixed `5486bcc` | `MonthSchedule`: a change takes effect at the end of the current month; past months are unchanged (property-tested) |
| 4 FLAW-004 | ✅ Fixed `321dd47` | `CapturedAt` and `MonthSchedule.monthOf` compute the month in the entry's capture zone |
| 5 DATA-005 | ✅ Fixed `9f11650` | `INR.`, `Rs:` and `₨` are accepted |
| 6 DATA-006 | ✅ Fixed `9f11650` | one trailing `.` or `,` is ignored |
| 7 DATA-007 | ✅ Verified for tested inputs | normalisation uses common Unicode tables; the same 47 tests (incl. Devanagari/fullwidth digits and NBSP) pass on the iOS simulator in CI (run on `52da16b`) |
| 8 SEC-008 | ⏳ Open | carried into the `core/backup` threat model |
| 9 PEN-009 | ⏳ Open | awaiting decision |
| 10 PEN-010 | ✅ Fixed `ee5cbc8` | `MessageParser` rejects texts over 2,000 characters before normalising (`NotTransaction(TOO_LONG)`) |
| 11 DATA-011 | ✅ Fixed `9f11650` | `TextNormalizer` converts Unicode digits to ASCII |
| 12 FLAW-012 | ✅ Fixed `9f11650` | `AmountReader` drops ₹0 |
| 13–19 | ⏳ Open | awaiting decision (accept the risk or fix with related work) |
| 20 SIM-020 | ⏳ Open | rerun once the real anonymised corpus exists |

New design choice to confirm:
- **MIN_TRANSITION_DAYS = 7.** When the start day changes, the first month under the new day is merged
  into the following one if it would be shorter than 7 days. Example: changing 1 → 3 gives 1 Apr–3 May
  rather than a 2-day month.
