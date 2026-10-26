"""Prints one GitHub Actions notice per test target with totals, and fails if no tests ran.

Usage: python3 scripts/test-summary.py <target>   (e.g. jvmTest, iosSimulatorArm64Test)
"""
import glob
import sys
import xml.etree.ElementTree as ET

target = sys.argv[1]
tests = failures = errors = skipped = 0
for path in glob.glob(f"core/*/build/test-results/{target}/*.xml"):
    suite = ET.parse(path).getroot()
    tests += int(suite.get("tests", 0))
    failures += int(suite.get("failures", 0))
    errors += int(suite.get("errors", 0))
    skipped += int(suite.get("skipped", 0))

print(f"::notice title={target}::{tests} tests, {failures} failures, {errors} errors, {skipped} skipped")
if tests == 0:
    print(f"::error title={target}::no tests ran")
    sys.exit(1)
