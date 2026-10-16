import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

// io.github.khubaibkhan4:devicehub-network — add it to your app (debug builds) and install DeviceHubNetwork in your
// Ktor HttpClient, or DeviceHubInterceptor in OkHttp.
kotlin {
    explicitApi()

    // Plain JVM bytecode: Android apps consume the jvm variant, so no Android Gradle Plugin is needed here.
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_1_8 }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        // Usable from apps on Kotlin 2.1 and newer.
        apiVersion = KotlinVersion.KOTLIN_2_1
        languageVersion = KotlinVersion.KOTLIN_2_1
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            implementation(libs.ktor.network)
        }
        jvmMain.dependencies {
            // Only needed by apps that use DeviceHubInterceptor, and they already have OkHttp.
            compileOnly(libs.okhttp)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.okhttp)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// ./gradlew :network:publishToMavenLocal until it's on Maven Central: io.github.khubaibkhan4:devicehub-network(-jvm, -iosarm64, …)
publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactId.replaceFirst("network", "devicehub-network")
    }
}
