# Findings: the Ignored tab: 2026-11-16

**Scope:** `e618e71`, which covers:
- `IgnoredStore` and the `ignored` table (core/data);
- capture recording skipped messages that show an amount;
- Detected → Logged / Ignored (N), "This was a payment" / "Not a payment".

**Decisions applied:** a summary only (sender, amount, reason, time), kept 30 days, never the message text.

**Method:**
- Code analysis.
- The store and UI tests' own checks: no column holds any of the message's words, one row per message, removed after
  30 days, phone numbers masked.
- The capture simulation in the gate is unchanged: 0 doubled, 0 noise accepted.
- The full local gate is green.

**Status:** reported only. **Nothing fixed.**

## Findings

1. **[SEC-001] [Medium] A scam message gets a one-tap "This was a payment"**
   - Where: every Ignored card has the same buttons, including messages skipped as PERSONAL_NUMBER ("From a phone
     number, not a bank: often a scam").
   - How to reproduce: "+91… Rs 5,000 credited to your account. Click to claim" → Ignored → "This was a payment" →
     the form opens with ₹5,000.
   - Why it matters: the tab is meant to build trust, but it puts a scam's amount one tap from the user's books. It
     also gives a scammer's "you received money" message a second look.
   - Direction (not a fix): for PERSONAL_NUMBER and UNTRUSTED_APP, no "This was a payment" button. Show a warning
     instead ("Banks never send from phone numbers"), with adding still possible by hand.

2. **[DATA-002] [Medium] Adding a "failed" payment that later went through counts it twice**
   - What happens (by analysis): the bank says "failed", then a minute later "debited" (a retry). The second message
     is captured. If the user also taps "This was a payment" on the failed one, there are two entries.
   - Direction: before adding from Ignored, look for an entry with the same amount within a day. If there is one, say
     "This looks already logged (₹2,000 at 14:06)" and offer to open it instead.

3. **[FLAW-003] [Medium] Promotions and OTPs with amounts crowd the list**
   - What happens: "Get ₹500 cashback", "₹100 off your next order" and OTPs mentioning an amount each make a card. A
     normal month can have dozens, burying the one real skipped payment.
   - Direction: show payment-like reasons first (FAILED, NOT_YET_HAPPENED, UNKNOWN_SENDER, NOT_A_TRANSACTION). Put
     PROMOTIONAL and OTP behind "Show promotions and codes (23)".

4. **[FLAW-004] [Low] "This was a payment" always starts as spending**
   - Where: the summary keeps no direction, so a skipped "credited" message opens as an expense.
   - Direction: keep the direction too (debit or credit, from the parser's direction words). It reveals nothing of the
     text.

5. **[FLAW-005] [Low] Times are shown in the phone's zone, not the home zone**
   - Where: `IgnoredList` uses `TimeZone.currentSystemDefault()`. Everything else uses the home time zone, so while
     travelling the times disagree with Home.
   - Direction: pass the home zone in.

6. **[FLAW-006] [Low] Old rows wait for the next capture or a visit to the tab**
   - Where: rows older than 30 days are removed when capture records something or the tab reads the list. With
     neither for a while (listener off, SMS off), older summaries stay on the phone past 30 days.
   - Direction: remove them on app open too.

7. **[Info] Things that held up**
   - No column holds the message's words (the test checks the stored row). Only sender, amount, reason and time are
     kept.
   - Phone-number senders are masked ("+91••••••3210"); bank headers and app names are shown as they are.
   - The same message read again (the SMS import) is one row, and an answered one stays answered.
   - Never in a backup. The table is local only, and the database file is already excluded from Auto Backup (SEC-007,
     core-data report).
   - Only messages that show an amount are kept, so OTPs and notices without money don't appear.

## Decisions needed from you
- **#1:** no one-tap add for messages from phone numbers and unverified apps, with a warning (recommended)?
- **#2:** check for an existing entry of the same amount within a day before adding (recommended)?
- **#3:** promotions and codes folded away behind a count (recommended)?
- **#4, #5, #6:** keep the direction, use the home zone, clean up on app open (recommended)?

## Resolution (2026-11-18)

Your decision: go with the recommendations (#1–#6). Each fix began with a failing test.

| # | Status | Fix |
|---|---|---|
| 1 SEC-001 | ✅ Fixed | From a phone number or an unverified app: no "This was a payment", a warning instead ("Banks never send from phone numbers… You can still add a payment by hand from Home"). "Not a payment" stays |
| 2 DATA-002 | ✅ Fixed | Before adding, `IgnoredStore.alreadyLogged` looks for a live entry of the same amount within a day. If there is one: "This looks already logged: ₹2,000 on … to Swiggy", with Open it (the message is answered, the entry opens) / Add anyway / Cancel |
| 3 FLAW-003 | ✅ Fixed | Promotions and codes are behind "Show promotions and codes (N)", and the tab's count leaves them out |
| 4 FLAW-004 | ✅ Fixed | The summary keeps whether money came in (the message's first direction word); "This was a payment" then starts as Income |
| 5 FLAW-005 | ✅ Fixed | Times are shown in the home time zone (the test uses a home zone other than the phone's) |
| 6 FLAW-006 | ✅ Fixed | App open also removes summaries older than 30 days |

The capture simulation is unchanged (0 doubled, 0 noise accepted). While fixing, four UI tests that raced the app's own
loading under full-gate load now wait for it. Two older test classes now close their database after the screen is
disposed, not before.
