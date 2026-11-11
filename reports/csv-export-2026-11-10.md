# Findings: spreadsheet export (CSV) from the database: 2026-11-10

**Scope:** `ebb5066`, which covers:
- `CsvExport.start()` / `row()` (core/backup);
- `CsvStore` (core/data, keyset pages by time then id);
- `BackupFiles.exportCsv` and the CSV document picker (Android);
- Settings → Spreadsheet → Export spreadsheet (CSV).

**Method:**
- Code analysis.
- A query-plan probe (sqlite3 `EXPLAIN QUERY PLAN`; not committed).
- Simulation: `DatabaseBackupSimulationRunTest -Psim.backup.users=2000`.
- Size measure: `BigDatabaseBackupMeasureTest -Psim.backup.big=true -Psim.heap=256m`.
- The full local gate is green.

**Status:** reported only. **Nothing fixed.**

## Simulation and measure

| Check | Result |
|---|---|
| Spreadsheet from the database = `CsvExport.export(snapshot)` (2,000 users, 37-row pages) | **2,000 / 2,000** byte for byte |
| Backup export, merge, replace (same run) | 2,000 / 2,000 each; 10,000 damaged files: 0 changed while refused, 0 crashed |
| 1,234 entries over 40 distinct times, 7-row pages | identical file; ties across pages neither lost nor repeated |
| 10,000 entries (256 MB heap) | csv 0.23 s, 1 MB, heap peak ~52 MB |
| 100,000 entries | csv **12.5 s**, 11 MB, heap peak ~25 MB |
| 200,000 entries | csv **48.0 s**, 22 MB, heap peak ~52 MB (backup export of the same data: 3.0 s) |

## Findings

1. **[SIM-001] [High] The spreadsheet export takes time that grows with the square of the entries**
   - Where: `EntryDao.pageByTime`: `WHERE deleted = 0 AND (occurredAtMs > :ms OR (occurredAtMs = :ms AND id > :id)) ORDER BY occurredAtMs, id LIMIT :n`.
   - How to reproduce: the measure above. 10× the entries (10k → 100k) takes 54× the time, and 200,000 entries take
     48 s on a Mac. A low-end phone would likely take several minutes.
   - What happens: the query plan is `SEARCH USING INDEX (occurredAtMs>?)` then `USE TEMP B-TREE FOR LAST TERM OF
     ORDER BY`. Each 500-row page sorts every remaining row to find its next 500.
   - Why it matters: a shopkeeper with years of entries waits minutes with "Writing the spreadsheet…". All that time
     the read transaction stays open (see #6).
   - Direction (not a fix): an index on `(occurredAtMs, id)` and a row-value condition `(occurredAtMs, id) > (:ms, :id)`.
     The probe's plan becomes `SEARCH USING INDEX j ((occurredAtMs,id)>(?,?))` with no sort.

2. **[DATA-002] [Medium] A failure part-way leaves a half-written spreadsheet in the user's folder**
   - Where: `BackupFiles.exportCsv` writes straight into the document the user picked. The backup export goes
     through a temporary file; this one doesn't.
   - How to reproduce (by analysis): the storage provider fails (Drive offline, SD card removed, storage full), or the
     app is killed mid-write (process death).
   - What happens: the screen says "The spreadsheet couldn't be written", but `vyaya-entries-<date>.csv` stays,
     cut off at some row. After process death there is no message at all. A cut-off CSV looks complete in a
     spreadsheet, so its totals are silently wrong.
   - Direction: write to a temporary file and copy when done, as backup does. Or delete the picked document on
     failure (`DocumentsContract.deleteDocument`).

3. **[PEN-003] [Medium] One damaged entry row crashes the app when exporting**
   - Where: `CsvStore` → `EntryStore.entryOf` throws `IllegalStateException` for a row that doesn't read back.
     `exportCsv` catches only `IOException`, and the call runs in `lifecycleScope`.
   - What happens (by analysis): the app crashes on every export attempt, which is the moment the user wants their
     data out. This is the same root cause as DATA-006 (core-data report, open).
   - Direction: catch it and say "N entries couldn't be read and were left out", or refuse with a message. Never
     crash.

4. **[SEC-004] [Low] On a phone without a screen lock, a plain-text file leaves with no check**
   - Where: `BiometricAuthenticator.authenticate` returns true when the device isn't secure, and `confirmThen` then
     exports. Backup export behaves the same, but a backup can have a password and a spreadsheet can't.
   - Why it matters: anyone holding the unlocked phone gets every payee, amount and UPI reference in a file they can
     send anywhere. Settings says "No password", but only as small print.
   - Direction: when the phone has no lock, show a confirmation that says plainly what the file contains.

5. **[FLAW-005] [Low] Expense and income amounts have the same sign**
   - What happens: `amount` is positive for both, so `SUM(D:D)` in Excel adds income to spending, a meaningless
     number. Users will try it first.
   - Direction: a signed amount (spending negative), or separate `spent` and `received` columns. This needs your
     decision: the column format is a promise once released.

6. **[FLAW-006] [Low] Long read transaction while writing to a slow provider**
   - Where: `CsvStore.export` holds one read transaction for the whole file and writes each page to the sink inside
     it. When the sink is a cloud provider, network time adds up.
   - What happens: SQLite's WAL can't be checkpointed meanwhile, so it grows. Captures still write. With #1 this can
     last minutes.
   - Direction: fixing #1 makes it short. Writing to a local temporary file (#2) also takes the network out of the
     transaction.

7. **[FLAW-007] [Low] Transfers and lent/borrowed aren't fully in the spreadsheet**
   - What happens: a transfer row shows only the account it left (there is no `to_account` column). The People ledger
     (lent, borrowed, pay-backs) isn't exported at all.
   - Direction: add a `to_account` column now, while the format is still free. Export the ledger as a second file or
     a section, as you decide.

8. **[Info] Rows are in time order, but each date and time is shown in the entry's own zone**
   - A traveller's rows can look out of order (IST 18:35 before a UTC 13:10 that happened later). This is correct
     and as designed, but confusing.
   - Direction: an extra `utc_offset` column if users ask.

9. **[SEC-009] [Info] Formula guard**
   - Every cell starting with `=`, `+`, `-`, `@`, a tab or a carriage return gets a leading `'`. That covers payee,
     note, tags, category and account names, and the first name of a split (test
     `catalog_sec_010_category_and_account_names_cannot_run_as_formulas`).
   - A formula later in a cell (a second tag `=cmd`, a split's second category) is text, not a formula. Leading
     spaces and full-width `＝` aren't run by Excel or Sheets.
   - Right-to-left override and lookalike characters pass through as text, by choice.

10. **[Info] Things that held up**
    - Byte-identical to the reference export for all 2,000 simulated users.
    - Memory stays flat (≤ 52 MB at 200,000 entries in a 256 MB heap). The file is 22 MB at 200,000 entries.
    - One read transaction: a capture arriving mid-export is in the file whole, or not at all.
    - Deleted entries are left out; deleted categories and accounts still name their old entries.
    - The export goes through the phone's own check when the phone has a lock. The picker suggests
      `vyaya-entries-<date>.csv` as `text/csv`.

## Decisions needed from you
- **#1:** composite index plus row-value pages (recommended; fix before the first release)?
- **#2:** temporary file then copy (recommended; the same as backup)?
- **#3:** leave damaged rows out with a count (recommended), or refuse the whole export?
- **#4:** a plain confirmation when the phone has no lock (recommended)?
- **#5:** signed amount column, or separate spent and received columns (recommended: keep `amount` positive and add a
  `signed_amount` column)?
- **#7:** add `to_account` now (recommended); ledger as a second file later?

## Resolution (2026-11-11)

Your decision: go with the recommendations. Each fix began with a failing test.

| # | Status | Fix |
|---|---|---|
| 1 SIM-001 | ✅ Fixed | Index `(occurredAtMs, id)` (replaces the `occurredAtMs` one) and pages by `(occurredAtMs, id) > (:ms, :id)`. Test `csv_sim_001_*` checks the query plan has no temp B-tree. Measure (256 MB heap): 100,000 entries 12.5 s → **1.3 s**, 200,000 entries 48.0 s → **4.1 s**, heap peak ~51 MB |
| 2 DATA-002 | ✅ Fixed | The spreadsheet is written to a temporary file and copied to the picked file only when complete. On any failure the picked document is deleted and the screen says "Nothing was saved". Test `csv_data_002_*` |
| 3 PEN-003 | ✅ Fixed | A row that doesn't read back is left out and counted (`CsvStore.Written(rows, skipped)`); the message says how many. Test `csv_pen_003_*` (store and message) |
| 4 SEC-004 | ✅ Fixed | With no screen lock, Export asks first ("Export without a check?"), saying what the file holds. Test `csv_sec_004_*` |
| 5 FLAW-005 | ✅ Fixed | New `signed_amount` column: spending negative, income positive, transfers and money put aside blank, so SUM is what's left. It is a number, never neutralised. `amount` stays positive |
| 6 FLAW-006 | ✅ Fixed by #1 and #2 | The read transaction is short and writes only to a local file |
| 7 FLAW-007 | ✅ Fixed (to_account) | New `to_account` column. The People ledger as a second file is left for later |

Columns now: `date,time,type,amount,signed_amount,category,account,to_account,payee,note,tags,source,upi_ref,id`.

Note from the measure: restoring 200,000 entries went from 5.1 s to 6.1 s. The new index is wider, so each write costs a
little more. Backup export is unchanged (3.0 s).

TDD note: the database tests (#1, #3) and the CSV format tests (#5, #7) were seen failing before the fix. For the
Android tests (#2, #4), the red was a compile failure; their test-level red wasn't observed separately.
