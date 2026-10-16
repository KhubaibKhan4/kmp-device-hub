plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // The Android app uses this jvm variant, like it would any KMP library without an Android target.
    jvm()
    iosSimulatorArm64 {
        // A command-line program run inside the simulator with `xcrun simctl spawn`.
        binaries.executable { entryPoint = "io.github.kmpdevicehub.demo.main" }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.khubaibkhan4:devicehub-network:0.1.0")
            implementation("io.ktor:ktor-client-core:3.6.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        jvmMain.dependencies { implementation("io.ktor:ktor-client-okhttp:3.6.0") }
        iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.6.0") }
    }
}
