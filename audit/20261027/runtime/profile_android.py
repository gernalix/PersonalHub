#!/usr/bin/env python3
"""Safe one-device QA-only runtime/safety diagnostics. No Pixel or production package."""
import json
import pathlib
import random
import re
import subprocess
import time

OUT = pathlib.Path("audit/20261027/runtime/results")
OUT.mkdir(parents=True, exist_ok=True)
PKG = "com.gernalix.personalhub.qa"
TEST_PKG = PKG + ".test"
PROFILE_CLASS = "com.gernalix.personalhub.AutoExportRuntimeCostQaTest"


def cmd(args, *, timeout=180, check=True):
    result = subprocess.run(args, capture_output=True, text=True,
                            timeout=timeout, check=False)
    if check and result.returncode != 0:
        raise RuntimeError("Command failed: " + " ".join(args[:6]) +
                           " rc=" + str(result.returncode) + " " +
                           (result.stderr or result.stdout)[-1200:])
    return result


rows = cmd(["adb", "devices"]).stdout.splitlines()
devices = [r.split()[0] for r in rows[1:] if len(r.split()) >= 2 and r.split()[1] == "device"]
assert len(devices) == 1 and re.fullmatch(r"emulator-[0-9]+", devices[0]), (
    "Exactly one online emulator and ZERO real physical devices are mandatory")
SERIAL = devices[0]
ADB = ["adb", "-s", SERIAL]


def adb(*args, timeout=180, check=True):
    return cmd(ADB + list(args), timeout=timeout, check=check)


fingerprint = adb("shell", "getprop", "ro.build.fingerprint").stdout.strip()
hardware = adb("shell", "getprop", "ro.hardware").stdout.strip()
assert hardware in ("ranchu", "goldfish"), "Unexpected device: " + hardware
device_info = {"serial": "ephemeral-" + SERIAL, "hardware": hardware,
               "fingerprint": fingerprint,
               "android_sdk": adb("shell", "getprop", "ro.build.version.sdk").stdout.strip(),
               "real_device": False}
(OUT / "device.json").write_text(json.dumps(device_info, indent=2))

apk = pathlib.Path("app/build/outputs/apk/qa/app-qa.apk")
testapk = pathlib.Path("app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk")
assert apk.is_file() and testapk.is_file(), "QA build outputs not present"
adb("install", "-r", str(apk), timeout=180)
adb("install", "-r", str(testapk), timeout=180)
assert "package:" in adb("shell", "pm", "path", PKG).stdout
assert "package:" in adb("shell", "pm", "path", TEST_PKG).stdout

# Like the existing CI gate, install ART compilation to remove first-boot JIT noise.
for p in [PKG, TEST_PKG]:
    result = adb("shell", "cmd", "package", "compile", "-m", "speed", "-f", p, timeout=180)
    assert "Success" in result.stdout, "ART compile failed for test-only package"


def launch(label, index):
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "dumpsys", "gfxinfo", PKG, "reset", check=False)
    run = adb("shell", "am", "start", "-W", "-n", PKG +
              "/com.gernalix.personalhub.MainActivity", timeout=90, check=False)
    (OUT / ("start-" + label + "-" + str(index) + ".txt")).write_text(
        run.stdout + "\n" + run.stderr)
    elapsed_match = re.search(r"TotalTime:\s*(\d+)", run.stdout)
    if run.returncode or not elapsed_match:
        raise RuntimeError("Invalid startup result: " + run.stdout[-900:] + run.stderr[-900:])
    gfx = adb("shell", "dumpsys", "gfxinfo", PKG, "framestats", check=False).stdout
    (OUT / ("gfx-" + label + "-" + str(index) + ".txt")).write_text(gfx)
    pss = adb("shell", "dumpsys", "meminfo", PKG, check=False).stdout
    (OUT / ("mem-" + label + "-" + str(index) + ".txt")).write_text(pss)
    match_pss = re.search(r"TOTAL PSS:\s*(\d+)", pss)
    return {"phase": label, "iteration": index, "total_time_ms": int(elapsed_match.group(1)),
            "pss_kib": int(match_pss.group(1)) if match_pss else None}


launches = [launch("initial-empty", 0)]
adb("logcat", "-c")
instrumentation = cmd(
    ["python3", "tools/android_connected_test_gate.py", "--"] + ADB +
    ["shell", "am", "instrument", "-w", "-r", "-e", "class", PROFILE_CLASS,
     TEST_PKG + "/androidx.test.runner.AndroidJUnitRunner"], timeout=660, check=False)
(OUT / "instrumentation.txt").write_text(
    instrumentation.stdout + "\n" + instrumentation.stderr)
if instrumentation.returncode or "OK (1 test)" not in instrumentation.stdout:
    raise RuntimeError("Isolated real DatabaseVault export profiling failed; inspect instrumentation.txt")

metrics_log = adb("logcat", "-d", "-v", "brief", "-s", "PHRuntimeAudit:I").stdout
(OUT / "export-log.txt").write_text(metrics_log)
metrics = []
for line in metrics_log.splitlines():
    if "PH_EXPORT_METRIC:" in line:
        metrics.append(json.loads(line.split("PH_EXPORT_METRIC:", 1)[1].strip()))
assert len(metrics) >= 4, "Expected three size samples and one burst metric; got " + str(metrics)
(OUT / "export-metrics.json").write_text(json.dumps(metrics, indent=2))

# Repeated cold process starts with the same synthetic 48 MiB database.
for i in range(5):
    launches.append(launch("synthetic-48mib", i))
(OUT / "startup.json").write_text(json.dumps(launches, indent=2))

# Perfetto: one focused cold-start flow on the disposable 48 MiB fixture.
trace_device = "/data/misc/perfetto-traces/personalhub-runtime-verification.pftrace"
perf = adb("shell", "perfetto", "--background-wait", "-o", trace_device,
           "-t", "15s", "--app", PKG,
           "sched", "freq", "idle", "am", "wm", "gfx", "view",
           "binder_driver", "hal", "dalvik", timeout=50, check=False)
(OUT / "perfetto-command.txt").write_text(perf.stdout + "\n" + perf.stderr)
if perf.returncode == 0:
    launches.append(launch("perfetto-48mib", 0))
    time.sleep(16)
    fetched = adb("pull", trace_device, str(OUT / "runtime-startup.pftrace"),
                  timeout=150, check=False)
    (OUT / "perfetto-pull.txt").write_text(fetched.stdout + "\n" + fetched.stderr)
(OUT / "startup.json").write_text(json.dumps(launches, indent=2))

# Non-exported Activity/provider probes execute from the separate Android shell UID.
# These MUST NOT launch sensitive UI or reveal any content.
permission_probes = [
    ("private_database_activity", ["shell", "am", "start", "-W", "-n",
                                   PKG + "/com.gernalix.personalhub.DatabaseActivity"]),
    ("private_tags_activity", ["shell", "am", "start", "-W", "-n",
                               PKG + "/com.gernalix.personalhub.HubTagsActivity"]),
    ("private_since_when_activity", ["shell", "am", "start", "-W", "-n",
                                     PKG + "/com.gernalix.personalhub.SinceWhenActivity"]),
    ("private_restart_activity", ["shell", "am", "start", "-W", "-n",
                                  PKG + "/com.gernalix.personalhub.DatabaseRestartActivity"]),
    ("private_places_provider", ["shell", "content", "query", "--uri",
                                 "content://" + PKG + ".luoghi.places/places"]),
    ("private_file_provider", ["shell", "content", "query", "--uri",
                               "content://" + PKG + ".fileprovider/"]),
]
denials = {}
for name, args in permission_probes:
    response = adb(*args, timeout=35, check=False)
    output = response.stdout + "\n" + response.stderr
    (OUT / ("security-" + name + ".txt")).write_text(output)
    protected = any(pat in output for pat in [
        "Permission Denial", "SecurityException", "not exported"])
    denials[name] = protected
    if not protected:
        raise RuntimeError("Sensitive surface not demonstrably denied: " + name +
                           " response=" + output[-800:])

# Short, bounded synthetic malformed URI mutations. No arbitrary shell scripts or secrets.
seed_urls = [
    "personalhub://entity/v1/people/person/42?action=delete",
    "personalhub://entity/v1/people/person/42?action=edit&action=view",
    "personalhub://search/v1?scope_module=people&module=places",
    "personalhub://since_when/v1/create?source=%7B%7D",
    "personalhub://event/v2/canonical",
    "personalhub://search/v1?from=INVALID",
    "personalhub://context/v1/..%2F..",
    "personalhub://entity/v1/a/b/c?action=admin",
]
rng = random.Random(20261027)
urls = seed_urls + [
    "personalhub://entity/v1/" +
    "".join(rng.choice("abcdef0123456789%?=._-") for _ in range(rng.randrange(1, 90)))
    for _ in range(24)
]
adb("logcat", "-c")
attempts = []
for i, uri in enumerate(urls):
    result = adb("shell", "am", "start", "-W", "-n",
                 PKG + "/com.gernalix.personalhub.HubDeepLinkActivity",
                 "-a", "android.intent.action.VIEW", "-d", uri,
                 timeout=30, check=False)
    attempts.append({"case": i, "am_exit": result.returncode})
    if "FATAL EXCEPTION" in result.stderr:
        raise RuntimeError("Unexpected fatal UI exception in deep link case " + str(i))
crash_log = adb("logcat", "-d", "-v", "brief", "-s", "AndroidRuntime:E").stdout
(OUT / "security-crash-log.txt").write_text(crash_log)
if "FATAL EXCEPTION" in crash_log and "Process: " + PKG in crash_log:
    raise RuntimeError("AndroidRuntime reports a crash of the target QA process")

(OUT / "security.json").write_text(json.dumps({
    "protected_component_denials": denials,
    "malformed_deep_links_attempted": len(attempts),
    "attributable_java_crashes": 0,
    "coverage_limits": "Android 35 ephemeral emulator; not rooted adversary, no network pen-test, no real Pixel",
}, indent=2))
(OUT / "status.txt").write_text("RUNTIME_AND_SECURITY_QA_PASS\n")
print(json.dumps({"status": "PASS", "startups": len(launches),
                  "export_records": len(metrics),
                  "protected_surfaces": len(denials),
                  "malformed_deep_links": len(attempts)}, indent=2))
