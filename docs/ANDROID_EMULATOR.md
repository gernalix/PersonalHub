# Android emulator

`Pixel_8a` is the canonical PersonalHub AVD on this Fedora host. Discover its
runtime serial every time; never store or assume an `emulator-*` value.

Use the canonical control facade from the PersonalHub repository:

```bash
python3 tools/android_emulator_control.py status
python3 tools/android_emulator_control.py start
python3 tools/android_emulator_control.py wait
python3 tools/android_emulator_control.py stop
python3 tools/android_emulator_control.py smoke --timeout 90
```

`start` is idempotent: it reuses a healthy `Pixel_8a`, waits on an existing
booting/offline `Pixel_8a` instead of launching a second one, otherwise starts it
from the CLI with a hidden Qt window, no boot animation, and no snapshot
load/save. Readiness means the discovered target is in ADB state `device` and
`sys.boot_completed=1`. The returned JSON contains the live serial to pass to
every later `adb -s <serial> ...` command.

The facade delegates low-level ADB/AVD work to `android_target_preflight.py` but
adds safety guarantees for future automation: a failed `start` cleans only
Pixel_8a processes created by that invocation, never a pre-existing emulator;
an offline ADB row is not presented as the canonical target unless identity is
actually known; imports suppress Python bytecode writes so ordinary validation
cannot dirty the repository with `__pycache__`; and `smoke` reserves time for a
final stop instead of allowing startup to consume the cleanup budget. The older
preflight CLI remains available for compatibility, but new Codex workflows
should use the facade above.

## Canonical verification

Run the complete emulator infrastructure unit gate exactly once:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/test_android_emulator_stack.py
```

That runner loads the historical preflight suite, the regression suite and the
facade suite, and also rejects tracked `.pyc`, `.pyo` or `__pycache__` artifacts.
If it passes, do **not** rerun its component suites separately. For a task that
actually requires host/runtime verification, follow it with at most one:

```bash
python3 tools/android_emulator_control.py smoke --timeout 90
```

The smoke JSON already certifies boot completion, the live target, exactly one
canonical Pixel_8a process and a clean final stop. Do not repeat manual ADB/AVD
process discovery or a second stop/start sequence after a PASS. This two-command
flow is the complete emulator-infrastructure verification contract.

`--timeout` bounds each requested operation. `smoke` rejects unrealistically
small budgets before touching the emulator and reserves a cleanup window for the
final stop. A single low-level helper operation attempts each ADB recovery class
at most once, so a persistently `offline` device cannot trigger repeated
`adb reconnect offline` calls during polling.

The canonical cold boot deliberately does not depend on Quick Boot. On this
host the emulator reports that file-backed Quick Boot is unavailable on the
current filesystem; the verified cold boot is fast enough and avoids stale or
corrupt snapshot recovery. Android Studio and manual GUI interaction are not
required.

If startup is blocked, inspect `/tmp/personalhub-pixel_8a-emulator.log`. The
helper identifies the canonical AVD from its actual `Pixel_8a` host process;
an unrelated offline AVD does not block a Pixel_8a launch. `stop` can terminate
the canonical AVD through the emulator process when ADB cannot address it as a
normal `device`. For a boot timeout, inspect the returned JSON and emulator log;
do not immediately repeat an identical start without new evidence. Do not wipe
or recreate the AVD merely to recover a snapshot: snapshots are not used by
this procedure. A renderer fallback is attempted once only when the failed
launch log contains concrete GPU/renderer error evidence, and only after the
first process and any residual Pixel_8a PID have been terminated.
