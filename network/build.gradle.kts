import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

version = providers.gradleProperty("devicehub.networkVersion").get()

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
        pom {
            name = "KMP Device Hub Network"
            description = "Sends your app's HTTP calls (Ktor or OkHttp) to the KMP Device Hub Network Inspector in Android Studio / IntelliJ IDEA."
            url = "https://github.com/KhubaibKhan4/kmp-device-hub"
            licenses {
                license {
                    name = "Apache-2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0"
                }
            }
            developers {
                developer {
                    id = "KhubaibKhan4"
                    name = "Khubaib Khan"
                }
            }
            scm { url = "https://github.com/KhubaibKhan4/kmp-device-hub" }
        }
    }
    repositories {
        // ./gradlew :network:publishAllPublicationsToDistRepository — a Maven repository folder to zip and share for testing.
        maven {
            name = "dist"
            url = uri(rootProject.layout.projectDirectory.dir("dist/maven"))
        }
    }
}
