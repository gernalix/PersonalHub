#!/usr/bin/env python3
"""Guarded, paired schema-23→24 or schema-24→25 cutover for a tested final APK.

This is an explicit production operation, NOT an ordinary test. Never invoke
without a verified exclusive Pixel notification window. The default is no-op.
The pre-existing migrator owns SQLite transformation and source preservation.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import shlex
import sqlite3
import subprocess
import sys
import tarfile
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

from migrate_personalhub_v23_to_v24 import migrate
from migrate_personalhub_v21_to_v23 import validate as validate_room
from android_pixel_apk import install as install_apk
from android_target_preflight import resolve_tool, select_target
from verify_apk_version import verify_final_apk

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.gernalix.personalhub"
DATABASE = "personalhub.db"
SIDECARS = (DATABASE, DATABASE + "-wal", DATABASE + "-shm")


class CutoverBlocked(RuntimeError):
    pass


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_notification_receipt(path: Path) -> None:
    receipt = json.loads(path.read_text())
    if not (
        receipt.get("phase") == "pre"
        and receipt.get("fedora") is True
        and receipt.get("telegram") is True
    ):
        raise CutoverBlocked("Verified pre-test Fedora and Telegram notification receipt required")


def require_single_profile(xml_bytes: bytes) -> None:
    """Prevent a partial cutover when a second database profile exists."""
    if not xml_bytes:
        raise CutoverBlocked("Profile registry unavailable; cannot prove a single active database")
    values = {node.attrib.get("name"): node.text or "" for node in ET.fromstring(xml_bytes)}
    if values.get("pending_switch_from") or values.get("pending_switch_to"):
        raise CutoverBlocked("Pending profile switch; freeze all profiles before cutover")
    registry = json.loads(values.get("profiles_json", "[]"))
    if len(registry) != 1 or registry[0].get("id") != values.get("active_profile", "personal"):
        raise CutoverBlocked("Multiple/unknown database profiles; this runner only handles a single profile")


class Pixel:
    def __init__(self, serial: str):
        resolved = select_target("pixel", False, timeout_s=30.0)
        target = resolved.get("target", {})
        if resolved.get("status") != "ok" or target.get("serial") != serial or target.get("kind") != "pixel":
            raise CutoverBlocked("Specified device is not the verified physical Pixel")
        self.serial = serial
        self.adb = resolve_tool("adb")

    def run(self, *args: str, input_bytes: bytes | None = None) -> bytes:
        result = subprocess.run(
            [self.adb, "-s", self.serial, *args],
            input=input_bytes, check=True, capture_output=True, timeout=120,
        )
        return result.stdout

    def shell(self, *args: str) -> str:
        return self.run("shell", *args).decode().strip()

    def app(self, command: str, input_bytes: bytes | None = None) -> str:
        # Command runs as the app's own UID; no root or global filesystem writes.
        return self.run(
            "shell", "-T", "run-as " + PACKAGE + " sh -c " + shlex.quote(command),
            input_bytes=input_bytes,
        ).decode().strip()

    def frozen(self) -> bool:
        result = subprocess.run(
            [self.adb, "-s", self.serial, "shell", "pidof", PACKAGE],
            capture_output=True, timeout=15,
        )
        return not result.stdout.strip()

    def installed_apk(self) -> str:
        matches = self.shell("pm", "path", PACKAGE).splitlines()
        if len(matches) != 1 or not matches[0].startswith("package:/"):
            raise CutoverBlocked("Expected a single installed APK for paired rollback")
        return matches[0].removeprefix("package:")

    def freeze(self) -> None:
        self.shell("pm", "disable-user", "--user", "0", PACKAGE)
        self.shell("am", "force-stop", PACKAGE)
        if not self.frozen():
            raise CutoverBlocked("PersonalHub is still running after package disable")

    def enable(self) -> None:
        self.shell("pm", "default-state", "--user", "0", PACKAGE)


def read_frozen_database(device: Pixel, root: Path, source_version: int = 23) -> tuple[Path, dict[str, str]]:
    """Retrieve only the DB and sidecars; produce a coherent, WAL-aware snapshot."""
    if not device.frozen():
        raise CutoverBlocked("The application resumed; cannot snapshot live data")
    try:
        prefs = device.run("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/personalhub_profiles.xml")
    except subprocess.CalledProcessError:
        prefs = b""
    require_single_profile(prefs)
    names = []
    for name in SIDECARS:
        path = "databases/" + name
        if device.app("test -f " + path + " && echo yes || true") == "yes":
            names.append(path)
    if "databases/" + DATABASE not in names:
        raise CutoverBlocked("PersonalHub database missing")
    raw = device.run("exec-out", "run-as", PACKAGE, "tar", "-cf", "-", *names)
    file_hashes: dict[str, str] = {}
    with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        entries = archive.getmembers()
        if {entry.name for entry in entries} != set(names):
            raise CutoverBlocked("Unexpected or missing DB backup entries")
        for entry in entries:
            if not entry.isfile() or entry.size > 512 * 1024 * 1024:
                raise CutoverBlocked("Unsafe DB backup archive member")
            out = root / "raw" / entry.name
            out.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            out.write_bytes(archive.extractfile(entry).read())
            out.chmod(0o600)
            file_hashes[entry.name] = sha(out)
    source = root / f"frozen-v{source_version}.db"
    with sqlite3.connect(f"file:{root / 'raw/databases/personalhub.db'}?mode=ro", uri=True) as src:
        with sqlite3.connect(source) as dest:
            src.backup(dest)
    source.chmod(0o600)
    with sqlite3.connect(f"file:{source}?mode=ro", uri=True) as check:
        if check.execute("PRAGMA user_version").fetchone()[0] != source_version:
            raise CutoverBlocked(f"Cutover requires schema {source_version}; source has a different schema")
        validate_room(check, source_version)
    return source, file_hashes


def assert_still_frozen(device: Pixel, expected: dict[str, str]) -> None:
    if not device.frozen():
        raise CutoverBlocked("App resumed after backup; discard prepared migration")
    for file, expected_hash in expected.items():
        if file.endswith("-shm"):
            # SQLite may rebuild the derived WAL-index; protect DB + WAL contents.
            continue
        actual = device.app("sha256sum " + file).split()[0]
        if actual != expected_hash:
            raise CutoverBlocked("Frozen DB or WAL changed after snapshot; aborting")


def stage_and_swap(device: Pixel, prepared: Path, report: dict, expected: dict[str, str], token: str, target_version: int = 24) -> str:
    assert_still_frozen(device, expected)
    stage = f"databases/.ph-v{target_version}-" + token
    rollback = "files/ph-cutover-rollback-" + token
    if device.app("test ! -e " + stage + " && test ! -e " + rollback + " && echo ok") != "ok":
        raise CutoverBlocked("Existing stage or rollback path conflicts with this cutover")
    # Stage first and verify before any original file moves.
    device.app("umask 077; cat > " + stage, input_bytes=prepared.read_bytes())
    if device.app("sha256sum " + stage).split()[0] != report["output_sha256"]:
        raise CutoverBlocked("Staged database hash mismatch")
    device.app("umask 077; mkdir " + rollback)
    moved = []
    try:
        for file in expected:
            device.app("mv " + file + " " + rollback + "/" + Path(file).name)
            moved.append(file)
        device.app("mv " + stage + " databases/" + DATABASE)
        device.app("chmod 600 databases/" + DATABASE)
        if device.app("sha256sum databases/" + DATABASE).split()[0] != report["output_sha256"]:
            raise CutoverBlocked("Published database differs from prepared validation")
    except Exception:
        # If no original file moved, preserve the original live DB as-is.
        # Otherwise the only possible file at the live path is the staged target.
        if moved:
            device.app("test ! -f databases/" + DATABASE +
                       " || mv databases/" + DATABASE + " " + rollback + f"/failed-v{target_version}.db")
        for file in reversed(moved):
            device.app("mv " + rollback + "/" + Path(file).name + " " + file)
        raise
    return rollback


def paired_rollback(device: Pixel, rollback: str, old_apk: Path, token: str) -> None:
    """Restore old schema and old APK while disabled; keep failed new data."""
    device.freeze()
    failed = "files/ph-cutover-failed-" + token
    device.app("umask 077; mkdir " + failed)
    for file in SIDECARS:
        device.app("test ! -f databases/" + file +
                   " || mv databases/" + file + " " + failed + "/" + file)
        device.app("test ! -f " + rollback + "/" + file +
                   " || mv " + rollback + "/" + file + " databases/" + file)
    install_apk(old_apk, device.serial, timeout_s=120, paired_rollback=True)
    if device.shell("sha256sum", device.installed_apk()).split()[0] != sha(old_apk):
        raise CutoverBlocked("Old APK rollback hash mismatch; package remains disabled")
    device.enable()


def execute(args: argparse.Namespace) -> dict:
    require_notification_receipt(args.pre_notification)
    if not args.execute or not args.allow_production:
        raise CutoverBlocked("Both --execute and --allow-production are required")
    if args.output_dir.exists() or args.output_dir.resolve().is_relative_to(ROOT):
        raise CutoverBlocked("Output directory must be new, private and outside the repository")
    upgrade = getattr(args, "schema_upgrade", "23-to-24")
    if upgrade not in ("23-to-24", "24-to-25"):
        raise CutoverBlocked("Unsupported schema upgrade")
    source_version, target_version = (24, 25) if upgrade == "24-to-25" else (23, 24)
    verify_final_apk(args.new_apk)
    os.umask(0o077)
    args.output_dir.mkdir(mode=0o700, parents=True)
    device = Pixel(args.serial)
    token = uuid.uuid4().hex[:12]
    old_apk = args.output_dir / "previous.apk"
    frozen = False
    swapped = False
    rollback = None
    file_hashes: dict[str, str] = {}
    try:
        old_path = device.installed_apk()
        device.run("pull", old_path, str(old_apk))
        old_apk.chmod(0o600)
        frozen = True
        device.freeze()
        source, file_hashes = read_frozen_database(device, args.output_dir, source_version)
        target = args.output_dir / f"prepared-v{target_version}.db"
        if source_version == 24:
            from migrate_personalhub_v24_to_v25 import migrate as migrate_countdown
            report = migrate_countdown(source, target)
        else:
            report = migrate(source, target, qa_overlay_decision=args.qa_overlay_decision)
        if report.get("source_version") != source_version or report.get("target_version") != target_version:
            raise CutoverBlocked("Migration reported an unexpected source or target schema")
        if report["status"] != "PASS" or report["source_snapshot_sha256"] != sha(source):
            raise CutoverBlocked("Migration continuity validation failed")
        if report["output_sha256"] != sha(target):
            raise CutoverBlocked("Prepared database hash mismatch")
        assert_still_frozen(device, file_hashes)
        rollback = stage_and_swap(device, target, report, file_hashes, token, target_version)
        swapped = True
        install_apk(args.new_apk, device.serial, timeout_s=120)
        installed = device.shell("sha256sum", device.installed_apk()).split()[0]
        if installed != sha(args.new_apk):
            raise CutoverBlocked("Installed APK hash differs from the validated final artifact")
        device.enable()
        frozen = False
        result = {
            "status": "PAIR_INSTALLED_QA_PENDING", "new_apk_sha256": installed,
            "previous_apk_sha256": sha(old_apk),
            "source_sha256": report["source_snapshot_sha256"],
            "new_db_sha256": report["output_sha256"],
            "source_schema": source_version, "target_schema": target_version,
            "rollback_on_device": rollback, "previous_apk": str(old_apk),
            "pre_notifications_verified": True, "post_notifications_required": True,
            "no_clear_data": True, "profile_count": 1,
        }
        (args.output_dir / "cutover-receipt.json").write_text(json.dumps(result, indent=2))
        return result
    except Exception as exc:
        if swapped and rollback:
            try:
                paired_rollback(device, rollback, old_apk, token)
                frozen = False
            except Exception as recovery:
                raise CutoverBlocked(
                    "Cutover failed and paired rollback failed; leave app disabled. "
                    f"Original: {type(exc).__name__}; rollback: {type(recovery).__name__}"
                ) from recovery
        raise
    finally:
        if frozen and not swapped:
            # Never re-enable after an uncertain partial swap. Stage-and-swap
            # must have restored every original byte before this assertion passes.
            try:
                if file_hashes:
                    assert_still_frozen(device, file_hashes)
            except Exception as unsafe:
                raise CutoverBlocked(
                    "Original database state unverified; leave package disabled for recovery"
                ) from unsafe
            device.enable()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Gate-verified physical Pixel serial")
    parser.add_argument("--new-apk", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--pre-notification", required=True, type=Path)
    parser.add_argument("--schema-upgrade", choices=["23-to-24", "24-to-25"], default="23-to-24")
    parser.add_argument("--qa-overlay-decision", choices=["preserve"])
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--allow-production", action="store_true")
    args = parser.parse_args()
    try:
        result = execute(args)
    except Exception as error:
        print(json.dumps({"status": "BLOCKED", "reason": str(error)}))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
