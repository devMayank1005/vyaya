# Findings: app lock and password-protected backups: 2026-11-06

**Scope:** branch `android-mvp`, `4f8636b`..`57cf092`:
- `BackupCrypto` and `docs/encrypted-backup-v1.md`;
- password export and restore (`BackupFiles`, `RestoreFlow`, `BackupScreen`);
- `AppLock` / `BiometricAuthenticator`, the lock screen, the switch, and the activity wiring.

**Method:**
- **Break phase:** code analysis, the tests' tamper cases, and a temporary probe of key-derivation time.
- **Release lint** found one real bug before this report (fixed in `57cf092`: Fragment 1.2 from biometric 1.1.0
  would have lost the file pickers' results).
- No simulation: neither feature touches parsing, de-dup, the engine or the database contents.

**Status:** reported only. **Nothing fixed.**

## Findings

1. **[FLAW-001] [Medium] Turning the phone (or any activity re-creation) asks to unlock again**
   - Where: `MainActivity` makes its `AppLock` per activity instance, and "away since" lives in it. A new instance
     (rotation, dark mode switch, language change) starts as if the app had been closed, so with the lock on, it locks.
   - Why it matters: a fingerprint prompt on every rotation makes people turn the lock off.
   - Direction: one `AppLock` for the process (in `VyayaApp`), so only a real absence or a new process locks.

2. **[SEC-002] [Medium] Any 8-character password is accepted**
   - What: `password1` is accepted. A stolen `.vyaya` file can be attacked offline. PBKDF2 at 600,000 iterations slows
     each guess (probe: ~0.1–0.25 s on this Mac, likely 1–5 s on a low-end phone), but common passwords fall quickly on a GPU.
   - Direction: refuse the most common passwords and suggest a passphrase of 3–4 words. A memory-hard KDF (Argon2)
     isn't in the platforms' crypto; that would mean a format v2.

3. **[FLAW-003] [Low] Nothing says a forgotten password loses the backup**
   - There is no recovery by design (nothing leaves the phone). The screen doesn't say so.
   - Direction: one line under the password fields.

4. **[FLAW-004] [Low] On a phone without a screen lock, the app lock does nothing**
   - `BiometricAuthenticator` allows everything when the phone has no screen lock (there is nothing to ask), so the
     switch can be on while protecting nothing.
   - Direction: say so next to the switch, with a link to set a screen lock.

5. **[SEC-005] [Low] Typed passwords stay in memory until collected**
   - Compose text fields hold Strings, which can't be wiped. The arrays handed on are cleared after use.
   - Direction: acceptable for now. Worth knowing for a memory dump of a rooted phone.

6. **[Info] The lock is off unless turned on** (the plan's "optional lock"). Export and restore always ask, so
   the data can't be taken out or replaced without the phone's own check. Everything else is visible to anyone holding
   the unlocked phone until the user turns the lock on.

7. **[Info] Not yet run on a phone**
   - Tests run the JDK's AES-GCM. On a phone the app uses Android's (Conscrypt/BoringSSL).
   - The biometric prompt, `FLAG_SECURE` and the device-credential fallback on Android 8–10 need a real device.

8. **[Info] Things that held up**
   - Byte-for-byte agreement with an independent implementation (Python `cryptography`) on the spec's vectors. They
     caught a real bug while it was being written (the empty last chunk).
   - Every single changed byte, a cut, a dropped, reordered or appended chunk, and a header asking for huge work or
     memory are refused.
   - A wrong password, or a change in a late chunk after earlier chunks were streamed into the restore, leaves the
     database unchanged (the transaction rolls back).
   - No plain text on disk on either side: the export's temporary file is already encrypted, and restore decrypts as
     it reads.
   - Each file has its own salt, so its own key; nonces can't repeat across files.
   - Turning the lock off asks too.

## Decisions needed from you
- **#1:** one lock for the process (recommended)?
- **#2:** refuse common passwords and suggest a passphrase (recommended)?
- **#3 and #4:** add the two lines of text (recommended)?
