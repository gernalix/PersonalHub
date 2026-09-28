plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gernalix.personalhub.core.hubcontext"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
        tasks.matching {
            it.name == "bundleLibCompileToJar$variantName" ||
                it.name == "bundleLibRuntimeToJar$variantName"
        }.configureEach { dependsOn("compile${variantName}Kotlin") }
    }
}

dependencies {
    api(project(":contracts:database"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
