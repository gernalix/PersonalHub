import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.tasks.testing.Test
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Retrieve once per signed build, through the scoped Fedora runtime. Never cache
// credentials in Gradle configuration state or fall back to a local password file.
val signingTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(":").lowercase() }
val signingRequested = signingTaskNames.any { name ->
    name == "build" || name == "assemble" || name.contains("signing") ||
        listOf("assemble", "bundle", "package", "install", "connected").any(name::startsWith) ||
        name.contains("androidtest")
}
val ciSigningException =
    (providers.gradleProperty("personalhub.allowUnsignedPlayBundle").orNull == "true" &&
        "bundleplay" in signingTaskNames && signingTaskNames.all {
            it in setOf("processplaymainmanifest", "lintplay", "bundleplay")
        }) ||
    (providers.gradleProperty("personalhub.allowCiEmulatorDebug").orNull == "true" &&
        System.getenv("CI")?.equals("true", ignoreCase = true) == true &&
        (gradle.startParameter.taskNames.all { it in setOf(
            ":feature:luoghi:assembleDebugAndroidTest", ":feature:multitimetracker:assembleDebugAndroidTest",
            ":core:database:assembleDebugAndroidTest") } || signingTaskNames.all {
            it in setOf("connecteddebugandroidtest", "connectedqaandroidtest", "assembleqa", "assembleqaandroidtest")
        }))
val canonicalSigningValues: Map<String, String> = if (signingRequested && !ciSigningException) {
    require(!gradle.startParameter.isConfigurationCacheRequested) {
        "Signed tasks require --no-configuration-cache."
    }
    val process = ProcessBuilder("python3", rootProject.file("tools/read_signing_credentials.py").path)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val privateOutput = process.inputStream.bufferedReader().use { it.readText() }
    require(process.waitFor() == 0) { "Canonical signing unavailable from Bitwarden; no local fallback." }
    @Suppress("UNCHECKED_CAST")
    (JsonSlurper().parseText(privateOutput) as Map<String, String>)
} else emptyMap()
extra["canonicalSigningValues"] = canonicalSigningValues

private fun org.gradle.api.Project.configureTranslationLint() {
    extensions.configure<ApplicationExtension>("android") {
        lint.lintConfig = rootProject.file("lint.xml")
    }
}

private fun org.gradle.api.Project.configureLibraryTranslationLint() {
    extensions.configure<LibraryExtension>("android") {
        lint.lintConfig = rootProject.file("lint.xml")
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        // Robolectric's Android shared-memory setup calls this public JDK interface.
        // Export it only to host test JVMs; application/runtime configuration is unchanged.
        jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    }
    pluginManager.withPlugin("com.android.application") { configureTranslationLint() }
    pluginManager.withPlugin("com.android.library") { configureLibraryTranslationLint() }
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
