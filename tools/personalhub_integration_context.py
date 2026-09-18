#!/usr/bin/env python3
"""Emit bounded Git context for Codex semantic integration review.

This helper deliberately never decides whether a branch is safe to merge and never
modifies branches. It only gathers the mechanical facts Codex needs before review.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


class IntegrationContextError(RuntimeError):
    pass


def _run(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(
        ["git", "-C", str(repo), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and proc.returncode:
        raise IntegrationContextError(f"git_{args[0]}_failed:{proc.stderr.strip()}")
    return proc


def _repo_root(path: Path) -> Path:
    return Path(_run(path, "rev-parse", "--show-toplevel").stdout.strip())


def _default_branch(repo: Path) -> str:
    symbolic = _run(
        repo,
        "symbolic-ref",
        "--quiet",
        "--short",
        "refs/remotes/origin/HEAD",
        check=False,
    )
    if symbolic.returncode == 0:
        value = symbolic.stdout.strip()
        if value.startswith("origin/") and len(value) > len("origin/"):
            return value[len("origin/"):]

    remote = _run(repo, "ls-remote", "--symref", "origin", "HEAD")
    for line in remote.stdout.splitlines():
        if line.startswith("ref: refs/heads/") and line.endswith("\tHEAD"):
            return line.removeprefix("ref: refs/heads/").removesuffix("\tHEAD")
    raise IntegrationContextError("origin_default_branch_unresolved")


def _count(repo: Path, rev_range: str) -> int:
    return int(_run(repo, "rev-list", "--count", rev_range).stdout.strip())


def collect(repo: Path, branch: str, *, max_paths: int = 80) -> dict[str, object]:
    repo = _repo_root(repo)
    _run(repo, "fetch", "--quiet", "--prune", "origin")
    base_branch = _default_branch(repo)
    if branch in {base_branch, f"origin/{base_branch}"}:
        raise IntegrationContextError("candidate_is_canonical_branch")

    remote_head = branch if branch.startswith("origin/") else f"origin/{branch}"
    if _run(repo, "rev-parse", "--verify", remote_head, check=False).returncode != 0:
        raise IntegrationContextError(f"remote_branch_not_found:{branch}")

    base_ref = f"origin/{base_branch}"
    base_sha = _run(repo, "rev-parse", base_ref).stdout.strip()
    head_sha = _run(repo, "rev-parse", remote_head).stdout.strip()
    merge_base = _run(repo, "merge-base", base_ref, remote_head).stdout.strip()
    raw_paths = _run(repo, "diff", "--name-only", "-z", f"{base_ref}...{remote_head}").stdout
    paths = [item for item in raw_paths.split("\0") if item]

    return {
        "base_branch": base_branch,
        "base_ref": base_ref,
        "base_sha": base_sha,
        "head_branch": branch.removeprefix("origin/"),
        "head_ref": remote_head,
        "head_sha": head_sha,
        "merge_base": merge_base,
        "ahead_by": _count(repo, f"{base_ref}..{remote_head}"),
        "behind_by": _count(repo, f"{remote_head}..{base_ref}"),
        "changed_count": len(paths),
        "changed_paths": paths[:max_paths],
        "changed_paths_truncated": max(0, len(paths) - max_paths),
        "semantic_review_required": True,
        "automatic_merge_decision": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Collect bounded facts for one PersonalHub branch before semantic integration review."
    )
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--branch", required=True)
    parser.add_argument("--max-paths", type=int, default=80)
    args = parser.parse_args(argv)

    if args.max_paths < 1:
        parser.error("--max-paths must be >= 1")

    try:
        payload = collect(args.repo.expanduser(), args.branch, max_paths=args.max_paths)
    except IntegrationContextError as exc:
        print(json.dumps({"status": "blocked", "error": str(exc)}, sort_keys=True))
        return 2
    print(json.dumps({"status": "ok", **payload}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
