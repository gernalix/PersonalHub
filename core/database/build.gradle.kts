plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.gernalix.personalhub.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
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

androidComponents { onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory("schemas")
    val suffix = variant.name.replaceFirstChar { it.uppercase() }
    // Migration/transfer validation must package the schema from this compilation.
    tasks.matching { it.name == "merge${suffix}Assets" }.configureEach { dependsOn("ksp${suffix}Kotlin") }
} }
