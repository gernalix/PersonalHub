#!/usr/bin/env python3
"""Import repeatable QA evidence into a dedicated local SQLite history.

No device operations, background service, notification contents or full logs are
stored. A measured Activity launch is not functional acceptance of its screen.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import xml.etree.ElementTree as ET

DEFAULT_DB = Path.home() / ".local/share/personalhub-qa/test-history.sqlite3"
SCHEMA = """
CREATE TABLE IF NOT EXISTS runs (
 id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL UNIQUE,
 observed_at_utc TEXT NOT NULL, imported_at_utc TEXT NOT NULL,
 category TEXT NOT NULL, status TEXT NOT NULL,
 device_kind TEXT NOT NULL, device_model TEXT, android_api TEXT,
 app_id TEXT, app_version TEXT, variant TEXT, apk_sha256 TEXT,
 test_apk_sha256 TEXT, source_path TEXT NOT NULL, source_sha256 TEXT NOT NULL,
 context_json TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS test_results (
 run_id INTEGER NOT NULL REFERENCES runs(id), name TEXT NOT NULL,
 status TEXT NOT NULL, duration_ms REAL, PRIMARY KEY(run_id, name)
);
CREATE TABLE IF NOT EXISTS measurements (
 run_id INTEGER NOT NULL REFERENCES runs(id), module TEXT NOT NULL,
 metric TEXT NOT NULL, value REAL NOT NULL CHECK(value >= 0),
 unit TEXT NOT NULL, phase TEXT NOT NULL,
 PRIMARY KEY(run_id, module, metric)
);
CREATE INDEX IF NOT EXISTS runs_observed ON runs(observed_at_utc);
CREATE VIEW IF NOT EXISTS startup_history AS
 SELECT r.observed_at_utc, r.device_kind, r.device_model, r.android_api,
 r.app_id, r.app_version, r.variant, r.apk_sha256,
 m.module, m.metric, m.value, m.unit, m.phase, r.id AS run_id
 FROM measurements m JOIN runs r ON r.id=m.run_id
 WHERE r.category='am-start';
"""


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def utc(timestamp: float | None = None) -> str:
    return datetime.fromtimestamp(timestamp, timezone.utc).isoformat() if timestamp is not None else datetime.now(timezone.utc).isoformat()


def instrumentation(text: str) -> tuple[str, list[tuple], list[tuple]]:
    fields: dict[str, str] = {}
    cases: dict[str, tuple] = {}
    for line in text.splitlines():
        field = re.fullmatch(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if field:
            fields[field[1]] = field[2]
        code = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if code:
            value = int(code[1])
            if value in (0, -1, -2, -3, -4) and all(k in fields for k in ("class", "test")):
                name = fields["class"] + "#" + fields["test"]
                cases[name] = (name, "PASS" if value == 0 else "SKIP" if value in (-3, -4) else "FAIL", None)
            fields = {}
    failed = any(marker in text for marker in ("INSTRUMENTATION_FAILED", "INSTRUMENTATION_RESULT: shortMsg=", "FAILURES!!!", "FAIL-CLOSED:"))
    failed = failed or any(row[1] == "FAIL" for row in cases.values())
    count = re.search(r"\bOK \((\d+) tests?\)", text)
    complete = bool(count and re.search(r"^INSTRUMENTATION_CODE: -1$", text, re.M)
                    and len(cases) == int(count[1]) and any(row[1] == "PASS" for row in cases.values()))
    samples = []
    durations = re.findall(r"^Time: ([0-9]+(?:\.[0-9]+)?)$", text, re.M)
    if durations:
        elapsed_ms = float(durations[-1]) * 1000
        if math.isfinite(elapsed_ms):
            samples.append(("@suite", "test_suite_duration", elapsed_ms, "ms", "INSTRUMENTATION"))
    return ("FAIL" if failed else "PASS" if complete else "INCOMPLETE", list(cases.values()), samples)


def startup(text: str, module: str, app_id: str | None) -> tuple[str, list[tuple], list[tuple]]:
    fields = dict(re.findall(r"^([A-Za-z]+): (.+)$", text, re.M))
    if fields.get("Status") != "ok" or not re.search(r"^Complete$", text, re.M):
        return "FAIL", [], []
    if not module or not app_id or not fields.get("Activity", "").startswith(app_id + "/"):
        raise ValueError("Startup evidence requires a module and the matching Activity package")
    phase = fields.get("LaunchState", "UNKNOWN")
    samples = []
    for field, metric in (("TotalTime", "activity_total_time"), ("WaitTime", "activity_wait_time")):
        value = fields.get(field, "")
        if not re.fullmatch(r"\d+", value):
            raise ValueError(f"Missing valid {field}; reused Activity launches are not timed samples")
        samples.append((module, metric, float(value), "ms", phase))
    return "MEASURED", [], samples


def junit(text: str) -> tuple[str, list[tuple], list[tuple]]:
    root = ET.fromstring(text)
    cases = []
    for node in root.iter("testcase"):
        name = node.get("classname", "") + "#" + node.attrib["name"]
        status = "FAIL" if node.find("failure") is not None or node.find("error") is not None else "SKIP" if node.find("skipped") is not None else "PASS"
        seconds = node.get("time")
        elapsed = float(seconds) * 1000 if seconds is not None else None
        if elapsed is not None and (not math.isfinite(elapsed) or elapsed < 0):
            raise ValueError("Invalid JUnit duration")
        cases.append((name, status, elapsed))
    suite_failure = any(int(node.get(field, "0")) > 0 for node in root.iter("testsuite") for field in ("failures", "errors"))
    return ("FAIL" if suite_failure or any(c[1] == "FAIL" for c in cases) else "PASS" if any(c[1] == "PASS" for c in cases) else "INCOMPLETE", cases, [])


def unittest_result(text: str) -> tuple[str, list[tuple], list[tuple]]:
    reports = list(re.finditer(r"^Ran (\d+) tests? in ([0-9.]+)s$", text, re.M))
    if not reports:
        return "INCOMPLETE", [], []
    report = reports[-1]
    tail = text[report.end():]
    count, seconds = int(report[1]), float(report[2])
    if not math.isfinite(seconds) or seconds < 0:
        raise ValueError("Invalid unittest duration")
    failed = bool(re.search(r"^FAILED(?:\s|$)", tail, re.M))
    success = bool(re.search(r"^OK(?:\s|$)", tail, re.M))
    skipped = re.search(r"^OK \(skipped=(\d+)\)", tail, re.M)
    has_run_case = count > 0 and (skipped is None or int(skipped[1]) < count)
    status = "FAIL" if failed else "PASS" if success and has_run_case else "INCOMPLETE"
    # Non-verbose unittest logs prove a suite result, not individual case IDs.
    return status, [("unittest-suite", status, seconds * 1000)], []


def record(db_path: Path, source: Path, category: str, context: dict, *, module: str = "") -> dict:
    source = source.resolve(strict=True)
    if category not in ("instrumentation", "am-start", "junit", "unittest"):
        raise ValueError("Unsupported evidence category")
    data = source.read_text()
    context = dict(context)
    parsed = instrumentation(data) if category == "instrumentation" else startup(data, module, context.get("app_id")) if category == "am-start" else unittest_result(data) if category == "unittest" else junit(data)
    status, cases, samples = parsed
    if category == "unittest":
        reports = re.findall(r"^Ran (\d+) tests? in [0-9.]+s$", data, re.M)
        context.update(granularity="suite", reported_test_count=int(reports[-1]) if reports else 0)
        cases = [(context.get("suite", source.stem), row[1], row[2]) for row in cases]
    if context.get("process_exit_code", 0) != 0:
        status = "FAIL"
        if category == "am-start":
            samples = []
    # File timestamp is evidence time, not an invented execution start time.
    observed = utc(source.stat().st_mtime)
    source_hash = digest(source)
    context_json = json.dumps(context, sort_keys=True)
    fingerprint = hashlib.sha256(json.dumps([category, observed, source_hash, context, module], sort_keys=True).encode()).hexdigest()
    db_path = db_path.expanduser()
    db_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if not db_path.exists():
        db_path.touch(mode=0o600, exist_ok=False)
    with sqlite3.connect(db_path, timeout=15) as db:
        db.execute("PRAGMA foreign_keys=ON")
        version = db.execute("PRAGMA user_version").fetchone()[0]
        if version not in (0, 1):
            raise ValueError(f"Unsupported QA history schema: {version}")
        db.executescript(SCHEMA)
        db.execute("PRAGMA user_version=1")
        columns = ("device_kind", "device_model", "android_api", "app_id", "app_version", "variant", "apk_sha256", "test_apk_sha256")
        db.execute("""INSERT OR IGNORE INTO runs
          (fingerprint, observed_at_utc, imported_at_utc, category, status,
           device_kind, device_model, android_api, app_id, app_version, variant,
           apk_sha256, test_apk_sha256, source_path, source_sha256, context_json)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
          (fingerprint, observed, utc(), category, status, *(context.get(c) for c in columns), str(source), source_hash, context_json))
        run_id = db.execute("SELECT id FROM runs WHERE fingerprint=?", (fingerprint,)).fetchone()[0]
        db.executemany("INSERT OR IGNORE INTO test_results VALUES (?,?,?,?)", ((run_id, *row) for row in cases))
        db.executemany("INSERT OR IGNORE INTO measurements VALUES (?,?,?,?,?,?)", ((run_id, *row) for row in samples))
    return {"run_id": run_id, "category": category, "status": status, "tests": len(cases), "measurements": len(samples), "db": str(db_path)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("category", choices=("instrumentation", "am-start", "junit", "unittest", "summary"))
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--device-kind", choices=("host", "pixel", "tcl", "emulator"), default="host")
    for name in ("device-model", "android-api", "app-id", "app-version", "variant", "module"):
        parser.add_argument("--" + name)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    args = parser.parse_args()
    if args.category == "summary":
        with sqlite3.connect(f"file:{args.db.expanduser()}?mode=ro", uri=True) as db:
            rows = db.execute("SELECT category,status,count(*) FROM runs GROUP BY category,status ORDER BY category,status").fetchall()
        print(json.dumps(rows))
        return 0
    if not args.source:
        parser.error("--source is required")
    context = {key: getattr(args, key) for key in ("device_kind", "device_model", "android_api", "app_id", "app_version", "variant")}
    for attr in ("apk", "test_apk"):
        path = getattr(args, attr)
        if path:
            context[attr + "_sha256"] = digest(path)
    print(json.dumps(record(args.db, args.source, args.category, context, module=args.module or "")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
