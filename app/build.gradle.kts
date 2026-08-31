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
val signingArtifactRequested = gradle.startParameter.taskNames
    .map { it.substringAfterLast(":").lowercase() }
    .any { name ->
        name == "build" || name == "assemble" ||
            listOf("assemble", "bundle", "package", "install", "connected").any(name::startsWith) ||
            name.contains("androidtest")
    }
if (signingArtifactRequested) {
    require(hasCanonicalSigning) {
        "APK/device tasks require /home/daniele/.config/codex/secrets/android_signing.env and all ANDROID_SHARED_* fields."
    }
    require(!gradle.startParameter.isConfigurationCacheRequested) {
        "Signed APK/device tasks require --no-configuration-cache."
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

    buildTypes {
        debug {
            if (hasCanonicalSigning) signingConfig = signingConfigs.getByName("canonicalShared")
        }
        release {
            if (hasCanonicalSigning) signingConfig = signingConfigs.getByName("canonicalShared")
            optimization {
                enable = false
            }
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

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:migration"))
    implementation(project(":feature:luoghi"))
    implementation(project(":feature:multitimetracker"))
    implementation(project(":feature:sostanze"))
    implementation(project(":feature:supercontacts"))
    implementation(project(":feature:wordpulse"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
