# Destructive Test Guardrail

PROMPT_ID=428619

Root cause: the `:benchmark` module used `targetProjectPath = ":app"` and its Macrobenchmark/BaselineProfile tests hardcoded `com.gernalix.personalhub` as the measured package. Android Gradle Plugin benchmark/profile workflows install and manage the tested app package for the run. With the target set to the real application id, that lifecycle can replace or remove the real Pixel/TCL install during teardown even when no manual `adb uninstall com.gernalix.personalhub` command is typed.

Guardrail: the `benchmark` app build type now uses the isolated package `com.gernalix.personalhub.benchmarktarget`, and benchmark/profile tests default to that package through the `targetPackage` instrumentation argument. The benchmark preflight fails before execution if `-Ppersonalhub.benchmark.targetPackage=com.gernalix.personalhub` is requested without same-prompt opt-in via `-Ppersonalhub.allowRealPackageDestructive=true`.

Additional protection: Gradle `:app:uninstall*`, `:app:uninstallAll`, and `:app:connectedDebugAndroidTest` against the real package fail closed unless the same explicit opt-in property is present. The benchmark clone also receives package-scoped provider authorities so it can coexist with the real install.

Safe path: use `:benchmark:connectedBenchmarkAndroidTest` with the default target package, preferably on an emulator or disposable device. Do not run destructive benchmark/profile/connected-test workflows against `com.gernalix.personalhub` on Pixel/TCL unless the current prompt explicitly authorizes the real-device data risk.

Soldi module QA uses `com.gernalix.personalhub.qa` with the `qa` build type and `-Ppersonalhub.testBuildType=qa`. Build the QA app and its test APK, install those exact packages, and invoke only `FinanceInstrumentedTest` with `adb -s <live-target> shell am instrument`. That test fails unless its application package is `.qa` and the persisted SAF folder is `PersonalHubSoldiQA`; it uses synthetic data only. The isolated package can be used on a physical device when the emulator cannot boot, without replacing or resetting the real PH installation. Do not run the unrelated full instrumentation suite on a physical device.
