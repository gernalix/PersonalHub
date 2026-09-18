#!/usr/bin/env python3
"""Regression gate for PersonalHub capsule, dependency, and persistence ownership boundaries."""

from __future__ import annotations

from collections import defaultdict
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []

PROJECT_DEPENDENCY = re.compile(r'project\("(:[^"]+)"\)')
PACKAGE_LINE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*$", re.MULTILINE)
IMPORT_LINE = re.compile(r"^\s*import\s+([^\s]+)(?:\s+as\s+\w+)?\s*$", re.MULTILINE)
TOP_LEVEL_DECLARATION = re.compile(
    r"^(?:(?:public|internal|private|protected|data|sealed|open|abstract|enum|annotation|value|expect|actual|inline|tailrec|operator|infix|suspend|const|lateinit)\s+)*"
    r"(?:class|interface|object|typealias|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)


def project_dependencies(build_file: Path) -> set[str]:
    return set(PROJECT_DEPENDENCY.findall(build_file.read_text()))


def kotlin_package(path: Path) -> str | None:
    match = PACKAGE_LINE.search(path.read_text())
    return match.group(1) if match else None


def kotlin_imports(path: Path) -> list[str]:
    return IMPORT_LINE.findall(path.read_text())


def kotlin_top_level_declarations(path: Path) -> set[str]:
    return set(TOP_LEVEL_DECLARATION.findall(path.read_text()))


# Gradle dependency direction: contracts -> nothing implementation-specific;
# core -> contracts/core only; a feature -> contracts/core and external libs, never another feature.
for build_file in ROOT.rglob("build.gradle.kts"):
    relative = build_file.relative_to(ROOT)
    parts = relative.parts
    dependencies = project_dependencies(build_file)
    if parts[:1] == ("contracts",):
        for dependency in sorted(dependencies):
            if dependency.startswith(":core:") or dependency.startswith(":feature:") or dependency == ":app":
                errors.append(f"{relative} contract module depends on implementation {dependency}")
    elif parts[:1] == ("core",):
        for dependency in sorted(dependencies):
            if dependency.startswith(":feature:") or dependency == ":app":
                errors.append(f"{relative} core module depends on feature/application {dependency}")
    elif len(parts) >= 2 and parts[0] == "feature":
        owner = parts[1]
        for dependency in sorted(dependencies):
            if dependency.startswith(":feature:") and dependency != f":feature:{owner}":
                errors.append(f"{relative} depends on feature implementation {dependency}")
            if dependency == ":app":
                errors.append(f"{relative} depends on host application {dependency}")


# Keep feature-owned persistence contracts out of generic core implementation source.
core_database_source = ROOT / "core/database/src/main/java"
for forbidden in (
    "com/supercontacts",
    "com/wordpulse",
    "com/gernalix/luoghi",
    "com/gernalix/sostanze",
):
    if (core_database_source / forbidden).exists():
        errors.append(f"feature persistence returned to generic core: {forbidden}")

for forbidden_file in ("TimerEntities.kt", "PeoplePhoto.kt"):
    if (core_database_source / "com/gernalix/personalhub/core/database" / forbidden_file).exists():
        errors.append(f"feature database contract returned to generic core: {forbidden_file}")


# Build package and symbol ownership indices from actual Kotlin sources. Contract-owned
# Room entities intentionally retain some legacy package names for compatibility, so a
# package can span contracts + implementation. Exact imported symbols must therefore win
# over package fallback or the gate reports false contract->implementation crossings.
source_roots: dict[str, Path] = {
    "app": ROOT / "app/src/main",
    "contracts:database": ROOT / "contracts/database/src/main",
    "core:database": ROOT / "core/database/src/main",
    "core:hub-context": ROOT / "core/hub-context/src/main",
    "core:alerts": ROOT / "core/alerts/src/main",
}
for feature_dir in sorted((ROOT / "feature").glob("*")):
    if feature_dir.is_dir():
        source_roots[f"feature:{feature_dir.name}"] = feature_dir / "src/main"

package_owners: dict[str, set[str]] = defaultdict(set)
symbol_owners: dict[str, set[str]] = defaultdict(set)
source_files: dict[str, list[Path]] = defaultdict(list)
for owner, source_root in source_roots.items():
    if not source_root.exists():
        continue
    for kotlin in source_root.rglob("*.kt"):
        source_files[owner].append(kotlin)
        package = kotlin_package(kotlin)
        if package:
            package_owners[package].add(owner)
            for declaration in kotlin_top_level_declarations(kotlin):
                symbol_owners[f"{package}.{declaration}"].add(owner)


def resolve_import_owner(import_name: str) -> str | None:
    target = import_name.removesuffix(".*")

    # Prefer exact symbol ownership. This is essential for split legacy packages where
    # stable entity/DAO contracts live in :contracts:database while runtime helpers remain
    # in a feature module under the same Kotlin package.
    owners = symbol_owners.get(target)
    if owners and len(owners) == 1:
        return next(iter(owners))

    parts = target.split(".")
    # Imports may target members or symbols we do not parse; walk upward to a uniquely
    # owned package as a conservative fallback.
    for size in range(len(parts), 0, -1):
        package = ".".join(parts[:size])
        owners = package_owners.get(package)
        if owners and len(owners) == 1:
            return next(iter(owners))
    return None


# Source-level capsule rules:
# - feature implementations never import another feature implementation;
# - core never imports a feature implementation;
# - contracts never import core/feature/application implementations;
# - the app composition root may see a feature only through explicit api/ or hub/ packages.
for owner, files in source_files.items():
    for kotlin in files:
        relative = kotlin.relative_to(ROOT)
        for imported in kotlin_imports(kotlin):
            imported_owner = resolve_import_owner(imported)
            if imported_owner is None or imported_owner == owner:
                continue

            if owner.startswith("feature:") and imported_owner.startswith("feature:"):
                errors.append(f"{relative} crosses feature capsules via import {imported}")
            elif owner.startswith("core:") and imported_owner.startswith("feature:"):
                errors.append(f"{relative} core imports feature implementation {imported}")
            elif owner.startswith("contracts:") and (
                imported_owner == "app" or imported_owner.startswith("core:") or imported_owner.startswith("feature:")
            ):
                errors.append(f"{relative} contract imports implementation {imported}")
            elif owner == "app" and imported_owner.startswith("feature:"):
                if ".api." not in imported and ".hub." not in imported:
                    errors.append(
                        f"{relative} composition root bypasses feature public surface: {imported} "
                        "(allowed feature surfaces: api, hub)"
                    )


if errors:
    print("ARCHITECTURE_BOUNDARIES=FAIL")
    print("\n".join(sorted(set(errors))))
    sys.exit(1)

print("ARCHITECTURE_BOUNDARIES=PASS")
