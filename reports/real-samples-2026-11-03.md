# Findings: changes from the user's real SMS: 2026-11-03

**Scope:**
- `9f99877`: Txn ID as reference, AIRBNK trusted, masked samples in the corpus.
- `e954967`: card summary SMS split into payments.
- `1c2fd7a`: transfers between the user's own accounts.

**Method:**
- 263 JVM and 256 iOS tests, the coverage gate, and the capture simulation (2,000 users: 0 doubled, 0 noise
  accepted, 0 false transfers, 24 merged as before).
- A review of the new paths against the real samples.

**Status:** reported only. **Nothing fixed.**

## Findings

1. **[DATA-001] [Medium] Card summary payments are dated when the SMS arrives, not on the day they happened**
   - Where: `MessageSplitter`: the parts keep the message time; the `yy-mm-dd` on each line is not used.
   - How to reproduce: rides on 31 Oct arrive in a summary on 1 Nov, so they count in November.
   - Why it matters: "what's left" is wrong at month ends for metro and card users.
   - Direction (not a fix): date each part from its line, at the message's time of day; check the
     `yy-mm-dd` reading against more real summaries.

2. **[DATA-002] [Low] A refund carrying the original payment's reference would be taken as a transfer**
   - Where: `Capture.otherSideOf`: same reference and amount, opposite direction.
   - How to reproduce (by analysis): a bank sending "Rs.500 credited, refund of UPI ref X" with the same X.
     Your samples show refunds with new references, and "reversed" messages are already ignored.
   - Direction: watch for it in more real samples; exclude texts with "refund" if it appears.

3. **[FLAW-003] [Low] Any 12-digit number after "Txn ID" is taken as the bank reference**
   - Why it matters: a merchant order number after "Txn ID" could make two different payments look like one.
   - Direction: limit it to the banks known to use it (Airtel), as a parser rule.

4. **[FLAW-004] [Info] The POP UPI app's notifications are ignored**
   - What happens: POP isn't on the list of UPI apps, so its notifications are treated as coming from an
     unknown app.
   - Direction: add it once its package name and notification texts are known (asked of the user).

5. **[Info] Things that held up**
   - Every Airtel debit and credit sample: right amount, direction and reference.
   - The UPI-link notice and the AutoPay "scheduled" notice are not entries.
   - A card summary read twice (live, then the import) gives each payment once.
   - The HDFC → Airtel ₹14,999 movement becomes one transfer, in either order.

## Decisions needed from you
- **#1:** date card summary payments from their line (recommended)?
- **#2, #3:** watch with more samples, or tighten now?
