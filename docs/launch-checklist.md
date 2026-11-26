# Launch checklist: Android beta, then the Play Store

Status: **approved 2026-11-26**. The play build ships to the Play Store. The full build (it reads SMS) is
for side-loading only: Play allows SMS permissions only for default SMS apps.

## Done in the repository (checked by CI on every PR)
- [x] Release builds are minified and obfuscated (R8). They're not debuggable, keep Auto Backup off, ask for no
      network permission, and export only the allowed components (`scripts/check-release.py`).
- [x] The classes found by name at run time survive R8: the database, the app class and the activity.
- [x] The minified release runs (`scripts/release-smoke.sh` on an emulator: Home, Add from the widget's intent, the
      widget's redraw, the Quick Settings tile, one copy of the screen). The backup, CSV, capture, lock and alert
      paths run as instrumented tests on the minified `staging` build. Both run on release tags (CI) and by hand.
- [x] Lint passes on both flavors.
- [x] An adaptive launcher icon with a themed version. **It is a placeholder: replace it with a designed one.**
- [x] The database schema Room compiled must be the one committed (`core/data/schemas`).
- [x] Coverage gates: shared core ≥ 90 %, Android code and screens ≥ 70 % per package.

## You do (once)
1. **Make the upload key.** Keep it and its passwords outside the repository, and back them up: a lost upload key
   needs a Play support request to replace.
   ```
   keytool -genkeypair -v -keystore ~/keys/vyaya-upload.jks -alias vyaya-upload -keyalg RSA -keysize 4096 -validity 10000
   ```
   Then add to `~/.gradle/gradle.properties` (your machine only, never committed):
   ```
   vyaya.signing.storeFile=/Users/<you>/keys/vyaya-upload.jks
   vyaya.signing.storePassword=…
   vyaya.signing.keyAlias=vyaya-upload
   vyaya.signing.keyPassword=…
   ```
2. **Play Console:**
   - Create the app (`app.vyaya`).
   - Turn on Play App Signing. Google keeps the app signing key; yours is the upload key.
3. **Store listing:** the name, short and full description, screenshots, a feature graphic, and the category
   (Finance).
4. **Privacy policy URL:** publish `docs/privacy-policy.md` somewhere public (for example GitHub Pages) once you've
   approved its text.
5. **App content forms:**
   - Data safety: the answers are in `docs/play-data-safety.md`.
   - Ads: none.
   - Target audience: 18+.
   - Financial features: a personal expense tracker; no loans, payments or trading.
6. **Closed test track** (beta). Play asks new personal accounts for a closed test with at least 12 testers for 14
   days before production.

## Each release
1. Bump `versionCode` (by 1 every upload) and `versionName` in `androidApp/build.gradle.kts`.
2. Run the minified build on a phone or emulator:
   - `./gradlew :androidApp:connectedPlayStagingAndroidTest` (backup with and without a password, CSV, capture,
     app lock and budget alert, on the release setup);
   - `./gradlew :androidApp:assemblePlayRelease && scripts/release-smoke.sh`.

   Pushing a `v*` tag runs both on CI too (`.github/workflows/release-check.yml`).
3. `scripts/release.sh` builds the signed `.aab` and runs the release checks on it. It files the `.aab`, its
   `mapping.txt` and the commit in `~/vyaya-releases/<versionCode>/`, and refuses a `versionCode` already released.
4. Upload the `.aab` and its `mapping.txt` (Play Console > App bundle explorer), so Play's crash reports, which stay
   in your console, can be read. Keep `~/vyaya-releases` backed up.
5. **From the first beta on** (your decision, branch review 2026-11-18): any database change bumps the Room
   version, adds a migration and a migration test. Any change to what a restore accepts follows CLAUDE.md rule 8.7.

## Before the first beta (open)
- [x] You approved the privacy policy and the data-safety answers (2026-11-26). Still to fill in: the contact email.
- [ ] A designed launcher icon (the placeholder is a white rupee mark on the app's teal).
- [ ] Your phone check: export a backup from the 7-Nov build, uninstall, install the new build, restore.
- [ ] Real SMS and UPI samples for the parser's accuracy (the Phase 2 gate: at least 95 %).
- [ ] The findings of the launch break phase (`reports/launch-2026-11-21.md`) you decide to fix.
