"""Exercise the actual CI app-test function with a host-only fake adb."""
from pathlib import Path
import os
import re
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]


class CiAppInstrumentationTests(unittest.TestCase):
    def run_preparation_function(self, function_name, response="Success", exit_code=0):
        source = (ROOT / "tools/run_ci_instrumentation.sh").read_text()
        function = re.search(r"(?ms)^" + re.escape(function_name) + r"\(\) \{\n.*?^\}", source)
        self.assertIsNotNone(function)
        with tempfile.TemporaryDirectory() as directory:
            calls = Path(directory) / "calls"
            adb = Path(directory) / "adb"
            adb.write_text(
                "#!/usr/bin/python3\nimport sys\nfrom pathlib import Path\n"
                + "with Path(" + repr(str(calls)) + ").open('a') as output: output.write(' '.join(sys.argv[1:]) + '\\n')\n"
                + "print(" + repr(response) + ")\nraise SystemExit(" + str(exit_code) + ")\n"
            )
            adb.chmod(0o700)
            environment = dict(os.environ, PATH=directory + os.pathsep + os.environ["PATH"])
            result = subprocess.run(
                ["bash", "-c", "set -euo pipefail\nserial=emulator-host-fixture\n"
                 + "capture_failure() { printf 'CAPTURE %s\\n' \"$1\"; }\n"
                 + function[0] + "\n" + function_name + "\n"],
                cwd=ROOT, env=environment, text=True, capture_output=True, timeout=10,
            )
            return result, calls.read_text().splitlines()

    def run_compilation_function(self, response="Success", exit_code=0):
        return self.run_preparation_function("prepare_qa_compilation", response, exit_code)

    def test_clean_boot_accepts_explicit_anr_free_baseline(self):
        result, calls = self.run_preparation_function(
            "assert_clean_boot", "ACTIVITY MANAGER LAST ANR\n  <no ANR has occurred since boot>",
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["-s emulator-host-fixture shell dumpsys activity lastanr"], calls)

    def test_existing_or_unknown_boot_anr_is_captured(self):
        for response in ["ANR time: Oct 19; com.android.launcher3", "unknown result"]:
            with self.subTest(response=response):
                result, calls = self.run_preparation_function("assert_clean_boot", response)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, len(calls))
                self.assertIn("CAPTURE emulator_first_boot_system_anr", result.stdout)

    def test_boot_anr_readback_error_is_captured(self):
        result, _ = self.run_preparation_function("assert_clean_boot", "", 1)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("CAPTURE emulator_boot_anr_readback", result.stdout)

    def test_prelaunch_display_preserves_logical_viewport_and_other_configuration(self):
        source = (ROOT / ".github/workflows/android-instrumentation-ci.yml").read_text()
        hook = re.search(r"(?m)^ {10}pre-emulator-launch-script: \|\n((?: {12}.*\n)+)", source)
        self.assertIsNotNone(hook)
        with tempfile.TemporaryDirectory() as directory:
            avd = Path(directory) / "test.avd"
            avd.mkdir()
            config = avd / "config.ini"
            config.write_text("hw.lcd.width=1080\nhw.lcd.height=2400\nhw.lcd.density=420\n"
                              "skin.name=1080x2400\nhw.cpu.ncore=4\nhw.ramSize=4096M\n")
            subprocess.run(["bash", "-e", "-c", textwrap.dedent(hook[1])],
                           env=dict(os.environ, ANDROID_AVD_HOME=directory), check=True, timeout=10)
            lines = config.read_text().splitlines()
            values = dict(line.split("=", 1) for line in lines)
            self.assertEqual(720, int(values["hw.lcd.width"]))
            self.assertEqual(1600, int(values["hw.lcd.height"]))
            self.assertEqual(280, int(values["hw.lcd.density"]))
            self.assertEqual(1080 / 420, int(values["hw.lcd.width"]) / int(values["hw.lcd.density"]))
            self.assertEqual(2400 / 420, int(values["hw.lcd.height"]) / int(values["hw.lcd.density"]))
            self.assertEqual("4", values["hw.cpu.ncore"])
            self.assertEqual("4096M", values["hw.ramSize"])
            self.assertEqual("720x1600", values["skin.name"])
            self.assertEqual(len(values), len(lines))

    def test_compilation_prepares_only_both_qa_packages(self):
        result, calls = self.run_compilation_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            "-s emulator-host-fixture shell cmd package compile -m speed -f com.gernalix.personalhub.qa",
            "-s emulator-host-fixture shell cmd package compile -m speed -f com.gernalix.personalhub.qa.test",
        ], calls)

    def test_compilation_failure_is_captured_without_retry(self):
        for response, exit_code in [("Failure", 0), ("Success", 1)]:
            with self.subTest(response=response, exit_code=exit_code):
                result, calls = self.run_compilation_function(response, exit_code)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, len(calls))
                self.assertIn("CAPTURE compile_com.gernalix.personalhub.qa", result.stdout)

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
