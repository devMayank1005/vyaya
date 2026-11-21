"""Checks a built Android APK before it ships (launch prep; CLAUDE.md break-phase items "debuggable or unobfuscated
builds", rule 8.8 "nothing leaves the device").

Usage: python3 scripts/check-release.py <apk> <mapping.txt>
Prints each problem and exits 1 if there are any. Needs aapt2 (Android build-tools; ANDROID_HOME or the default SDK).
"""
import glob
import os
import re
import subprocess
import sys

# Every component another app or the system may start, and the permission the caller must hold (None: none exists).
EXPORTED = {
    "app.vyaya.android.MainActivity": None,  # the launcher
    "app.vyaya.android.QuickAddWidget": None,  # home-screen widget: the launcher must reach it
    "app.vyaya.android.CaptureListener": "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
    "app.vyaya.android.QuickAddTile": "android.permission.BIND_QUICK_SETTINGS_TILE",
    "app.vyaya.android.SmsReceiver": "android.permission.BROADCAST_SMS",  # full build only
    "androidx.profileinstaller.ProfileInstallReceiver": "android.permission.DUMP",
}
NEVER_ASKED = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE"}
# Found by reflection or the system by name: R8 must keep these (renamed or removed, the release build crashes).
KEPT = ["app.vyaya.core.data.VyayaDatabase_Impl", "app.vyaya.android.VyayaApp", "app.vyaya.android.MainActivity"]
# Obfuscated: our own code is renamed (samples that nothing needs by name).
RENAMED = ["app.vyaya.core.engine.SafeToSpend", "app.vyaya.core.parser.MessageParser"]


def aapt2():
    sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    found = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
    if not found:
        sys.exit("aapt2 not found: set ANDROID_HOME")
    return found[-1]


class Manifest:
    def __init__(self, debuggable, allow_backup, permissions, exported):
        self.debuggable, self.allow_backup, self.permissions, self.exported = debuggable, allow_backup, permissions, exported


def manifest(apk):
    """The parts of the APK's compiled manifest that matter here, from `aapt2 dump xmltree`."""
    out = subprocess.run([aapt2(), "dump", "xmltree", "--file", "AndroidManifest.xml", apk], check=True, capture_output=True, text=True).stdout
    stack = []  # (indent, tag, attrs)
    elements = []
    for line in out.splitlines():
        indent = len(line) - len(line.lstrip())
        text = line.strip()
        if text.startswith("E: "):
            while stack and stack[-1][0] >= indent:
                stack.pop()
            element = (indent, text[3:].split(" ")[0], {}, [s[1] for s in stack])
            stack.append(element)
            elements.append(element)
        elif text.startswith("A: ") and stack:
            m = re.match(r"A: (?:http://schemas.android.com/apk/res/android:)?([\w]+)(?:\(0x[0-9a-f]+\))?=(.*)", text)
            if m:
                value = m.group(2)
                q = re.match(r'"(.*?)"', value)
                stack[-1][2][m.group(1)] = q.group(1) if q else value
    app = next((e for e in elements if e[1] == "application"), None)
    attrs = app[2] if app else {}
    permissions = {e[2].get("name") for e in elements if e[1] == "uses-permission"}
    exported = {}
    for _, tag, a, parents in elements:
        if tag in ("activity", "activity-alias", "service", "receiver", "provider") and a.get("exported", "false").lower() in ("true", "0xffffffff"):
            exported[a.get("name")] = a.get("permission")
    truthy = lambda v: v is not None and v.lower() in ("true", "0xffffffff")
    return Manifest(truthy(attrs.get("debuggable")), attrs.get("allowBackup"), permissions, exported)


def problems(apk, mapping):
    found = []
    m = manifest(apk)
    if m.debuggable:
        found.append("the build is debuggable")
    if (m.allow_backup or "").lower() not in ("false", "0x0"):
        found.append(f"allowBackup is {m.allow_backup!r}, not false (Auto Backup would copy the data off the phone)")
    for p in sorted(m.permissions & NEVER_ASKED):
        found.append(f"asks for {p} (nothing may leave the phone)")
    for name, permission in sorted(m.exported.items()):
        if name not in EXPORTED:
            found.append(f"{name} is exported, and isn't on the allowed list")
        elif EXPORTED[name] != permission:
            found.append(f"{name} is exported guarded by {permission!r}, expected {EXPORTED[name]!r}")
    if mapping is None or not os.path.exists(mapping):
        found.append("no R8 mapping: the build isn't minified and obfuscated")
    else:
        names = {}
        with open(mapping, encoding="utf-8") as f:
            for line in f:
                if not line.startswith((" ", "#")) and " -> " in line:
                    original, renamed = line.rstrip().rstrip(":").split(" -> ")
                    names[original] = renamed
        for k in KEPT:
            if k not in names:
                found.append(f"{k} was removed by R8; it is found by name at run time")
            elif names[k] != k:
                found.append(f"{k} was renamed by R8 to {names[k]}; it is found by name at run time")
        # Absent from the mapping: merged or inlined away, which is fine. Present under its own name: not obfuscated.
        for r in RENAMED:
            if names.get(r) == r:
                found.append(f"{r} isn't obfuscated")
        ours = {k: v for k, v in names.items() if k.startswith("app.vyaya.")}
        kept = sum(1 for k, v in ours.items() if k == v)
        if not ours or kept > len(ours) / 2:
            found.append(f"most of the app's classes keep their names ({kept} of {len(ours)}): obfuscation looks off")
    return found


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    issues = problems(sys.argv[1], sys.argv[2])
    for issue in issues:
        print(f"::error::{issue}" if os.environ.get("GITHUB_ACTIONS") else f"- {issue}")
    print(f"{os.path.basename(sys.argv[1])}: {'OK' if not issues else f'{len(issues)} problem(s)'}")
    sys.exit(1 if issues else 0)
