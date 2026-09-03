plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.gernalix.personalhub.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

androidComponents { onVariants { it.sources.assets?.addStaticSourceDirectory("schemas") } }
