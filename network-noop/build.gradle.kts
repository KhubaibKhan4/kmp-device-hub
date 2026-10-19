import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
}

version = providers.gradleProperty("devicehub.networkVersion").get()

// io.github.khubaibkhan4:devicehub-network-noop: the same API as devicehub-network, doing nothing. Use it in release
// builds so the same code compiles while no traffic ever leaves the app.
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

/*
 * Publishing
 *   ./gradlew :network:publishToMavenLocal                      — this Mac only
 *   ./gradlew :network:publishAllPublicationsToDistRepository   — dist/maven, a folder to zip and share for testing
 *   ./gradlew :network:publishToMavenCentral                    — uploads a signed deployment to the Central Portal; you
 *                                                                 release it there (central.sonatype.com → Deployments)
 * Maven Central needs, in ~/.gradle/gradle.properties (never in the repo): mavenCentralUsername / mavenCentralPassword
 * (a Central Portal user token) and signing.gnupg.keyName (the GPG key id). Signing uses the gpg command, so the
 * passphrase stays in gpg-agent; CI can pass signingInMemoryKey / signingInMemoryKeyPassword instead.
 */
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    // Only Maven Central needs signatures; local and test publishing stay key-free.
    if (gradle.startParameter.taskNames.any { "MavenCentral" in it }) signAllPublications()
    coordinates("io.github.khubaibkhan4", "devicehub-network-noop", version.toString())
    pom {
        name = "KMP Device Hub Network (no-op)"
        description = "Release-build stand-in for devicehub-network: the same API, and it does nothing."
        inceptionYear = "2026"
        url = "https://github.com/KhubaibKhan4/kmp-device-hub"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "KhubaibKhan4"
                name = "Khubaib Khan"
                url = "https://github.com/KhubaibKhan4"
            }
        }
        scm {
            url = "https://github.com/KhubaibKhan4/kmp-device-hub"
            connection = "scm:git:git://github.com/KhubaibKhan4/kmp-device-hub.git"
            developerConnection = "scm:git:ssh://git@github.com/KhubaibKhan4/kmp-device-hub.git"
        }
    }
}

// Local GPG (passphrase from gpg-agent) unless CI provides an in-memory key.
if (!providers.gradleProperty("signingInMemoryKey").isPresent) {
    pluginManager.withPlugin("signing") { extensions.configure<SigningExtension> { useGpgCmd() } }
}

publishing {
    repositories {
        maven {
            name = "dist"
            url = uri(rootProject.layout.projectDirectory.dir("dist/maven"))
        }
    }
}
