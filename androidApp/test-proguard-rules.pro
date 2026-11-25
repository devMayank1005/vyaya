# R8 rules for the instrumented test APK only (the minified `staging` build, launch report FLAW-001).
# androidx.test refers to Error Prone's compile-time annotations, which aren't shipped.
-dontwarn com.google.errorprone.annotations.**
# The runner finds test classes and methods by their annotations, at run time: keep them and JUnit itself, or no test
# is found and the run "passes" with 0 tests (scripts/test-summary.py fails on that).
-keep @org.junit.runner.RunWith class * { *; }
-keepclassmembers class * { @org.junit.* <methods>; }
-keep class org.junit.** { *; }
-keep class androidx.test.** { *; }
-dontwarn org.junit.**
