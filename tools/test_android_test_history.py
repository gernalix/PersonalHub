from pathlib import Path
import os
import sqlite3
import tempfile
import unittest

import android_test_history as history


SUCCESS = """INSTRUMENTATION_STATUS: class=Example
INSTRUMENTATION_STATUS: test=liveFlow
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=Example
INSTRUMENTATION_STATUS: test=liveFlow
INSTRUMENTATION_STATUS_CODE: 0
OK (1 test)
INSTRUMENTATION_CODE: -1
"""


class TestHistoryTests(unittest.TestCase):
    def test_instrumentation_duration_is_retained_even_when_the_suite_fails(self):
        source = SUCCESS + "Time: 6.302\nFAILURES!!!\n"
        status, _, measurements = history.instrumentation(source)
        self.assertEqual("FAIL", status)
        self.assertEqual([("@suite", "test_suite_duration", 6302., "ms", "INSTRUMENTATION")], measurements)

    def test_python_suite_uses_its_terminal_summary_not_expected_failure_output(self):
        log = "FAIL-CLOSED: expected negative test\nRan 17 tests in 0.090s\n\nOK\n"
        status, rows, _ = history.unittest_result(log)
        self.assertEqual("PASS", status)
        self.assertEqual(90., rows[0][2])
        self.assertEqual("FAIL", history.unittest_result("Ran 2 tests in 0.010s\nFAILED (failures=1)\n")[0])
        self.assertEqual("INCOMPLETE", history.unittest_result("Ran 0 tests in 0.000s\nOK\n")[0])
        self.assertEqual("INCOMPLETE", history.unittest_result("Ran 2 tests in 0.000s\nOK (skipped=2)\n")[0])

    def test_crashes_and_incomplete_streams_never_become_pass(self):
        self.assertEqual("PASS", history.instrumentation(SUCCESS)[0])
        self.assertEqual("FAIL", history.instrumentation(SUCCESS + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.")[0])
        self.assertEqual("INCOMPLETE", history.instrumentation(SUCCESS.replace("INSTRUMENTATION_CODE: -1", ""))[0])
        self.assertEqual("INCOMPLETE", history.instrumentation("OK (1 test)\nINSTRUMENTATION_CODE: -1")[0])

    def test_reimport_is_idempotent_but_later_identical_execution_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "test.log"
            source.write_text(SUCCESS)
            db = root / "history.sqlite3"
            context = {"device_kind": "pixel", "apk_sha256": "a" * 64}
            first = history.record(db, source, "instrumentation", context)
            self.assertEqual(first, history.record(db, source, "instrumentation", context))
            stamp = source.stat().st_mtime + 10
            os.utime(source, (stamp, stamp))
            second = history.record(db, source, "instrumentation", context)
            self.assertNotEqual(first["run_id"], second["run_id"])
            with sqlite3.connect(db) as connection:
                self.assertEqual(2, connection.execute("SELECT count(*) FROM runs").fetchone()[0])
                self.assertEqual(2, connection.execute("SELECT count(*) FROM test_results").fetchone()[0])
                self.assertEqual("ok", connection.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual(0o600, db.stat().st_mode & 0o777)

    def test_startup_samples_preserve_phase_and_do_not_claim_functional_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "startup.log"
            source.write_text("Status: ok\nActivity: app.qa/Main\nLaunchState: COLD\nTotalTime: 1238\nWaitTime: 1240\nComplete\n")
            db = root / "history.sqlite3"
            result = history.record(db, source, "am-start", {"device_kind": "pixel", "app_id": "app.qa"}, module="home")
            self.assertEqual("MEASURED", result["status"])
            with sqlite3.connect(db) as connection:
                self.assertEqual([("activity_total_time", 1238., "COLD"), ("activity_wait_time", 1240., "COLD")],
                                 connection.execute("SELECT metric,value,phase FROM startup_history ORDER BY metric").fetchall())
            with self.assertRaises(ValueError):
                history.startup(source.read_text(), "home", "other.app")
            with self.assertRaises(ValueError):
                history.startup(source.read_text().replace("TotalTime: 1238\n", ""), "home", "app.qa")

    def test_junit_import_preserves_failures_skips_and_durations(self):
        source = '<testsuite><testcase classname="C" name="ok" time="0.125"/><testcase classname="C" name="bad"><failure>private assertion text</failure></testcase><testcase classname="C" name="skip"><skipped/></testcase></testsuite>'
        status, cases, _ = history.junit(source)
        self.assertEqual("FAIL", status)
        self.assertEqual([("C#ok", "PASS", 125.), ("C#bad", "FAIL", None), ("C#skip", "SKIP", None)], cases)
        self.assertNotIn("private", str(cases))


if __name__ == "__main__":
    unittest.main()
