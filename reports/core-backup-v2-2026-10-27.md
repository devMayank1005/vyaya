# Findings: core/backup format v2: 2026-10-27

**Scope:** `597557a` (v2: line-based, checksum, shared limits, BOM/CRLF, duplicate keys, future
timestamps, v1 migration) and the SHA-256 in `23696d2`.

**Method:**
- **Break phase:** forged checksums, reordered and extra lines, escapes, a wrong clock, lines at the limit,
  and memory attacks within the limits. The probes were temporary.
- **Simulation:** `./gradlew :core:simulation:jvmTest -Psim.backup.users=2000 -Psim.backup.big=true`:
  - 2,000 users, with 20,000 corrupted files;
  - large datasets **streamed through a real file**, as the app will do, under a 512 MB heap.

**Status:** reported only. **Nothing fixed.**

## Simulation summary (v1 → v2)

| Check | v1 | v2 |
|---|---|---|
| Round trips identical (2,000 users) | 2,000 | **2,000** |
| Corrupted files accepted **with changed data** (of 20,000) | **577** | **0** |
| Corrupted files rejected | 19,008 | 19,885 |
| Accepted with identical data (whitespace or newline after the end) | 415 | 115 |
| Crashes | 0 | **0** |
| 100k entries | 62 M chars, import 2.4 s | 46 MB file, read 1.4 s |
| 150k entries | **written but can't be restored** | restorable |
| 200k entries, 512 MB heap | **out of memory** | **92 MB file, write 1.5 s, read 3.1 s, fidelity ok, about 457 MB heap used** |

The "400k" run measured the same data as 200k: the shopkeeper persona caps at about 200,000 entries. So
**the upper bound is still unmeasured.**

## Findings

1. **[SEC-001] [Medium] Someone who edits the file can just recompute the checksum**
   - How to reproduce: change the ledger amount to ₹99,999, recompute the SHA-256 in the end line →
     accepted.
   - Why it matters: the checksum stops accidents and casual edits, not someone deliberately tampering.
     A tampered backup restores as genuine.
   - Direction (not a fix): the encrypted `.vyaya` format already agreed for "before release" (AES-GCM)
     also provides authenticity. Until then, the UI must not call backups "verified".

2. **[PEN-002] [Medium] A crafted file within the limits can exhaust memory before it's rejected**
   - How to reproduce: 3,000 entry lines with 60 KB notes (180 MB, every line under the 64 KB limit)
     → about 433 of 512 MB in use **before** validation rejects the notes (limit 500 characters).
     About 3,500 lines would run out of memory.
   - Why it matters: per-record limits are only checked after the whole file has been read, and the
     64 KB line limit is about 100 times what a real line needs (around 1 KB).
   - Direction: validate each line as it's read and fail fast, and/or a much lower line limit (for
     example 8 KB, which needs a split cap) plus a total-size cap.

3. **[PEN-003] [Medium] 200,000 real entries leave little memory to spare**
   - How to reproduce: about 457 of 512 MB in use while reading 200k entries. The reader holds every
     record twice: as file classes and as domain records.
   - Why it matters: low-end phones often give apps 256 MB or less. `MAX_RECORDS` (2 M) is far beyond
     what fits in memory, so the limits don't reflect memory.
   - Direction: convert each line to a record straight away and drop the file object. Later, stream
     straight into the database (`core/data`). Measure on a real low-end device.

4. **[FLAW-004] [Medium] A phone with its clock set behind can't restore a valid backup**
   - How to reproduce: the restoring phone's clock is 3 days behind → "This backup says it was made in
     the future", and the restore is refused.
   - Why it matters: this is a side effect of the SEC-005 fix. The user is stuck until they correct the
     clock, and the message doesn't tell them to.
   - Direction: tell the user to check the date and time. Possibly allow restore after an explicit
     confirmation.

5. **[FLAW-005] [Low] One blank line in the middle breaks the file**
   - How to reproduce: a blank line between records → "Line 4 is damaged".
   - Why it matters: a tool that reflows or edits the text breaks the backup.
   - Direction: probably fine, since the checksum would reject an edited file anyway. Decide whether the
     message should say "edited".

6. **[FLAW-006] [Low] A failed export leaves a partial file**
   - Where: `BackupCodec.write`
   - What happens: on `EncodeResult.Failure`, lines already written stay in the sink.
   - Direction: the Android and iOS adapters must write to a temporary file and rename it only on
     success. **→ carry to the adapters**

7. **[FLAW-007] [Low] Old large v1 files still load as one string**
   - What happens: v1 files near the 64 M-character limit are read whole, the PEN-002 risk from before.
   - Why it matters: only backups made before v2 are affected.

8. **[Info] Things that held up**
   - Reordered record lines with a recomputed checksum are accepted and give identical data.
   - A `"t"` key written with escapes is understood.
   - `{"t":"end"}` inside a string value doesn't end the file.
   - Spaces after the end line are ignored.
   - Two end lines, lines after the end, record types in the wrong case, negative counts, an uppercase
     checksum and unknown settings fields are all rejected.

9. **[Info] The 115 accepted-unchanged corruptions are harmless**
   - They changed only whitespace or the final newline after the end line.

## Decisions needed from you
- **#1:** keep "encryption before release" as the answer to deliberate tampering?
- **#2, #3:** stricter per-line limits and validation as each line is read (both small), and measure on a
  low-end phone once the app exists?
- **#4:** what should happen when the restoring phone's clock is wrong?
