from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

import android_consumer_preflight as preflight


class AndroidConsumerPreflightTest(unittest.TestCase):
    def make_repo(self) -> tuple[tempfile.TemporaryDirectory[str], Path]:
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        (root / "settings.gradle.kts").write_text("", encoding="utf-8")
        (root / "gradlew").write_text("", encoding="utf-8")
        return temp, root

    def write(self, root: Path, rel: str, text: str) -> None:
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_scan_returns_only_matching_paths_and_modules(self) -> None:
        temp, root = self.make_repo()
        try:
            self.write(root, "feature/luoghi/src/Foo.kt", "val x: OldType? = null")
            self.write(root, "core/database/src/Bar.kt", "fun value(): OldType = TODO()")
            self.write(root, "app/src/Unrelated.kt", "class Unrelated")

            payload, code = preflight.scan(
                root,
                list(preflight.DEFAULT_ROOTS),
                ["OldType"],
                max_files=60,
            )

            self.assertEqual(0, code)
            self.assertEqual("ok", payload["status"])
            self.assertEqual([":core:database", ":feature:luoghi"], payload["modules"])
            result = payload["results"][0]
            self.assertEqual(2, result["count"])
            self.assertEqual(
                ["core/database/src/Bar.kt", "feature/luoghi/src/Foo.kt"],
                result["files"],
            )
        finally:
            temp.cleanup()

    def test_gate_fails_until_obsolete_symbol_is_removed(self) -> None:
        temp, root = self.make_repo()
        try:
            self.write(root, "feature/luoghi/src/Foo.kt", "val x: OldType? = null")
            payload, code = preflight.gate(
                root,
                list(preflight.DEFAULT_ROOTS),
                ["OldType"],
                max_files=60,
            )
            self.assertEqual(2, code)
            self.assertEqual("fail", payload["status"])

            self.write(root, "feature/luoghi/src/Foo.kt", "val x: NewType? = null")
            payload, code = preflight.gate(
                root,
                list(preflight.DEFAULT_ROOTS),
                ["OldType"],
                max_files=60,
            )
            self.assertEqual(0, code)
            self.assertEqual("pass", payload["status"])
        finally:
            temp.cleanup()

    def test_too_broad_is_bounded_and_nonzero(self) -> None:
        temp, root = self.make_repo()
        try:
            for index in range(3):
                self.write(root, f"feature/m{index}/src/F{index}.kt", "val x = SharedSymbol")

            payload, code = preflight.scan(
                root,
                list(preflight.DEFAULT_ROOTS),
                ["SharedSymbol"],
                max_files=2,
            )

            self.assertEqual(3, code)
            self.assertEqual("too_broad", payload["status"])
            result = payload["results"][0]
            self.assertEqual(3, result["count"])
            self.assertEqual(2, len(result["files"]))
            self.assertTrue(result["truncated"])
        finally:
            temp.cleanup()


if __name__ == "__main__":
    unittest.main()
