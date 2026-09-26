#!/usr/bin/env bash
set -euo pipefail

# The app's physical-device/SAF import tests are not emulator-CI tests. Keep
# those tests intact; execute every self-contained emulator case explicitly.
mapfile -t devices < <(adb devices | awk '$2 == "device" { print $1 }')
if (( ${#devices[@]} != 1 )) || [[ "${devices[0]:-}" != emulator-* ]]; then
  echo "Expected exactly one booted emulator and no physical device" >&2
  exit 1
fi
serial=${devices[0]}
runner=com.gernalix.personalhub.qa.test/androidx.test.runner.AndroidJUnitRunner

timeout --signal=TERM --kill-after=2m 18m ./gradlew --no-daemon --console=plain --max-workers=1 \
  :app:assembleQa :app:assembleQaAndroidTest \
  -Ppersonalhub.testBuildType=qa -Ppersonalhub.allowCiEmulatorDebug=true
adb -s "$serial" install -r app/build/outputs/apk/qa/app-qa.apk >/dev/null
adb -s "$serial" install -r app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk >/dev/null

run_app_test() {
  local test_name=$1 result
  if ! result=$(timeout --signal=TERM --kill-after=15s 3m \
    adb -s "$serial" shell am instrument -w -r \
    -e class "com.gernalix.personalhub.$test_name" "$runner" \
    | awk '/^INSTRUMENTATION_STATUS_CODE:|^OK \(|^FAILURES|^INSTRUMENTATION_CODE:/'); then
    echo "Instrumentation command failed: $test_name" >&2
    return 1
  fi
  if [[ "$result" != *'OK (1 test)'* || "$result" == *'INSTRUMENTATION_STATUS_CODE: -2'* ]]; then
    printf 'Instrumentation failed: %s\n%s\n' "$test_name" "$result" >&2
    return 1
  fi
  printf 'PASS %s\n' "$test_name"
}

app_tests=(
  'DatabaseVaultLegacyTableValidationTest#inertLegacyTableIsAllowedButUnexpectedTriggerIsRejected'
  'DatasetteSyncInstrumentedTest#journalMutationTriggersDatasetteWorkAndRecoveryWithoutPolling'
  'GlobalDatabaseInstrumentedTest#roomAndTimerWritesScheduleAndProduceExportWithoutPolling'
  'GlobalDatabaseInstrumentedTest#cleanIdleStartupHasRecoveryButNoDirtyPollingThread'
  'GlobalDatabaseInstrumentedTest#repeatedImportsDeleteOrphansButProtectAPendingRollbackCopy'
  'QuickEventWidgetClickDeviceTest#twoWidgetEventTapsCreateOneEntryEach'
  'SubstancesWidgetHostDeviceTest#twoWidgetInstancesTargetAndRecordTwoSubstancesOnceEach'
  'HubDiscoverabilityEpisodesEmulatorTest#homeSearchEpisodesAndFatigueAreReachableFromUi'
  'HubTemporalDeferredQaDeviceTest#homeSearchEpisodesAndFatigueAreReachableFromUi'
  'DatasetteSyncInstrumentedTest#importPreservesKnownIdentitiesForReconciliation'
  'DatasetteSyncInstrumentedTest#importReconciliationAfterProcessRestart'
)
for test_name in "${app_tests[@]}"; do
  run_app_test "$test_name"
done

if [[ -z "${PERSONALHUB_DATASETTE_RUNTIME_JSON:-}" ]]; then
  echo "Missing private Datasette test runtime" >&2
  exit 1
fi
# run-as owns the redirect inside the isolated QA app sandbox. No value is put
# in argv, instrumentation arguments, workspace files, or runner logs.
printf '%s' "$PERSONALHUB_DATASETTE_RUNTIME_JSON" \
  | adb -s "$serial" shell "run-as com.gernalix.personalhub.qa sh -c 'umask 077; cat > /data/user/0/com.gernalix.personalhub.qa/no_backup/datasette-runtime.json'"
unset PERSONALHUB_DATASETTE_RUNTIME_JSON
trap 'adb -s "$serial" shell "run-as com.gernalix.personalhub.qa sh -c '\''unlink /data/user/0/com.gernalix.personalhub.qa/no_backup/datasette-runtime.json'\''" >/dev/null 2>&1 || true' EXIT
run_app_test 'DatasetteSyncInstrumentedTest#configureFromPrivateRuntimeFile'
run_app_test 'DatasetteSyncInstrumentedTest#oracleFullRoundTripUpload'

# Library instrumentation uses the same emulator, never concurrently.
python3 tools/android_connected_test_gate.py -- \
  timeout --signal=TERM --kill-after=2m 22m ./gradlew --no-daemon --console=plain --max-workers=1 \
  :feature:luoghi:connectedDebugAndroidTest \
  :feature:multitimetracker:connectedDebugAndroidTest \
  -Ppersonalhub.allowCiEmulatorDebug=true
