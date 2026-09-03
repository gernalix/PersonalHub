import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

plugins {
    alias(libs.plugins.android.test)
}

val realPersonalHubPackage = "com.gernalix.personalhub"
val isolatedBenchmarkTargetPackage = "$realPersonalHubPackage.benchmarktarget"
val benchmarkTargetPackage = providers.gradleProperty("personalhub.benchmark.targetPackage")
    .orElse(isolatedBenchmarkTargetPackage)
val realPackageDestructiveOptIn = providers.gradleProperty("personalhub.allowRealPackageDestructive")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

val canonicalSigningEnvFile = File("/home/daniele/.config/codex/secrets/android_signing.env")
val canonicalSigningProperties = Properties().apply {
    if (canonicalSigningEnvFile.exists()) canonicalSigningEnvFile.inputStream().use(::load)
}
val canonicalSigningNames = listOf(
    "ANDROID_SHARED_STORE_FILE",
    "ANDROID_SHARED_STORE_PASSWORD",
    "ANDROID_SHARED_KEY_ALIAS",
    "ANDROID_SHARED_KEY_PASSWORD",
)
val canonicalSigningValues = canonicalSigningNames.associateWith { name ->
    canonicalSigningProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
}
val hasCanonicalSigning = canonicalSigningValues.values.all { !it.isNullOrBlank() }
if (hasCanonicalSigning) {
    val store = File(canonicalSigningValues.getValue("ANDROID_SHARED_STORE_FILE")!!)
    require(store.isFile && store.canRead()) { "Canonical Android signing keystore is missing or unreadable." }
    require(
        Files.getPosixFilePermissions(store.toPath()) == setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        ),
    ) { "Canonical Android signing keystore must have mode 0600." }
}

android {
    namespace = "com.gernalix.personalhub.benchmark"
    compileSdk = 37
    targetProjectPath = ":app"

    signingConfigs {
        create("canonicalShared") {
            if (hasCanonicalSigning) {
                storeFile = file(canonicalSigningValues.getValue("ANDROID_SHARED_STORE_FILE")!!)
                storePassword = canonicalSigningValues.getValue("ANDROID_SHARED_STORE_PASSWORD")
                keyAlias = canonicalSigningValues.getValue("ANDROID_SHARED_KEY_ALIAS")
                keyPassword = canonicalSigningValues.getValue("ANDROID_SHARED_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["targetPackage"] = benchmarkTargetPackage.get()
    }

    buildTypes {
        debug {
            if (hasCanonicalSigning) signingConfig = signingConfigs.getByName("canonicalShared")
        }
        create("benchmark") {
            isDebuggable = false
            isMinifyEnabled = true
            matchingFallbacks += listOf("debug")
            proguardFiles("proguard-rules.pro")
            if (hasCanonicalSigning) signingConfig = signingConfigs.getByName("canonicalShared")
        }
    }
}

val preflightPersonalHubBenchmarkSafety = tasks.register("preflightPersonalHubBenchmarkSafety") {
    group = "verification"
    description = "Fails before benchmark/profile execution if the real PersonalHub package would be targeted without explicit opt-in."
    doLast {
        val targetPackage = benchmarkTargetPackage.get().trim()
        require(targetPackage.isNotEmpty()) { "personalhub.benchmark.targetPackage must not be blank." }
        if (targetPackage == realPersonalHubPackage && !realPackageDestructiveOptIn.get()) {
            throw GradleException(
                "Refusing to benchmark/profile the real PersonalHub package $realPersonalHubPackage. " +
                    "Macrobenchmark/BaselineProfile installs and tears down the tested app, so use " +
                    "$isolatedBenchmarkTargetPackage or pass -Ppersonalhub.allowRealPackageDestructive=true only with explicit same-prompt authorization.",
            )
        }
    }
}

tasks.register("verifyPersonalHubBenchmarkSafety") {
    group = "verification"
    description = "Verifies that benchmark/profile tests default to the isolated PersonalHub package."
    doLast {
        check(benchmarkTargetPackage.get() == isolatedBenchmarkTargetPackage) {
            "Benchmark target must default to $isolatedBenchmarkTargetPackage, got ${benchmarkTargetPackage.get()}."
        }
    }
}

tasks.matching {
    it.name.startsWith("connected", ignoreCase = true) ||
        it.name.contains("BaselineProfile", ignoreCase = true)
}.configureEach {
    dependsOn(preflightPersonalHubBenchmarkSafety)
}

dependencies {
    implementation(libs.androidx.arch.core.runtime)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.errorprone.annotations)
}
