#!/usr/bin/env python3
"""Audit the latest Room schema for timestamp columns that are not INTEGER epoch milliseconds.

This is intentionally not wired into CI until the v16 migration lands; it is the acceptance
oracle for that migration and should report zero violations before merge.
"""
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase"
MOMENT = re.compile(r"(?:timestamp(?:_utc)?|(?:created|updated|deleted|occurred|started|finished|scheduled|sent|saved|computed|fired|checked|opened|reminder|last_fired)(?:_at|At)|scheduled_for_utc|sent_at_utc)$", re.IGNORECASE)
# Dates, coordinates, elapsed times and measurements cannot be converted to instants.
DATE_ONLY = re.compile(r"(?:date(?:_utc)?|epoch_day|day_of_month)$", re.IGNORECASE)

def is_instant(column: str) -> bool:
    return not DATE_ONLY.search(column) and bool(MOMENT.search(column))

def main() -> int:
    versions = sorted(
        (int(path.stem), path)
        for path in SCHEMA_DIR.glob("*.json")
        if path.stem.isdigit()
    )
    if not versions:
        print("No Room schema files found", file=sys.stderr)
        return 2
    version, path = versions[-1]
    schema = json.loads(path.read_text())["database"]
    violations: list[str] = []
    for entity in schema["entities"]:
        table = entity["tableName"]
        for field in entity["fields"]:
            column = field["columnName"]
            if is_instant(column) and field["affinity"] != "INTEGER":
                violations.append(f"{table}.{column}: {field['affinity']}")
    print(f"Room schema v{version}: {len(violations)} timestamp storage violation(s)")
    for violation in violations:
        print(" -", violation)
    return 1 if violations else 0

if __name__ == "__main__":
    raise SystemExit(main())
