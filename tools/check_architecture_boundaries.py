#!/usr/bin/env python3
"""Small regression gate for PersonalHub module and persistence ownership."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
FEATURES = sorted((ROOT / "feature").glob("*/build.gradle.kts"))
errors: list[str] = []

for build_file in FEATURES:
    owner = build_file.parent.name
    text = build_file.read_text()
    for dependency in re.findall(r'project\("(:feature:[^"]+)"\)', text):
        if dependency != f":feature:{owner}":
            errors.append(f"{build_file.relative_to(ROOT)} depends on feature implementation {dependency}")

core_source = ROOT / "core/database/src/main/java"
for forbidden in (
    "com/supercontacts",
    "com/wordpulse",
    "com/gernalix/luoghi",
    "com/gernalix/sostanze",
):
    if (core_source / forbidden).exists():
        errors.append(f"feature persistence returned to generic core: {forbidden}")

for forbidden_file in ("TimerEntities.kt", "PeoplePhoto.kt"):
    if (core_source / "com/gernalix/personalhub/core/database" / forbidden_file).exists():
        errors.append(f"feature database contract returned to generic core: {forbidden_file}")

for kotlin in (ROOT / "app/src/main").rglob("*.kt"):
    for line in kotlin.read_text().splitlines():
        if line.startswith("import ") and any(part in line for part in (".data.", ".repository.", ".internal.", ".impl.")):
            errors.append(f"app composition imports feature internals: {kotlin.relative_to(ROOT)}: {line}")

if errors:
    print("ARCHITECTURE_BOUNDARIES=FAIL")
    print("\n".join(errors))
    sys.exit(1)

print("ARCHITECTURE_BOUNDARIES=PASS")
