# Findings: branch review before merging PR #3: 2026-11-06

**Scope:** PR #3 (`origin/main..f124812`), with most attention on what the 2026-11-04 review didn't cover:
- the database backup (`SnapshotStore`, `StreamingBackup`, `BackupDecoder`, `BackupStore`);
- the Android app (capture from notifications and SMS, the Detected inbox, the manifest);
- the fixes since.

**Method:**
- `/code-review high` on the PR;
- each finding checked against the code before it went in here.

**Also found:** CI hasn't run since `ce75b10`. GitHub's mergeability check for PR #3 is stuck at UNKNOWN, so
`pull_request` runs aren't created, and nothing since has been tested on CI. An `android` CI job was added in
`f124812`. The PR must show green CI before it merges.

## Findings

1. **[DATA-001] [High] A bank SMS without a reference is counted twice when it is read again by the import**
   - Where: `Capture.duplicateOf`. For two SMS-source entries without a UPI reference, "the same message read again"
     needs exactly the same instant.
   - The three ways one SMS is read all stamp it at different milliseconds:
     - the live receiver: the phone's time in `onReceive`;
     - the SMS app's notification: `postTime`;
     - the import: the inbox's `date`, written by the SMS app.
   - How to reproduce: a card SMS (no reference) arrives and is read live, then the user taps Import. A second entry
     appears. The same happens on the full build to a week of SMS-app notifications when SMS is then allowed (DATA-001
     of the Android report made those notifications come in).
   - Direction: an SMS read again matches the same type, amount and payee (or no payee on both) within 2 minutes.

2. **[DATA-002] [High] Opening the app deletes this month's recurring entries that are due later today, moved, or skipped**
   - Where: `ClockRepair` (DATA-008 of the 2026-11-04 review) drops this month's generated entries dated after now.
   - How to reproduce:
     - Catch-up makes rent at 12:00 on the 1st; at 09:00 the user edits it, and the next open deletes it (the edit is
       lost; catch-up makes the default again).
     - A rent moved to the 5th is deleted on the 2nd.
     - A "No" for a paused month (a deleted skip marker) is removed, so catch-up adds the entry the user declined.
   - Direction: drop this month's generated entries only when they were *made* after now (`createdAt`), which is what
     a clock that was ahead leaves behind.

3. **[DATA-003] [High] A merge restore of 1,000+ entries crashes on Android 8–10**
   - Where: `BackupStore.writeEntries`: `WHERE id IN (:ids)` with a whole batch (2,000 ids). The system SQLite before
     Android 11 allows 999 variables.
   - What happens: `SQLiteException` (too many SQL variables). It is not a refusal, so it escapes `restore()` as a
     crash. JVM tests use a driver with a higher limit.
   - Direction: look up existing entries in chunks of at most 500 ids.

4. **[DATA-004] [Medium] Confirming a review item can add a payment that was added meanwhile**
   - Where: `DetectedInbox.confirm` saves the draft without checking current entries again.
   - How to reproduce: an unverified app's notification goes to Review. The bank's SMS for the same payment is then
     Added. The user taps Add on the review card, and the payment is now twice.
   - Direction: on confirm, check the draft against current entries. A duplicate closes the item without adding.

5. **[DATA-005] [Medium] A UPI app's notification posted again after 2 minutes becomes a second entry**
   - Where: notifications are dated by `postTime`, which changes when an app updates its notification. With no payee,
     or after 2 minutes, it isn't the same payment any more.
   - Direction: date a notification by its own time (`Notification.when`, kept across updates) when it is plausible,
     so a re-post is the same message read again.

6. **[FLAW-006] [Medium] The listener check (FLAW-005) misses the case it was made for**
   - Where: `ListenerHealth` keeps "connected" in preferences. When a phone kills the app, no disconnect callback runs,
     so the flag stays true and the warning never shows. After a reboot it is true before the system binds.
   - Direction: "connected" in this process's memory only. A new process starts not connected, and if the system
     doesn't bind the listener shortly after the app opens, the card shows.

7. **[SEC-007] [Low] Every app's notification is read in full before it is dropped**
   - Where: `NotificationContent.of(sbn)` copies title, texts and whole chat histories for every notification, before
     the package check drops it. This is wasted work, and it contradicts "dropped unread".
   - Direction: check the package first, and read the content only for the apps that are read.

8. **[FLAW-008] [Low] `RecordLines.snapshotOf` builds and throws away a problem string per record**
   - The Reader checks against a sentinel export time. `SnapshotStore.load` is now used by tests and backup only
     (merge reads records without entries).
   - Direction: a Reader mode without export-time checks.

9. **[FLAW-009] [Low] Export pages with LIMIT/OFFSET (quadratic)**
   - 200k entries make 100 queries that skip about 10M rows in total.
   - Direction: keyset pages (`WHERE id > :last ORDER BY id LIMIT n`).

## Resolution (2026-11-06)

Your decision: go with the recommendations. Fix 1–7 and 9 before merging; 8 stays open (Low).

| # | Status | Commit | Fix |
|---|---|---|---|
| 1 DATA-001 | ✅ Fixed | `76f569b` | SMS-source entries without a reference match within 2 minutes with the same payee (or none). **Also found:** SMS-only parser rules (SBI, ICICI, card spends) didn't apply to the SMS app's notification; they do now (4 golden formats were read worse or not at all by the Play build) |
| 2 DATA-002 | ✅ Fixed | `f8cf49b` | Clock repair removes this month's generated entries only when both dated and made after now |
| 3 DATA-003 | ✅ Fixed | `0c76ee8` | Merge lookups in chunks of 500 ids. Robolectric's SQLite allows more than 999 variables, so the chunking is tested in core/data and a 2,500-entry merge on Android 10 end to end |
| 4 DATA-004 | ✅ Fixed | `00e619f` | `confirm()` checks the draft against current entries in one write transaction; a duplicate closes the item (`ConfirmResult.ALREADY_ADDED`) |
| 5 DATA-005 | ✅ Fixed | `84665d8` | Notifications dated by `Notification.when` when plausible, so an app's update is the same message |
| 6 FLAW-006 | ✅ Fixed | `0b6e8cb` | Listener state in process memory; rebind on open, checked again after 3 s |
| 7 SEC-007 | ✅ Fixed | `d323647` | Package checked before any content is read |
| 8 FLAW-008 | ⏳ Open (Low) | | Reader mode without export-time checks |
| 9 FLAW-009 | ✅ Fixed | `bc8da57` | Keyset paging |

**After the fixes:**
- Tests: 329 JVM, 320 iOS, 36 + 41 Android; coverage gate passing.
- Capture simulation (2,000 users): 0 doubled, 24 merged (unchanged), 0 noise, 0 false transfers.
- Engine simulation: 0 duplicates, 0 missing, 0 mismatches.
- Database backup simulation: 300 / 300 identical; damaged files never changed the database.
- 200k entries under 256 MB: export peak ~74 MB, restore ~76 MB.
