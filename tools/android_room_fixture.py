#!/usr/bin/env python3
"""Create a Room-compatible disposable database inside a debuggable Android package.

This helper is intentionally narrow: it turns a tracked Room schema JSON into a
real SQLite database through the device's own sqlite3 binary, under run-as. It
avoids host-SQLite compatibility differences and ad-hoc push/cp/.read loops.

Example:
  python3 tools/android_room_fixture.py \
    --serial emulator-5554 \
    --package com.gernalix.personalhub.qa \
    --schema core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/11.json \
    --database personalhub.db \
    --seed-sql /tmp/ph-v11-seed.sql

The package must be debuggable and the target device must expose sqlite3.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys


def run(cmd: list[str], *, input_text: str | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        input=input_text,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=check,
    )


def adb(serial: str, *args: str, input_text: str | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    return run(["adb", "-s", serial, *args], input_text=input_text, check=check)


def adb_shell(serial: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return adb(serial, "shell", shlex.join(args), check=check)


def room_sql(schema_path: Path) -> tuple[int, str]:
    root = json.loads(schema_path.read_text(encoding="utf-8"))
    database = root["database"]
    version = int(database["version"])
    identity = str(database["identityHash"])
    statements = ["PRAGMA foreign_keys=OFF;"]
    for entity in database["entities"]:
        table = entity["tableName"]
        statements.append(entity["createSql"].replace("${TABLE_NAME}", table) + ";")
        for index in entity.get("indices", []):
            statements.append(index["createSql"].replace("${TABLE_NAME}", table) + ";")
    statements.extend(
        [
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT);",
            "INSERT OR REPLACE INTO room_master_table(id,identity_hash) VALUES(42," + json.dumps(identity) + ");",
            f"PRAGMA user_version={version};",
            "PRAGMA foreign_keys=ON;",
        ]
    )
    return version, "\n".join(statements) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--database", default="personalhub.db")
    parser.add_argument("--seed-sql", type=Path)
    args = parser.parse_args()

    version, sql = room_sql(args.schema)
    if args.seed_sql:
        sql += args.seed_sql.read_text(encoding="utf-8")
        if not sql.endswith("\n"):
            sql += "\n"

    sqlite = adb(args.serial, "shell", "command", "-v", "sqlite3", check=False)
    if sqlite.returncode != 0 or not sqlite.stdout.strip():
        print(json.dumps({"status": "blocked", "reason": "device_sqlite3_missing"}, sort_keys=True))
        return 2

    sandbox = adb(args.serial, "shell", "run-as", args.package, "pwd", check=False)
    if sandbox.returncode != 0 or not sandbox.stdout.strip():
        print(json.dumps({"status": "blocked", "reason": "run_as_unavailable", "detail": sandbox.stderr.strip()}, sort_keys=True))
        return 2
    data_dir = sandbox.stdout.strip()
    db_dir = f"{data_dir}/databases"
    db_path = f"{db_dir}/{args.database}"

    adb(args.serial, "shell", "am", "force-stop", args.package)
    adb(args.serial, "shell", "run-as", args.package, "mkdir", "-p", db_dir)
    # Operates only inside the explicitly supplied debuggable package sandbox.
    adb(args.serial, "shell", "run-as", args.package, "rm", "-f", db_path, f"{db_path}-wal", f"{db_path}-shm")

    created = adb_shell(
        args.serial,
        "run-as",
        args.package,
        "sqlite3",
        db_path,
        sql,
        check=False,
    )
    if created.returncode != 0:
        print(json.dumps({"status": "blocked", "reason": "fixture_create_failed", "detail": created.stderr.strip()}, sort_keys=True))
        return 2

    verify = adb_shell(
        args.serial,
        "run-as",
        args.package,
        "sqlite3",
        db_path,
        "PRAGMA user_version; PRAGMA integrity_check; SELECT identity_hash FROM room_master_table WHERE id=42;",
        check=False,
    )
    lines = [line.strip() for line in verify.stdout.splitlines() if line.strip()]
    ok = verify.returncode == 0 and len(lines) >= 3 and lines[0] == str(version) and lines[1].lower() == "ok"
    payload = {
        "status": "ok" if ok else "blocked",
        "database": db_path,
        "schema_version": version,
        "verification": lines,
    }
    if not ok:
        payload["reason"] = "fixture_verification_failed"
        payload["detail"] = verify.stderr.strip()
    print(json.dumps(payload, sort_keys=True))
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
