from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import android_perfetto_query as perfetto


class AndroidPerfettoQueryTests(unittest.TestCase):
    def test_resolve_pixel_serial_uses_physical_pixel_target(self) -> None:
        with mock.patch.object(
            perfetto.preflight,
            "select_target",
            return_value={"status": "ok", "target": {"kind": "pixel", "serial": "pixel-serial"}},
        ) as select:
            serial = perfetto.resolve_pixel_serial(timeout_s=15)

        self.assertEqual("pixel-serial", serial)
        select.assert_called_once_with("pixel", False, timeout_s=15)

    def test_resolve_latest_trace_chooses_newest_existing_trace(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            older = root / "old" / "older.pftrace"
            newer = root / "new" / "newer.pftrace"
            older.parent.mkdir()
            newer.parent.mkdir()
            older.write_bytes(b"old")
            newer.write_bytes(b"new")
            os.utime(older, ns=(1_000_000_000, 1_000_000_000))
            os.utime(newer, ns=(2_000_000_000, 2_000_000_000))

            resolved = perfetto.resolve_latest_trace(search_roots=[root])

        self.assertEqual(newer.resolve(), resolved)

    def test_resolve_latest_trace_fails_without_trace(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(perfetto.PerfettoQueryError, "no existing Perfetto trace"):
                perfetto.resolve_latest_trace(search_roots=[Path(tmp)])

    def test_query_uses_query_file_instead_of_shell_quoting(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            processor = root / "trace_processor_shell_aarch64"
            trace = root / "trace.pftrace"
            processor.write_bytes(b"processor")
            trace.write_bytes(b"trace")
            calls: list[list[str]] = []

            def fake_run(args: list[str]) -> subprocess.CompletedProcess[str]:
                calls.append(args)
                stdout = "query-result\n" if "-q" in args else ""
                return subprocess.CompletedProcess(args, 0, stdout, "")

            with mock.patch.object(perfetto.preflight, "run_adb", side_effect=fake_run):
                output = perfetto.run_query(
                    trace,
                    "select 'a b';",
                    processor=processor,
                    serial="pixel-serial",
                )

        self.assertEqual("query-result\n", output)
        query_calls = [args for args in calls if "-q" in args]
        self.assertEqual(1, len(query_calls))
        query_call = query_calls[0]
        self.assertEqual(["-s", "pixel-serial", "shell"], query_call[:3])
        self.assertIn("-q", query_call)
        self.assertNotIn("select 'a b';", query_call)

    def test_missing_processor_fails_before_adb(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            trace = Path(tmp) / "trace.pftrace"
            trace.write_bytes(b"trace")
            with mock.patch.object(perfetto.preflight, "run_adb") as run_adb:
                with self.assertRaisesRegex(perfetto.PerfettoQueryError, "trace_processor_shell"):
                    perfetto.run_query(
                        trace,
                        "select 1;",
                        processor=Path(tmp) / "missing",
                        serial="pixel-serial",
                    )
        run_adb.assert_not_called()


if __name__ == "__main__":
    unittest.main()
