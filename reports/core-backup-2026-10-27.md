# Findings: core/backup (backup format v1, restore, CSV export): 2026-10-27

**Scope:** `7e1c842` (backup v1, restore) and `aa43407` (CSV export), plus the domain records in `9a28d00`.

**Method:**
- **Break phase:** deep nesting, duplicate JSON keys, extreme amounts, future timestamps, odd Unicode,
  oversized metadata, odd references, BOM, CSV formula variants. The probe code was temporary.
- **Simulation:** `./gradlew :core:simulation:jvmTest -Psim.backup.users=2000`, which runs 2,000
  synthetic users (student and salaried, up to 400 entries each):
  - export → import → export for every user;
  - **20,000 corrupted files** (random character replacement, insertion, deletion, truncation).
- **Size test:** shopkeeper-style data of 10k–200k entries, under a **512 MB heap**, which is in the
  range of an Android app's memory limit.

**Status:** reported only. **Nothing fixed.** Each point is waiting for discussion.

## Simulation summary

| Check | Result |
|---|---|
| Export → import → export, 2,000 users | **2,000 / 2,000 identical** |
| Corrupted files, 20,000 | 19,008 rejected · 415 harmless (whitespace) · **577 accepted with changed data** · **0 crashes** |
| Deep nesting (10k and 100k levels) | rejected, no crash |

| Entries | JSON size | Export | Import | CSV | Fits 512 MB? |
|---:|---:|---:|---:|---:|---|
| 10,000 | 6 M chars | 0.3 s | 1.0 s | 0.1 s | yes |
| 50,000 | 31 M chars | 0.7 s | 1.7 s | 0.4 s | yes |
| 100,000 | 62 M chars | 1.7 s | 2.4 s | 0.8 s | yes, just under the 64 M import limit |
| 150,000 | 94 M chars | 2.1 s | **refused: over the import limit** | 1.2 s | export yes, import no |
| 200,000 | – | **out of memory** | – | – | **no** |

These times are on a desktop JVM. Expect a mid-range phone to be several times slower.

## Findings

1. **[DATA-001] [High] The app writes backups it can't restore**
   - Where: `BackupCodec.encode` has no size limit, but `decode` refuses anything over `MAX_CHARS`
     (64 M characters)
   - How to reproduce: 150,000 entries export to 94 M characters, and the same app refuses to import
     the file.
   - What happens: a heavy user (a shopkeeper after about 7–8 years) makes backups that look fine but can
     never be restored.
   - Why it matters: the user finds out only when they need the backup.
   - Direction (not a fix): one limit for both sides, compact (not pretty-printed) JSON, which is about
     45 % smaller, and/or streaming.

2. **[PEN-002] [High] Exporting a 10-year backup runs out of memory**
   - How to reproduce: 200,000 entries under a 512 MB heap → `OutOfMemoryError` during encode. The
     whole file is built as one string.
   - Why it matters: the app crashes during export on the users with the most data to lose.
   - Direction: stream the JSON to the file, and test against the real heap limits of low-end phones.

3. **[DATA-003] [High] A corrupted file can restore with changed amounts and no warning**
   - How to reproduce: 577 of 20,000 randomly corrupted files decoded **successfully with different data**.
     Examples: ₹41,919.54 became ₹4,19,219.54; "Sharma & Sons" became "SZarma & Sons".
   - Why it matters: a bad disk, a partial download or a hand edit can silently change money.
   - Direction: a checksum over the content (SHA-256 in the file), verified before restore.

4. **[DATA-004] [High] Extreme amounts are accepted and later crash the totals**
   - How to reproduce:
     - `amountPaise` = 9,223,372,036,854,775,807 is accepted.
     - Adding two large accepted amounts throws `ArithmeticException: Money overflow`.
     - Amounts above `Money.MAX` (₹100 crore) are accepted by the domain records, even though the parser
       caps them there.
   - Why it matters: one edited backup can crash every screen that sums money.
   - Direction: cap the amount in the records themselves, using the same `Money.MAX` everywhere.

5. **[SEC-005] [Medium] A future `updatedAt` wins every merge**
   - How to reproduce: a backup person with `updatedAt` = 2099-12-31 replaces the user's edit from today,
     and will win every merge after that too.
   - Why it matters: a backup from a phone with a wrong clock, or a crafted one, permanently overrides
     local edits and can bring back deleted records.
   - Direction: refuse or clamp `updatedAt` values later than the export time (`exportedAt`).

6. **[FLAW-006] [Medium] Duplicate JSON keys are silently accepted (the last one wins)**
   - How to reproduce: `"amountPaise": 12050, "amountPaise": 99999999` → 99,999,999 is used. It was only
     caught here because the splits stopped adding up.
   - Why it matters: an edited file can hide a value change.
   - Direction: reject duplicate keys.

7. **[FLAW-007] [Medium] A file with a byte-order mark at the start is refused**
   - How to reproduce: `"﻿" + valid backup` → "not valid JSON".
   - Why it matters: saving the file in Windows Notepad, and some cloud drives, adds one, so the user's
     valid backup gets refused.
   - Direction: strip a leading BOM before parsing.

8. **[FLAW-008] [Medium] Merge restore keeps the current settings**
   - What happens: restoring a backup onto a fresh install with MERGE keeps the default settings and loses
     the backup's month start day, time zone and alert choice.
   - Why it matters: the user's month boundaries change without notice after moving to a new phone.
   - Direction: product decision. For example, the UI offers REPLACE on a fresh install, or merge takes
     the backup's settings when current ones are untouched defaults.

9. **[SEC-009] [Medium] Backups are plain, unencrypted JSON**
   - What happens: every transaction, payee and friend's name is readable by anyone who gets the file,
     including from Drive. Password encryption (`plan.md` Part D) isn't built yet.
   - Why it matters: this is the most sensitive file the app produces, and users are told to store it in
     the cloud.
   - Direction: build the optional encrypted `.vyaya` format before the first release.

10. **[SEC-010] [Low] Invisible and control characters pass validation**
    - How to reproduce: a payee with a right-to-left override ("‮yggiws" shows as "swiggy") and a
      note with a null byte are both accepted.
    - Why it matters: names can be made to look like someone else in the UI and the CSV.
    - Direction: strip format and control characters from text fields (the same family as parser DATA-003).

11. **[FLAW-011] [Low] File metadata is unbounded and unchecked**
    - How to reproduce: a 1 MB `appVersion` and `createdOn = "<script>…"` are accepted into `ExportMeta`.
    - Why it matters: a restore screen that displays them could be abused, and the size is wasted.
    - Direction: limit length and allowed characters.

12. **[FLAW-012] [Low] Logically odd records are accepted**
    - What happens:
      - an entry linked to itself (a refund chain loop);
      - a transfer whose source and destination are the same account;
      - a budget on a deleted category;
      - two merchant rules for the same payee pointing at different categories (ambiguous auto-category);
      - `createdAt` later than `updatedAt`.
    - Direction: decide which of these the records should forbid.

13. **[DATA-013] [Low] Time-zone aliases are kept as written**
    - What happens: `Asia/Calcutta` stays `Asia/Calcutta`. Combined with schedule DATA-004 (aliases don't
      compare as equal), equal moments can compare as different after a cross-device restore.

14. **[FLAW-014] [Info] Pretty-printed JSON is large**
    - What happens: about 620 characters per entry. Compact JSON would be roughly 45 % smaller, which
      helps with #1 and #2. The trade-off is how readable the file is to a human.

15. **[FLAW-015] [Info] Import time on a phone**
    - What happens: 100k entries import in 2.4 s on a desktop, so likely 10–25 s on a phone.
    - Why it matters: restore needs a progress screen and must run off the main thread.

16. **[SIM-016] [Info] CSV formula variants that are safe as they are**
    - A full-width "＝HYPERLINK(…)" and a DDE-style "cmd|' /C calc'!A0" with no leading `=` are left as
      they are. Spreadsheets don't run either.
    - All real formula starts (`=`, `+`, `-`, `@`, tab, CR) are neutralised (tested).

17. **[SIM-017] [Info] What held up**
    - Every one of 2,000 round trips was identical, byte for byte.
    - There were 0 crashes across 20,000 corrupted files and deeply nested JSON.
    - Unknown time zones and out-of-range dates are reported rather than crashing (SEC-008 and PEN-009
      are fixed).

18. **[SIM-018] [Info] Not built yet, so not tested**
    - the receipts `.zip` variant;
    - scheduled automatic backups;
    - the Android and iOS file pickers and OS backup.

## Decisions needed from you
- **#1, #2, #14:** compact JSON plus streaming, and/or a hard entry limit? This has to be decided
  before real users have years of data.
- **#3:** add a checksum to the file (a schema change, so v1 → v2 with a migration)?
- **#4, #5, #6, #7:** fix now? All are small, test-first fixes.
- **#8:** which settings should a merge restore keep?
- **#9:** build encrypted backups before the first release?
- **#10, #11, #12:** fix now or later?

## Resolution (2026-10-27)

Decisions by the user: compact + streaming, a checksum (format v2), refuse future timestamps, reject
duplicate keys and accept a BOM, and encryption before release. Every fix started with a failing test.

| # | Status | Fix |
|---|---|---|
| 1 DATA-001 | ✅ Fixed `597557a` | Writer and reader share `BackupLimits`; the app refuses to write what it can't restore |
| 2 PEN-002 | ✅ Fixed `597557a` | Line-by-line write and read; 200k entries now fit a 512 MB heap (little margin left, see v2 report PEN-003) |
| 3 DATA-003 | ✅ Fixed `597557a` | SHA-256 end line; silent changes went from 577 to 0 of 20,000 corruptions (integrity only, see v2 report SEC-001) |
| 4 DATA-004 | ⏸ Not fixed (your choice) | Amounts above ₹100 crore are still accepted |
| 5 SEC-005 | ✅ Fixed `597557a` | Records newer than the export, or an export from the future, are refused (side effect: v2 report FLAW-004) |
| 6 FLAW-006 | ✅ Fixed `597557a` | Duplicate keys refused in v1 and v2, comparing keys after unescaping |
| 7 FLAW-007 | ✅ Fixed `597557a` | A leading BOM and CRLF line endings are accepted |
| 8 FLAW-008 | ⏳ Open | Which settings a merge keeps |
| 9 SEC-009 | 📅 Agreed: before release | Encrypted backups, built with the app modules (needs platform crypto) |
| 10–12 | ⏳ Open | Awaiting decision |
| 14 FLAW-014 | ✅ Fixed `597557a` | Compact JSON lines (100k entries: 62 M chars → 46 MB) |
