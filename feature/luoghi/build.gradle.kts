import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun loadPropertiesFile(file: File): Properties =
    Properties().apply {
        if (file.exists()) file.inputStream().use(::load)
    }

fun propertyValue(properties: Properties, key: String): String? =
    properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

fun envValue(key: String): String? =
    providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun String.escapeForBuildConfig(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

val localProperties = loadPropertiesFile(rootProject.file("local.properties"))
val secretsProperties = loadPropertiesFile(rootProject.file("secrets.properties"))
val defaultMapsApiPropertiesFile = File("/home/daniele/.config/codex/secrets/map.env")
val mapsApiPropertiesFile = File(
    envValue("LUOGHI_MAPS_API_PROPERTIES")
        ?: propertyValue(localProperties, "LUOGHI_MAPS_API_PROPERTIES")
        ?: defaultMapsApiPropertiesFile.path,
)
val mapsApiProperties = loadPropertiesFile(mapsApiPropertiesFile)

fun googleMapsApiKeyValue(vararg keys: String): String? {
    for (key in keys) {
        propertyValue(localProperties, key)?.let { return it }
        propertyValue(secretsProperties, key)?.let { return it }
        propertyValue(mapsApiProperties, key)?.let { return it }
        envValue(key)?.let { return it }
    }
    return null
}

val googleMapsApiKey = googleMapsApiKeyValue(
    "google.maps.api.key",
    "GOOGLE_MAPS_API_KEY",
    "PLACES_API_KEY",
    "MAP_API",
).orEmpty()

val googleRoutesApiKey = googleMapsApiKeyValue(
    "google.routes.api.key",
    "GOOGLE_ROUTES_API_KEY",
    "google.maps.api.key",
    "GOOGLE_MAPS_API_KEY",
    "PLACES_API_KEY",
    "MAP_API",
).orEmpty()

fun requiresGoogleMapsApiKey(taskNames: List<String>): Boolean =
    taskNames.any { taskName ->
        taskName == "assemble" ||
            taskName.startsWith("assemble") ||
            taskName == "build" ||
            taskName.startsWith("install") && !taskName.startsWith("uninstall") ||
            taskName.startsWith("package") ||
            taskName.startsWith("bundle") ||
            taskName.startsWith("connected") ||
            taskName.contains("androidtest")
    }

val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(":").lowercase() }
if (googleMapsApiKey.isBlank() && requiresGoogleMapsApiKey(requestedTaskNames)) {
    throw GradleException(
        "GOOGLE_MAPS_API_KEY is required for APK-producing or device-install tasks. " +
            "Configure /home/daniele/.config/codex/secrets/map.env (MAP_API) or an accepted local property/environment variable.",
    )
}

android {
    namespace = "com.gernalix.luoghi"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APPLICATION_ID", "\"com.gernalix.luoghi\"")
        buildConfigField("int", "VERSION_CODE", "15")
        buildConfigField("String", "VERSION_NAME", "\"15\"")
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"${googleMapsApiKey.escapeForBuildConfig()}\"")
        buildConfigField("String", "GOOGLE_ROUTES_API_KEY", "\"${googleRoutesApiKey.escapeForBuildConfig()}\"")
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
    implementation(project(":core:database"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.google.places)
    implementation(libs.google.play.services.location)
    implementation(libs.osmdroid.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
