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
    description = "Checks capsule public surfaces, module dependency direction, and persistence ownership boundaries."
    commandLine("python3", "tools/check_architecture_boundaries.py")
}

val moduleChecks = subprojects.map { project ->
    project.tasks.matching { task -> task.name == "check" }
}

tasks.register("check") {
    group = "verification"
    description = "Runs architecture checks and every module's verification lifecycle."
    dependsOn(checkArchitectureBoundaries)
    dependsOn(moduleChecks)
}
