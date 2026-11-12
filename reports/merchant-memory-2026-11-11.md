# Findings: merchant memory ("always put Swiggy in Food"): 2026-11-11

**Scope:** `9e4f56a`, which covers:
- `MerchantMemory` (core/dedup);
- `MerchantStore` (core/data), and capture reading accounts, categories and rules in its transaction;
- the "Always put <payee> in <category>" checkbox on a detected payment's edit form;
- Settings → Remembered payees.

**Method:**
- Code analysis.
- Two temporary probes (not committed): every corpus template × every payee through the parser; and capture time
  with 0, 1,000 and 10,000 categories and rules.
- Capture simulation `-Psim.capture.users=2000`, now with merchant memory: 0–3 remembered payees per user, some as
  income, some forgotten.
- The full local gate is green.

**Status:** reported only. **Nothing fixed.**

## Simulation

| Check | Result |
|---|---|
| Payments / messages | 138,346 / 455,520 (24,291 noise) |
| Doubled / noise accepted / false transfers | **0 / 0 / 0**; 27 merged (SIM-006, known) |
| Payments to a remembered payee that suit its category | 10,026 |
| … put in that category | 9,907 (98.8 %) |
| Entries in a category they shouldn't be in | **0** |
| Capture p95 | 59 µs per message (in memory) |

## Findings

1. **[FLAW-001] [Medium] A payment first seen without a payee never gets one, or its remembered category**
   - Where: `Capture.process`. A message that turns out to be a duplicate adds only an account (catalog #4), never a
     payee.
   - How to reproduce (probe): the `sbi-upi-credit` template parses no counterparty. When SBI's SMS arrives before
     the UPI app's notification, the entry has no payee. The notification ("Received ₹250 from Swiggy") is then a
     duplicate and adds nothing.
   - What happens: in the simulation, 119 of 10,026 remembered payments (1.2 %) stayed uncategorised for this reason.
     Home shows a payment with no payee.
   - Why it matters: the user taught Vyaya and it "forgot"; this is the most visible failure of the feature.
   - Direction (not a fix): as with the account, a duplicate fills a missing payee on a captured entry, then applies
     merchant memory, only when the entry has no category.

2. **[FLAW-002] [Medium] Recategorising a remembered payee's payment leaves the old rule in place, silently**
   - Where: the edit form. The checkbox starts unticked and doesn't show that a rule exists.
   - How to reproduce: Swiggy is remembered as Food. Open a Swiggy payment, choose Treats, Save without ticking.
   - What happens: the next Swiggy payment goes to Food again. The user thinks they changed it.
   - Direction: when the payee has a rule with another category, say so ("Swiggy goes in Food. Always put it in Treats
     instead?").

3. **[FLAW-003] [Medium] Remembering affects only payments still to come**
   - What happens: after "Always put Swiggy in Food", the 30 earlier Swiggy payments with no category stay
     uncategorised. Most users expect the earlier ones to follow.
   - Direction: offer "Also put the 30 earlier Swiggy payments that have no category in Food". Never change a
     category the user chose.

4. **[FLAW-004] [Low] Short or shared payee names catch unrelated payments**
   - Where: matching by `Capture.payeeKey` (letters and digits before `@`, lowercase).
   - What happens (by analysis): "Rahul → Rent" puts every payment to any Rahul into Rent. "A K" becomes `ak`; `atm`
     matches every ATM withdrawal (wanted for "ATM → Cash", not for others).
   - Direction: before saving, show how many past payments match ("12 payments to Rahul"). Warn when the key is very
     short.

5. **[SIM-005] [Low] Capture reads every category, rule and account for each message**
   - Where: `CaptureStore.process` → `captureRecordsInTransaction()` decodes all three kinds every time.
   - Measure (probe, Mac, per message): 5.7 ms with none, 11.6 ms with 1,000 of each, **92 ms with 10,000 of each**
     (the limit).
   - Why it matters: normal users have dozens and won't notice. At the limits, the 30-day SMS import (hundreds of
     messages) slows by minutes on a phone.
   - Direction: build the memory once per import batch, or cache it until the records change. Or keep the rules'
     payee key in a column and read only the matching one.

6. **[FLAW-006] [Low] Remembered payees are shown with lost capitals**
   - Where: `MerchantRule` stores the payee lowercase, so the list shows "Uber india", "Dr arjun rao".
   - Direction: keep the payee as first written for display, and compare by `payeeKey` as now.

7. **[FLAW-007] [Low] An entry doesn't show that its category came from memory**
   - What happens: a payment put in Food by a rule looks the same as one the user chose. There's nothing to tap to
     see or undo why.
   - Direction: on the edit form, "Food: remembered for Swiggy", linking to the rule.

8. **[SEC-008] [Info] Spoofing**
   - A crafted notification from an allowed app naming "Swiggy" gets Swiggy's category. Merchant memory adds no new
     trust: it sets only the category, never the amount, type, account or review state. The sender filters are
     unchanged, and a Review item stays a Review item.

9. **[Info] Things that held up**
   - 0 entries in a wrong category across 138,346 simulated payments.
   - A refund from a payee remembered as spending is never put in that spending category.
   - A forgotten rule, a deleted category, or an unknown category does nothing.
   - Two rules for one payee (from two phones): the latest change wins.
   - "SWIGGY", "Swiggy" and "swiggy@axl" are one payee and one rule. Remembering again changes it in place, and a
     forgotten one comes back.
   - Limit of 10,000 rules (forgotten ones included), the same as other records.
   - The backup format is unchanged: merchant rules were already in v3.

## Decisions needed from you
- **#1:** a duplicate fills a missing payee, then applies memory (recommended)?
- **#2:** say when the payee already has another category, and offer to change it (recommended)?
- **#3:** offer to categorise earlier uncategorised payments from that payee (recommended)?
- **#4:** show how many past payments match before saving, and warn on very short names (recommended)?
- **#5:** cache the memory until records change (recommended)?
- **#6, #7:** keep the payee's own capitals, and show "remembered for Swiggy" on the entry (recommended)?

## Resolution (2026-11-12)

Your decision: go with the recommendations (#1–#7). Each fix began with a failing test.

| # | Status | Fix |
|---|---|---|
| 1 FLAW-001 | ✅ Fixed | A later message for the same payment fills a missing payee (and account); with no category yet, the payee's remembered category follows. The Detected inbox item shows the entry as it is now. Tests: `merchant_flaw_001_*` (capture, store and inbox) |
| 2 FLAW-002 | ✅ Fixed | Choosing another category for a remembered payee says so: "Swiggy goes in Food & dining. Always put it in Shopping instead" |
| 3 FLAW-003 | ✅ Fixed | With "Always put…" ticked, "Also put the 2 other Swiggy payments with no category in …" (unticked by default). Only entries of the same type with no category are changed, in one transaction (`MerchantStore.applyToEarlier`) |
| 4 FLAW-004 | ✅ Fixed | The form shows "3 payments to Swiggy so far" (`MerchantStore.matches`), and "Short name: other payees may match" under 3 letters or digits |
| 5 SIM-005 | ✅ Fixed | Capture keeps accounts and memory until the records change (count, change times and lengths of accounts, categories and rules). Probe: 10,000 of each, 92 ms → **6.4 ms** per message, records read once |
| 6 FLAW-006 | ✅ Fixed | Rules keep the payee as written ("Uber India"); matching is by key as before. Rules saved lowercase earlier show capitalised. Golden backup files unchanged |
| 7 FLAW-007 | ✅ Fixed | An entry whose category is the remembered one says "Food & dining: remembered for Swiggy" |

Simulation after the fixes (`-Psim.capture.users=2000`): 0 doubled, 0 noise accepted, 0 false transfers, **0 in a wrong
category**. 9,922 of 10,026 remembered payments are categorised (was 9,907); **all 104 left never had a message
naming the payee**, so there's nothing to match. The simulation harness now also saves what a duplicate fills in, as
`CaptureStore` does.

Also changed while fixing:
- The shared-core branch coverage gate (90 %) caught untested edges in the new stores, so tests were added. Unreachable
  "can't be saved" branches in `CatalogStore` and `MerchantStore` were removed: their checks are the model's own.
- UI tests now close the database after the screen is disposed (an outer JUnit rule), instead of racing a reload.
