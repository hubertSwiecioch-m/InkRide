pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "InkRide"
include(":app")
include(":core:domain")
include(":core:data")
include(":core:database")
include(":core:presentation")
include(":core:design-system")
include(":feature:dashboard:domain")
include(":feature:dashboard:data")
include(":feature:dashboard:presentation")
include(":feature:history:data")
include(":feature:history:presentation")
include(":feature:settings:data")
include(":feature:settings:presentation")
include(":feature:tracking:data")
include(":feature:ble:data")
include(":feature:ble:presentation")
