"""Exercise the actual CI app-test function with a host-only fake adb."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class CiAppInstrumentationTests(unittest.TestCase):
    def run_ci_function(self, marker: str = ""):
        source = (ROOT / "tools/run_ci_instrumentation.sh").read_text()
        function = re.search(r"(?ms)^run_app_test\(\) \{\n.*?^\}", source)
        self.assertIsNotNone(function)
        with tempfile.TemporaryDirectory() as directory:
            adb = Path(directory) / "adb"
            adb.write_text("#!/usr/bin/python3\nprint('OK (1 test)')\nprint(" + repr(marker) + ")\n")
            adb.chmod(0o700)
            environment = dict(os.environ, PATH=directory + os.pathsep + os.environ["PATH"])
            return subprocess.run(
                ["bash", "-c", "set -euo pipefail\nserial=emulator-host-fixture\nrunner=fixture/runner\n"
                 + function[0] + "\nrun_app_test Fixture#case\n"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )

    def test_successful_single_case_passes(self):
        result = self.run_ci_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("PASS Fixture#case", result.stdout)

    def test_cleanup_failure_after_ok_cannot_pass(self):
        result = self.run_ci_function("DELETE_FAILED_INTERNAL_ERROR")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("PASS Fixture#case", result.stdout)
        self.assertIn("FAIL-CLOSED", result.stderr)

    def test_runner_failure_after_ok_cannot_pass(self):
        result = self.run_ci_function("INSTRUMENTATION_FAILED: fixture crash")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("PASS Fixture#case", result.stdout)
        self.assertIn("FAIL-CLOSED", result.stderr)


if __name__ == "__main__":
    unittest.main()
