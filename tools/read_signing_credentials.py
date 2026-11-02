#!/usr/bin/env python3
"""Private stdout protocol for Gradle: consume only through a captured pipe."""
import json
import os
from pathlib import Path
import resource
import subprocess
import sys

FIELDS = ("ANDROID_SHARED_STORE_FILE", "ANDROID_SHARED_STORE_PASSWORD",
          "ANDROID_SHARED_KEY_ALIAS", "ANDROID_SHARED_KEY_PASSWORD")


def read_signing():
    runtime = Path.home() / ".local/bin/bws-credential"
    command = [str(runtime), "--ref", "FEDORA_ANDROID_SIGNING_ENV"]
    for name in FIELDS:
        command += ["--var", name]
    # The scoped runtime strips the bootstrap token; selected values only enter
    # this short-lived child, never the Gradle daemon's inherited environment.
    child = "import json,os; print(json.dumps({k:os.environ[k] for k in " + repr(FIELDS) + "}))"
    result = subprocess.run(command + ["--", sys.executable, "-c", child],
                            capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError("signing_vault_unavailable")
    values = json.loads(result.stdout)
    if set(values) != set(FIELDS) or any(not isinstance(v, str) or not v for v in values.values()):
        raise RuntimeError("signing_fields_incomplete")
    return values


if __name__ == "__main__":
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    # Refuse a terminal, to keep accidental manual invocations out of logs.
    if sys.stdout.isatty():
        raise SystemExit("Signing protocol requires a private captured pipe.")
    try:
        print(json.dumps(read_signing()))
    except Exception:
        raise SystemExit("Canonical signing unavailable from Bitwarden.") from None
