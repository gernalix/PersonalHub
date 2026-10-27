#!/usr/bin/env bash
# QA package only; never run against a real PersonalHub installation.
set -euo pipefail
OUT="$PWD/audit/20261027/dynamic-results"
mkdir -p "$OUT"
PKG=com.gernalix.personalhub.qa
SERIAL="$(adb devices | awk '$2=="device" && $1 ~ /^emulator-[0-9]+$/ {print $1}')"
if [ -z "$SERIAL" ] || [ "$(printf '%s\n' "$SERIAL" | wc -l)" -ne 1 ]; then
  echo "BLOCKED: exactly one online emulator required" > "$OUT/status.txt"; exit 2
fi
adb_target() { adb -s "$SERIAL" "$@"; }
APK="$(find app/build/outputs/apk/qa -name '*.apk' -type f -print -quit)"
test -f "$APK"
echo "ephemeral=$SERIAL package=$PKG" > "$OUT/status.txt"
adb_target install -r "$APK" >> "$OUT/status.txt"
adb_target shell pm path "$PKG" >> "$OUT/status.txt"
adb_target shell dumpsys package "$PKG" | grep -iE 'DEBUGGABLE|profileable|pkgFlags' > "$OUT/flags.txt" || true
profile() {
  local name="$1" activity="$2"
  adb_target shell am force-stop "$PKG"
  adb_target shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1 || true
  adb_target shell am start -W -n "$PKG/$activity" > "$OUT/start-$name.txt" 2>&1 || true
  sleep 1
  adb_target shell dumpsys gfxinfo "$PKG" framestats > "$OUT/gfxinfo-$name.txt" || true
  adb_target shell dumpsys meminfo "$PKG" > "$OUT/meminfo-$name.txt" || true
}
profile home com.gernalix.personalhub.MainActivity
profile places com.gernalix.personalhub.shortcut.PlacesShortcutActivity
profile timer com.gernalix.personalhub.shortcut.TimerShortcutActivity
profile money com.gernalix.personalhub.shortcut.SoldiShortcutActivity
adb_target shell am force-stop "$PKG"
adb_target shell perfetto --background-wait -o /data/misc/perfetto-traces/ph-audit.pftrace \
 -t 12s --app "$PKG" sched freq idle am wm gfx view binder_driver hal dalvik \
 > "$OUT/perfetto-start.txt" 2>&1 || true
adb_target shell am start -W -n "$PKG/com.gernalix.personalhub.MainActivity" > "$OUT/perfetto-flow.txt" 2>&1 || true
sleep 14
adb_target pull /data/misc/perfetto-traces/ph-audit.pftrace "$OUT/trace.pftrace" > "$OUT/perfetto-pull.txt" 2>&1 || true
adb_target shell am start -W -n "$PKG/com.gernalix.personalhub.DatabaseActivity" > "$OUT/nonexported-activity.txt" 2>&1 || true
adb_target shell content query --uri "content://$PKG.luoghi.places/places" > "$OUT/nonexported-provider.txt" 2>&1 || true
adb_target logcat -c
for uri in \
 'personalhub://entity/v1/people/person/42?action=delete' \
 'personalhub://entity/v1/people/person/42?action=view&action=edit' \
 'personalhub://search/v1?module=places&scope_module=people' \
 'personalhub://search/v1?from=INVALID' \
 'personalhub://since_when/v1/create?source=%7B%7D' \
 'personalhub://context/v1/..%2F..' \
 'personalhub://event/v2/mismatch' \
 'personalhub://entity/v1/%2F/%3F/%25' ; do
  adb_target shell am start -W -n "$PKG/com.gernalix.personalhub.HubDeepLinkActivity" \
   -a android.intent.action.VIEW -d "$uri" >> "$OUT/deeplink.txt" 2>&1 || true
done
adb_target logcat -d -s AndroidRuntime:E | grep -A 18 -B 2 -F "$PKG" > "$OUT/app-crashes.txt" || true
if grep -q 'Permission Denial' "$OUT/nonexported-activity.txt"; then echo "PRIVATE_ACTIVITY=DENIED" >> "$OUT/status.txt"; else echo "PRIVATE_ACTIVITY=INCONCLUSIVE" >> "$OUT/status.txt"; fi
if grep -q 'Permission Denial' "$OUT/nonexported-provider.txt"; then echo "PRIVATE_PROVIDER=DENIED" >> "$OUT/status.txt"; else echo "PRIVATE_PROVIDER=INCONCLUSIVE" >> "$OUT/status.txt"; fi
echo "APP_CRASH_LOG_LINES=$(wc -l < "$OUT/app-crashes.txt")" >> "$OUT/status.txt"
cat "$OUT/status.txt"
