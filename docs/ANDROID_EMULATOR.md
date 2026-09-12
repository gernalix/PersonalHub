# Android emulator

`Pixel_8a` is the canonical PersonalHub AVD on this Fedora host. Discover its
runtime serial every time; never store or assume an `emulator-*` value.

Use the existing helper from the PersonalHub repository:

```bash
python3 tools/android_target_preflight.py status
python3 tools/android_target_preflight.py start
python3 tools/android_target_preflight.py wait
python3 tools/android_target_preflight.py stop
```

`start` is idempotent: it reuses a healthy `Pixel_8a`, waits on an existing
booting/offline `Pixel_8a` instead of launching a second one, otherwise starts it
from the CLI with a hidden Qt window, no boot animation, and no snapshot
load/save. Readiness means the discovered target is in ADB state `device` and
`sys.boot_completed=1`. The returned JSON contains the live serial to pass to
every later `adb -s <serial> ...` command. The older `--target emulator`
invocation remains supported.

The canonical cold boot deliberately does not depend on Quick Boot. On this
host the emulator reports that file-backed Quick Boot is unavailable on the
current filesystem; the verified cold boot is fast enough and avoids stale or
corrupt snapshot recovery. Android Studio and manual GUI interaction are not
required.

If startup is blocked, inspect `/tmp/personalhub-pixel_8a-emulator.log`. The
helper performs one controlled ADB recovery for failed device listings or
`offline` devices, and `stop` can terminate the canonical AVD through the
emulator process when ADB cannot address it as a normal `device`. For a boot
timeout, run `stop`, inspect the log, then `start`. Do not wipe or recreate the
AVD merely to recover a snapshot: snapshots are not used by this procedure. A
renderer fallback is attempted once only when the failed launch log contains
concrete GPU/renderer error evidence, and only after the first process has
terminated.
