# Findings: safe to spend per day: 2026-11-16

**Scope:** `85c7e27`, which covers:
- `SafeToSpend` (core/engine);
- `RulesStore.madeKeys`;
- the Home line: "Safe to spend ₹789 a day, for 19 days, after ₹15,000 of bills still due", or "Bills still due
  (₹1,500) are ₹500 more than what's left".

**Method:**
- Code analysis.
- One temporary probe (not committed): rent paid early by UPI, then the rule's due day.
- Safe-to-spend simulation `-Psim.engine.users=2000`: catch-up on open, then the database month view, then the
  rules' made months, then `SafeToSpend.of`, against an independent day-by-day count.
- A property test: the daily amount every day left, plus every bill, never goes below zero.
- The full local gate is green.

**Status:** reported only. **Nothing fixed.**

## Simulation

| Check | Result |
|---|---|
| Days checked (2,000 users × 4 days) | 8,000: **app = independent count 8,000, differ 0** |
| Shown | a daily amount 1,150 · bills more than what's left 6,624 · nothing left 226 |
| Daily amount every day + every bill below zero | **0** |

The simulated users overspend often (random data), so most days say the bills are more than what's left. That number
says nothing about real users, but see #2.

## Findings

1. **[DATA-001] [High] A bill paid before its due day is counted twice (in Left, budgets and Saved too)**
   - Where: capture vs recurring catch-up. Capture treats a payment near an existing recurring entry as a possible
     duplicate (`RECURRING_NEARBY`, 5 days). But when the payment comes first, there's no recurring entry yet, so it's
     simply added. On the due day, catch-up makes the rule's entry anyway.
   - How to reproduce (probe): rule "Rent ₹15,000 on the 20th". On 18 Nov, PhonePe "₹15,000 paid to Sharma Landlord"
     → Added. App opens on 20 Nov → catch-up adds the rule's entry. November has **2 entries of ₹15,000** (NOTIFICATION
     and RECURRING), and the Detected inbox shows only the first.
   - What happens:
     - "Left" is ₹15,000 too low, and so are budgets and Saved for the month.
     - Before the 20th, safe to spend subtracts the rent twice: once as spent, once as a bill still due.
   - Why it matters: it breaks plan.md invariant 1 ("no payment logged twice") in a very common case (rent, EMIs and
     SIPs are often paid a day or two early).
   - Direction (not a fix): when catch-up is about to make a rule's entry, look for a captured payment of the same
     amount (and payee, if the rule has one) within the 5-day window. Link it instead (mark the month as made by that
     payment, or ask in the Detected inbox). For safe to spend, the same match also takes the bill off "still due".

2. **[FLAW-002] [Medium] Before the salary comes in, nothing looks safe**
   - Where: income still to come is not counted (on purpose: the safe side).
   - What happens: a user with month start 1 and salary on the last working day sees "Bills still due are … more than
     what's left" for most of the month. The line is useless exactly when it matters.
   - Direction (needs your decision):
     - (a) Count income rules due this month and not yet made ("expected salary ₹60,000"), saying so on the line.
     - (b) Suggest setting the month's start day to the salary day (the app supports it), shown when the first income
       of a month arrives after its start.
     - (c) Both.

3. **[FLAW-003] [Low] The daily amount changes during the day**
   - What happens: spending ₹500 at noon lowers the rest of the month's daily amount right away. Users read "a day" as
     today's allowance, so this looks wrong.
   - Direction: show "₹789 a day" from the start of today, plus "₹289 left for today".

4. **[FLAW-004] [Low] Budgets and safe to spend don't know about each other**
   - What happens: Food has ₹300 left in its budget while the line says ₹789 a day. Both are right, but they read as a
     contradiction.
   - Direction: leave both, and when a budget is NEAR or OVER, add "Food is nearly used up" under the line.

5. **[Info] Edge cases that held up**
   - A custom month counts due dates in both calendar months it spans.
   - A rule on the 31st falls on the last day of shorter months.
   - A rule starting next month, a paused one, a deleted one, or an income rule adds no bill.
   - A skipped month counts as made.
   - On the last day: "today", the whole remainder.
   - The amount is rounded down to whole rupees, and never below zero.
   - Never stored (rule 8.5); money in paise.

## Decisions needed from you
- **#1:** match an early payment to its rule (recommended): link it automatically when the amount is equal and the
  payee matches, otherwise ask in the Detected inbox?
- **#2:** (a) count expected income from income rules, (b) suggest the salary day as the month start, or (c) both
  (recommended: c)?
- **#3, #4:** "left for today", and a note when a budget is nearly used up (recommended)?
