"""Every GitHub Action a workflow uses is pinned to a full commit SHA (core-model SEC-017: a moved tag can't change
what CI runs). Local actions (./…) and docker:// images with a digest are allowed.

Usage: python3 scripts/check-pinned.py [workflow files…]   (default: .github/workflows/*.yml)
"""
import glob
import re
import sys

USES = re.compile(r"^\s*-?\s*uses:\s*([^\s#]+)")
PINNED = re.compile(r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$")


def problems(text, name="workflow"):
    found = []
    for n, line in enumerate(text.splitlines(), 1):
        m = USES.match(line)
        if not m:
            continue
        ref = m.group(1).strip("'\"")
        if ref.startswith("./") or re.match(r"^docker://.+@sha256:[0-9a-f]{64}$", ref):
            continue
        if not PINNED.match(ref):
            found.append(f"{name}:{n}: {ref} isn't pinned to a commit SHA")
    return found


if __name__ == "__main__":
    files = sys.argv[1:] or sorted(glob.glob(".github/workflows/*.yml"))
    issues = [p for f in files for p in problems(open(f, encoding="utf-8").read(), f)]
    for i in issues:
        print(i)
    print("all actions pinned" if not issues else f"{len(issues)} action(s) not pinned")
    sys.exit(1 if issues else 0)
