"""Tests for check-release.py: it must refuse a debug build and pass a release build (launch prep).

Run after `./gradlew :androidApp:assemblePlayDebug :androidApp:assemblePlayRelease`:
    python3 -m unittest scripts/test_check_release.py
"""
import importlib.util
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("check_release", os.path.join(HERE, "check-release.py"))
check_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_release)

APKS = os.path.join(HERE, "..", "androidApp", "build", "outputs")
DEBUG = os.path.join(APKS, "apk", "play", "debug", "androidApp-play-debug.apk")
RELEASE = os.path.join(APKS, "apk", "play", "release", "androidApp-play-release-unsigned.apk")
MAPPING = os.path.join(APKS, "mapping", "playRelease", "mapping.txt")


class CheckReleaseTest(unittest.TestCase):
    def test_a_debug_build_is_refused(self):
        problems = check_release.problems(DEBUG, mapping=None)
        self.assertTrue(any("debuggable" in p for p in problems), problems)
        self.assertTrue(any("mapping" in p for p in problems), problems)

    def test_the_release_build_passes(self):
        self.assertEqual([], check_release.problems(RELEASE, mapping=MAPPING))

    def test_exported_components_and_permissions_are_read_from_the_manifest(self):
        m = check_release.manifest(RELEASE)
        self.assertNotIn("android.permission.INTERNET", m.permissions)
        self.assertIn("app.vyaya.android.MainActivity", m.exported)
        self.assertEqual("android.permission.BIND_QUICK_SETTINGS_TILE", m.exported["app.vyaya.android.QuickAddTile"])


if __name__ == "__main__":
    unittest.main()
