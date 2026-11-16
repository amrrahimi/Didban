import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.0.21"
}

kotlin {
    val xcf = XCFramework("PokerCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "PokerCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain {
            // Same code as the Android app: game engines, advice, card reading and the card memory.
            kotlin.srcDir("../../core/model/src/main/kotlin")
            kotlin.srcDir("../../core/poker/src/main/kotlin")
            kotlin.srcDir("../../core/domain/src/main/kotlin/com/pokervision/core/domain/vision")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
        }
    }
}
