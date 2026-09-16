#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import sys
import zipfile

MAX_BUNDLE_BYTES = 200 * 1024 * 1024


def main() -> int:
    root = Path.cwd()
    bundles = sorted((root / "app/build/outputs/bundle/play").glob("*.aab"))
    if len(bundles) != 1:
        print(f"PLAY_BUNDLE=FAIL expected exactly one AAB, found {len(bundles)}", file=sys.stderr)
        return 1
    bundle = bundles[0]
    if bundle.stat().st_size > MAX_BUNDLE_BYTES:
        print(f"PLAY_BUNDLE=FAIL size={bundle.stat().st_size} exceeds 200 MiB preflight ceiling", file=sys.stderr)
        return 1
    try:
        with zipfile.ZipFile(bundle) as archive:
            names = set(archive.namelist())
            if "base/manifest/AndroidManifest.xml" not in names:
                print("PLAY_BUNDLE=FAIL base manifest missing", file=sys.stderr)
                return 1
            native = sorted(name for name in names if name.startswith("base/lib/") and name.endswith(".so"))
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"PLAY_BUNDLE=FAIL {exc}", file=sys.stderr)
        return 2

    if native:
        print("PLAY_BUNDLE=FAIL native libraries present; 16 KiB page-size compatibility needs explicit validation", file=sys.stderr)
        for name in native[:20]:
            print(f"NATIVE_LIB={name}", file=sys.stderr)
        return 1

    print(f"PLAY_BUNDLE=PASS path={bundle} size={bundle.stat().st_size} native_libs=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
