# Findings: code review of the branch (origin/main..HEAD): 2026-11-04

**Scope:** the 24 commits on `claude/vyaya-continuation-jj1rgx`: capture (filters, de-dup), the real-sample
changes, the 31-app list, `core/data` (entries, capture into the database, Detected inbox), `ClockRepair`.

**Method:** a code review of the diff. Each finding was traced through the code but is **not yet reproduced by a
test**. When a fix is agreed, its first step is a failing test that proves it.

**Status:** reported only. **Nothing fixed.**

## Findings

1. **[DATA-001] [High] Re-reading either SMS of a transfer adds a new expense or income**
   - Where: `Capture.process` → `otherSideOf` / `duplicateOf` (`core/dedup/.../Capture.kt`).
   - How to reproduce: HDFC "Sent ₹14,999 Ref X" is added; the Airtel credit "Txn ID X" turns it into a
     TRANSFER; the 30-day import reads the HDFC SMS again.
   - What happens: no rule matches an entry of type TRANSFER (`otherSideOf` wants EXPENSE+INCOME,
     `duplicateOf` wants the same type), so a second ₹14,999 expense is added. Re-reading the Airtel SMS adds
     ₹14,999 income.
   - Why it matters: double counting (invariant 1), on every import.
   - Direction (not a fix): a captured TRANSFER with the same reference and amount is a duplicate of either side.

2. **[DATA-002] [High] Re-reading a card summary doubles rides dated more than 6 days before the SMS**
   - Where: `CaptureStore.process` loads entries within ±6 days of the SMS's arrival; `MessageSplitter` dates each
     part on its own line's day, and card rides have no reference.
   - How to reproduce: a summary arriving on 10 Nov lists a 31 Oct ride; the import reads it again.
   - What happens: the 31 Oct entry isn't loaded, so the ride is added again, on every import.
   - Direction: centre the window on each part's own time (split once, look up per part).

3. **[DATA-003] [High] A review item for a payment already captured shows no "possible duplicate"**
   - Where: `Capture.process` skips `duplicateOf` for Review verdicts; `possibleDuplicateOf` only checks
     typed-in and recurring entries.
   - How to reproduce: PhonePe adds ₹500 to Zomato; the bank SMS arrives from a bare or unknown header, so it is
     a Review item with no duplicate pointer. The user confirms it.
   - What happens: ₹500 is logged twice.
   - Direction: run the same duplicate check for Review items, and show it as "possible duplicate of".

4. **[DATA-004] [High] Capture isn't atomic: two messages at once can both be added**
   - Where: `CaptureStore.process`: read, decide, write inbox, write entries, with no transaction.
   - How to reproduce: the SMS receiver and the notification listener process one payment at the same moment.
   - What happens: both see nothing and both add. Also, if the app dies between the inbox write and the entry
     write, the inbox shows an added payment that doesn't exist.
   - Direction: one database transaction per message, and one capture at a time (a single queue).

5. **[DATA-005] [Medium] After a transfer, the inbox still shows the first side as an expense; undo deletes the transfer**
   - Where: `DetectedInbox.record` ignores Transfer outcomes; `undo` deletes whatever the entry now is.
   - Direction: update the item when its entry becomes a transfer, and make undo of a transfer explicit.

6. **[FLAW-006] [Medium] A real alert ending "Never share your OTP" is ignored as an OTP**
   - Where: `Filters.judge`, the OTP pattern matches the word anywhere.
   - How to reproduce: "Rs.500.00 debited from A/c XX1234 to Swiggy. UPI Ref … Never share your OTP/PIN with
     anyone." from a trusted header.
   - What happens: Ignore(OTP); the real payment is silently missed. Many Indian banks add this line.
   - Direction: treat a message as an OTP only when it gives a code ("123456 is your OTP"), not when it warns.

7. **[FLAW-007] [Medium] Screenshot and Shortcut messages can never become entries**
   - Where: `Filters.judge`: every source except notifications needs an SMS header sender.
   - Why it matters: blocks screenshot OCR (Android, Phase 2) and iOS Shortcuts (Phase 4). Not used yet.
   - Direction: give these sources their own trust rule when those features are built.

8. **[DATA-008] [Low] `ClockRepair` keeps this month's generated entries that aren't due yet**
   - How to reproduce: clock set to 28 Nov makes rent due on the 25th; the clock goes back to 10 Nov.
   - What happens: rent shows as spent 15 days early, until the 25th.
   - Direction: also remove generated entries whose due day is after today.

9. **[FLAW-009] [Low] Each message is split three times and parsed twice**
   - Where: `CaptureStore.process`. About twice the work per message during a large import.
   - Direction: split and parse once, pass the parts on.

10. **[FLAW-010] [Low] Inbox actions decode every open item, and one bad row breaks the whole inbox**
    - Where: `DetectedInbox.open` goes through `items()`; `items()` throws on the first undecodable row.
    - Direction: look up the one row by id; skip (and report) a bad row instead of failing.

## Decisions needed from you
- Which to fix. Recommended: **1–6 and 8–10 now** (1–4 can double-count or lose money; all are small);
  **7** when screenshots and Shortcuts are built.
