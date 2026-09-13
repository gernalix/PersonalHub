#!/usr/bin/env python3
"""Create and optionally migrate/verify a Room-compatible disposable Android database.

The helper is intentionally narrow: it turns a tracked Room schema JSON into a
real SQLite database through the device's own sqlite3 binary under run-as.
Optional post-launch verification keeps Room migration QA in one deterministic
command instead of ad-hoc adb quoting/push/cp/.read loops.

Example fixture only:
  python3 tools/android_room_fixture.py \
    --serial emulator-5554 \
    --package com.gernalix.personalhub.qa \
    --schema core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/11.json \
    --database personalhub.db \
    --seed-sql /tmp/ph-v11-seed.sql

Example fixture + Room migration verification:
  python3 tools/android_room_fixture.py \
    --serial emulator-5554 \
    --package com.gernalix.personalhub.qa \
    --schema core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/11.json \
    --seed-sql /tmp/ph-v11-seed.sql \
    --launch-and-verify \
    --target-version 12 \
    --expect-table finance_recurrences \
    --verify-sql /tmp/ph-v12-verify.sql \
    --expect-line 'QA EUR|42.00|QA_MIGRATION'

The package must be debuggable and the target device must expose sqlite3.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shlex
import subprocess
import time


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
    # adb shell re-parses one command string on-device; shlex.join preserves
    # SQL and paths as single argv values.
    return adb(serial, "shell", shlex.join(args), check=check)


def sqlite(serial: str, package: str, db_path: str, sql: str) -> subprocess.CompletedProcess[str]:
    return adb_shell(serial, "run-as", package, "sqlite3", db_path, sql, check=False)


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def output_lines(result: subprocess.CompletedProcess[str]) -> list[str]:
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def room_sql(schema_path: Path) -> tuple[int, str, str]:
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
            f"INSERT OR REPLACE INTO room_master_table(id,identity_hash) VALUES(42,{sql_literal(identity)});",
            f"PRAGMA user_version={version};",
            "PRAGMA foreign_keys=ON;",
        ]
    )
    return version, identity, "\n".join(statements) + "\n"


def blocked(reason: str, **extra: object) -> int:
    print(json.dumps({"status": "blocked", "reason": reason, **extra}, sort_keys=True))
    return 2


def migration_health(
    serial: str,
    package: str,
    db_path: str,
) -> tuple[subprocess.CompletedProcess[str], list[str]]:
    result = sqlite(
        serial,
        package,
        db_path,
        "PRAGMA user_version; PRAGMA integrity_check; PRAGMA foreign_key_check;",
    )
    return result, output_lines(result)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--database", default="personalhub.db")
    parser.add_argument("--seed-sql", type=Path)
    parser.add_argument("--launch-and-verify", action="store_true")
    parser.add_argument("--target-version", type=int)
    parser.add_argument("--expect-table", action="append", default=[])
    parser.add_argument("--verify-sql", type=Path)
    parser.add_argument("--expect-line", action="append", default=[])
    parser.add_argument(
        "--launch-wait",
        type=float,
        default=3.0,
        help="maximum seconds to wait for Room to reach the target schema; 0 performs one check",
    )
    args = parser.parse_args()

    if args.launch_and_verify and args.target_version is None:
        parser.error("--target-version is required with --launch-and-verify")
    if not args.launch_and_verify and (args.target_version is not None or args.expect_table or args.verify_sql or args.expect_line):
        parser.error("post-migration verification options require --launch-and-verify")
    if args.expect_line and args.verify_sql is None:
        parser.error("--expect-line requires --verify-sql")
    if args.launch_wait < 0:
        parser.error("--launch-wait must be >= 0")

    version, identity, sql = room_sql(args.schema)
    if args.seed_sql:
        sql += args.seed_sql.read_text(encoding="utf-8")
        if not sql.endswith("\n"):
            sql += "\n"

    sqlite_path = adb(args.serial, "shell", "command", "-v", "sqlite3", check=False)
    if sqlite_path.returncode != 0 or not sqlite_path.stdout.strip():
        return blocked("device_sqlite3_missing")

    sandbox = adb(args.serial, "shell", "run-as", args.package, "pwd", check=False)
    if sandbox.returncode != 0 or not sandbox.stdout.strip():
        return blocked("run_as_unavailable", detail=sandbox.stderr.strip())
    data_dir = sandbox.stdout.strip()
    db_dir = f"{data_dir}/databases"
    db_path = f"{db_dir}/{args.database}"

    adb(args.serial, "shell", "am", "force-stop", args.package)
    adb(args.serial, "shell", "run-as", args.package, "mkdir", "-p", db_dir)
    # Operates only inside the explicitly supplied debuggable package sandbox.
    adb(args.serial, "shell", "run-as", args.package, "rm", "-f", db_path, f"{db_path}-wal", f"{db_path}-shm")

    created = sqlite(args.serial, args.package, db_path, sql)
    if created.returncode != 0:
        return blocked("fixture_create_failed", detail=created.stderr.strip())

    verify = sqlite(
        args.serial,
        args.package,
        db_path,
        "PRAGMA user_version; PRAGMA integrity_check; SELECT identity_hash FROM room_master_table WHERE id=42;",
    )
    lines = output_lines(verify)
    fixture_ok = (
        verify.returncode == 0
        and len(lines) >= 3
        and lines[0] == str(version)
        and lines[1].lower() == "ok"
        and lines[2] == identity
    )
    if not fixture_ok:
        return blocked(
            "fixture_verification_failed",
            database=db_path,
            schema_version=version,
            verification=lines,
            detail=verify.stderr.strip(),
        )

    payload: dict[str, object] = {
        "status": "ok",
        "database": db_path,
        "schema_version": version,
        "verification": lines,
    }

    if args.launch_and_verify:
        launched = adb(args.serial, "shell", "monkey", "-p", args.package, "1", check=False)
        if launched.returncode != 0:
            return blocked("package_launch_failed", detail=launched.stderr.strip() or launched.stdout.strip())

        deadline = time.monotonic() + args.launch_wait
        migrated_version: str | None = None
        health_lines: list[str] = []
        health_detail = ""
        while True:
            health_result, health_lines = migration_health(args.serial, args.package, db_path)
            migrated_version = health_lines[0] if health_lines else None
            health_detail = health_result.stderr.strip()
            target_reached = migrated_version == str(args.target_version)
            healthy = health_result.returncode == 0 and health_lines == [str(args.target_version), "ok"]
            if target_reached and healthy:
                break
            if target_reached:
                return blocked(
                    "post_migration_integrity_failed",
                    expected_version=args.target_version,
                    verification=health_lines,
                    detail=health_detail,
                )
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return blocked(
                    "target_version_not_reached",
                    expected_version=args.target_version,
                    actual_version=migrated_version,
                    verification=health_lines,
                    detail=health_detail,
                )
            time.sleep(min(0.25, remaining))

        table_results: dict[str, bool] = {}
        for table in args.expect_table:
            query = (
                "SELECT count(*) FROM sqlite_master "
                f"WHERE type='table' AND name={sql_literal(table)};"
            )
            result = sqlite(args.serial, args.package, db_path, query)
            rows = output_lines(result)
            present = result.returncode == 0 and rows == ["1"]
            table_results[table] = present
            if not present:
                return blocked(
                    "expected_table_missing",
                    table=table,
                    verification=rows,
                    detail=result.stderr.strip(),
                )

        custom_lines: list[str] = []
        if args.verify_sql:
            custom = sqlite(
                args.serial,
                args.package,
                db_path,
                args.verify_sql.read_text(encoding="utf-8"),
            )
            custom_lines = output_lines(custom)
            if custom.returncode != 0:
                return blocked("post_migration_query_failed", detail=custom.stderr.strip())
            missing = [line for line in args.expect_line if line not in custom_lines]
            if missing:
                return blocked(
                    "post_migration_expectation_failed",
                    missing_lines=missing,
                    verification=custom_lines,
                )

        payload["migration"] = {
            "target_version": args.target_version,
            "integrity": "ok",
            "foreign_keys": "ok",
            "tables": table_results,
            "verification": custom_lines,
        }

    print(json.dumps(payload, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())