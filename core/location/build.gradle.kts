plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gernalix.personalhub.core.location"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":contracts:database"))
    implementation(project(":core:database"))
    implementation(libs.google.places)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
