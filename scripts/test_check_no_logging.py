"""Tests for check-no-logging.py.   python3 -m unittest scripts/test_check_no_logging.py"""
import importlib.util
import os
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("check_no_logging", os.path.join(os.path.dirname(os.path.abspath(__file__)), "check-no-logging.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def tree(files):
    root = tempfile.mkdtemp()
    for path, text in files.items():
        full = os.path.join(root, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        open(full, "w", encoding="utf-8").write(text)
    return root


class CheckNoLoggingTest(unittest.TestCase):
    def test_a_log_or_println_in_production_code_is_found(self):
        root = tree({"app/src/main/kotlin/A.kt": 'fun f() { Log.d("x", sms.text) }\n', "core/src/commonMain/kotlin/B.kt": "fun g() = println(entry)\n"})
        self.assertEqual(2, len(check.problems(root)))

    def test_tests_comments_and_build_output_are_left_alone(self):
        root = tree({
            "app/src/test/kotlin/A.kt": "fun t() = println(x)\n",
            "core/src/commonTest/kotlin/B.kt": "fun t() = println(x)\n",
            "core/src/commonMain/kotlin/C.kt": "// println(x) is not allowed here\nval printable = 1\n",
            "core/build/generated/D.kt": "fun x() = println(1)\n",
        })
        self.assertEqual([], check.problems(root))

    def test_the_repository_has_none(self):
        self.assertEqual([], check.problems(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")))


if __name__ == "__main__":
    unittest.main()
