# Findings: core/filters and core/dedup (capture pipeline): 2026-11-03

**Scope:** `9fb3a0b` (the parser keeps the SMS header category), `7a21041` (core/filters), and core/dedup
(`Capture`: parse → filter → de-dup → entry).

**Method:**
- **Break phase:** by analysis, plus a temporary diagnostic (not committed) that ran pairs of message formats
  for one payment through capture.
- **Simulation:** `./gradlew :core:simulation:jvmTest -Psim.capture.users=2000`. 2,000 users, 140,938 real
  payments.
  - Each payment arrives as a bank SMS (delayed up to 6 minutes) plus 0–2 UPI app notifications (within 30
    seconds), delivered out of order.
  - 15–40 % noise and scam messages are mixed in.
  - Some same-amount payments come minutes apart.
  - At the end, the SMS import reads every SMS again.

**Status:** reported only. **Nothing fixed.**

## Simulation summary

| Check | Result |
|---|---|
| Messages processed | 464,347 (24,784 noise or scam) |
| Noise or scam messages that became entries (invariant 7) | **0** |
| Real payments with no entry | 24 (0.02 %): same-amount payments minutes apart, merged |
| Real payments doubled (invariant 1) | **7,899 (5.6 %)**, see #1 |
| Capture time | p95 107 µs per message, scanning all of the user's entries |

## Findings

1. **[SIM-001] [High] Two apps' notifications for one payment become two entries when they name the payee differently**
   - Where: `Capture.duplicateOf`, the two-app rule (same amount and payee within 2 minutes).
   - How to reproduce: BHIM shows `rahulsharma@okaxis`; PhonePe, Paytm, CRED, Navi and others show `Rahul Sharma`.
     Both notifications become entries. *(Diagnostic confirmed; this explains the 5.6 % in the simulation.)*
   - Why it matters: double counting, the thing the product promises not to do.
   - Direction (not a fix): compare payees loosely: lowercase letters and digits only, and a UPI ID's part
     before `@` (`rahulsharma@okaxis` → `rahulsharma` = `Rahul Sharma`). Or record which app an entry came
     from, so two different apps within seconds always mean one payment. That needs a model and backup
     format change.

2. **[SEC-002] [High, for the Play build] A contact named like a bank header could pass as a bank SMS**
   - Where: the planned adapter that reads the SMS app's notifications (no SMS permission). The notification
     title is a contact's saved name, not the sender's address.
   - How to reproduce (by analysis): save a contact as `VM-HDFCBK-S`; their "Rs.5,000 credited…" message shows
     with that title and would be judged as an HDFC SMS.
   - Why it matters: the scam the filters exist to stop.
   - Direction: from SMS-app notifications, auto-accept only when the title is not a saved contact (the
     adapter can check), or send them all to Review. Live SMS via `RECEIVE_SMS` gets the real address and
     doesn't have this problem.

3. **[SEC-003] [Medium] A bare header like `HDFCBK` (no operator prefix) is trusted**
   - How to reproduce (by analysis): international SMS routes can carry any alphanumeric sender ID, without the
     Indian `XX-` prefix. `HDFCBK` alone is accepted like `VM-HDFCBK-S`.
   - Direction: auto-accept only the prefixed form; send a bare known entity to Review.

4. **[FLAW-004] [Medium] Customers of banks not on the list get everything in Review**
   - What happens: `TrustedSenders.default` has 20 entities. Any other bank's real SMS waits in the inbox.
   - Why it matters: review fatigue for many users. The list also needs checking against real SMS.
   - Direction: grow the list from your real samples, make it versioned data like the parser rules, and let a
     confirmed review add the header ("teach-the-parser").

5. **[FLAW-005] [Low] Common amounts paid by hand often go to Review**
   - What happens: a manual ₹20 entry today makes every captured ₹20 within 24 hours a "possible duplicate".
   - Direction: also require the same payee, or a shorter window, for small amounts.

6. **[SIM-006] [Info] Same-amount payments minutes apart can merge (24 of 140,938)**
   - What happens: payment A's SMS (with a UPI reference) can merge into payment B's notification entry (no
     reference) within 10 minutes.
   - Direction: when the entry already has a reference from another message, don't merge a different one.
     Needs the entry to keep the reference it got from the merged SMS.

7. **[Info] Things that held up**
   - 0 of 24,784 noise and scam messages became entries: OTPs, promotions, failed payments, collect requests,
     autopay reminders, and fake credits from phone numbers.
   - Re-reading every SMS with the import added nothing new.
   - A delayed bank SMS matches its notification entry by time; the same reference matches across hours.
   - Spoofed apps, phone numbers, lookalike headers and hidden characters are refused or sent to review.

## Decisions needed from you
- **#1:** loose payee matching (small; recommended), or record the source app on entries (a format change)?
- **#2:** for SMS-app notifications: auto-accept only from non-contacts (recommended), or always review?
- **#3:** send bare headers to Review (recommended)?
- **#5:** require the same payee for "possible duplicate" of a manual entry?

## Resolution (2026-11-03)

Your decision: fix 1, 2, 3 and 5.

| # | Status | Fix |
|---|---|---|
| 1 SIM-001 | ✅ Fixed | Loose payee key (part before `@`, letters and digits, any case). Simulation: doubled 7,899 → **0** |
| 2 SEC-002 | ✅ Fixed | `Source.SMS_APP_NOTIFICATION` + `senderIsContact`: a saved contact's name goes to Review |
| 3 SEC-003 | ✅ Fixed | A bare header (`HDFCBK`) goes to Review |
| 4 FLAW-004 | ⏳ Open | Grow the trusted list from your real samples (your manual task) |
| 5 FLAW-005 | ✅ Fixed | Typed-in match needs the same payee within 24 h, or within 2 h when it has no payee |
| 6 SIM-006 | ⏳ Open | 24 of 140,938 same-amount payments minutes apart merge |
