plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val personalHubVersion = rootProject.file("version.txt").readText().trim().toInt()

android {
    namespace = "com.gernalix.personalhub.soldi"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        buildConfigField("int", "VERSION_CODE", personalHubVersion.toString())
        buildConfigField("String", "VERSION_NAME", "\"$personalHubVersion\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":contracts:database"))
    implementation(project(":core:database"))
    implementation(project(":core:hub-context"))
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
