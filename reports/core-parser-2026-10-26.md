# Findings: core/parser (MessageParser, rules, extractors, sender classifier): 2026-10-26

**Scope:** commits `ee5cbc8` to `db04aa0`. The parser turns one SMS, notification or OCR text into a
transaction, or says why it isn't one.

**Method:**
- **Break phase:** spoofed and lookalike senders, crafted bodies, invisible characters, semantic traps,
  and worst-case regex timing within the 2,000-character cap. The probe code was temporary and is not
  committed.
- **Simulation:** `core/simulation`, run as `./gradlew :core:simulation:jvmTest -Psim.users=10000`.
  - **10,000 synthetic users**, each with 30 days of messages: 2,770,083 messages in total.
  - Personas: student 3,050, salaried 2,983, heavy UPI 1,498, Hindi 982, scam target 512,
    shopkeeper 484, traveller 491.
  - Chaos injected: NBSP, Devanagari digits, line breaks, uppercase, tricky and special-character
    payee names, huge and tiny amounts, split SMS, Hindi-language text, foreign currency, and 15–40 %
    noise.

**Status:** reported only. **Nothing fixed.** Each point is waiting for discussion.

**Out of scope by design (later modules):**
- trust decisions (`core/filters`)
- de-duplication (`core/dedup`)
- transfer and categorisation logic (`core/engine`)
- Android/iOS adapters

Findings that land there are marked **→ carry to …**.

## Simulation summary

| Group | Samples | Recognised | All fields right |
|---|---:|---:|---:|
| All 20 templates, no chaos | 1,866,983 | 100 % | 100 % |
| NBSP / Devanagari digits / line breaks / uppercase | 226,901 | 100 % | 100 % |
| Huge (₹50 lakh–₹99 crore) / tiny (₹1) amounts | 102,232 | 100 % | 100 % |
| Tricky payee names ("Cafe On Road") | 50,743 | 100 % | **82.9 %** |
| Special-character names ("A.B. Traders", "(Main)") | 50,949 | 100 % | **88.5 %** |
| Split SMS, first half only | 23,372 | **89.0 %** | **5.6 %** |
| Hindi-language notifications | 21,934 | **0 %** | 0 % |
| Foreign currency card spends | 13,229 | **0 %** | 0 % |

| Noise | Samples | Parsed as a transaction |
|---|---:|---:|
| OTP / promo / failed / collect request | 260,033 | 0 % |
| Autopay reminder | 65,058 | **100 %** (→ filters) |
| Fake "₹25,000 credited" scam | 65,277 | **100 %** (→ filters) |
| Second halves of split SMS | 23,372 | 0 % |

**Speed (JVM):**
- p50 14 µs, p95 52 µs, p99 70 µs per message.
- One 54 ms spike, which looks like JIT warm-up or garbage collection.
- The worst crafted 2,000-character message took 13.7 ms.

## Findings

1. **[SEC-001] [High] The parser trusts any sender, and bank-format rules match from anyone**
   - Where: `MessageParser.parse`; `ParserRules` default rules have no `senderPattern`
   - How to reproduce:
     - A notification from `com.evil.lookalike` saying "₹50,000 received from Rahul Sharma" →
       `TXN CREDIT ₹50,000`.
     - An SMS from `+919812345678` in SBI's exact format → `TXN DEBIT ₹5,000`, rule `sbi-upi-debit`.
     - The BHIM rule fires for any app's notification.
   - What happens: any app or any phone number can create money entries. The sender kind is attached,
     but nothing uses it yet.
   - Why it matters: fake money appears in "what's left". This is the main scam vector.
   - Direction (not a fix): filters must allowlist notification packages and SMS headers. Decide whether
     rules should also be sender-scoped. **→ carry to core/filters**

2. **[DATA-002] [High] Hindi-language notifications are never read**
   - How to reproduce: "₹120 का भुगतान Swiggy को सफल रहा" and "Rahul से ₹500 प्राप्त हुए" → `Unrecognized`
     (21,934 of 21,934 simulated samples lost).
   - Why it matters: users whose phone language is Hindi lose every app-notified payment.
   - Direction: add Hindi keyword sets (भुगतान, प्राप्त, भेजे, डेबिट, क्रेडिट), and decide which other
     languages matter.

3. **[DATA-003] [High] Invisible characters silently change the amount**
   - How to reproduce:
     - `"₹1​20 paid to Swiggy"` (zero-width space) → **₹1**
     - `"₹1,­200 paid to Swiggy"` (soft hyphen) → **₹1**
     - A right-to-left mark around the amount → missed
   - Why it matters: the amount is wrong with no warning. Copy-paste, OCR or a crafted message can do this.
   - Direction: the normaliser could strip format characters (Unicode category Cf) before parsing.

4. **[FLAW-004] [High] Failed, future and reminder messages parse as real money**
   - How to reproduce:
     - "Txn of Rs.500.00 debited from A/c XX1234 failed. Amount will be reversed" → DEBIT ₹500
     - "Refund of Rs.500 initiated. It will be credited … in 5-7 days" → CREDIT ₹500 now
     - Autopay reminders → 100 % DEBIT; fake credits → 100 % CREDIT
   - Why it matters: these are the exact cases the product promises to ignore.
   - Direction: decide which layer owns failed, future and reminder detection. **→ carry to core/filters**
     (these simulation numbers are its test targets).

5. **[DATA-005] [High] Transfers count as income or spending**
   - How to reproduce:
     - "Payment of Rs.5,000.00 received towards your HDFC Bank Credit Card" → **CREDIT ₹5,000 (income)**
     - "Rs.10,000 debited from A/c XX1234 to A/c XX5678 (self)" → **DEBIT ₹10,000 (spending)**
     - ATM withdrawals are DEBITs
   - Why it matters: card bills get counted twice, and self-transfers and cash withdrawals distort "what's left".
   - Direction: the parser could emit a `TRANSFER` hint (card bill, self, ATM). **→ carry to core/engine**

6. **[SEC-006] [Medium] The TRAI category suffix is thrown away**
   - How to reproduce: `VK-PHONPE-P` (promotional) and `VK-PHONPE-T` both become `Header(PHONPE)`.
     "Rs.100 cashback credited to your PhonePe wallet" from a `-P` header → CREDIT ₹100.
   - Why it matters: the promotional/transactional marker is the cheapest promo filter available, and it's lost.
   - Direction: keep the suffix in `SenderKind.Header`.

7. **[SEC-007] [Medium] Lookalike headers look the same as real ones**
   - How to reproduce: `VM-HDFCBNK-S` → `Header(HDFCBNK)`, accepted like `HDFCBK`. Any 3–9 character
     entity is accepted.
   - Why it matters: if filters trust "any header", a lookalike passes.
   - Direction: a registry of known bank and app entities. **→ carry to core/filters**

8. **[DATA-008] [Medium] Payee names are cut at keywords and punctuation**
   - How to reproduce: "Hotel From Kerala" → "Hotel", "Pay And Go Mart" → "Pay", "A.B. Traders" →
     "A.B", "Shree Ganesh (Main)" → "Shree Ganesh". The simulation found 17.0 % and 11.4 % of these
     names wrong.
   - Why it matters: merchant memory and auto-categories key on the payee, so different shops merge
     ("Cafe …").
   - Direction: per-template end markers rather than generic terminator words. Needs real samples.

9. **[DATA-009] [Medium] Split SMS lose their fields**
   - How to reproduce: when only the first 70 characters arrive, 11 % of payments are missed and 94 % have
     wrong or missing fields (payee 61.7 %, ref 78.1 %). The second half never becomes a transaction,
     which is good.
   - Why it matters: lost UPI refs weaken de-duplication.
   - Direction: the Android SMS receiver must join multi-part messages before parsing. Add a test for
     it there. **→ carry to the Android adapter**

10. **[DATA-010] [Medium] Phone numbers are read as UPI references**
    - How to reproduce:
      - "credited … by UPI 919876543210@paytm" → ref `919876543210`, party "UPI 919876543210@paytm"
      - "Ref 918001234567 is our helpline" → ref `918001234567`
    - Why it matters: a wrong ref can make de-duplication merge two different payments into one.
    - Direction: don't take digits followed by `@`, and decide which keywords count as a ref.

11. **[DATA-011] [Medium] Foreign-currency spends are missed**
    - How to reproduce: "Spent USD 25.00 Axis Bank Card…" → `Unrecognized` (13,229 simulated samples
      lost). When the INR equivalent is in the text it's read correctly (₹2,100.50).
    - Why it matters: travellers' card spends vanish from the totals.
    - Direction: product decision. Ask for the INR amount, log as "needs amount", or ignore.

12. **[DATA-012] [Medium] The date in the SMS is ignored**
    - How to reproduce: "debited … on 31-10-26 23:58" received at 00:01 on 1 Nov → `occurredAt` is
      1 Nov, so the entry lands in the wrong month.
    - Why it matters: month-end Saved and budgets shift. The 30-day import is the bigger risk.
    - Direction: decide whether the message's own date overrides arrival time. The import must pass the
      SMS's inbox timestamp. **→ carry to the adapters**

13. **[FLAW-013] [Low] A bad rules file breaks all parsing**
    - Where: `ParserRules.default` is lazy and throws on bad data
    - What happens: harmless today (the rules are built in), but once rules can be updated, one bad
      update stops every message being parsed.
    - Direction: fall back to the bundled rules when an update fails to load.

14. **[PEN-014] [Low] Crafted messages cost about 125 times a normal one**
    - How to reproduce: the worst 2,000-character inputs take 13.7 ms (SBI rule) and 13.1 ms (card rule),
      against 0.11 ms for a typical message. No catastrophic backtracking was found.
    - Why it matters: on a low-end phone, a flood of crafted SMS costs about 0.1 s each.
    - Direction: tighter patterns, or a lower cap for SMS (real SMS are under 500 characters).

15. **[FLAW-015] [Low] Lookalike glyphs are missed**
    - How to reproduce: "Rѕ.120" (Cyrillic ѕ) and superscript digits "₹¹²⁰" → `Unrecognized`
    - Why it matters: mostly an OCR risk.

16. **[FLAW-016] [Low] The title/body boundary is lost**
    - What happens: the normaliser turns line breaks into spaces, which caused the "Payment sent / You
      paid" bug (fixed). Other title/body pairs may hide more of the same.
    - Direction: decide how adapters pass a notification's title and body.

17. **[SIM-017] [Info] Speed**
    - 2.77 M messages took about 70 s on a desktop-class JVM.
    - Phone numbers are unknown until there's an on-device benchmark.

18. **[SIM-018] [Info] The earlier fixes hold at scale**
    - Every chaos type that only changes formatting parses at 100 % (NBSP, Devanagari digits, line
      breaks, uppercase, huge and tiny amounts).
    - Parser-owned noise produced 0 false transactions in 260,033 messages.

19. **[SIM-019] [Info] The simulation's limits**
    - Everything is synthetic: the templates are my models of real formats, amounts are uniform between
      ₹1 and ₹2 lakh, there's one date format, and payee names come from a fixed list.
    - So "100 %" means the parser matches my templates, not that it matches real bank messages.
    - Your masked real samples are what turn this into a real measurement.

20. **[FLAW-020] [Info] Package names need checking on a real phone**
    - Navi, MobiKwik, Freecharge and Amazon Pay need checking. Amazon Pay UPI notifications may come from
      an app other than Amazon Shopping.

21. **[DATA-021] [Info] Notifications have no UPI ref, and names differ between sources**
    - What happens: the app says "Swiggy" while the bank says "swiggy@icici" or "SWIGGY".
    - Why it matters: de-duplication can't rely on matching the ref or the name. **→ carry to core/dedup**

## Decisions needed from you
- **#1, #4, #6, #7:** confirm these go to `core/filters` (next module), with these numbers as its targets.
- **#2:** which languages besides English and Hindi?
- **#3, #10, #15:** fix in the parser now?
- **#5:** should the parser tag transfers (card bill, self, ATM), or leave it all to the engine?
- **#8:** fix the payee cut-offs now, or wait for real samples?
- **#11:** foreign currency: ask the user, log as "needs amount", or ignore?
- **#12:** should the message's own date override the time it arrived?
- **#13, #14, #16:** fix now or later?
