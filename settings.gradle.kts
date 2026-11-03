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
        maven("https://jitpack.io") {
            content { includeGroup("com.github.PhilJay.MPAndroidChart") }
        }
    }
}

rootProject.name = "BlockAds"

include(":app")

include(
    ":core:common",
    ":core:designsystem",
    ":core:data",
    ":core:engine-bridge",
    ":core:tunnel",
    ":core:filter",
)

include(
    ":feature:home",
    ":feature:querylog",
    ":feature:filters",
    ":feature:apps",
    ":feature:settings",
    ":feature:certificate",
)

include(":baselineprofile")
