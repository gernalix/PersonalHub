plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.gernalix.personalhub.contracts.database"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.kotlinx.coroutines.android)
}
