# Findings: Android capture (notifications, SMS, Detected inbox): 2026-11-04

**Scope:** `1e7e4ef`..`dbd2106`:
- the `androidApp` module, with `play` and `full` flavors;
- `NotificationReader` / `NotificationCapture` / `CaptureListener`;
- the full build's `SmsReceiver` and `SmsImport`;
- the Detected screen and the setup card;
- the manifest hardening.

**Method:**
- **Break phase:** code analysis, plus temporary Robolectric probes (not committed):
  - the full build with SMS refused;
  - 10 promotional and reminder notifications from a listed UPI app through the real pipeline.
- **Merged release manifests:** inspected for both flavors.
- **Simulation:** the adapters only turn notifications and SMS into the same `RawMessage`s the capture simulation
  already uses. That simulation (2,000 users, run earlier today) is unchanged: 0 doubled, 0 noise accepted.
- **Not run on a phone:** no device was connected.

**Status:** reported only. **Nothing fixed.**

## Test summary
- Android: 51 unit tests (play 24, full 27): reader, Robolectric capture with real notifications, SMS, Compose UI,
  main screen over a real database, manifest. All pass.
- Shared core: 320 JVM and 311 iOS tests, coverage gate passing.
- Release APKs (R8) of both flavors build, about 1.1 MB each, not debuggable.

## Findings

1. **[DATA-001] [High] The full build reads no bank SMS at all when the SMS permission is refused**
   - Where: `CaptureListener.smsAppsFor(context, Channel.READS_SMS)`. The full build skips the SMS app's notifications
     because it *can* read SMS, whether or not the permission was *granted*.
   - How to reproduce (probe): full build, SMS not granted, a Google Messages notification for an HDFC debit gives
     **0 messages**.
   - Why it matters: a user who says no to SMS, or revokes it later, silently loses every bank SMS.
     Only UPI app notifications are still caught.
   - Direction: skip the SMS app's notifications only while RECEIVE_SMS is granted (checked at each notification).

2. **[FLAW-002] [Medium] The 30-day import reads the SMS inbox on the main thread**
   - Where: `MainActivity.importSms` runs on `lifecycleScope` (main), and `SmsImport.run` / `ContentResolverSmsSource`
     never switch thread (confirmed by code; the probe for it deadlocked under Robolectric, see Info).
   - What happens: thousands of SMS in 30 days means a long main-thread query, so the screen freezes and
     "App not responding" can appear.
   - Direction: run the query on `Dispatchers.IO`, and show progress.

3. **[SEC-003] [Medium] A listed app is trusted by package name only**
   - How (by analysis): a user without POP installs a fake APK named `com.popclub.android` from a link. Its
     "Received ₹50,000 from …" notifications are added automatically. Android guarantees the package name is unique
     on the phone, not that it is the real POP.
   - Direction: add each listed app's signing-certificate digest to the app list (it's data), and send a mismatch to
     Review. Or send anything not installed from the Play Store to Review.

4. **[FLAW-004] [Medium] Without people info, every SMS-app notification goes to Review (play build)**
   - Where: `NotificationReader.fromSmsApp`: no people list counts as "saved contact" (SEC-002, the conservative choice).
   - Not verified on a real phone: if Google Messages or Samsung Messages give no people list for bank senders, every
     bank SMS waits in the inbox.
   - Direction: check on your phone (Google Messages and Samsung). If people info is missing, use READ_CONTACTS
     (optional) to check the title against saved contacts.

5. **[FLAW-005] [Medium] Nothing notices when the phone stops the notification listener**
   - What: Xiaomi, Oppo, Vivo and some Samsung builds kill background listeners. Payments are then missed with no sign.
   - Direction: a "last notification seen" check on the home screen, a battery-optimisation guide, and
     `requestRebind` when the app opens.

6. **[DATA-006] [Low] Wallet cashback becomes income**
   - Probe: "₹50 cashback credited to your wallet" from PhonePe was **Added** as income. The other 9 promotions,
     reminders, collect requests and failed or initiated payments were ignored.
   - Decision for you: count wallet cashback as income, or ignore it (the wallet isn't an account in Vyaya)?

7. **[DATA-007] [Low] A live SMS and the same SMS imported later get different times**
   - Where: live uses the SMS's sent time (`timestampMillis`, from the network); the import uses the stored
     receive time (`date`).
   - What happens: a message delivered hours late gets two different times. With a reference this doesn't matter. A
     card SMS without one may fall outside the matching window and count twice.
   - Direction: use the receive time for live SMS too.

8. **[SEC-008] [Low] The database is unencrypted on disk**
   - Backup and device transfer are now excluded (SEC-007 fixed). A rooted phone or forensic copy can still read
     payees, notes and references.
   - Direction: decide about SQLCipher together with the app lock.

9. **[FLAW-009] [Low] The import's message counts only payments added**
   - "Added N payments from the last 30 days" leaves out the ones sent to Review.
   - Direction: "N added, M to check".

10. **[Info]**
    - Robolectric 4.17 needs Java 21 or newer. Tests run on JDK 24 (`-Pvyaya.testJdk=…`); CI must provide one.
    - A Robolectric probe that switched to `Dispatchers.Main` inside `runBlocking` deadlocks (the main looper is the
      test thread). Tests must not do that.
    - The release APKs were not run on a phone, so R8 behaviour at runtime (Room, serialization) is unverified.
    - Debug builds export compose's empty test host activity (`ui-test-manifest`); release does not.

11. **[Info] Things that held up**
    - No INTERNET or network permission in either build (rule 8.8).
    - Exported components:
      - the launcher;
      - the listener (`BIND_NOTIFICATION_LISTENER_SERVICE`);
      - the SMS receiver (`BROADCAST_SMS`, full only);
      - AndroidX's profile installer (`DUMP`).
      All are system-only, so no app can fake a notification or SMS into Vyaya.
    - The play build asks for no SMS permission (checked on its merged manifest).
    - Other apps' notifications are dropped unread; summaries and ongoing ones too.
    - A closed database or any capture error never crashes the listener. Nothing is logged.
    - Re-posted notifications and re-imported SMS add nothing.
    - A contact saved under a bank's header goes to Review (SEC-002).

## Decisions needed from you
- **#1:** skip SMS-app notifications only while SMS is granted (recommended)?
- **#2:** move the import off the main thread, with progress (recommended)?
- **#3:** signing-certificate check per listed app (recommended; I'd collect the digests), or Review for anything not
  from the Play Store?
- **#4:** test on your phone first. Which SMS app do you use?
- **#5:** listener health check and battery guide (recommended)?
- **#6:** wallet cashback: income, or ignored?
- **#7:** use the receive time for live SMS (recommended)?

## Resolution (2026-11-04)

Your decision: go with the recommendations.

| # | Status | Fix |
|---|---|---|
| 1 DATA-001 | ✅ Fixed | Which SMS apps' notifications are read is decided at each notification: all of them until RECEIVE_SMS is granted (or after it is taken back) |
| 2 FLAW-002 | ✅ Fixed | `SmsImport` reads the SMS inbox on `Dispatchers.IO` |
| 3 SEC-003 | ✅ Fixed (digests pending) | `AppVerifier`: a known certificate digest decides; otherwise Play Store, Galaxy Store or preinstalled. Unverified notifications go to Review (`UNVERIFIED_APP`). `<queries>` names every listed app. **The digest list is still empty**: to be read from the real apps on your phone |
| 4 FLAW-004 | ⏳ Open | Needs a check on your phone (which SMS app gives people info) |
| 5 FLAW-005 | ✅ Fixed | `ListenerHealth`; rebind when dropped and when the app opens; the setup card shows a stopped listener with Battery settings |
| 6 DATA-006 | ⏳ Open | Wallet cashback: your decision (counted as income for now) |
| 7 DATA-007 | ✅ Fixed | A live SMS is dated at receive time, like the import |
| 8 SEC-008 | ⏳ Open | Encryption at rest, with the app lock |
| 9 FLAW-009 | ⏳ Open | Import message counts |
