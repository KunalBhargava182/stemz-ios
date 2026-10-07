import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    // Physical iPhone only (iPhone 16). Simulator targets are not needed without a Mac.
    iosArm64 {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    // Web (PWA) target: same commonMain code, runs in iPhone Safari. Added in step W1.
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "stemz.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}
