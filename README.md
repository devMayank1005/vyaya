# Vyaya

An expense tracker that keeps everything on your phone. Log money in and money out, and Vyaya tells you
what's left. Payments are picked up automatically from UPI app notifications and bank SMS where the
platform allows it. No account, no server, no sync.

- **Plan:** [`plan.md`](plan.md) (product, architecture, backup format, simulation, roadmap)
- **How we work:** [`CLAUDE.md`](CLAUDE.md): test-driven development, unit and integration tests,
  a break phase after every feature, and scale simulation. Findings go in [`reports/`](reports).

## Status

Phase 0: foundation. The shared Kotlin core is being built test-first.

| Module | What it does |
|---|---|
| `core/model` | `Money` (paise, parsing from SMS/notification text, Indian lakh/crore formatting) and `BudgetMonth` (custom month start, timezone-aware) |

Next modules: `core/parser`, `core/filters`, `core/dedup`, `core/engine`, `core/data`, `core/backup`,
then `androidApp` and `iosApp`.

## Getting started

You need **JDK 17 or newer**. Android Studio is needed later for the Android app, and a Mac with Xcode for iOS.

```bash
git clone https://github.com/<you>/vyaya.git
cd vyaya

./gradlew jvmTest          # run shared-core tests (any OS)
./gradlew koverVerify      # coverage gate: shared core >= 90 % lines and branches
./gradlew koverHtmlReport  # coverage report in build/reports/kover/html
./gradlew iosSimulatorArm64Test   # macOS only: run shared-core tests on the iOS simulator
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Project layout

```
core/model/src/commonMain   shared production code (no Android/iOS APIs)
core/model/src/commonTest   shared tests (run on JVM and iOS)
reports/                    break-phase and simulation reports
.github/workflows/ci.yml    CI: JVM tests + coverage on Linux, iOS tests on macOS
```
