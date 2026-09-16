import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
val requestedTaskNames = gradle.startParameter.taskNames
    .map { it.substringAfterLast(":").lowercase() }
val signingArtifactRequested = requestedTaskNames.any { name ->
    name == "build" || name == "assemble" ||
        listOf("assemble", "bundle", "package", "install", "connected").any(name::startsWith) ||
        name.contains("androidtest")
}
val allowUnsignedPlayBundle = providers.gradleProperty("personalhub.allowUnsignedPlayBundle")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val unsignedPlayBundlePreflight = allowUnsignedPlayBundle.get() &&
    requestedTaskNames.isNotEmpty() &&
    requestedTaskNames.all { it == "bundleplay" }
if (signingArtifactRequested && !unsignedPlayBundlePreflight) {
    require(hasCanonicalSigning) {
        "APK/AAB/device tasks require /home/daniele/.config/codex/secrets/android_signing.env and all ANDROID_SHARED_* fields. " +
            "Only CI may opt into the unsigned Play bundle preflight with -Ppersonalhub.allowUnsignedPlayBundle=true."
    }
    require(!gradle.startParameter.isConfigurationCacheRequested) {
        "Signed APK/AAB/device tasks require --no-configuration-cache."
    }
}
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

val appVersion = rootProject.file("version.txt").readText().trim().toInt()
val realPersonalHubPackage = "com.gernalix.personalhub"
val isolatedBenchmarkPackage = "$realPersonalHubPackage.benchmarktarget"
val realPackageDestructiveOptIn = providers.gradleProperty("personalhub.allowRealPackageDestructive")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

android {
    namespace = "com.gernalix.personalhub"
    compileSdk = 37

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
        applicationId = "com.gernalix.personalhub"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersion
        versionName = appVersion.toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testBuildType = providers.gradleProperty("personalhub.testBuildType").getOrElse("debug")

    buildTypes {
        debug {
            if (hasCanonicalSigning) signingConfig = signingConfigs.getByName("canonicalShared")
        }
        release {
            if (hasCanonicalSigning) signingConfig = signingConfigs.getByName("canonicalShared")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            if (hasCanonicalSigning) signingConfig = signingConfigs.getByName("canonicalShared")
            isDebuggable = false
        }
        create("qa") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            applicationIdSuffix = ".qa"
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmarktarget"
            if (hasCanonicalSigning) signingConfig = signingConfigs.getByName("canonicalShared")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output -> output.outputFileName.set("$appVersion.apk") }
    }
}

fun Provider<Boolean>.requireRealPackageDestructiveOptIn(action: String) {
    if (!get()) {
        throw GradleException(
            "$action would target the real PersonalHub package $realPersonalHubPackage. " +
                "Use the isolated benchmark package $isolatedBenchmarkPackage, or pass " +
                "-Ppersonalhub.allowRealPackageDestructive=true only when the same prompt explicitly authorizes real-device data risk.",
        )
    }
}

tasks.configureEach {
    val taskPath = path.lowercase()
    when {
        taskPath == ":app:uninstallall" ||
            (taskPath.startsWith(":app:uninstall") && !taskPath.contains("benchmark")) -> {
            doFirst {
                realPackageDestructiveOptIn.requireRealPackageDestructiveOptIn(path)
            }
        }
        taskPath == ":app:connecteddebugandroidtest" -> {
            doFirst {
                realPackageDestructiveOptIn.requireRealPackageDestructiveOptIn(path)
            }
        }
    }
}

dependencies {
    implementation(project(":contracts:database"))
    implementation(project(":core:database"))
    implementation(project(":core:hub-context"))
    implementation(project(":feature:luoghi"))
    implementation(project(":feature:multitimetracker"))
    implementation(project(":feature:sostanze"))
    implementation(project(":feature:supercontacts"))
    implementation(project(":feature:wordpulse"))
    implementation(project(":feature:soldi"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.documentfile)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
}
