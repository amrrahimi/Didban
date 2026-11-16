// Standalone build for the iPhone app's shared Kotlin framework ("PokerCore").
// Reuses the platform-neutral sources of the Android app (core:model, core:poker, domain vision).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "PokerCore"
