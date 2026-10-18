plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gernalix.luoghi"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APPLICATION_ID", "\"com.gernalix.luoghi\"")
        buildConfigField("int", "VERSION_CODE", "15")
        buildConfigField("String", "VERSION_NAME", "\"15\"")
        buildConfigField("boolean", "PLAY_DISTRIBUTION", "false")
    }

    buildTypes {
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "PLAY_DISTRIBUTION", "true")
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        named("testDebug") {
            assets.srcDir(rootProject.file("core/database/src/main/assets"))
        }
    }
}

dependencies {
    implementation(project(":contracts:database"))
    implementation(project(":core:database"))
    implementation(project(":core:hub-context"))
    implementation(project(":core:alerts"))
    implementation(project(":core:location"))
    implementation(project(":core:ui"))
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(files("libs/osmand-aidl-api-20261019.jar"))
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.uiautomator)
}
