"""Tests for check-pinned.py.   python3 -m unittest scripts/test_check_pinned.py"""
import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location("check_pinned", os.path.join(os.path.dirname(os.path.abspath(__file__)), "check-pinned.py"))
check_pinned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_pinned)


class CheckPinnedTest(unittest.TestCase):
    def test_a_tag_or_branch_is_refused(self):
        text = "steps:\n  - uses: actions/checkout@v7\n  - uses: gradle/actions/setup-gradle@main\n"
        self.assertEqual(2, len(check_pinned.problems(text)))

    def test_a_full_sha_local_action_and_digest_pass(self):
        text = ("  - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567 # v7\n"
                "  - uses: ./.github/actions/mine\n"
                "  - uses: docker://alpine@sha256:" + "a" * 64 + "\n")
        self.assertEqual([], check_pinned.problems(text))

    def test_a_short_sha_is_refused(self):
        self.assertEqual(1, len(check_pinned.problems("- uses: actions/checkout@0123456\n")))


if __name__ == "__main__":
    unittest.main()
