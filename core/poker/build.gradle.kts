import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure poker mathematics: hand evaluation, equity, pot odds, advice. No Android, no DI.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
}
