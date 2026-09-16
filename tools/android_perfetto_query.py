#!/usr/bin/env python3
from __future__ import annotations

import argparse
from collections.abc import Iterable
import json
import os
from pathlib import Path
import tempfile

import android_target_preflight as preflight


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROCESSOR = (
    ROOT
    / "benchmark/build/intermediates/assets/debug/mergeDebugAssets/trace_processor_shell_aarch64"
)
DEFAULT_TRACE_ROOTS = (Path("/tmp"), ROOT / "benchmark/build")


class PerfettoQueryError(RuntimeError):
    pass


def resolve_pixel_serial(*, timeout_s: float = 30.0) -> str:
    result = preflight.select_target("pixel", False, timeout_s=timeout_s)
    target = result.get("target") if isinstance(result, dict) else None
    if result.get("status") != "ok" or not isinstance(target, dict):
        reason = result.get("reason") if isinstance(result, dict) else "invalid_preflight_result"
        raise PerfettoQueryError(f"physical Pixel unavailable: {reason or 'unknown'}")
    if target.get("kind") != "pixel" or not target.get("serial"):
        raise PerfettoQueryError("preflight did not resolve the physical Pixel")
    return str(target["serial"])


def resolve_latest_trace(*, search_roots: Iterable[Path] = DEFAULT_TRACE_ROOTS) -> Path:
    """Return the newest existing ``.pftrace`` from the known PH runtime roots."""

    candidates: list[tuple[int, str, Path]] = []
    for root in search_roots:
        root = Path(root).expanduser()
        if not root.is_dir():
            continue
        try:
            traces = root.rglob("*.pftrace")
            for trace in traces:
                try:
                    if not trace.is_file():
                        continue
                    resolved = trace.resolve()
                    candidates.append((trace.stat().st_mtime_ns, str(resolved), resolved))
                except OSError:
                    continue
        except OSError:
            continue
    if not candidates:
        roots = ", ".join(str(Path(root).expanduser()) for root in search_roots)
        raise PerfettoQueryError(f"no existing Perfetto trace found under: {roots}")
    return max(candidates)[2]


def _adb(serial: str, args: list[str]) -> str:
    result = preflight.run_adb(["-s", serial, *args])
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "adb command failed").strip()
        raise PerfettoQueryError(detail[:1000])
    return result.stdout or ""


def run_query(
    trace: Path,
    query: str,
    *,
    processor: Path = DEFAULT_PROCESSOR,
    serial: str | None = None,
    timeout_s: float = 30.0,
) -> str:
    trace = trace.expanduser().resolve()
    processor = processor.expanduser().resolve()
    if not trace.is_file():
        raise PerfettoQueryError(f"trace not found: {trace}")
    if not processor.is_file():
        raise PerfettoQueryError(
            f"Android trace_processor_shell not found: {processor}; build benchmark debug assets first or pass --processor"
        )
    if not query.strip():
        raise PerfettoQueryError("query is empty")

    serial = serial or resolve_pixel_serial(timeout_s=timeout_s)
    suffix = f"{os.getpid()}"
    remote_processor = f"/data/local/tmp/ph_trace_processor_{suffix}"
    remote_trace = f"/data/local/tmp/ph_trace_{suffix}.pftrace"
    remote_query = f"/data/local/tmp/ph_trace_query_{suffix}.sql"

    fd, query_name = tempfile.mkstemp(prefix="ph-perfetto-", suffix=".sql")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(query.rstrip() + "\n")
        query_path = Path(query_name)
        _adb(serial, ["push", str(processor), remote_processor])
        _adb(serial, ["shell", "chmod", "755", remote_processor])
        _adb(serial, ["push", str(trace), remote_trace])
        _adb(serial, ["push", str(query_path), remote_query])
        return _adb(serial, ["shell", remote_processor, "-q", remote_query, remote_trace])
    finally:
        try:
            Path(query_name).unlink()
        except FileNotFoundError:
            pass
        if serial:
            try:
                _adb(serial, ["shell", "rm", "-f", remote_processor, remote_trace, remote_query])
            except PerfettoQueryError:
                pass


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Query a Perfetto trace on the physical Pixel using the benchmark Android trace processor."
    )
    parser.add_argument("trace", nargs="?", type=Path)
    parser.add_argument(
        "--latest-trace",
        action="store_true",
        help="use the newest existing .pftrace from /tmp or benchmark/build",
    )
    query_group = parser.add_mutually_exclusive_group(required=True)
    query_group.add_argument("--query")
    query_group.add_argument("--query-file", type=Path)
    parser.add_argument("--processor", type=Path, default=DEFAULT_PROCESSOR)
    parser.add_argument("--serial")
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args(argv)

    if args.latest_trace and args.trace is not None:
        parser.error("trace and --latest-trace are mutually exclusive")
    if not args.latest_trace and args.trace is None:
        parser.error("provide trace or --latest-trace")

    try:
        trace = resolve_latest_trace() if args.latest_trace else args.trace
        assert trace is not None
        query = (
            args.query
            if args.query is not None
            else args.query_file.expanduser().read_text(encoding="utf-8")
        )
        output = run_query(
            trace,
            query,
            processor=args.processor,
            serial=args.serial,
            timeout_s=max(1.0, args.timeout),
        )
    except (OSError, PerfettoQueryError) as exc:
        print(json.dumps({"status": "blocked", "reason": str(exc)}, sort_keys=True))
        return 2

    print(output, end="" if output.endswith("\n") or not output else "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
