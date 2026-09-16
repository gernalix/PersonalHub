#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable


DEFAULT_ROOTS = ("app", "benchmark", "contracts", "core", "feature")
SKIP_PARTS = {"build", ".gradle", ".git"}


def repo_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if (candidate / "settings.gradle.kts").is_file() and (candidate / "gradlew").exists():
            return candidate
    raise RuntimeError("PersonalHub repository root not found")


def kotlin_files(root: Path, roots: Iterable[str]) -> list[Path]:
    files: list[Path] = []
    for raw in roots:
        base = (root / raw).resolve()
        try:
            base.relative_to(root)
        except ValueError as exc:
            raise RuntimeError(f"root escapes repository: {raw}") from exc
        if not base.exists():
            continue
        if base.is_file():
            if base.suffix == ".kt":
                files.append(base)
            continue
        for path in base.rglob("*.kt"):
            rel = path.relative_to(root)
            if any(part in SKIP_PARTS for part in rel.parts):
                continue
            files.append(path)
    return sorted(set(files))


def module_for_path(path: str) -> str | None:
    parts = Path(path).parts
    if not parts:
        return None
    if parts[0] in {"app", "benchmark"}:
        return f":{parts[0]}"
    if parts[0] in {"contracts", "core", "feature"} and len(parts) >= 2:
        return f":{parts[0]}:{parts[1]}"
    return None


def symbol_matches(root: Path, files: Iterable[Path], symbol: str) -> list[str]:
    matches: list[str] = []
    needle = symbol.encode("utf-8")
    for path in files:
        try:
            data = path.read_bytes()
        except OSError:
            continue
        if needle in data:
            matches.append(path.relative_to(root).as_posix())
    return matches


def scan(
    root: Path,
    roots: list[str],
    symbols: list[str],
    max_files: int,
) -> tuple[dict[str, object], int]:
    files = kotlin_files(root, roots)
    results: list[dict[str, object]] = []
    modules: set[str] = set()
    too_broad = False

    for symbol in symbols:
        matches = symbol_matches(root, files, symbol)
        visible = matches[:max_files]
        symbol_modules = sorted(
            module
            for module in {module_for_path(path) for path in matches}
            if module is not None
        )
        modules.update(symbol_modules)
        truncated = len(matches) > max_files
        too_broad = too_broad or truncated
        results.append(
            {
                "symbol": symbol,
                "count": len(matches),
                "files": visible,
                "modules": symbol_modules,
                "truncated": truncated,
            }
        )

    return (
        {
            "status": "too_broad" if too_broad else "ok",
            "roots": roots,
            "kotlin_files_scanned": len(files),
            "modules": sorted(modules),
            "results": results,
        },
        3 if too_broad else 0,
    )


def gate(
    root: Path,
    roots: list[str],
    forbidden: list[str],
    max_files: int,
) -> tuple[dict[str, object], int]:
    payload, _ = scan(root, roots, forbidden, max_files)
    remaining = [item for item in payload["results"] if item["count"]]
    return (
        {
            "status": "fail" if remaining else "pass",
            "roots": roots,
            "forbidden": remaining,
        },
        2 if remaining else 0,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Bounded exact-symbol consumer closure before Android/Gradle validation."
    )
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument(
        "--root",
        action="append",
        dest="roots",
        help="Repository-relative search root; repeatable. Defaults to Android code roots.",
    )
    parser.add_argument(
        "--max-files",
        type=int,
        default=60,
        help="Maximum paths printed per symbol; exceeding it returns status=too_broad and exit 3.",
    )

    commands = parser.add_subparsers(dest="command", required=True)
    scan_parser = commands.add_parser(
        "scan",
        help="List Kotlin consumer files/modules for exact literal symbols without printing source.",
    )
    scan_parser.add_argument("--symbol", action="append", required=True)

    gate_parser = commands.add_parser(
        "gate",
        help="Fail if any obsolete exact literal remains in Kotlin consumers.",
    )
    gate_parser.add_argument("--forbid", action="append", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.max_files < 1:
        raise SystemExit("--max-files must be >= 1")

    try:
        root = repo_root(args.repo)
        roots = args.roots or list(DEFAULT_ROOTS)
        if args.command == "scan":
            payload, exit_code = scan(root, roots, args.symbol, args.max_files)
        else:
            payload, exit_code = gate(root, roots, args.forbid, args.max_files)
    except RuntimeError as exc:
        print(json.dumps({"status": "error", "error": str(exc)}, sort_keys=True))
        return 4

    print(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
