plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

val checkArchitectureBoundaries = tasks.register<Exec>("checkArchitectureBoundaries") {
    group = "verification"
    description = "Checks module dependency and database ownership boundaries."
    commandLine("python3", "tools/check_architecture_boundaries.py")
}

tasks.register("check") {
    group = "verification"
    dependsOn(checkArchitectureBoundaries)
}
