# Architecture audit: CLAUDE.md rules 8.1–8.8 and plan.md design rules: 2026-11-02

**Scope:** the shared core at `a9057ab` (`core/model`, `core/parser`, `core/backup`, `core/simulation`),
checked against the rules in the code itself, not from memory.

## Followed
1. **Money is `Long` paise (8.1).** No `Float` or `Double` in shipped code; the only `Double`s are persona
   weights in the test-only simulation.
2. **UUID ids, `updatedAt` and soft delete (8.2)** on all 8 record types; system categories have fixed ids.
3. **One shared month function (8.3).** All month maths goes through `BudgetMonth` and `MonthSchedule`.
   Times are stored as an instant plus the user's time zone.
4. **No Android or iOS APIs in the shared core (8.6).** There are no platform imports, and it compiles for
   the iOS simulator.
5. **Nothing leaves the device (8.8).** No network, analytics or crash-reporting libraries.
6. **Parser rules are versioned data**, and each backup schema is frozen.

## Findings
7. **[FLAW] [High] Generated entries had no key (8.4).** An `Entry` didn't record which rule made it, so
   "one per rule and due date" couldn't be stored, backed up or checked. A restore merge would have kept two
   copies of the same rent payment.
8. **[FLAW] [Medium] Derived values stored (8.5).**
   - Saved was planned as a stored month-end entry that also "self-adjusts", which contradicts itself.
   - `RecurringRule.lastGeneratedOn` is stored, but it could be worked out from the generated entries.
9. **[FLAW] [Low] Backup limits narrowed without a version bump (8.7).** The 16 KB line limit and 20-split
   cap reject some files the old v2 reader accepted. No real backups exist yet, so no data is affected.
10. **[Info] "Both targets":** the core has no Android target; JVM tests stand in until the app modules exist.
    iOS tests run only in CI on macOS (pull requests and `main`).
11. **[Info] Not built yet:** Room, Koin, capability flags, filters, de-dup, engine and data.

## Resolution (2026-11-02)

| # | Status | Fix or reason |
|---|---|---|
| 7 | ✅ Fixed `aadeeed`, `8a6b0f5` | `Entry.generatedBy` (rule + due date); the entry id is derived from it; backup schema v3 carries it; v1 and v2 migrate with no key. Follow-up break phase: [`recurring-key-v3-2026-11-02.md`](recurring-key-v3-2026-11-02.md) |
| 8 Saved | ✅ Decided: calculated, never stored | `CLAUDE.md` and `plan.md` updated. The engine will calculate it |
| 8 `lastGeneratedOn` | ✅ Removed `12b5952` | Folded into v3 before release; worked out from the generation keys |
| 9 | ⏳ Open | Your reading of "limit vs format" decides whether this needs recording as accepted risk |
| 10 | ➡ PR opened so CI runs the iOS tests | |
