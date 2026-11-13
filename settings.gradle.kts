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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PokerVisionAI"

include(":app")

// Pure Kotlin/JVM modules (no Android dependency)
include(":core:common")
include(":core:model")
include(":core:domain")
include(":core:poker")

// Android library modules
include(":core:database")
include(":core:data")
include(":core:ui")
include(":core:media")

// Feature modules
include(":feature:home")
include(":feature:advisor")
include(":feature:camera")
include(":feature:imageanalysis")
include(":feature:history")
include(":feature:settings")
