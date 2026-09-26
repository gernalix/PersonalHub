from __future__ import annotations

import sys
import unittest

import android_connected_test_gate as gate


class AndroidConnectedTestGateTests(unittest.TestCase):
    def test_success_without_failure_markers_passes(self) -> None:
        self.assertEqual(0, gate.run([sys.executable, "-c", "print(\"BUILD SUCCESSFUL\")"]))

    def test_instrumentation_failed_is_fail_closed_even_when_child_exits_zero(self) -> None:
        self.assertEqual(
            2,
            gate.run([sys.executable, "-c", "print(\"INSTRUMENTATION_FAILED: process crashed\"); print(\"BUILD SUCCESSFUL\")"]),
        )

    def test_delete_failed_is_fail_closed_even_when_child_exits_zero(self) -> None:
        self.assertEqual(
            2,
            gate.run([sys.executable, "-c", "print(\"DELETE_FAILED_INTERNAL_ERROR\"); print(\"BUILD SUCCESSFUL\")"]),
        )

    def test_nonzero_child_status_is_preserved(self) -> None:
        self.assertEqual(7, gate.run([sys.executable, "-c", "raise SystemExit(7)"]))


if __name__ == "__main__":
    unittest.main()
