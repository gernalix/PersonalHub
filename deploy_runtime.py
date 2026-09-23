#!/usr/bin/env python3
"""Install/refresh the PersonalHub consolidation watcher runtime."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess


ROOT = Path(__file__).resolve().parent
UNIT_SOURCE = ROOT / "systemd"
UNITS = (
    "personalhub-consolidation.service",
    "personalhub-consolidation.timer",
)


def checked(*args: str) -> None:
    subprocess.run(
        args,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    target = Path.home() / ".config/systemd/user"
    target.mkdir(parents=True, exist_ok=True)
    for name in UNITS:
        source = UNIT_SOURCE / name
        destination = target / name
        if not destination.exists() or destination.read_bytes() != source.read_bytes():
            shutil.copyfile(source, destination)

    checked("systemctl", "--user", "daemon-reload")
    checked("systemctl", "--user", "enable", "--now", "personalhub-consolidation.timer")
    # Evaluate immediately after a new PersonalHub revision is deployed, but do
    # not make github-autosync wait for an Android build.
    checked("systemctl", "--user", "start", "--no-block", "personalhub-consolidation.service")
    print("PersonalHub consolidation watcher deployed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
