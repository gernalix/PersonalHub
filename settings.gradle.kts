pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PersonalHub"
include(":app")
include(":benchmark")
include(":contracts:database")
include(":core:database")
include(":core:hub-context")
include(":feature:luoghi")
include(":feature:multitimetracker")
include(":feature:sostanze")
include(":feature:supercontacts")
include(":feature:wordpulse")
include(":feature:soldi")
