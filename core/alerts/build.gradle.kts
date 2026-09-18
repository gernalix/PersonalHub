plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gernalix.personalhub.core.alerts"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)\n    testImplementation(libs.junit)
}
