"""No logging in production code (core-model SEC-016): the policy is "none", so no SMS text, payee or amount can reach
logcat, the console or a crash log (Money.toString() prints the amount). Tests may print.

Usage: python3 scripts/check-no-logging.py [root]
"""
import os
import re
import sys

CALLS = re.compile(r"\b(Log\.(v|d|i|w|e|wtf)|println|print|printStackTrace|System\.(out|err)|NSLog|Napier\.\w+|Timber\.\w+)\s*\(")
TEST_DIRS = re.compile(r"[\\/](\w*[Tt]est\w*|androidTest|testFull|testPlay|commonTest|jvmTest|iosTest)[\\/]")


def problems(root):
    found = []
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".git", "node_modules")]
        if "/src/" not in base.replace("\\", "/") + "/" or TEST_DIRS.search(base + "/"):
            continue
        for f in files:
            if not f.endswith((".kt", ".kts", ".swift", ".java")):
                continue
            path = os.path.join(base, f)
            for n, line in enumerate(open(path, encoding="utf-8"), 1):
                code = line.split("//", 1)[0]
                if CALLS.search(code):
                    found.append(f"{os.path.relpath(path, root)}:{n}: {line.strip()[:100]}")
    return found


if __name__ == "__main__":
    issues = problems(sys.argv[1] if len(sys.argv) > 1 else ".")
    for i in issues:
        print(i)
    print("no logging in production code" if not issues else f"{len(issues)} logging call(s) in production code")
    sys.exit(1 if issues else 0)
