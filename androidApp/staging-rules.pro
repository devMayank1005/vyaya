# The minified `staging` build only (instrumented tests on the release setup, launch report FLAW-001). The test runner
# uses androidx.tracing, which the test APK takes from the app; release builds don't keep it.
-keep class androidx.tracing.** { *; }
# The test APK also takes Kotlin's standard library and coroutines from the app. Kept whole here; the app's own code and
# its serialization stay shrunk and renamed exactly as in release, which is what these tests check.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
# The tests call the app's own code (stores, BackupFiles): R8 may not remove or inline what they call, but it still
# renames it (the test APK is renamed the same way), so obfuscation is as in release.
-keep,allowobfuscation class app.vyaya.** { public *; }
# Libraries the tests call directly (time zones; closing the database): the same, not removed, still renamed.
-keep,allowobfuscation class kotlinx.datetime.** { public *; }
-keep,allowobfuscation class androidx.room.RoomDatabase { public *; }
